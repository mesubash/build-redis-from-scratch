package io.github.mesubash;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Main {
    static void main(String[] args) throws IOException {

        try( ServerSocket serverSocket = new ServerSocket()) {

            // lets us rebind immediately after a restart instead of waiting out TIME_WAIT
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(6379));

            System.out.println("Listening on port " + serverSocket.getLocalPort());

            while (true) {
                // blocks until the kernel has a complete connection waiting for us
                Socket clientSocket = serverSocket.accept();

                System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());

                InputStream inputStream = clientSocket.getInputStream();
                OutputStream outputStream = clientSocket.getOutputStream();
                byte[] buffer = new byte[1024];
                int bytesRead;

                // bytes that arrived but don't form a complete request yet
                ByteArrayOutputStream pending = new ByteArrayOutputStream();

                // read() returns -1 when the client closes its end
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    pending.write(buffer, 0, bytesRead);

                    // one read can carry several requests, or none at all
                    byte[] data = pending.toByteArray();
                    int start = 0;
                    for (int i = 0; i < data.length; i++) {
                        if(data[i] != '\n'){
                            continue;
                        }

                        String request = new String(data, start, i - start, StandardCharsets.UTF_8);
                        System.out.println("Request: " + request.replace("\r", "\\r"));

                        outputStream.write((request + "\n").getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();

                        start = i + 1;
                    }

                    // whatever came after the last newline is an unfinished request
                    pending.reset();
                    pending.write(data, start, data.length - start);
                }




                System.out.println("Client disconnected");
                clientSocket.close();
            }
        }catch (BindException e){
            System.err.println("Port 6379 already in use. Is another Redis running?");
            System.exit(1);
        }

    }
}
