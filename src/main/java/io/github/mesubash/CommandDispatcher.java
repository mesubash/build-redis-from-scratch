package io.github.mesubash;


import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// turns a parsed command into RESP reply. Knows nothing about sockets.
public class CommandDispatcher {

    private static final Set<String> KNOWN_COMMANDS = Set.of(
            "PING", "ECHO", "SET", "GET", "EXISTS", "DEL", "TYPE", "KEYS",
            "INCR", "DECR", "INCRBY", "DECRBY",
            "RPUSH", "LPUSH", "LRANGE", "LLEN", "LPOP", "BLPOP",
            "MULTI", "EXEC", "DISCARD",
            "SUBSCRIBE", "UNSUBSCRIBE", "PUBLISH");

    // once subscribed a connection may only do these
    private static final Set<String> SUBSCRIBED_MODE_COMMANDS =
            Set.of("SUBSCRIBE", "UNSUBSCRIBE", "PING", "QUIT", "RESET");

    private final RedisStore store;
    private final PubSub pubSub = new PubSub();

    public CommandDispatcher(RedisStore store) {
        this.store = store;
    }

    // the connection loop tells us when a client is gone
    public void onDisconnect(ClientSession session) {
        pubSub.removeAll(session);
    }

    // tests and any caller that doesn't care about transactions
    public byte[] execute(String[] command) {
        return execute(command, new ClientSession());
    }

    public byte[] execute(String[] command, ClientSession session) {
        if (command.length == 0) {
            return RespWriter.error("ERR empty command");
        }

        //command names are case-insensitive, arguments never are
        String name = command[0].toUpperCase(Locale.ROOT);

        if (session.inTransaction() && !name.equals("EXEC") && !name.equals("DISCARD")) {
            return queueForLater(name, command, session);
        }

        if (session.isSubscribed() && !SUBSCRIBED_MODE_COMMANDS.contains(name)) {
            return RespWriter.error("ERR Can't execute '" + command[0].toLowerCase(Locale.ROOT)
                    + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context");
        }

        try {
            return switch (name) {
                case "PING" -> ping(command);
                case "ECHO" -> echo(command);
                case "SET" -> set(command);
                case "GET" -> get(command);
                case "EXISTS" -> exists(command);
                case "DEL" -> del(command);
                case "TYPE" -> type(command);
                case "KEYS" -> keys(command);
                case "INCR" -> incrementBy(command, 1, "incr");
                case "DECR" -> incrementBy(command, -1, "decr");
                case "INCRBY" -> incrementByArgument(command, false);
                case "DECRBY" -> incrementByArgument(command, true);
                case "RPUSH" -> push(command, false);
                case "LPUSH" -> push(command, true);
                case "LRANGE" -> lrange(command);
                case "LLEN" -> llen(command);
                case "LPOP" -> lpop(command);
                case "BLPOP" -> blpop(command);
                case "MULTI" -> multi(command, session);
                case "EXEC" -> exec(command, session);
                case "DISCARD" -> discard(command, session);
                case "SUBSCRIBE" -> subscribe(command, session);
                case "UNSUBSCRIBE" -> unsubscribe(command, session);
                case "PUBLISH" -> publish(command);
                default ->  RespWriter.error("ERR unknown command '" + command[0] + "'");
            };
        }catch (WrongTypeException e){
            return RespWriter.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
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

    private byte[] exists(String[] command) {
        if (command.length < 2) {
            return RespWriter.error("ERR wrong number of arguments for 'exists' command");
        }

        long count = 0;

        // duplicates count more than once, that's real redis behavior
        for ( int i = 1; i < command.length; i++ ) {
            if ( store.exists(command[i]) ) {
                count++;
            }
        }
        return RespWriter.integer(count);
    }

    private byte[] del(String[] command) {
        if (command.length < 2) {
            return RespWriter.error("ERR wrong number of arguments for 'del' command");
        }
        long removed =  0;
        for ( int i = 1; i < command.length; i++ ) {
            if (store.delete(command[i]) ) {
                removed++;
            }

        }
        return RespWriter.integer(removed);
    }

    private byte[] type(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'type' command");
        }
        return RespWriter.simpleString(store.type(command[1]));
    }

    private byte[] keys(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'keys' command");
        }
        List<String> matches = store.keys(command[1]);
        byte[][] encoded = new byte[matches.size()][];

        for ( int i = 0; i < matches.size(); i++) {
            encoded[i] = RespWriter.bulkString(matches.get(i));
        }
        return RespWriter.array(encoded);
    }

    private byte[] push(String[] command, boolean left) {
        String name = left ? "lpush" : "rpush";
        if (command.length < 3) {
            return RespWriter.error("ERR wrong number of arguments for '" + name +"' command");
        }
        String[] values = Arrays.copyOfRange(command, 2, command.length);
        return RespWriter.integer(store.push(command[1], left, values));
    }

    private byte[] lrange(String[] command) {
        if (command.length != 4) {
            return RespWriter.error("ERR wrong number of arguments for 'lrange' command");
        }

        long start;
        long stop;
        try {
            start = Long.parseLong(command[2]);
            stop = Long.parseLong(command[3]);
        } catch (NumberFormatException e) {
            return RespWriter.error("ERR value is not an integer or out of range");
        }
        List<String> values = store.lrange(command[1], start, stop);
        byte[][] encoded = new byte[values.size()][];
        for (int i = 0; i < values.size(); i++) {
            encoded[i] = RespWriter.bulkString(values.get(i));
        }
        return RespWriter.array(encoded);
    }

    private byte[] llen(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'llen' command");
        }
        return RespWriter.integer(store.llen(command[1]));
    }

    // one confirmation array per channel, each carrying the running subscription count
    private byte[] subscribe(String[] command, ClientSession session) {
        if (command.length < 2) {
            return RespWriter.error("ERR wrong number of arguments for 'subscribe' command");
        }

        byte[][] confirmations = new byte[command.length - 1][];
        for (int i = 1; i < command.length; i++) {
            int count = pubSub.subscribe(session, command[i]);
            confirmations[i - 1] = RespWriter.array(
                    RespWriter.bulkString("subscribe"),
                    RespWriter.bulkString(command[i]),
                    RespWriter.integer(count));
        }
        return concat(confirmations);
    }

    private byte[] unsubscribe(String[] command, ClientSession session) {
        String[] channels = command.length > 1
                ? Arrays.copyOfRange(command, 1, command.length)
                // bare UNSUBSCRIBE leaves every channel
                : session.subscriptions().toArray(new String[0]);

        if (channels.length == 0) {
            return RespWriter.array(
                    RespWriter.bulkString("unsubscribe"),
                    RespWriter.bulkString(null),
                    RespWriter.integer(0));
        }

        byte[][] confirmations = new byte[channels.length][];
        for (int i = 0; i < channels.length; i++) {
            int count = pubSub.unsubscribe(session, channels[i]);
            confirmations[i] = RespWriter.array(
                    RespWriter.bulkString("unsubscribe"),
                    RespWriter.bulkString(channels[i]),
                    RespWriter.integer(count));
        }
        return concat(confirmations);
    }

    private byte[] publish(String[] command) {
        if (command.length != 3) {
            return RespWriter.error("ERR wrong number of arguments for 'publish' command");
        }
        return RespWriter.integer(pubSub.publish(command[1], command[2]));
    }

    // several replies to one command, so they can't go through RespWriter.array
    private static byte[] concat(byte[][] parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] joined = new byte[size];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, at, part.length);
            at += part.length;
        }
        return joined;
    }

    // inside MULTI nothing runs, it just piles up until EXEC
    private byte[] queueForLater(String name, String[] command, ClientSession session) {
        if (name.equals("MULTI")) {
            return RespWriter.error("ERR MULTI calls can not be nested");
        }
        if (!KNOWN_COMMANDS.contains(name)) {
            // redis refuses the whole transaction rather than skipping the bad command
            session.abort();
            return RespWriter.error("ERR unknown command '" + command[0] + "'");
        }
        session.queue(command);
        return RespWriter.simpleString("QUEUED");
    }

    private byte[] multi(String[] command, ClientSession session) {
        if (command.length != 1) {
            return RespWriter.error("ERR wrong number of arguments for 'multi' command");
        }
        session.begin();
        return RespWriter.simpleString("OK");
    }

    private byte[] exec(String[] command, ClientSession session) {
        if (command.length != 1) {
            return RespWriter.error("ERR wrong number of arguments for 'exec' command");
        }
        if (!session.inTransaction()) {
            return RespWriter.error("ERR EXEC without MULTI");
        }
        if (session.aborted()) {
            session.reset();
            return RespWriter.error("EXECABORT Transaction discarded because of previous errors.");
        }

        List<String[]> commands = session.drain();
        byte[][] replies = new byte[commands.size()][];
        for (int i = 0; i < commands.size(); i++) {
            // a failing command inside a transaction doesn't stop the others
            replies[i] = execute(commands.get(i), session);
        }
        return RespWriter.array(replies);
    }

    private byte[] discard(String[] command, ClientSession session) {
        if (command.length != 1) {
            return RespWriter.error("ERR wrong number of arguments for 'discard' command");
        }
        if (!session.inTransaction()) {
            return RespWriter.error("ERR DISCARD without MULTI");
        }
        session.reset();
        return RespWriter.simpleString("OK");
    }

    private byte[] blpop(String[] command) {
        if (command.length < 3) {
            return RespWriter.error("ERR wrong number of arguments for 'blpop' command");
        }

        double seconds;
        try {
            seconds = Double.parseDouble(command[command.length - 1]);
        } catch (NumberFormatException e) {
            return RespWriter.error("ERR timeout is not a float or out of range");
        }
        if (seconds < 0) {
            return RespWriter.error("ERR timeout is negative");
        }

        String[] keys = Arrays.copyOfRange(command, 1, command.length - 1);
        try {
            String[] popped = store.blockingPop(keys, (long) (seconds * 1000));
            if (popped == null) {
                return RespWriter.nullArray();
            }
            return RespWriter.array(RespWriter.bulkString(popped[0]), RespWriter.bulkString(popped[1]));
        } catch (InterruptedException e) {
            // the client's own thread was interrupted, treat it as a timeout
            Thread.currentThread().interrupt();
            return RespWriter.nullArray();
        }
    }

    private byte[] lpop(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'lpop' command");
        }
        return RespWriter.bulkString(store.lpop(command[1]));
    }



    private byte[] incrementBy(String[] command, long delta, String name) {
        if ( command.length != 2){
            return RespWriter.error("ERR wrong number of arguments for '" + name + "' command");
        }
        return applyIncrement(command[1], delta);

    }

    private byte[] incrementByArgument(String[] command, boolean negate) {
        String name = negate ? "decrby": "incrby";
        if (command.length != 3){
            return RespWriter.error("ERR wrong number of arguments for '" + name + "' command");
        }
        long delta;

        try {
            delta = Long.parseLong(command[2]);

            //negating Long.MIN_VALUE overflows, so let negateExact reject it

            if ( negate ){
                delta = Math.negateExact(delta);
            }
        }catch (NumberFormatException e){
            return RespWriter.error("ERR value is not an integer or out of range");
        }catch (ArithmeticException e){
            return RespWriter.error("ERR increment or decrement would overflow");
        }
        return applyIncrement(command[1], delta);

    }

    private byte[] applyIncrement(String key, long delta) {
        try {
            return RespWriter.integer(store.increment(key, delta));

        }catch (NumberFormatException e){
            return RespWriter.error("ERR value is not an integer or out of range");

        }catch (ArithmeticException e){
            return RespWriter.error("ERR increment or decrement would overflow");
        }
    }

}
