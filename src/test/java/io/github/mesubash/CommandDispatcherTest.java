package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandDispatcherTest {

    // fresh dispatcher per test method, so no state leaks between tests
    private final CommandDispatcher dispatcher = new CommandDispatcher(new RedisStore());

    private String reply(String... command) {
        return new String(dispatcher.execute(command), StandardCharsets.UTF_8);
    }

    @Test
    void pingReturnsPong(){
        assertEquals("+PONG\r\n", reply("PING"));
    }

    @Test
    void commandNameIsCaseInsensitive(){

        //redis-cli sends lowercase on the wire
        assertEquals("+PONG\r\n", reply("ping"));
        assertEquals("+PONG\r\n", reply("PiNg"));
    }

    @Test
    void pingWithArgumentEchoesIt() {
        assertEquals("$5\r\nhello\r\n", reply("PING", "hello"));
    }

    @Test
    void pingArgumentKeepsItsCase() {
        assertEquals("$5\r\nHeLLo\r\n", reply("PING", "HeLLo"));
    }
    @Test
    void pingWithTooManyArgumentsIsAnError() {
        assertEquals("-ERR wrong number of arguments for 'ping' command\r\n",
                reply("PING", "a", "b"));
    }

    @Test
    void unknownCommandNamesTheCommand() {
        assertEquals("-ERR unknown command 'foo'\r\n", reply("foo"));
    }

    @Test
    void emptyCommandIsAnErrorNotACrash() {
        assertTrue(reply().startsWith("-ERR"));
    }

    @Test
    void echoReturnsItsArgument() {
        assertEquals("$9\r\nhey there\r\n", reply("ECHO", "hey there"));
    }

    @Test
    void echoIsCaseInsensitiveButItsArgumentIsNot() {
        assertEquals("$5\r\nHeLLo\r\n", reply("echo", "HeLLo"));
    }

    @Test
    void echoEmptyStringIsAValueNotAnError() {
        assertEquals("$0\r\n\r\n", reply("ECHO", ""));
    }

    @Test
    void echoWithoutArgumentIsAnError() {
        assertEquals("-ERR wrong number of arguments for 'echo' command\r\n", reply("ECHO"));
    }

    @Test
    void echoWithTwoArgumentsIsAnError() {
        assertEquals("-ERR wrong number of arguments for 'echo' command\r\n",
                reply("ECHO", "a", "b"));
    }

    @Test
    void echoArgumentMayContainCrlf() {
        // bulk string, so the payload is safe - a simple string would break here
        assertEquals("$5\r\na\r\nbc\r\n", reply("ECHO", "a\r\nbc"));
    }

    @Test
    void setReturnsOk() {
        assertEquals("+OK\r\n", reply("SET", "name", "Subash"));
    }

    @Test
    void getReturnsStoredValue() {
        reply("SET", "name", "Subash");
        assertEquals("$6\r\nSubash\r\n", reply("GET", "name"));
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertEquals("$-1\r\n", reply("GET", "nothing"));
    }

    @Test
    void setOverwrites() {
        reply("SET", "city", "Pokhara");
        reply("SET", "city", "Kathmandu");
        assertEquals("$9\r\nKathmandu\r\n", reply("GET", "city"));
    }

    @Test
    void emptyValueIsStoredNotNull() {
        reply("SET", "empty", "");
        assertEquals("$0\r\n\r\n", reply("GET", "empty"));
    }

    @Test
    void keysAndValuesKeepTheirCase() {
        reply("set", "Key", "VaLuE");
        assertEquals("$5\r\nVaLuE\r\n", reply("get", "Key"));
        assertEquals("$-1\r\n", reply("get", "key"));
    }

    @Test
    void valueMayContainCrlf() {
        reply("SET", "raw", "a\r\nbc");
        assertEquals("$5\r\na\r\nbc\r\n", reply("GET", "raw"));
    }

    @Test
    void setWrongArityIsAnError() {
        assertEquals("-ERR wrong number of arguments for 'set' command\r\n",
                reply("SET", "k"));
    }

    @Test
    void getWrongArityIsAnError() {
        assertEquals("-ERR wrong number of arguments for 'get' command\r\n",
                reply("GET", "k", "extra"));
        assertEquals("-ERR wrong number of arguments for 'get' command\r\n",
                reply("GET"));
    }

    @Test
    void freshDispatcherStartsEmpty() {
        reply("SET", "name", "Subash");

        // a separate dispatcher must not see the other one's data
        CommandDispatcher other = new CommandDispatcher(new RedisStore());
        assertEquals("$-1\r\n",
                new String(other.execute(new String[]{"GET", "name"}), StandardCharsets.UTF_8));
    }

    @Test
    void storeIsSharedAcrossDispatchersUsingTheSameStore() {
        // this is what makes one client's SET visible to another client's GET
        RedisStore shared = new RedisStore();
        CommandDispatcher writer = new CommandDispatcher(shared);
        CommandDispatcher reader = new CommandDispatcher(shared);

        writer.execute(new String[]{"SET", "shared", "hello"});
        assertEquals("$5\r\nhello\r\n",
                new String(reader.execute(new String[]{"GET", "shared"}), StandardCharsets.UTF_8));
    }

    @Test
    void setWithPxReturnsOk() {
        assertEquals("+OK\r\n", reply("SET", "k", "v", "PX", "100"));
    }

    @Test
    void setWithExReturnsOk() {
        assertEquals("+OK\r\n", reply("SET", "k", "v", "EX", "10"));
    }

    @Test
    void expiryOptionIsCaseInsensitive() {
        assertEquals("+OK\r\n", reply("SET", "k", "v", "px", "100"));
    }

    @Test
    void nonNumericTtlIsAnError() {
        assertEquals("-ERR value is not an integer or out of range\r\n",
                reply("SET", "k", "v", "PX", "abc"));
    }

    @Test
    void zeroOrNegativeTtlIsAnError() {
        assertEquals("-ERR invalid expire time in 'set' command\r\n",
                reply("SET", "k", "v", "PX", "0"));
        assertEquals("-ERR invalid expire time in 'set' command\r\n",
                reply("SET", "k", "v", "EX", "-1"));
    }

    @Test
    void unknownSetOptionIsASyntaxError() {
        assertEquals("-ERR syntax error\r\n", reply("SET", "k", "v", "FOO", "10"));
    }

    @Test
    void valueSetWithTtlIsReadableImmediately() {
        reply("SET", "k", "v", "PX", "10000");
        assertEquals("$1\r\nv\r\n", reply("GET", "k"));
    }
}
