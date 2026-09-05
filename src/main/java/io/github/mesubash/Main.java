package io.github.mesubash;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
    static void main(String[] args) throws IOException {

        try( ServerSocket serverSocket = new ServerSocket()) {

            // lets us rebind immediately after a restart instead of waiting out TIME_WAIT
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(6379));

            System.out.println("Listening on port " + serverSocket.getLocalPort());

            // one store for the whole server, shared by every client thread
            CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());

            while (true) {
                // blocks until the kernel has a complete connection waiting for us
                Socket clientSocket = serverSocket.accept();

                System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());

                // hand off and go straight back to accepting
                new Thread(() -> handleClient(clientSocket, dispatcher)).start();
            }
        }catch (BindException e){
            System.err.println("Port 6379 already in use. Is another Redis running?");
            System.exit(1);
        }

    }

    private static void handleClient(Socket clientSocket, CommandDispatcher dispatcher){

        // declared out here so the disconnect cleanup can still see it after a failure
        ClientSession session = null;

        //try-with-resources so this worker owns closing its own socket
        try ( Socket socket = clientSocket ){
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;

            // the parser owns the pending bytes now
            RespParser parser = new RespParser();

            // transactions and subscriptions are per connection, unlike the store
            session = new ClientSession(outputStream);

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                parser.append(buffer, bytesRead);

                // one read may hold several commands, or none
                String[] command;
                while ((command = parser.next()) != null) {
                    // session.send serialises against publishers writing to this same socket
                    session.send(dispatcher.execute(command, session));
                }
            }

        }catch (IOException e){

            //one client failing must not take the server down
            System.err.println("Client error: " + e.getMessage());
        }catch (IllegalStateException e){
            // the byte stream is broken, nothing after this can be trusted
            System.err.println("Protocol error, closing connection: " + e.getMessage());
        }
        if (session != null) {
            dispatcher.onDisconnect(session);
        }
        System.out.println("Client disconnected");
    }
}
