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

                // hand off and go straight back to accepting
                new Thread(() -> handleClient(clientSocket)).start();
            }
        }catch (BindException e){
            System.err.println("Port 6379 already in use. Is another Redis running?");
            System.exit(1);
        }

    }

    private static void handleClient(Socket clientSocket){

        //try-with-resources so this works owns closing its own socket
        try ( Socket socket = clientSocket ){
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;

            // bytes that arrive but don't form a complete request yet
            ByteArrayOutputStream pending = new ByteArrayOutputStream();

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                pending.write(buffer, 0, bytesRead);

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

                pending.reset();
                pending.write(data, start, data.length - start);

            }

        }catch (IOException e){

            //one client failing must not take the server down
            System.err.println("Client error: " + e.getMessage());
        }
        System.out.println("Client disconnected");
    }
}
