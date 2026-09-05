package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdbReaderTest {

    // builds rdb bytes by hand, so the parser is tested against the format rather than itself
    private static class RdbBuilder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        RdbBuilder() {
            out.writeBytes("REDIS0011".getBytes(StandardCharsets.US_ASCII));
        }

        RdbBuilder aux(String key, String value) {
            out.write(0xFA);
            return string(key).string(value);
        }

        RdbBuilder selectDb(int db) {
            out.write(0xFE);
            out.write(db);
            return this;
        }

        RdbBuilder expireMillis(long epochMillis) {
            out.write(0xFC);
            for (int i = 0; i < 8; i++) {
                out.write((int) ((epochMillis >> (8 * i)) & 0xFF));
            }
            return this;
        }

        RdbBuilder stringEntry(String key, String value) {
            out.write(0x00);
            return string(key).string(value);
        }

        RdbBuilder string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            length(bytes.length);
            out.writeBytes(bytes);
            return this;
        }

        RdbBuilder length(int length) {
            if (length < 64) {
                out.write(length);
            } else {
                // 14-bit form: 01 followed by the remaining bits
                out.write(0x40 | (length >> 8));
                out.write(length & 0xFF);
            }
            return this;
        }

        byte[] eof() {
            out.write(0xFF);
            out.writeBytes(new byte[8]);
            return out.toByteArray();
        }
    }

    private List<RdbReader.Record> readBytes(byte[] rdb) throws IOException {
        Path file = Files.createTempFile("test", ".rdb");
        Files.write(file, rdb);
        try {
            return RdbReader.read(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingFileIsAnEmptyKeyspaceNotAnError() throws IOException {
        assertEquals(List.of(), RdbReader.read(Path.of("/tmp/does-not-exist-" + System.nanoTime())));
    }

    @Test
    void readsPlainStringEntries() throws IOException {
        byte[] rdb = new RdbBuilder()
                .aux("redis-ver", "7.0.0")
                .selectDb(0)
                .stringEntry("name", "Subash")
                .stringEntry("city", "Kathmandu")
                .eof();

        List<RdbReader.Record> records = readBytes(rdb);
        assertEquals(2, records.size());
        assertEquals("name", records.get(0).key());
        assertEquals("Subash", records.get(0).value());
        assertEquals(0, records.get(0).expiresAtEpochMillis());
        assertEquals("Kathmandu", records.get(1).value());
    }

    @Test
    void readsAnExpiryAndAppliesItToTheFollowingKeyOnly() throws IOException {
        long deadline = System.currentTimeMillis() + 60_000;
        byte[] rdb = new RdbBuilder()
                .selectDb(0)
                .expireMillis(deadline)
                .stringEntry("expiring", "soon")
                .stringEntry("permanent", "forever")
                .eof();

        List<RdbReader.Record> records = readBytes(rdb);
        assertEquals(deadline, records.get(0).expiresAtEpochMillis());

        // the expiry must not leak onto the next key
        assertEquals(0, records.get(1).expiresAtEpochMillis());
    }

    @Test
    void readsStringsLongerThanSixtyThreeBytes() throws IOException {
        String longValue = "x".repeat(500);
        byte[] rdb = new RdbBuilder().selectDb(0).stringEntry("big", longValue).eof();

        assertEquals(longValue, readBytes(rdb).getFirst().value());
    }

    @Test
    void rejectsAFileThatIsNotAnRdb() {
        byte[] junk = "NOTREDIS-junk".getBytes(StandardCharsets.US_ASCII);
        IOException error = assertThrows(IOException.class, () -> readBytes(junk));
        assertTrue(error.getMessage().contains("not an rdb file"));
    }

    @Test
    void rejectsAnUnsupportedValueType() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("REDIS0011".getBytes(StandardCharsets.US_ASCII));
        out.write(0xFE);
        out.write(0);
        // 0x02 is a set, which this reader does not handle
        out.write(0x02);

        IOException error = assertThrows(IOException.class, () -> readBytes(out.toByteArray()));
        assertTrue(error.getMessage().contains("unsupported rdb value type"));
    }

    @Test
    void readsAFileWrittenByRealRedis() throws IOException, URISyntaxException {
        // generated by redis-server 8.2.0, checked in so the parser is held to the real format
        Path file = Path.of(getClass().getResource("/redis-8.2.0.rdb").toURI());
        Map<String, RdbReader.Record> byKey = RdbReader.read(file).stream()
                .collect(Collectors.toMap(RdbReader.Record::key, Function.identity()));

        assertEquals("Subash", byKey.get("name").value());
        assertEquals("Kathmandu", byKey.get("city").value());

        // stored as an integer encoding rather than a plain string
        assertEquals("12345", byKey.get("counter").value());

        assertEquals(0, byKey.get("city").expiresAtEpochMillis());
        assertTrue(byKey.get("later").expiresAtEpochMillis() > 0);
    }

    @Test
    void restoringSkipsKeysThatAlreadyExpired() throws IOException, URISyntaxException {
        Path file = Path.of(getClass().getResource("/redis-8.2.0.rdb").toURI());
        RedisStore store = new RedisStore();

        for (RdbReader.Record record : RdbReader.read(file)) {
            store.restore(record.key(), record.value(), record.expiresAtEpochMillis());
        }

        assertEquals("Subash", store.get("name"));

        // "soon" was written with a 100ms ttl and is long gone
        assertNull(store.get("soon"));

        // nothing here may depend on how long ago the fixture was generated. "later" was written
        // with a one hour ttl, so asserting it is still alive would make this test expire too
    }

    @Test
    void restoringKeepsATtlThatIsStillInTheFuture() {
        RedisStore store = new RedisStore();
        long inOneMinute = System.currentTimeMillis() + 60_000;

        store.restore("live", "value", inOneMinute);
        store.restore("permanent", "value", 0);
        store.restore("stale", "value", System.currentTimeMillis() - 1);

        assertTrue(store.ttlMillis("live") > 55_000);
        assertEquals(-1, store.ttlMillis("permanent"));

        // -2 means absent, which is what an already-expired deadline must produce
        assertEquals(-2, store.ttlMillis("stale"));
    }
}
