package io.github.mesubash;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// turns a parsed command into RESP reply. Knows nothing about sockets.
public class CommandDispatcher {

    private static final Set<String> KNOWN_COMMANDS = Set.of(
            "PING", "ECHO", "SET", "GET", "EXISTS", "DEL", "TYPE", "KEYS",
            "INCR", "DECR", "INCRBY", "DECRBY",
            "RPUSH", "LPUSH", "LRANGE", "LLEN", "LPOP", "RPOP", "LINDEX", "BLPOP",
            "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH",
            "SUBSCRIBE", "UNSUBSCRIBE", "PUBLISH",
            "XADD", "XRANGE", "XLEN", "XREAD",
            "HSET", "HGET", "HGETALL", "HDEL", "HEXISTS", "HLEN", "HKEYS", "HVALS",
            "SADD", "SREM", "SMEMBERS", "SISMEMBER", "SCARD",
            "CONFIG", "INFO", "DBSIZE", "FLUSHALL", "COMMAND",
            "TTL", "PTTL", "EXPIRE", "PEXPIRE", "PERSIST",
            "MGET", "MSET", "SETNX", "APPEND", "STRLEN", "GETDEL");

    // once subscribed a connection may only do these
    private static final Set<String> SUBSCRIBED_MODE_COMMANDS =
            Set.of("SUBSCRIBE", "UNSUBSCRIBE", "PING", "QUIT", "RESET");

    private final RedisStore store;
    private final ServerConfig config;
    private final PubSub pubSub = new PubSub();

    public CommandDispatcher(RedisStore store) {
        this(store, new ServerConfig());
    }

    public CommandDispatcher(RedisStore store, ServerConfig config) {
        this.store = store;
        this.config = config;
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

        // WATCH is rejected rather than queued, so it has to reach the switch
        if (session.inTransaction()
                && !name.equals("EXEC") && !name.equals("DISCARD") && !name.equals("WATCH")) {
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
                case "RPOP" -> rpop(command);
                case "LINDEX" -> lindex(command);
                case "BLPOP" -> blpop(command);
                case "MULTI" -> multi(command, session);
                case "EXEC" -> exec(command, session);
                case "DISCARD" -> discard(command, session);
                case "WATCH" -> watch(command, session);
                case "UNWATCH" -> unwatch(command, session);
                case "SUBSCRIBE" -> subscribe(command, session);
                case "UNSUBSCRIBE" -> unsubscribe(command, session);
                case "PUBLISH" -> publish(command);
                case "XADD" -> xadd(command);
                case "XRANGE" -> xrange(command);
                case "XLEN" -> xlen(command);
                case "XREAD" -> xread(command);
                case "HSET" -> hset(command);
                case "HGET" -> hget(command);
                case "HGETALL" -> encodeStrings(store.hgetall(argument(command, "hgetall")));
                case "HKEYS" -> encodeStrings(store.hkeys(argument(command, "hkeys")));
                case "HVALS" -> encodeStrings(store.hvals(argument(command, "hvals")));
                case "HLEN" -> RespWriter.integer(store.hlen(argument(command, "hlen")));
                case "HDEL" -> hdel(command);
                case "HEXISTS" -> hexists(command);
                case "SADD" -> sadd(command);
                case "SREM" -> srem(command);
                case "SMEMBERS" -> encodeStrings(store.smembers(argument(command, "smembers")));
                case "SCARD" -> RespWriter.integer(store.scard(argument(command, "scard")));
                case "SISMEMBER" -> sismember(command);
                case "CONFIG" -> config(command);
                case "INFO" -> info(command);
                case "DBSIZE" -> RespWriter.integer(store.keys("*").size());
                case "FLUSHALL" -> flushall(command);
                // redis-cli sends this on connect, an empty array keeps it happy
                case "COMMAND" -> RespWriter.array();
                case "TTL" -> ttl(command, 1000);
                case "PTTL" -> ttl(command, 1);
                case "EXPIRE" -> expire(command, 1000, "expire");
                case "PEXPIRE" -> expire(command, 1, "pexpire");
                case "PERSIST" -> persist(command);
                case "MGET" -> mget(command);
                case "MSET" -> mset(command);
                case "SETNX" -> setnx(command);
                case "APPEND" -> append(command);
                case "STRLEN" -> strlen(command);
                case "GETDEL" -> getdel(command);
                default ->  RespWriter.error("ERR unknown command '" + command[0] + "'");
            };
        }catch (WrongTypeException e){
            return RespWriter.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }catch (ArityException e){
            return RespWriter.error("ERR wrong number of arguments for '" + e.getMessage() + "' command");
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

    // TTL reports seconds, PTTL milliseconds, both using the same -2/-1 sentinels
    private byte[] ttl(String[] command, long divisor) {
        String name = divisor == 1 ? "pttl" : "ttl";
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for '" + name + "' command");
        }

        long millis = store.ttlMillis(command[1]);
        if (millis < 0) {
            return RespWriter.integer(millis);
        }
        // redis rounds seconds up, so TTL right after EXPIRE 5 says 5 rather than 4
        return RespWriter.integer((millis + divisor - 1) / divisor);
    }

    private byte[] expire(String[] command, long multiplier, String name) {
        if (command.length != 3) {
            return RespWriter.error("ERR wrong number of arguments for '" + name + "' command");
        }

        long amount;
        try {
            amount = Long.parseLong(command[2]);
        } catch (NumberFormatException e) {
            return RespWriter.error("ERR value is not an integer or out of range");
        }

        return RespWriter.integer(store.expire(command[1], amount * multiplier) ? 1 : 0);
    }

    private byte[] persist(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'persist' command");
        }
        return RespWriter.integer(store.persist(command[1]) ? 1 : 0);
    }

    private byte[] mget(String[] command) {
        if (command.length < 2) {
            return RespWriter.error("ERR wrong number of arguments for 'mget' command");
        }

        byte[][] values = new byte[command.length - 1][];
        for (int i = 1; i < command.length; i++) {
            String value;
            try {
                value = store.get(command[i]);
            } catch (WrongTypeException e) {
                // MGET never fails, a wrong-typed key just reads as nil
                value = null;
            }
            values[i - 1] = RespWriter.bulkString(value);
        }
        return RespWriter.array(values);
    }

    private byte[] mset(String[] command) {
        if (command.length < 3 || command.length % 2 != 1) {
            return RespWriter.error("ERR wrong number of arguments for 'mset' command");
        }
        for (int i = 1; i < command.length; i += 2) {
            store.set(command[i], command[i + 1]);
        }
        return RespWriter.simpleString("OK");
    }

    private byte[] setnx(String[] command) {
        if (command.length != 3) {
            return RespWriter.error("ERR wrong number of arguments for 'setnx' command");
        }
        return RespWriter.integer(store.setIfAbsent(command[1], command[2]) ? 1 : 0);
    }

    private byte[] append(String[] command) {
        if (command.length != 3) {
            return RespWriter.error("ERR wrong number of arguments for 'append' command");
        }
        return RespWriter.integer(store.append(command[1], command[2]));
    }

    private byte[] strlen(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'strlen' command");
        }
        String value = store.get(command[1]);
        return RespWriter.integer(value == null ? 0 : value.length());
    }

    private byte[] getdel(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'getdel' command");
        }
        return RespWriter.bulkString(store.getDelete(command[1]));
    }

    private byte[] config(String[] command) {
        if (command.length < 3 || !command[1].equalsIgnoreCase("GET")) {
            return RespWriter.error("ERR Unknown CONFIG subcommand or wrong number of arguments");
        }

        // the reply is a flat name/value array, and unknown names are simply left out
        List<byte[]> pairs = new ArrayList<>();
        for (int i = 2; i < command.length; i++) {
            String value = config.get(command[i]);
            if (value != null) {
                pairs.add(RespWriter.bulkString(command[i].toLowerCase(Locale.ROOT)));
                pairs.add(RespWriter.bulkString(value));
            }
        }
        return RespWriter.array(pairs.toArray(new byte[0][]));
    }

    private byte[] info(String[] command) {
        if (command.length > 2) {
            return RespWriter.error("ERR wrong number of arguments for 'info' command");
        }

        // one bulk string of key:value lines, sections separated by # headers
        String body = """
                # Server
                redis_version:7.0.0
                tcp_port:%d

                # Replication
                role:master
                connected_slaves:0

                # Keyspace
                db0:keys=%d
                """.formatted(config.port(), store.keys("*").size());

        return RespWriter.bulkString(body);
    }

    private byte[] flushall(String[] command) {
        if (command.length != 1) {
            return RespWriter.error("ERR wrong number of arguments for 'flushall' command");
        }
        store.clear();
        return RespWriter.simpleString("OK");
    }

    // the single-key commands all look the same, so they share the arity check
    private static String argument(String[] command, String name) {
        if (command.length != 2) {
            throw new ArityException(name);
        }
        return command[1];
    }

    private static byte[] encodeStrings(List<String> values) {
        byte[][] encoded = new byte[values.size()][];
        for (int i = 0; i < values.size(); i++) {
            encoded[i] = RespWriter.bulkString(values.get(i));
        }
        return RespWriter.array(encoded);
    }

    private byte[] hset(String[] command) {
        // key plus at least one field/value pair
        if (command.length < 4 || command.length % 2 != 0) {
            return RespWriter.error("ERR wrong number of arguments for 'hset' command");
        }
        List<String> pairs = Arrays.asList(Arrays.copyOfRange(command, 2, command.length));
        return RespWriter.integer(store.hset(command[1], pairs));
    }

    private byte[] hget(String[] command) {
        if (command.length != 3) {
            return RespWriter.error("ERR wrong number of arguments for 'hget' command");
        }
        return RespWriter.bulkString(store.hget(command[1], command[2]));
    }

    private byte[] hdel(String[] command) {
        if (command.length < 3) {
            return RespWriter.error("ERR wrong number of arguments for 'hdel' command");
        }
        return RespWriter.integer(
                store.hdel(command[1], Arrays.copyOfRange(command, 2, command.length)));
    }

    private byte[] hexists(String[] command) {
        if (command.length != 3) {
            return RespWriter.error("ERR wrong number of arguments for 'hexists' command");
        }
        return RespWriter.integer(store.hexists(command[1], command[2]) ? 1 : 0);
    }

    private byte[] sadd(String[] command) {
        if (command.length < 3) {
            return RespWriter.error("ERR wrong number of arguments for 'sadd' command");
        }
        return RespWriter.integer(
                store.sadd(command[1], Arrays.copyOfRange(command, 2, command.length)));
    }

    private byte[] srem(String[] command) {
        if (command.length < 3) {
            return RespWriter.error("ERR wrong number of arguments for 'srem' command");
        }
        return RespWriter.integer(
                store.srem(command[1], Arrays.copyOfRange(command, 2, command.length)));
    }

    private byte[] sismember(String[] command) {
        if (command.length != 3) {
            return RespWriter.error("ERR wrong number of arguments for 'sismember' command");
        }
        return RespWriter.integer(store.sismember(command[1], command[2]) ? 1 : 0);
    }

    private byte[] xadd(String[] command) {
        // key, id, then at least one field/value pair
        if (command.length < 5 || (command.length - 3) % 2 != 0) {
            return RespWriter.error("ERR wrong number of arguments for 'xadd' command");
        }

        List<String> fields = Arrays.asList(Arrays.copyOfRange(command, 3, command.length));
        try {
            return RespWriter.bulkString(store.xadd(command[1], command[2], fields));
        } catch (IllegalArgumentException e) {
            // the store carries redis's own wording, so pass it straight through
            return RespWriter.error(e.getMessage());
        }
    }

    private byte[] xrange(String[] command) {
        if (command.length != 4) {
            return RespWriter.error("ERR wrong number of arguments for 'xrange' command");
        }
        try {
            return encodeEntries(store.xrange(command[1], command[2], command[3]));
        } catch (NumberFormatException e) {
            return RespWriter.error("ERR Invalid stream ID specified as stream command argument");
        }
    }

    private byte[] xlen(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'xlen' command");
        }
        return RespWriter.integer(store.xlen(command[1]));
    }

    // XREAD [BLOCK ms] STREAMS key [key...] id [id...] - the halves after STREAMS must match up
    private byte[] xread(String[] command) {
        long blockMillis = -1;
        if (command.length > 2 && command[1].equalsIgnoreCase("BLOCK")) {
            try {
                blockMillis = Long.parseLong(command[2]);
            } catch (NumberFormatException e) {
                return RespWriter.error("ERR timeout is not an integer or out of range");
            }
            if (blockMillis < 0) {
                return RespWriter.error("ERR timeout is negative");
            }
        }

        int streamsAt = -1;
        for (int i = 1; i < command.length; i++) {
            if (command[i].equalsIgnoreCase("STREAMS")) {
                streamsAt = i;
                break;
            }
        }
        if (streamsAt < 0) {
            return RespWriter.error("ERR syntax error");
        }

        int remaining = command.length - streamsAt - 1;
        if (remaining < 2 || remaining % 2 != 0) {
            return RespWriter.error(
                    "ERR Unbalanced XREAD list of streams: for each stream key an ID or '$' must be specified.");
        }

        int pairs = remaining / 2;
        String[] keys = new String[pairs];
        String[] ids = new String[pairs];
        try {
            for (int i = 0; i < pairs; i++) {
                keys[i] = command[streamsAt + 1 + i];
                String id = command[streamsAt + 1 + pairs + i];

                // $ has to be pinned now - re-resolving it each retry would never match anything
                ids[i] = id.equals("$") ? store.lastStreamId(keys[i]).toString() : id;
            }
        } catch (NumberFormatException e) {
            return RespWriter.error("ERR Invalid stream ID specified as stream command argument");
        }

        long deadline = blockMillis == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + blockMillis;

        while (true) {
            List<byte[]> results = new ArrayList<>();
            try {
                for (int i = 0; i < pairs; i++) {
                    List<StreamEntry> entries = store.xread(keys[i], ids[i]);
                    if (entries.isEmpty()) {
                        // streams with nothing new are left out entirely
                        continue;
                    }
                    results.add(RespWriter.array(
                            RespWriter.bulkString(keys[i]), encodeEntries(entries)));
                }
            } catch (NumberFormatException e) {
                return RespWriter.error("ERR Invalid stream ID specified as stream command argument");
            }

            if (!results.isEmpty()) {
                return RespWriter.array(results.toArray(new byte[0][]));
            }
            if (blockMillis < 0) {
                return RespWriter.nullArray();
            }

            long timeLeft = deadline - System.currentTimeMillis();
            if (timeLeft <= 0) {
                return RespWriter.nullArray();
            }
            try {
                store.awaitWrite(timeLeft);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RespWriter.nullArray();
            }
        }
    }

    // each entry is [id, [field, value, ...]]
    private static byte[] encodeEntries(List<StreamEntry> entries) {
        byte[][] encoded = new byte[entries.size()][];
        for (int i = 0; i < entries.size(); i++) {
            StreamEntry entry = entries.get(i);

            List<String> fields = entry.fields();
            byte[][] encodedFields = new byte[fields.size()][];
            for (int f = 0; f < fields.size(); f++) {
                encodedFields[f] = RespWriter.bulkString(fields.get(f));
            }

            encoded[i] = RespWriter.array(
                    RespWriter.bulkString(entry.id().toString()),
                    RespWriter.array(encodedFields));
        }
        return RespWriter.array(encoded);
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

        // optimistic locking: if anything we watched moved, the whole transaction is off
        for (Map.Entry<String, Long> watched : session.watched().entrySet()) {
            if (store.version(watched.getKey()) != watched.getValue()) {
                session.reset();
                return RespWriter.nullArray();
            }
        }

        session.watched().clear();
        List<String[]> commands = session.drain();
        byte[][] replies = new byte[commands.size()][];
        for (int i = 0; i < commands.size(); i++) {
            // a failing command inside a transaction doesn't stop the others
            replies[i] = execute(commands.get(i), session);
        }
        return RespWriter.array(replies);
    }

    // WATCH records what a key looks like now, EXEC checks it hasn't moved since
    private byte[] watch(String[] command, ClientSession session) {
        if (command.length < 2) {
            return RespWriter.error("ERR wrong number of arguments for 'watch' command");
        }
        if (session.inTransaction()) {
            return RespWriter.error("ERR WATCH inside MULTI is not allowed");
        }

        for (int i = 1; i < command.length; i++) {
            session.watched().put(command[i], store.version(command[i]));
        }
        return RespWriter.simpleString("OK");
    }

    private byte[] unwatch(String[] command, ClientSession session) {
        if (command.length != 1) {
            return RespWriter.error("ERR wrong number of arguments for 'unwatch' command");
        }
        session.watched().clear();
        return RespWriter.simpleString("OK");
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

    private byte[] rpop(String[] command) {
        if (command.length != 2) {
            return RespWriter.error("ERR wrong number of arguments for 'rpop' command");
        }
        return RespWriter.bulkString(store.rpop(command[1]));
    }

    private byte[] lindex(String[] command) {
        if (command.length != 3) {
            return RespWriter.error("ERR wrong number of arguments for 'lindex' command");
        }

        long index;
        try {
            index = Long.parseLong(command[2]);
        } catch (NumberFormatException e) {
            return RespWriter.error("ERR value is not an integer or out of range");
        }
        return RespWriter.bulkString(store.lindex(command[1], index));
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
