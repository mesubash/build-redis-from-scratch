package io.github.mesubash;


import java.util.Locale;

// turns a parsed command into RESP reply. Knows nothing about sockets.
public class CommandDispatcher {

    private final RedisStore store;

    public CommandDispatcher(RedisStore store) {
        this.store = store;
    }

    public byte[] execute(String[] command) {
        if (command.length == 0) {
            return RespWriter.error("ERR empty command");
        }

        //command names are case-insensitive, arguments never are
        String name = command[0].toUpperCase(Locale.ROOT);

        return switch (name) {
            case "PING" -> ping(command);
            case "ECHO" -> echo(command);
            case "SET" -> set(command);
            case "GET" -> get(command);
            default ->  RespWriter.error("ERR unknown command '" + command[0] + "'");
        };
    }

    private byte[] ping(String[] command) {
        if (command.length == 1) {
            return RespWriter.simpleString("PONG");
        }

        if (command.length == 2) {
            return RespWriter.bulkString(command[1]);
        }
        return RespWriter.error("ERR wrong number of arguments for 'ping' command");
    }

    private byte[] echo(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'echo' command");
        }

        return RespWriter.bulkString(command[1]);
    }

    private byte[] set(String[] command) {
        if (command.length != 3 && command.length != 5) {
            return RespWriter.error("ERR wrong number of arguments for 'set' command");
        }
        if(command.length == 3) {
            store.set(command[1], command[2]);
            return RespWriter.simpleString("OK");
        }
        long multiplier = switch (command[3].toUpperCase(Locale.ROOT)){
            case "PX" -> 1L;
            case "EX" -> 1000L;
            default -> 0L;
        };
        if(multiplier == 0L) {
            return RespWriter.error("ERR syntax error");
        }

        long amount;
        try {
            amount = Long.parseLong(command[4]);
        }catch (NumberFormatException e){
            return RespWriter.error("ERR value is not an integer or out of range");
        }
        if( amount <= 0 ){
            return RespWriter.error("ERR invalid expire time in 'set' command");
        }
        store.set(command[1], command[2], amount * multiplier);
        return RespWriter.simpleString("OK");
    }

    private byte[] get(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'get' command");
        }
        //bulkString turns a null into $-1 for us
        return RespWriter.bulkString(store.get(command[1]));
    }

}
