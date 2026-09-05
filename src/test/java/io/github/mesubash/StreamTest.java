package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamTest {

    private final CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());

    private String reply(String... command) {
        return new String(dispatcher.execute(command), StandardCharsets.UTF_8);
    }

    @Test
    void xaddReturnsTheIdItUsed() {
        assertEquals("$3\r\n1-1\r\n", reply("XADD", "s", "1-1", "temp", "36"));
        assertEquals("$3\r\n1-2\r\n", reply("XADD", "s", "1-2", "temp", "37"));
    }

    @Test
    void xaddAutoGeneratesTheSequence() {
        reply("XADD", "s", "5-1", "a", "1");
        assertEquals("$3\r\n5-2\r\n", reply("XADD", "s", "5-*", "a", "2"));

        // a new millisecond restarts the sequence
        assertEquals("$3\r\n6-0\r\n", reply("XADD", "s", "6-*", "a", "3"));
    }

    @Test
    void xaddAutoGeneratesTheWholeId() {
        String result = reply("XADD", "s", "*", "a", "1");
        assertTrue(result.matches("\\$\\d+\r\n\\d+-0\r\n"), result);
    }

    @Test
    void idsMustIncrease() {
        reply("XADD", "s", "5-5", "a", "1");
        assertEquals("-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n",
                reply("XADD", "s", "5-5", "a", "2"));
        assertEquals("-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n",
                reply("XADD", "s", "4-0", "a", "2"));
    }

    @Test
    void zeroIdIsRejected() {
        assertEquals("-ERR The ID specified in XADD must be greater than 0-0\r\n",
                reply("XADD", "s", "0-0", "a", "1"));
    }

    @Test
    void sequenceForTimeZeroStartsAtOne() {
        // 0-0 is forbidden, so the first auto sequence at time 0 has to be 1
        assertEquals("$3\r\n0-1\r\n", reply("XADD", "s", "0-*", "a", "1"));
    }

    @Test
    void xrangeIsInclusiveAtBothEnds() {
        reply("XADD", "s", "1-1", "a", "1");
        reply("XADD", "s", "2-1", "a", "2");
        reply("XADD", "s", "3-1", "a", "3");

        String result = reply("XRANGE", "s", "1-1", "2-1");
        assertEquals("*2\r\n"
                + "*2\r\n$3\r\n1-1\r\n*2\r\n$1\r\na\r\n$1\r\n1\r\n"
                + "*2\r\n$3\r\n2-1\r\n*2\r\n$1\r\na\r\n$1\r\n2\r\n", result);
    }

    @Test
    void xrangeSupportsOpenEnds() {
        reply("XADD", "s", "1-1", "a", "1");
        reply("XADD", "s", "2-1", "a", "2");

        assertTrue(reply("XRANGE", "s", "-", "+").startsWith("*2\r\n"));
        assertEquals("*0\r\n", reply("XRANGE", "missing", "-", "+"));
    }

    @Test
    void xrangeTreatsABareMillisecondAsAWholeMillisecond() {
        reply("XADD", "s", "5-1", "a", "1");
        reply("XADD", "s", "5-2", "a", "2");
        reply("XADD", "s", "6-1", "a", "3");

        // "5" as a start means 5-0, as an end means 5-max
        assertTrue(reply("XRANGE", "s", "5", "5").startsWith("*2\r\n"));
    }

    @Test
    void xlenCountsEntries() {
        assertEquals(":0\r\n", reply("XLEN", "missing"));
        reply("XADD", "s", "1-1", "a", "1");
        reply("XADD", "s", "1-2", "a", "2");
        assertEquals(":2\r\n", reply("XLEN", "s"));
    }

    @Test
    void xreadIsExclusiveOfTheGivenId() {
        reply("XADD", "s", "1-1", "a", "1");
        reply("XADD", "s", "1-2", "a", "2");

        String result = reply("XREAD", "STREAMS", "s", "1-1");
        assertEquals("*1\r\n*2\r\n$1\r\ns\r\n"
                + "*1\r\n*2\r\n$3\r\n1-2\r\n*2\r\n$1\r\na\r\n$1\r\n2\r\n", result);
    }

    @Test
    void xreadWithNothingNewReturnsNullArray() {
        reply("XADD", "s", "1-1", "a", "1");
        assertEquals("*-1\r\n", reply("XREAD", "STREAMS", "s", "1-1"));
    }

    @Test
    void xreadDollarMeansOnlyWhatArrivesNext() {
        reply("XADD", "s", "1-1", "a", "1");
        assertEquals("*-1\r\n", reply("XREAD", "STREAMS", "s", "$"));
    }

    @Test
    void xreadAcrossSeveralStreams() {
        reply("XADD", "a", "1-1", "f", "1");
        reply("XADD", "b", "1-1", "f", "2");

        String result = reply("XREAD", "STREAMS", "a", "b", "0", "0");
        assertTrue(result.startsWith("*2\r\n"), result);
        assertTrue(result.contains("$1\r\na\r\n"));
        assertTrue(result.contains("$1\r\nb\r\n"));
    }

    @Test
    void xreadRejectsUnbalancedArguments() {
        assertEquals("-ERR Unbalanced XREAD list of streams: for each stream key an ID or '$' must be specified.\r\n",
                reply("XREAD", "STREAMS", "a", "b", "0"));
        assertEquals("-ERR syntax error\r\n", reply("XREAD", "a", "0"));
    }

    @Test
    void typeReportsStream() {
        reply("XADD", "s", "1-1", "a", "1");
        assertEquals("+stream\r\n", reply("TYPE", "s"));
    }

    @Test
    void streamsAndOtherTypesDoNotMix() {
        String wrongType = "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";

        reply("SET", "str", "hello");
        assertEquals(wrongType, reply("XADD", "str", "1-1", "a", "1"));

        reply("XADD", "s", "1-1", "a", "1");
        assertEquals(wrongType, reply("GET", "s"));
        assertEquals(wrongType, reply("LPUSH", "s", "x"));
    }

    @Test
    void xaddRejectsWrongArity() {
        assertEquals("-ERR wrong number of arguments for 'xadd' command\r\n", reply("XADD", "s", "1-1"));
        assertEquals("-ERR wrong number of arguments for 'xadd' command\r\n",
                reply("XADD", "s", "1-1", "field"));
    }
}
