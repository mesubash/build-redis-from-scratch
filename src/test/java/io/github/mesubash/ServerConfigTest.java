package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {

    private String reply(CommandDispatcher dispatcher, String... command) {
        return new String(dispatcher.execute(command), StandardCharsets.UTF_8);
    }

    @Test
    void defaultsWhenNoArgumentsGiven() {
        ServerConfig config = new ServerConfig();
        assertEquals(6379, config.port());
        assertEquals(".", config.get("dir"));
        assertEquals("dump.rdb", config.get("dbfilename"));
    }

    @Test
    void argumentsOverrideDefaults() {
        ServerConfig config = new ServerConfig("--port", "6380", "--dir", "/tmp");
        assertEquals(6380, config.port());
        assertEquals("/tmp", config.get("dir"));
        // untouched defaults survive
        assertEquals("dump.rdb", config.get("dbfilename"));
    }

    @Test
    void optionNamesAreCaseInsensitive() {
        assertEquals("/tmp", new ServerConfig("--DIR", "/tmp").get("dir"));
        assertEquals("/tmp", new ServerConfig("--dir", "/tmp").get("DIR"));
    }

    @Test
    void unknownParameterIsNull() {
        assertNull(new ServerConfig().get("nonsense"));
    }

    @Test
    void junkArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig("port", "6380"));
    }

    @Test
    void configGetReturnsNameValuePairs() {
        CommandDispatcher dispatcher = new CommandDispatcher(
                new RedisStore(), new ServerConfig("--dir", "/tmp", "--dbfilename", "save.rdb"));

        assertEquals("*2\r\n$3\r\ndir\r\n$4\r\n/tmp\r\n", reply(dispatcher, "CONFIG", "GET", "dir"));
        assertEquals("*4\r\n$3\r\ndir\r\n$4\r\n/tmp\r\n$10\r\ndbfilename\r\n$8\r\nsave.rdb\r\n",
                reply(dispatcher, "CONFIG", "GET", "dir", "dbfilename"));
    }

    @Test
    void configGetSkipsUnknownParameters() {
        CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());
        assertEquals("*0\r\n", reply(dispatcher, "CONFIG", "GET", "nonsense"));
    }

    @Test
    void configRejectsOtherSubcommands() {
        CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());
        assertTrue(reply(dispatcher, "CONFIG", "SET", "dir", "/tmp").startsWith("-ERR Unknown CONFIG"));
    }

    @Test
    void infoReportsRoleAndKeyCount() {
        CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());
        reply(dispatcher, "SET", "k", "v");

        String info = reply(dispatcher, "INFO");
        assertTrue(info.startsWith("$"));
        assertTrue(info.contains("role:master"));
        assertTrue(info.contains("db0:keys=1"));
    }

    @Test
    void dbsizeCountsKeys() {
        CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());
        assertEquals(":0\r\n", reply(dispatcher, "DBSIZE"));

        reply(dispatcher, "SET", "a", "1");
        reply(dispatcher, "SET", "b", "2");
        assertEquals(":2\r\n", reply(dispatcher, "DBSIZE"));
    }

    @Test
    void flushallEmptiesTheStore() {
        CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());
        reply(dispatcher, "SET", "a", "1");

        assertEquals("+OK\r\n", reply(dispatcher, "FLUSHALL"));
        assertEquals(":0\r\n", reply(dispatcher, "DBSIZE"));
        assertEquals("$-1\r\n", reply(dispatcher, "GET", "a"));
    }

    @Test
    void commandReturnsAnEmptyArray() {
        // redis-cli sends COMMAND DOCS on connect and must not get an error
        CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());
        assertEquals("*0\r\n", reply(dispatcher, "COMMAND", "DOCS"));
    }
}
