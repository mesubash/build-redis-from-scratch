package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RespWriterTest {

    private static String encoded(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void writesSimpleString() {
        assertEquals("+OK\r\n", encoded(RespWriter.simpleString("OK")));
        assertEquals("+PONG\r\n", encoded(RespWriter.simpleString("PONG")));
    }

    @Test
    void writesError() {
        assertEquals("-ERR unknown command 'foo'\r\n",
                encoded(RespWriter.error("ERR unknown command 'foo'")));
    }

    @Test
    void writesIntegers() {
        assertEquals(":42\r\n", encoded(RespWriter.integer(42)));
        assertEquals(":-1\r\n", encoded(RespWriter.integer(-1)));
        assertEquals(":0\r\n", encoded(RespWriter.integer(0)));
    }

    @Test
    void writesBulkString() {
        assertEquals("$5\r\nhello\r\n", encoded(RespWriter.bulkString("hello")));
    }

    @Test
    void emptyBulkStringIsNotNull() {
        assertEquals("$0\r\n\r\n", encoded(RespWriter.bulkString("")));
    }

    @Test
    void nullBulkString() {
        assertEquals("$-1\r\n", encoded(RespWriter.bulkString(null)));
    }

    @Test
    void bulkStringLengthIsBytesNotCharacters() {
        // café is 4 characters but 5 bytes in UTF-8
        assertEquals("$5\r\ncafé\r\n", encoded(RespWriter.bulkString("café")));
    }

    @Test
    void bulkStringMayContainCrlf() {
        assertEquals("$5\r\na\r\nbc\r\n", encoded(RespWriter.bulkString("a\r\nbc")));
    }

    @Test
    void writesArray() {
        assertEquals("*2\r\n$1\r\na\r\n$1\r\nb\r\n",
                encoded(RespWriter.array(RespWriter.bulkString("a"), RespWriter.bulkString("b"))));
    }

    @Test
    void writesEmptyArray() {
        assertEquals("*0\r\n", encoded(RespWriter.array()));
    }

    @Test
    void writesNestedArray() {
        assertEquals("*2\r\n:1\r\n*1\r\n$1\r\nx\r\n",
                encoded(RespWriter.array(
                        RespWriter.integer(1),
                        RespWriter.array(RespWriter.bulkString("x")))));
    }

    @Test
    void arrayMayContainNullElements() {
        // GET on a missing key inside a multi-value reply
        assertEquals("*2\r\n$1\r\na\r\n$-1\r\n",
                encoded(RespWriter.array(RespWriter.bulkString("a"), RespWriter.bulkString(null))));
    }

    @Test
    void writerOutputParsesBackAsACommand() {
        // the two protocol classes must agree: encode an array, parse it back
        byte[] encodedCommand = RespWriter.array(
                RespWriter.bulkString("ECHO"), RespWriter.bulkString("a\r\nbc"));

        RespParser parser = new RespParser();
        parser.append(encodedCommand, encodedCommand.length);

        String[] command = parser.next();
        assertEquals("ECHO", command[0]);
        assertEquals("a\r\nbc", command[1]);
    }
}
