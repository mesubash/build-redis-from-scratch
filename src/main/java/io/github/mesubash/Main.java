package io.github.mesubash;


import java.io.IOException;
import java.io.InputStream;
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
                byte[] buffer = new byte[1024];
                int bytesRead;

                // read() returns -1 when the client closes its end
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    String data = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    // escape the invisible bytes, they matter once RESP arrives
                    System.out.println("Received: " + bytesRead + " bytes: " + data.replace("\r", "\\r").replace("\n", "\\n"));
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
