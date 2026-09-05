package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringAndTtlCommandsTest {

    private long now = 0;
    private final RedisStore store = new RedisStore(() -> now);
    private final CommandDispatcher dispatcher = new CommandDispatcher(store);

    private String reply(String... command) {
        return new String(dispatcher.execute(command), StandardCharsets.UTF_8);
    }

    private static long millis(long ms) {
        return ms * 1_000_000L;
    }

    @Test
    void ttlSentinels() {
        // -2 missing, -1 no expiry
        assertEquals(":-2\r\n", reply("TTL", "missing"));
        reply("SET", "k", "v");
        assertEquals(":-1\r\n", reply("TTL", "k"));
    }

    @Test
    void ttlCountsDown() {
        reply("SET", "k", "v", "PX", "10000");
        assertEquals(":10\r\n", reply("TTL", "k"));
        assertEquals(":10000\r\n", reply("PTTL", "k"));

        now = millis(4000);
        assertEquals(":6\r\n", reply("TTL", "k"));
    }

    @Test
    void ttlOnAnExpiredKeyIsMinusTwo() {
        reply("SET", "k", "v", "PX", "100");
        now = millis(200);
        assertEquals(":-2\r\n", reply("TTL", "k"));
    }

    @Test
    void expireSetsADeadlineOnAnExistingKey() {
        reply("SET", "k", "v");
        assertEquals(":1\r\n", reply("EXPIRE", "k", "5"));
        assertEquals(":5\r\n", reply("TTL", "k"));

        now = millis(6000);
        assertEquals("$-1\r\n", reply("GET", "k"));
    }

    @Test
    void expireOnAMissingKeyReturnsZero() {
        assertEquals(":0\r\n", reply("EXPIRE", "missing", "5"));
    }

    @Test
    void pexpireUsesMilliseconds() {
        reply("SET", "k", "v");
        assertEquals(":1\r\n", reply("PEXPIRE", "k", "500"));
        assertEquals(":500\r\n", reply("PTTL", "k"));
    }

    @Test
    void expireKeepsTheValue() {
        reply("SET", "k", "v");
        reply("EXPIRE", "k", "5");
        assertEquals("$1\r\nv\r\n", reply("GET", "k"));
    }

    @Test
    void persistRemovesTheTtl() {
        reply("SET", "k", "v", "PX", "1000");
        assertEquals(":1\r\n", reply("PERSIST", "k"));
        assertEquals(":-1\r\n", reply("TTL", "k"));

        now = millis(5000);
        assertEquals("$1\r\nv\r\n", reply("GET", "k"));
    }

    @Test
    void persistReturnsZeroWhenThereWasNoTtl() {
        reply("SET", "k", "v");
        assertEquals(":0\r\n", reply("PERSIST", "k"));
        assertEquals(":0\r\n", reply("PERSIST", "missing"));
    }

    @Test
    void mgetReturnsOneEntryPerKeyIncludingNulls() {
        reply("SET", "a", "1");
        assertEquals("*3\r\n$1\r\n1\r\n$-1\r\n$-1\r\n", reply("MGET", "a", "b", "c"));
    }

    @Test
    void mgetDoesNotFailOnAWrongTypedKey() {
        reply("RPUSH", "list", "x");
        assertEquals("*1\r\n$-1\r\n", reply("MGET", "list"));
    }

    @Test
    void msetWritesEveryPair() {
        assertEquals("+OK\r\n", reply("MSET", "a", "1", "b", "2"));
        assertEquals("$1\r\n1\r\n", reply("GET", "a"));
        assertEquals("$1\r\n2\r\n", reply("GET", "b"));
    }

    @Test
    void msetRejectsAnOddNumberOfArguments() {
        assertEquals("-ERR wrong number of arguments for 'mset' command\r\n",
                reply("MSET", "a", "1", "b"));
    }

    @Test
    void setnxOnlyWritesWhenAbsent() {
        assertEquals(":1\r\n", reply("SETNX", "k", "first"));
        assertEquals(":0\r\n", reply("SETNX", "k", "second"));
        assertEquals("$5\r\nfirst\r\n", reply("GET", "k"));
    }

    @Test
    void setnxTreatsAnExpiredKeyAsAbsent() {
        reply("SET", "k", "old", "PX", "100");
        now = millis(200);
        assertEquals(":1\r\n", reply("SETNX", "k", "new"));
        assertEquals("$3\r\nnew\r\n", reply("GET", "k"));
    }

    @Test
    void appendCreatesOrExtends() {
        assertEquals(":5\r\n", reply("APPEND", "k", "hello"));
        assertEquals(":10\r\n", reply("APPEND", "k", "world"));
        assertEquals("$10\r\nhelloworld\r\n", reply("GET", "k"));
    }

    @Test
    void appendKeepsTheTtl() {
        reply("SET", "k", "a", "PX", "1000");
        reply("APPEND", "k", "b");
        now = millis(2000);
        assertEquals("$-1\r\n", reply("GET", "k"));
    }

    @Test
    void strlenCountsCharactersAndZeroForMissing() {
        reply("SET", "k", "hello");
        assertEquals(":5\r\n", reply("STRLEN", "k"));
        assertEquals(":0\r\n", reply("STRLEN", "missing"));
    }

    @Test
    void getdelReturnsThenRemoves() {
        reply("SET", "k", "v");
        assertEquals("$1\r\nv\r\n", reply("GETDEL", "k"));
        assertEquals(":0\r\n", reply("EXISTS", "k"));
        assertEquals("$-1\r\n", reply("GETDEL", "k"));
    }

    @Test
    void stringCommandsRejectListKeys() {
        String wrongType = "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        reply("RPUSH", "list", "x");

        assertEquals(wrongType, reply("APPEND", "list", "x"));
        assertEquals(wrongType, reply("GETDEL", "list"));
        assertEquals(wrongType, reply("STRLEN", "list"));
    }

    @Test
    void ttlCommandsRejectWrongArity() {
        assertEquals("-ERR wrong number of arguments for 'ttl' command\r\n", reply("TTL"));
        assertEquals("-ERR wrong number of arguments for 'expire' command\r\n", reply("EXPIRE", "k"));
        assertEquals("-ERR value is not an integer or out of range\r\n",
                reply("EXPIRE", "k", "soon"));
    }
}
