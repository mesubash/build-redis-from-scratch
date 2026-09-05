package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandDispatcherTest {

    private static String reply(String... command){
        return new String(CommandDispatcher.execute(command), StandardCharsets.UTF_8);
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

}
