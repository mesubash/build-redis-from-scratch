package io.github.mesubash;


import java.io.IOException;
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

            while (true) {
                // blocks until the kernam has a complete connection waiting for us
                Socket clientSocket = serverSocket.accept();

                System.out.println("Client conntected: " + clientSocket.getRemoteSocketAddress());

                // nothing to sey to it yet so hand up
                clientSocket.close();
            }
        }catch (BindException e){
            System.err.println("Port 6379 already in use. Is another Redis running?");
            System.exit(1);
        }

    }
}
