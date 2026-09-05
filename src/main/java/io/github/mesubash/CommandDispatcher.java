package io.github.mesubash;


import java.util.Locale;

// turns a parsed command into RESP reply. Knows nothing about sockets.
public class CommandDispatcher {

    public static byte[] execute(String[] command) {
        if (command.length == 0) {
            return RespWriter.error("ERR empty command");
        }

        //command names are case-insensitive, arguments never are
        String name = command[0].toUpperCase(Locale.ROOT);

        return switch (name) {
            case "PING" -> ping(command);
            default ->  RespWriter.error("ERR unknown command '" + command[0] + "'");
        };
    }

    private static byte[] ping(String[] command) {
        if (command.length == 1) {
            return RespWriter.simpleString("PONG");
        }

        if (command.length == 2) {
            return RespWriter.bulkString(command[1]);
        }
        return RespWriter.error("ERR wrong number of arguments for 'ping' command");
    }

}
