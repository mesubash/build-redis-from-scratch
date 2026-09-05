package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SortedSetTest {

    private long now = 0;
    private final RedisStore store = new RedisStore(() -> now);
    private final CommandDispatcher dispatcher = new CommandDispatcher(store);

    private String reply(String... command) {
        return new String(dispatcher.execute(command), StandardCharsets.UTF_8);
    }

    @Test
    void zaddCountsOnlyNewMembers() {
        assertEquals(":2\r\n", reply("ZADD", "z", "1", "a", "2", "b"));
        // rescoring an existing member adds nothing
        assertEquals(":0\r\n", reply("ZADD", "z", "9", "a"));
        assertEquals(":1\r\n", reply("ZADD", "z", "3", "c"));
    }

    @Test
    void membersComeBackInScoreOrder() {
        reply("ZADD", "z", "3", "c", "1", "a", "2", "b");
        assertEquals("*3\r\n$1\r\na\r\n$1\r\nb\r\n$1\r\nc\r\n", reply("ZRANGE", "z", "0", "-1"));
    }

    @Test
    void rescoringMovesAMember() {
        reply("ZADD", "z", "1", "a", "2", "b");
        reply("ZADD", "z", "99", "a");
        assertEquals("*2\r\n$1\r\nb\r\n$1\r\na\r\n", reply("ZRANGE", "z", "0", "-1"));
    }

    @Test
    void tiesBreakLexicographically() {
        reply("ZADD", "z", "1", "banana", "1", "apple", "1", "cherry");
        assertEquals("*3\r\n$5\r\napple\r\n$6\r\nbanana\r\n$6\r\ncherry\r\n",
                reply("ZRANGE", "z", "0", "-1"));
    }

    @Test
    void negativeScoresSortFirst() {
        reply("ZADD", "z", "-5", "low", "0", "mid", "5", "high");
        assertEquals("*3\r\n$3\r\nlow\r\n$3\r\nmid\r\n$4\r\nhigh\r\n", reply("ZRANGE", "z", "0", "-1"));
    }

    @Test
    void zrangeUsesTheSameIndexRulesAsLrange() {
        reply("ZADD", "z", "1", "a", "2", "b", "3", "c");
        assertEquals("*2\r\n$1\r\na\r\n$1\r\nb\r\n", reply("ZRANGE", "z", "0", "1"));
        assertEquals("*1\r\n$1\r\nc\r\n", reply("ZRANGE", "z", "-1", "-1"));
        assertEquals("*3\r\n$1\r\na\r\n$1\r\nb\r\n$1\r\nc\r\n", reply("ZRANGE", "z", "-100", "100"));
        assertEquals("*0\r\n", reply("ZRANGE", "z", "2", "1"));
        assertEquals("*0\r\n", reply("ZRANGE", "missing", "0", "-1"));
    }

    @Test
    void withScoresInterleavesMemberAndScore() {
        reply("ZADD", "z", "1", "a", "2.5", "b");
        assertEquals("*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$3\r\n2.5\r\n",
                reply("ZRANGE", "z", "0", "-1", "WITHSCORES"));
    }

    @Test
    void wholeScoresPrintWithoutADecimalPoint() {
        reply("ZADD", "z", "1", "a", "2.5", "b");
        assertEquals("$1\r\n1\r\n", reply("ZSCORE", "z", "a"));
        assertEquals("$3\r\n2.5\r\n", reply("ZSCORE", "z", "b"));
    }

    @Test
    void zscoreOnAMissingMemberIsNull() {
        reply("ZADD", "z", "1", "a");
        assertEquals("$-1\r\n", reply("ZSCORE", "z", "nobody"));
        assertEquals("$-1\r\n", reply("ZSCORE", "missing", "a"));
    }

    @Test
    void zrankIsZeroBasedAndNullWhenAbsent() {
        reply("ZADD", "z", "1", "a", "2", "b", "3", "c");
        assertEquals(":0\r\n", reply("ZRANK", "z", "a"));
        assertEquals(":2\r\n", reply("ZRANK", "z", "c"));

        // nil rather than 0, because 0 is a real rank
        assertEquals("$-1\r\n", reply("ZRANK", "z", "nobody"));
    }

    @Test
    void zcardAndZrem() {
        reply("ZADD", "z", "1", "a", "2", "b");
        assertEquals(":2\r\n", reply("ZCARD", "z"));
        assertEquals(":1\r\n", reply("ZREM", "z", "a", "nobody"));
        assertEquals(":1\r\n", reply("ZCARD", "z"));
    }

    @Test
    void removingTheLastMemberDeletesTheKey() {
        reply("ZADD", "z", "1", "a");
        reply("ZREM", "z", "a");
        assertEquals(":0\r\n", reply("EXISTS", "z"));
        assertEquals(":0\r\n", reply("ZCARD", "z"));
    }

    @Test
    void typeReportsZset() {
        reply("ZADD", "z", "1", "a");
        assertEquals("+zset\r\n", reply("TYPE", "z"));
    }

    @Test
    void typesDoNotMix() {
        String wrongType = "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";

        reply("SET", "str", "x");
        assertEquals(wrongType, reply("ZADD", "str", "1", "a"));

        reply("ZADD", "z", "1", "a");
        assertEquals(wrongType, reply("GET", "z"));
        assertEquals(wrongType, reply("SADD", "z", "x"));
        assertEquals(wrongType, reply("HGET", "z", "a"));
    }

    @Test
    void sortedSetsRespectExpiry() {
        reply("ZADD", "z", "1", "a");
        reply("EXPIRE", "z", "1");

        now = 2_000_000_000L;
        assertEquals(":0\r\n", reply("ZCARD", "z"));
        assertEquals("+none\r\n", reply("TYPE", "z"));
    }

    @Test
    void nonNumericScoreIsAnError() {
        assertEquals("-ERR value is not a valid float\r\n", reply("ZADD", "z", "high", "a"));
    }

    @Test
    void wrongArityIsReported() {
        assertEquals("-ERR wrong number of arguments for 'zadd' command\r\n", reply("ZADD", "z", "1"));
        assertEquals("-ERR wrong number of arguments for 'zscore' command\r\n", reply("ZSCORE", "z"));
        assertEquals("-ERR wrong number of arguments for 'zrange' command\r\n", reply("ZRANGE", "z", "0"));
        assertEquals("-ERR wrong number of arguments for 'zcard' command\r\n", reply("ZCARD"));
    }

    @Test
    void watchNoticesSortedSetWrites() {
        ClientSession watcher = new ClientSession();
        dispatcher.execute(new String[]{"WATCH", "z"}, watcher);
        reply("ZADD", "z", "1", "a");
        dispatcher.execute(new String[]{"MULTI"}, watcher);
        dispatcher.execute(new String[]{"PING"}, watcher);

        assertEquals("*-1\r\n", new String(
                dispatcher.execute(new String[]{"EXEC"}, watcher), StandardCharsets.UTF_8));
    }
}
