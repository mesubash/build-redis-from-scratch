package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WatchTest {

    private final RedisStore store = new RedisStore();
    private final CommandDispatcher dispatcher = new CommandDispatcher(store);
    private final ClientSession session = new ClientSession();

    private String reply(String... command) {
        return new String(dispatcher.execute(command, session), StandardCharsets.UTF_8);
    }

    // a second connection, standing in for another client racing us
    private String otherClient(String... command) {
        return new String(dispatcher.execute(command, new ClientSession()), StandardCharsets.UTF_8);
    }

    @Test
    void execRunsWhenNothingTouchedTheWatchedKey() {
        reply("SET", "k", "1");
        assertEquals("+OK\r\n", reply("WATCH", "k"));
        reply("MULTI");
        reply("SET", "k", "2");

        assertEquals("*1\r\n+OK\r\n", reply("EXEC"));
        assertEquals("$1\r\n2\r\n", otherClient("GET", "k"));
    }

    @Test
    void execAbortsWhenAWatchedKeyChanged() {
        reply("SET", "k", "1");
        reply("WATCH", "k");

        // another client gets in first
        otherClient("SET", "k", "999");

        reply("MULTI");
        reply("SET", "k", "2");

        // null array, the signal a client retries on
        assertEquals("*-1\r\n", reply("EXEC"));
        assertEquals("$3\r\n999\r\n", otherClient("GET", "k"));
    }

    @Test
    void deletingAWatchedKeyAlsoAborts() {
        reply("SET", "k", "1");
        reply("WATCH", "k");
        otherClient("DEL", "k");

        reply("MULTI");
        reply("SET", "other", "x");
        assertEquals("*-1\r\n", reply("EXEC"));
        assertEquals("$-1\r\n", otherClient("GET", "other"));
    }

    @Test
    void creatingAWatchedKeyThatDidNotExistAborts() {
        reply("WATCH", "fresh");
        otherClient("SET", "fresh", "now here");

        reply("MULTI");
        reply("SET", "k", "1");
        assertEquals("*-1\r\n", reply("EXEC"));
    }

    @Test
    void everyMutatingCommandInvalidatesAWatch() {
        // if a command mutates without bumping the version, WATCH silently stops working
        assertAborts("INCR", "k");
        assertAborts("RPUSH", "k", "x");
        assertAborts("APPEND", "k", "x");
        assertAborts("SETNX", "k", "x");
        assertAborts("XADD", "k", "1-1", "f", "v");
        assertAborts("MSET", "k", "x");
    }

    private void assertAborts(String... mutation) {
        RedisStore freshStore = new RedisStore();
        CommandDispatcher freshDispatcher = new CommandDispatcher(freshStore);
        ClientSession watcher = new ClientSession();

        freshDispatcher.execute(new String[]{"WATCH", "k"}, watcher);
        freshDispatcher.execute(mutation, new ClientSession());
        freshDispatcher.execute(new String[]{"MULTI"}, watcher);
        freshDispatcher.execute(new String[]{"PING"}, watcher);

        String result = new String(
                freshDispatcher.execute(new String[]{"EXEC"}, watcher), StandardCharsets.UTF_8);
        assertEquals("*-1\r\n", result, "watch survived " + mutation[0]);
    }

    @Test
    void ourOwnWritesInsideTheTransactionDoNotAbortIt() {
        reply("SET", "k", "1");
        reply("WATCH", "k");
        reply("MULTI");
        reply("SET", "k", "2");
        reply("INCR", "counter");

        assertEquals("*2\r\n+OK\r\n:1\r\n", reply("EXEC"));
    }

    @Test
    void unwatchClearsTheWatch() {
        reply("SET", "k", "1");
        reply("WATCH", "k");
        assertEquals("+OK\r\n", reply("UNWATCH"));

        otherClient("SET", "k", "999");
        reply("MULTI");
        reply("SET", "other", "x");
        assertEquals("*1\r\n+OK\r\n", reply("EXEC"));
    }

    @Test
    void execAndDiscardBothEndTheWatch() {
        reply("SET", "k", "1");
        reply("WATCH", "k");
        reply("MULTI");
        reply("DISCARD");

        otherClient("SET", "k", "999");
        reply("MULTI");
        reply("SET", "other", "x");
        assertEquals("*1\r\n+OK\r\n", reply("EXEC"));
    }

    @Test
    void watchInsideMultiIsRejected() {
        reply("MULTI");
        assertEquals("-ERR WATCH inside MULTI is not allowed\r\n", reply("WATCH", "k"));
    }

    @Test
    void watchesAreNotSharedBetweenConnections() {
        reply("SET", "k", "1");
        reply("WATCH", "k");

        // the other client never watched anything, so its EXEC runs
        ClientSession other = new ClientSession();
        dispatcher.execute(new String[]{"MULTI"}, other);
        dispatcher.execute(new String[]{"SET", "k", "2"}, other);
        assertEquals("*1\r\n+OK\r\n",
                new String(dispatcher.execute(new String[]{"EXEC"}, other), StandardCharsets.UTF_8));
    }

    @Test
    void watchRejectsWrongArity() {
        assertEquals("-ERR wrong number of arguments for 'watch' command\r\n", reply("WATCH"));
        assertEquals("-ERR wrong number of arguments for 'unwatch' command\r\n",
                reply("UNWATCH", "k"));
    }
}
