package io.github.mesubash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

// Master-side replica registry. Replicas are connections that stopped being ordinary clients.
public class Replication {

    // only writes are propagated, reads would just waste bandwidth on the replica
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "SET", "DEL", "GETDEL", "MSET", "SETNX", "APPEND", "EXPIRE", "PEXPIRE", "PERSIST",
            "INCR", "DECR", "INCRBY", "DECRBY", "FLUSHALL",
            "RPUSH", "LPUSH", "LPOP", "RPOP",
            "HSET", "HDEL", "SADD", "SREM", "ZADD", "ZREM", "XADD");

    private final String replicationId;
    private final List<ClientSession> replicas = new CopyOnWriteArrayList<>();

    private volatile long offset;

    public Replication() {
        // 40 hex characters, the shape real redis uses
        StringBuilder id = new StringBuilder();
        while (id.length() < 40) {
            id.append(Integer.toHexString((int) (Math.random() * 16)));
        }
        this.replicationId = id.toString();
    }

    public String replicationId() {
        return replicationId;
    }

    public long offset() {
        return offset;
    }

    public int replicaCount() {
        return replicas.size();
    }

    public static boolean isWrite(String commandName) {
        return WRITE_COMMANDS.contains(commandName);
    }

    public void addReplica(ClientSession replica) {
        replicas.add(replica);
    }

    // the command is re-encoded rather than forwarded verbatim, so every replica sees the
    // same canonical form regardless of how the client wrote it
    public void propagate(String[] command) {
        byte[] encoded = encode(command);
        offset += encoded.length;

        for (ClientSession replica : replicas) {
            try {
                replica.send(encoded);
            } catch (IOException e) {
                // a replica that has gone away stops being one
                replicas.remove(replica);
            }
        }
    }

    public static byte[] encode(String[] command) {
        byte[][] parts = new byte[command.length][];
        for (int i = 0; i < command.length; i++) {
            parts[i] = RespWriter.bulkString(command[i]);
        }
        return RespWriter.array(parts);
    }

    public static byte[] fullResyncHeader(String replicationId, long offset) {
        return ("+FULLRESYNC " + replicationId + " " + offset + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    // the snapshot is framed like a bulk string but without the trailing CRLF
    public static byte[] rdbPayload(byte[] snapshot) {
        byte[] header = ("$" + snapshot.length + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[header.length + snapshot.length];

        System.arraycopy(header, 0, payload, 0, header.length);
        System.arraycopy(snapshot, 0, payload, header.length, snapshot.length);
        return payload;
    }
}
