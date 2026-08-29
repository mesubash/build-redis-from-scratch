package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RespParserTest {

    private static RespParser parserWith(String input) {
        RespParser parser = new RespParser();
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        parser.append(bytes, bytes.length);
        return parser;
    }

    @Test
    void parsesSingleArgument() {
        assertArrayEquals(new String[]{"PING"},
                parserWith("*1\r\n$4\r\nPING\r\n").next());
    }

    @Test
    void parsesMultipleArguments() {
        assertArrayEquals(new String[]{"SET", "mykey", "myvalue"},
                parserWith("*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$7\r\nmyvalue\r\n").next());
    }

    @Test
    void parsesEmptyBulkString() {
        assertArrayEquals(new String[]{"ECHO", ""},
                parserWith("*2\r\n$4\r\nECHO\r\n$0\r\n\r\n").next());
    }

    @Test
    void parsesEmptyArray() {
        assertArrayEquals(new String[]{}, parserWith("*0\r\n").next());
    }

    @Test
    void payloadMayContainCrlf() {
        // the declared length decides where the value ends, not the delimiter
        assertArrayEquals(new String[]{"ECHO", "a\r\nbc"},
                parserWith("*2\r\n$4\r\nECHO\r\n$5\r\na\r\nbc\r\n").next());
    }

    @Test
    void returnsNullUntilCommandComplete() {
        RespParser parser = parserWith("*2\r\n$4\r\nECHO\r\n$5\r\nhel");
        assertNull(parser.next());

        byte[] rest = "lo\r\n".getBytes(StandardCharsets.UTF_8);
        parser.append(rest, rest.length);
        assertArrayEquals(new String[]{"ECHO", "hello"}, parser.next());
    }

    @Test
    void returnsNullOnPartialHeader() {
        assertNull(parserWith("*2\r\n$4").next());
    }

    @Test
    void parsesTwoCommandsFromOneAppend() {
        RespParser parser = parserWith("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPING\r\n");
        assertArrayEquals(new String[]{"PING"}, parser.next());
        assertArrayEquals(new String[]{"PING"}, parser.next());
        assertNull(parser.next());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalStateException.class, () -> parserWith("hello\r\n").next());
        assertThrows(IllegalStateException.class, () -> parserWith("*1\r\n+OK\r\n").next());
    }

    @Test
    void rejectsAbsurdBulkLength() {
        assertThrows(IllegalStateException.class,
                () -> parserWith("*1\r\n$2000000000\r\n").next());
    }
}
