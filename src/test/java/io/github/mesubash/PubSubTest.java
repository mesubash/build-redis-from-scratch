package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PubSubTest {

    private final CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());

    // captures what the server pushes to this client without being asked
    private final ByteArrayOutputStream pushed = new ByteArrayOutputStream();
    private final ClientSession subscriber = new ClientSession(pushed);
    private final ClientSession publisher = new ClientSession();

    private String reply(ClientSession session, String... command) {
        return new String(dispatcher.execute(command, session), StandardCharsets.UTF_8);
    }

    private String pushedBytes() {
        return pushed.toString(StandardCharsets.UTF_8);
    }

    @Test
    void subscribeConfirmsWithARunningCount() {
        assertEquals("*3\r\n$9\r\nsubscribe\r\n$4\r\nnews\r\n:1\r\n",
                reply(subscriber, "SUBSCRIBE", "news"));
        assertEquals("*3\r\n$9\r\nsubscribe\r\n$6\r\nsports\r\n:2\r\n",
                reply(subscriber, "SUBSCRIBE", "sports"));
    }

    @Test
    void subscribingToSeveralChannelsAtOnceRepliesOncePerChannel() {
        String result = reply(subscriber, "SUBSCRIBE", "a", "b");
        assertEquals("*3\r\n$9\r\nsubscribe\r\n$1\r\na\r\n:1\r\n"
                + "*3\r\n$9\r\nsubscribe\r\n$1\r\nb\r\n:2\r\n", result);
    }

    @Test
    void publishDeliversToSubscribers() {
        reply(subscriber, "SUBSCRIBE", "news");
        assertEquals(":1\r\n", reply(publisher, "PUBLISH", "news", "hello"));

        assertEquals("*3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$5\r\nhello\r\n", pushedBytes());
    }

    @Test
    void publishToNobodyReturnsZero() {
        assertEquals(":0\r\n", reply(publisher, "PUBLISH", "empty", "hello"));
    }

    @Test
    void unsubscribedClientsStopReceiving() {
        reply(subscriber, "SUBSCRIBE", "news");
        reply(subscriber, "UNSUBSCRIBE", "news");
        pushed.reset();

        assertEquals(":0\r\n", reply(publisher, "PUBLISH", "news", "hello"));
        assertEquals("", pushedBytes());
    }

    @Test
    void bareUnsubscribeLeavesEveryChannel() {
        reply(subscriber, "SUBSCRIBE", "a", "b");
        String result = reply(subscriber, "UNSUBSCRIBE");

        assertTrue(result.contains("$1\r\na\r\n"));
        assertTrue(result.contains("$1\r\nb\r\n"));
        assertEquals(":0\r\n", reply(publisher, "PUBLISH", "a", "x"));
    }

    @Test
    void subscribedClientsAreRestrictedToASmallCommandSet() {
        reply(subscriber, "SUBSCRIBE", "news");

        assertEquals("+PONG\r\n", reply(subscriber, "PING"));
        assertTrue(reply(subscriber, "GET", "k").startsWith("-ERR Can't execute 'get'"));
        assertTrue(reply(subscriber, "SET", "k", "v").startsWith("-ERR Can't execute 'set'"));
    }

    @Test
    void otherClientsAreNotRestricted() {
        reply(subscriber, "SUBSCRIBE", "news");
        assertEquals("+OK\r\n", reply(publisher, "SET", "k", "v"));
    }

    @Test
    void disconnectRemovesSubscriptions() {
        reply(subscriber, "SUBSCRIBE", "news");
        dispatcher.onDisconnect(subscriber);

        assertEquals(":0\r\n", reply(publisher, "PUBLISH", "news", "hello"));
    }

    @Test
    void manySubscribersAllReceive() {
        ByteArrayOutputStream secondOut = new ByteArrayOutputStream();
        ClientSession second = new ClientSession(secondOut);

        reply(subscriber, "SUBSCRIBE", "news");
        reply(second, "SUBSCRIBE", "news");

        assertEquals(":2\r\n", reply(publisher, "PUBLISH", "news", "hi"));
        assertEquals(pushedBytes(), secondOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    void publishRejectsWrongArity() {
        assertEquals("-ERR wrong number of arguments for 'publish' command\r\n",
                reply(publisher, "PUBLISH", "channel"));
        assertEquals("-ERR wrong number of arguments for 'subscribe' command\r\n",
                reply(subscriber, "SUBSCRIBE"));
    }
}
