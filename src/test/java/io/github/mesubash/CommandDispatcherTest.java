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

    @Test
    void existsCountsKeysThatExist() {
        reply("SET", "name", "Subash");
        reply("SET", "city", "Kathmandu");
        assertEquals(":1\r\n", reply("EXISTS", "name"));
        assertEquals(":2\r\n", reply("EXISTS", "name", "city", "missing"));
        assertEquals(":0\r\n", reply("EXISTS", "missing"));
    }

    @Test
    void existsCountsDuplicateArguments() {
        reply("SET", "k", "v");
        assertEquals(":2\r\n", reply("EXISTS", "k", "k"));
    }

    @Test
    void delReturnsHowManyWereRemoved() {
        reply("SET", "name", "Subash");
        assertEquals(":1\r\n", reply("DEL", "name", "missing"));
        assertEquals("$-1\r\n", reply("GET", "name"));
    }

    @Test
    void delDoesNotDoubleCountTheSameKey() {
        reply("SET", "k", "v");
        assertEquals(":1\r\n", reply("DEL", "k", "k"));
    }

    @Test
    void typeReturnsStringOrNone() {
        reply("SET", "k", "v");
        assertEquals("+string\r\n", reply("TYPE", "k"));
        assertEquals("+none\r\n", reply("TYPE", "missing"));
    }

    @Test
    void keysReturnsAnArrayOfBulkStrings() {
        reply("SET", "only", "v");
        assertEquals("*1\r\n$4\r\nonly\r\n", reply("KEYS", "*"));
    }

    @Test
    void keysOnEmptyStoreReturnsEmptyArray() {
        assertEquals("*0\r\n", reply("KEYS", "*"));
    }

    @Test
    void inspectionCommandsRejectWrongArity() {
        assertEquals("-ERR wrong number of arguments for 'exists' command\r\n", reply("EXISTS"));
        assertEquals("-ERR wrong number of arguments for 'del' command\r\n", reply("DEL"));
        assertEquals("-ERR wrong number of arguments for 'type' command\r\n", reply("TYPE"));
        assertEquals("-ERR wrong number of arguments for 'type' command\r\n", reply("TYPE", "a", "b"));
        assertEquals("-ERR wrong number of arguments for 'keys' command\r\n", reply("KEYS"));
        assertEquals("-ERR wrong number of arguments for 'keys' command\r\n", reply("KEYS", "a", "b"));
    }

    @Test
    void incrementCommands() {
        reply("SET", "counter", "10");
        assertEquals(":11\r\n", reply("INCR", "counter"));
        assertEquals(":16\r\n", reply("INCRBY", "counter", "5"));
        assertEquals(":15\r\n", reply("DECR", "counter"));
        assertEquals(":10\r\n", reply("DECRBY", "counter", "5"));
    }

    @Test
    void incrOnMissingKeyReturnsOne() {
        assertEquals(":1\r\n", reply("INCR", "fresh"));
    }

    @Test
    void incrementedValueIsStillAString() {
        reply("SET", "counter", "10");
        reply("INCR", "counter");
        assertEquals("$2\r\n11\r\n", reply("GET", "counter"));
        assertEquals("+string\r\n", reply("TYPE", "counter"));
    }

    @Test
    void incrOnNonNumericValueIsAnError() {
        reply("SET", "word", "hello");
        assertEquals("-ERR value is not an integer or out of range\r\n", reply("INCR", "word"));
    }

    @Test
    void nonNumericIncrementArgumentIsAnError() {
        assertEquals("-ERR value is not an integer or out of range\r\n",
                reply("INCRBY", "counter", "abc"));
    }

    @Test
    void overflowIsAnError() {
        reply("SET", "c", Long.toString(Long.MAX_VALUE));
        assertEquals("-ERR increment or decrement would overflow\r\n", reply("INCR", "c"));
    }

    @Test
    void decrbyLongMinValueIsAnErrorNotAWrap() {
        // negating Long.MIN_VALUE overflows
        assertEquals("-ERR increment or decrement would overflow\r\n",
                reply("DECRBY", "c", Long.toString(Long.MIN_VALUE)));
    }

    @Test
    void incrementCommandsRejectWrongArity() {
        assertEquals("-ERR wrong number of arguments for 'incr' command\r\n", reply("INCR"));
        assertEquals("-ERR wrong number of arguments for 'decr' command\r\n",
                reply("DECR", "a", "b"));
        assertEquals("-ERR wrong number of arguments for 'incrby' command\r\n",
                reply("INCRBY", "k"));
        assertEquals("-ERR wrong number of arguments for 'decrby' command\r\n",
                reply("DECRBY", "k"));
    }

    @Test
    void listCommandsRoundTrip() {
        assertEquals(":2\r\n", reply("RPUSH", "fruits", "apple", "banana"));
        assertEquals(":3\r\n", reply("LPUSH", "fruits", "cherry"));
        assertEquals("*3\r\n$6\r\ncherry\r\n$5\r\napple\r\n$6\r\nbanana\r\n",
                reply("LRANGE", "fruits", "0", "-1"));
        assertEquals(":3\r\n", reply("LLEN", "fruits"));
        assertEquals("+list\r\n", reply("TYPE", "fruits"));
        assertEquals("$6\r\ncherry\r\n", reply("LPOP", "fruits"));
        assertEquals(":2\r\n", reply("LLEN", "fruits"));
    }

    @Test
    void listCommandsOnMissingKey() {
        assertEquals("*0\r\n", reply("LRANGE", "missing", "0", "-1"));
        assertEquals(":0\r\n", reply("LLEN", "missing"));
        assertEquals("$-1\r\n", reply("LPOP", "missing"));
    }

    @Test
    void wrongTypeInBothDirections() {
        String wrongType = "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";

        reply("SET", "str", "hello");
        assertEquals(wrongType, reply("LPUSH", "str", "x"));
        assertEquals(wrongType, reply("LRANGE", "str", "0", "-1"));

        reply("RPUSH", "list", "a");
        assertEquals(wrongType, reply("GET", "list"));
        assertEquals(wrongType, reply("INCR", "list"));
    }

    @Test
    void lrangeRejectsNonNumericIndices() {
        assertEquals("-ERR value is not an integer or out of range\r\n",
                reply("LRANGE", "k", "0", "abc"));
    }

    @Test
    void listCommandsRejectWrongArity() {
        assertEquals("-ERR wrong number of arguments for 'rpush' command\r\n", reply("RPUSH", "k"));
        assertEquals("-ERR wrong number of arguments for 'lpush' command\r\n", reply("LPUSH", "k"));
        assertEquals("-ERR wrong number of arguments for 'lrange' command\r\n",
                reply("LRANGE", "k", "0"));
        assertEquals("-ERR wrong number of arguments for 'llen' command\r\n", reply("LLEN"));
        assertEquals("-ERR wrong number of arguments for 'lpop' command\r\n", reply("LPOP"));
    }

    @Test
    void singleValuePushIsValid() {
        assertEquals(":1\r\n", reply("RPUSH", "k", "one"));
    }
}
