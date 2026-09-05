package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionTest {

    private final CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());
    private final ClientSession session = new ClientSession();

    private String reply(String... command) {
        return new String(dispatcher.execute(command, session), StandardCharsets.UTF_8);
    }

    // a second connection, to prove queued commands haven't run yet
    private String otherClient(String... command) {
        return new String(dispatcher.execute(command, new ClientSession()), StandardCharsets.UTF_8);
    }

    @Test
    void commandsAreQueuedThenRunOnExec() {
        assertEquals("+OK\r\n", reply("MULTI"));
        assertEquals("+QUEUED\r\n", reply("SET", "k", "v"));
        assertEquals("+QUEUED\r\n", reply("INCR", "n"));

        // nothing has actually happened yet
        assertEquals("$-1\r\n", otherClient("GET", "k"));

        assertEquals("*2\r\n+OK\r\n:1\r\n", reply("EXEC"));
        assertEquals("$1\r\nv\r\n", otherClient("GET", "k"));
    }

    @Test
    void discardThrowsTheQueueAway() {
        reply("MULTI");
        reply("SET", "k", "v");
        assertEquals("+OK\r\n", reply("DISCARD"));

        assertEquals("$-1\r\n", otherClient("GET", "k"));
        assertEquals("-ERR EXEC without MULTI\r\n", reply("EXEC"));
    }

    @Test
    void execWithoutMultiIsAnError() {
        assertEquals("-ERR EXEC without MULTI\r\n", reply("EXEC"));
    }

    @Test
    void discardWithoutMultiIsAnError() {
        assertEquals("-ERR DISCARD without MULTI\r\n", reply("DISCARD"));
    }

    @Test
    void multiCannotBeNested() {
        reply("MULTI");
        assertEquals("-ERR MULTI calls can not be nested\r\n", reply("MULTI"));
    }

    @Test
    void emptyTransactionReturnsEmptyArray() {
        reply("MULTI");
        assertEquals("*0\r\n", reply("EXEC"));
    }

    @Test
    void unknownCommandAbortsTheWholeTransaction() {
        reply("MULTI");
        reply("SET", "k", "v");
        assertEquals("-ERR unknown command 'nope'\r\n", reply("nope"));

        assertEquals("-EXECABORT Transaction discarded because of previous errors.\r\n",
                reply("EXEC"));

        // the good command was thrown away with the bad one
        assertEquals("$-1\r\n", otherClient("GET", "k"));
    }

    @Test
    void aFailingCommandDoesNotStopTheOthers() {
        // redis has no rollback - errors are reported per command
        reply("SET", "word", "hello");
        reply("MULTI");
        reply("INCR", "word");
        reply("SET", "after", "ran");

        String result = reply("EXEC");
        assertEquals("*2\r\n-ERR value is not an integer or out of range\r\n+OK\r\n", result);
        assertEquals("$3\r\nran\r\n", otherClient("GET", "after"));
    }

    @Test
    void sessionIsUsableAgainAfterExec() {
        reply("MULTI");
        reply("SET", "k", "v");
        reply("EXEC");

        assertEquals("+PONG\r\n", reply("PING"));
        assertEquals("+OK\r\n", reply("MULTI"));
    }

    @Test
    void transactionsAreNotSharedBetweenConnections() {
        reply("MULTI");
        // the other connection is not in a transaction, so this runs immediately
        assertEquals("+PONG\r\n", otherClient("PING"));
    }
}
