package io.github.mesubash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

// The replica's side: hand-shakes with the master, loads its snapshot, then follows its writes.
public class ReplicaClient {

    private final ServerConfig config;
    private final RedisStore store;
    private final CommandDispatcher dispatcher;

    public ReplicaClient(ServerConfig config, RedisStore store, CommandDispatcher dispatcher) {
        this.config = config;
        this.store = store;
        this.dispatcher = dispatcher;
    }

    public void start() {
        Thread.ofVirtual().start(() -> {
            try {
                run();
            } catch (IOException e) {
                System.err.println("Replication stopped: " + e.getMessage());
            }
        });
    }

    private void run() throws IOException {
        try (Socket socket = new Socket(config.masterHost(), config.masterPort())) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            handshake(in, out);
            loadSnapshot(in);

            System.out.println("Replicating from " + config.masterHost() + ":" + config.masterPort());
            follow(in, out);
        }
    }

    // four exchanges, each one waiting for its reply before the next
    private void handshake(InputStream in, OutputStream out) throws IOException {
        send(out, "PING");
        expect(in, "+PONG");

        send(out, "REPLCONF", "listening-port", Integer.toString(config.port()));
        expect(in, "+OK");

        send(out, "REPLCONF", "capa", "psync2");
        expect(in, "+OK");

        send(out, "PSYNC", "?", "-1");
        String resync = readLine(in);
        if (!resync.startsWith("+FULLRESYNC")) {
            throw new IOException("expected FULLRESYNC, got '" + resync + "'");
        }
    }

    // the snapshot is framed like a bulk string but has no trailing CRLF
    private void loadSnapshot(InputStream in) throws IOException {
        String header = readLine(in);
        if (!header.startsWith("$")) {
            throw new IOException("expected an rdb payload, got '" + header + "'");
        }

        int length = Integer.parseInt(header.substring(1));
        byte[] snapshot = in.readNBytes(length);
        if (snapshot.length != length) {
            throw new IOException("master closed the connection during the snapshot");
        }

        try {
            for (RdbReader.Record record : RdbReader.readBytes(snapshot)) {
                store.restore(record.key(), record.value(), record.expiresAtEpochMillis());
            }
        } catch (IOException e) {
            // an unreadable snapshot leaves us empty rather than refusing to replicate
            System.err.println("Could not read the master's snapshot: " + e.getMessage());
        }
    }

    private void follow(InputStream in, OutputStream out) throws IOException {
        RespParser parser = new RespParser();
        byte[] buffer = new byte[4096];
        ClientSession session = new ClientSession(out);
        int read;

        while ((read = in.read(buffer)) != -1) {
            parser.append(buffer, read);

            String[] command;
            while ((command = parser.next()) != null) {
                byte[] reply = dispatcher.execute(command, session);

                // a replica answers GETACK and stays silent about everything else
                if (command.length > 1 && command[0].equalsIgnoreCase("REPLCONF")
                        && command[1].equalsIgnoreCase("GETACK")) {
                    out.write(reply);
                    out.flush();
                }

                // the master re-encodes canonically, so re-encoding gives the same byte count
                session.advanceReplicaOffset(Replication.encode(command).length);
            }
        }
    }

    private static void send(OutputStream out, String... command) throws IOException {
        out.write(Replication.encode(command));
        out.flush();
    }

    private static void expect(InputStream in, String expected) throws IOException {
        String line = readLine(in);
        if (!line.equals(expected)) {
            throw new IOException("expected '" + expected + "' from the master, got '" + line + "'");
        }
    }

    // reads one CRLF-terminated line, byte at a time so nothing is buffered past it
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read();
                return line.toString();
            }
            line.append((char) b);
        }
        throw new IOException("master closed the connection");
    }

    // exposed so tests and the replica path can share the reader
    public static List<RdbReader.Record> parseSnapshot(byte[] snapshot) throws IOException {
        return RdbReader.readBytes(snapshot);
    }
}
