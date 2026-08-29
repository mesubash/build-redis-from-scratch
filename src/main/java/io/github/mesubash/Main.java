package io.github.mesubash;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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

        //try-with-resources so this worker owns closing its own socket
        try ( Socket socket = clientSocket ){
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;

            // the parser owns the pending bytes now
            RespParser parser = new RespParser();

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                parser.append(buffer, bytesRead);

                // one read may hold several commands, or none
                String[] command;
                while ((command = parser.next()) != null) {
                    System.out.println("Command: " + Arrays.toString(command));

                    outputStream.write(RespWriter.simpleString("PONG"));
                    outputStream.flush();
                }
            }

        }catch (IOException e){

            //one client failing must not take the server down
            System.err.println("Client error: " + e.getMessage());
        }
        System.out.println("Client disconnected");
    }
}
