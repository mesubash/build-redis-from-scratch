package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashAndSetTest {

    private long now = 0;
    private final RedisStore store = new RedisStore(() -> now);
    private final CommandDispatcher dispatcher = new CommandDispatcher(store);

    private String reply(String... command) {
        return new String(dispatcher.execute(command), StandardCharsets.UTF_8);
    }

    private static final String WRONG_TYPE =
            "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";

    @Test
    void hsetCountsOnlyNewFields() {
        assertEquals(":2\r\n", reply("HSET", "h", "a", "1", "b", "2"));
        // overwriting an existing field adds nothing
        assertEquals(":0\r\n", reply("HSET", "h", "a", "9"));
        assertEquals(":1\r\n", reply("HSET", "h", "c", "3"));
    }

    @Test
    void hgetReadsFieldsAndNullsMissingOnes() {
        reply("HSET", "h", "a", "1");
        assertEquals("$1\r\n1\r\n", reply("HGET", "h", "a"));
        assertEquals("$-1\r\n", reply("HGET", "h", "missing"));
        assertEquals("$-1\r\n", reply("HGET", "missing", "a"));
    }

    @Test
    void hgetallIsAFlatFieldValueArray() {
        reply("HSET", "h", "a", "1", "b", "2");
        assertEquals("*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$1\r\n2\r\n", reply("HGETALL", "h"));
        assertEquals("*0\r\n", reply("HGETALL", "missing"));
    }

    @Test
    void hkeysHvalsAndHlen() {
        reply("HSET", "h", "a", "1", "b", "2");
        assertEquals("*2\r\n$1\r\na\r\n$1\r\nb\r\n", reply("HKEYS", "h"));
        assertEquals("*2\r\n$1\r\n1\r\n$1\r\n2\r\n", reply("HVALS", "h"));
        assertEquals(":2\r\n", reply("HLEN", "h"));
        assertEquals(":0\r\n", reply("HLEN", "missing"));
    }

    @Test
    void hexistsAndHdel() {
        reply("HSET", "h", "a", "1", "b", "2");
        assertEquals(":1\r\n", reply("HEXISTS", "h", "a"));
        assertEquals(":0\r\n", reply("HEXISTS", "h", "z"));

        assertEquals(":1\r\n", reply("HDEL", "h", "a", "z"));
        assertEquals(":0\r\n", reply("HEXISTS", "h", "a"));
    }

    @Test
    void deletingTheLastFieldRemovesTheKey() {
        reply("HSET", "h", "a", "1");
        reply("HDEL", "h", "a");
        assertEquals(":0\r\n", reply("EXISTS", "h"));
    }

    @Test
    void saddCountsOnlyNewMembers() {
        assertEquals(":2\r\n", reply("SADD", "s", "a", "b"));
        assertEquals(":0\r\n", reply("SADD", "s", "a"));
        assertEquals(":1\r\n", reply("SADD", "s", "c"));
        assertEquals(":3\r\n", reply("SCARD", "s"));
    }

    @Test
    void duplicatesInOneCommandCountOnce() {
        assertEquals(":1\r\n", reply("SADD", "s", "a", "a", "a"));
    }

    @Test
    void smembersAndSismember() {
        reply("SADD", "s", "a", "b");
        assertEquals("*2\r\n$1\r\na\r\n$1\r\nb\r\n", reply("SMEMBERS", "s"));
        assertEquals(":1\r\n", reply("SISMEMBER", "s", "a"));
        assertEquals(":0\r\n", reply("SISMEMBER", "s", "z"));
        assertEquals("*0\r\n", reply("SMEMBERS", "missing"));
        assertEquals(":0\r\n", reply("SCARD", "missing"));
    }

    @Test
    void sremRemovesAndDeletesAnEmptySet() {
        reply("SADD", "s", "a", "b");
        assertEquals(":1\r\n", reply("SREM", "s", "a", "z"));
        assertEquals(":1\r\n", reply("SREM", "s", "b"));
        assertEquals(":0\r\n", reply("EXISTS", "s"));
    }

    @Test
    void typeReportsHashAndSet() {
        reply("HSET", "h", "a", "1");
        reply("SADD", "s", "a");
        assertEquals("+hash\r\n", reply("TYPE", "h"));
        assertEquals("+set\r\n", reply("TYPE", "s"));
    }

    @Test
    void typesDoNotMix() {
        reply("SET", "str", "x");
        assertEquals(WRONG_TYPE, reply("HSET", "str", "a", "1"));
        assertEquals(WRONG_TYPE, reply("SADD", "str", "a"));

        reply("HSET", "h", "a", "1");
        assertEquals(WRONG_TYPE, reply("GET", "h"));
        assertEquals(WRONG_TYPE, reply("SADD", "h", "x"));
        assertEquals(WRONG_TYPE, reply("LPUSH", "h", "x"));

        reply("SADD", "s", "a");
        assertEquals(WRONG_TYPE, reply("HGET", "s", "a"));
        assertEquals(WRONG_TYPE, reply("GET", "s"));
    }

    @Test
    void hashesAndSetsRespectExpiry() {
        reply("HSET", "h", "a", "1");
        reply("SADD", "s", "a");
        reply("EXPIRE", "h", "1");
        reply("EXPIRE", "s", "1");

        now = 2_000_000_000L;
        assertEquals(":0\r\n", reply("HLEN", "h"));
        assertEquals(":0\r\n", reply("SCARD", "s"));
        assertEquals("+none\r\n", reply("TYPE", "h"));
    }

    @Test
    void readsReturnCopies() {
        reply("HSET", "h", "a", "1");
        store.hgetall("h").clear();
        assertEquals(":1\r\n", reply("HLEN", "h"));

        reply("SADD", "s", "a");
        store.smembers("s").clear();
        assertEquals(":1\r\n", reply("SCARD", "s"));
    }

    @Test
    void wrongArityIsReported() {
        assertEquals("-ERR wrong number of arguments for 'hset' command\r\n",
                reply("HSET", "h", "a"));
        assertEquals("-ERR wrong number of arguments for 'hget' command\r\n", reply("HGET", "h"));
        assertEquals("-ERR wrong number of arguments for 'hgetall' command\r\n",
                reply("HGETALL", "h", "extra"));
        assertEquals("-ERR wrong number of arguments for 'sadd' command\r\n", reply("SADD", "s"));
        assertEquals("-ERR wrong number of arguments for 'scard' command\r\n", reply("SCARD"));
    }

    @Test
    void watchNoticesHashAndSetWrites() {
        ClientSession watcher = new ClientSession();
        dispatcher.execute(new String[]{"WATCH", "h"}, watcher);
        reply("HSET", "h", "a", "1");
        dispatcher.execute(new String[]{"MULTI"}, watcher);
        dispatcher.execute(new String[]{"PING"}, watcher);

        assertTrue(new String(dispatcher.execute(new String[]{"EXEC"}, watcher),
                StandardCharsets.UTF_8).equals("*-1\r\n"));
    }
}
