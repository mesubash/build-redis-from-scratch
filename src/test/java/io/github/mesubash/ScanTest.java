package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanTest {

    private long now = 0;
    private final RedisStore store = new RedisStore(() -> now);
    private final CommandDispatcher dispatcher = new CommandDispatcher(store);

    private String reply(String... command) {
        return new String(dispatcher.execute(command), StandardCharsets.UTF_8);
    }

    // walks the whole keyspace the way a client would, collecting every key it is handed
    private Set<String> fullScan(int count, String... matchArgs) {
        Set<String> seen = new HashSet<>();
        long cursor = 0;
        int guard = 0;

        do {
            List<String> command = new ArrayList<>(List.of("SCAN", Long.toString(cursor)));
            command.addAll(List.of(matchArgs));
            command.addAll(List.of("COUNT", Integer.toString(count)));

            RedisStore.ScanPage page = store.scan(cursor,
                    matchArgs.length > 0 ? matchArgs[1] : "*", count);
            seen.addAll(page.keys());
            cursor = page.nextCursor();

            if (++guard > 1000) {
                throw new AssertionError("scan did not terminate");
            }
        } while (cursor != 0);

        return seen;
    }

    @Test
    void scanReturnsACursorAndAPageOfKeys() {
        reply("SET", "a", "1");
        assertEquals("*2\r\n$1\r\n0\r\n*1\r\n$1\r\na\r\n", reply("SCAN", "0"));
    }

    @Test
    void scanOfAnEmptyKeyspace() {
        assertEquals("*2\r\n$1\r\n0\r\n*0\r\n", reply("SCAN", "0"));
    }

    @Test
    void aFullScanVisitsEveryKey() {
        for (int i = 0; i < 25; i++) {
            reply("SET", "key" + i, "v");
        }

        Set<String> seen = fullScan(4);
        assertEquals(25, seen.size());
        assertTrue(seen.contains("key0"));
        assertTrue(seen.contains("key24"));
    }

    @Test
    void aCountOfOneStillTerminates() {
        for (int i = 0; i < 5; i++) {
            reply("SET", "key" + i, "v");
        }
        assertEquals(5, fullScan(1).size());
    }

    @Test
    void countIsAHintNotAGuarantee() {
        for (int i = 0; i < 10; i++) {
            reply("SET", "key" + i, "v");
        }

        // asking for more than exists just ends the scan
        RedisStore.ScanPage page = store.scan(0, "*", 100);
        assertEquals(10, page.keys().size());
        assertEquals(0, page.nextCursor());
    }

    @Test
    void matchFiltersWithoutBreakingThePaging() {
        reply("SET", "user:1", "a");
        reply("SET", "user:2", "b");
        reply("SET", "other", "c");

        assertEquals(Set.of("user:1", "user:2"), fullScan(1, "MATCH", "user:*"));
    }

    @Test
    void expiredKeysAreNotReturned() {
        reply("SET", "permanent", "v");
        reply("SET", "temp", "v", "PX", "100");

        now = 200_000_000L;
        assertEquals(Set.of("permanent"), fullScan(10));
    }

    @Test
    void scanRejectsABadCursor() {
        assertEquals("-ERR invalid cursor\r\n", reply("SCAN", "abc"));
    }

    @Test
    void scanRejectsBadOptions() {
        assertEquals("-ERR syntax error\r\n", reply("SCAN", "0", "NONSENSE", "x"));
        assertEquals("-ERR syntax error\r\n", reply("SCAN", "0", "MATCH"));
        assertEquals("-ERR syntax error\r\n", reply("SCAN", "0", "COUNT", "0"));
        assertEquals("-ERR value is not an integer or out of range\r\n",
                reply("SCAN", "0", "COUNT", "many"));
        assertEquals("-ERR wrong number of arguments for 'scan' command\r\n", reply("SCAN"));
    }

    @Test
    void cursorIsABulkStringNotAnInteger() {
        for (int i = 0; i < 20; i++) {
            reply("SET", "key" + i, "v");
        }
        // mid-scan the cursor is non-zero, and it must still be $-prefixed
        assertTrue(reply("SCAN", "0", "COUNT", "5").startsWith("*2\r\n$"));
    }
}
