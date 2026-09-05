package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdbWriterTest {

    private Path tempFile() throws IOException {
        Path file = Files.createTempDirectory("rdb").resolve("dump.rdb");
        file.toFile().deleteOnExit();
        return file;
    }

    private Map<String, RdbReader.Record> roundTrip(List<RdbReader.Record> records)
            throws IOException {
        Path file = tempFile();
        RdbWriter.write(file, records);
        return RdbReader.read(file).stream()
                .collect(Collectors.toMap(RdbReader.Record::key, Function.identity()));
    }

    @Test
    void writesAFileTheReaderUnderstands() throws IOException {
        Map<String, RdbReader.Record> back = roundTrip(List.of(
                new RdbReader.Record("name", "Subash", 0),
                new RdbReader.Record("city", "Kathmandu", 0)));

        assertEquals("Subash", back.get("name").value());
        assertEquals("Kathmandu", back.get("city").value());
    }

    @Test
    void writesTheRedisMagicAndVersion() throws IOException {
        Path file = tempFile();
        RdbWriter.write(file, List.of());

        byte[] header = Files.readAllBytes(file);
        assertEquals("REDIS0011", new String(header, 0, 9, StandardCharsets.US_ASCII));

        // ends with the EOF opcode plus an eight byte checksum
        assertEquals((byte) 0xFF, header[header.length - 9]);
        assertArrayEquals(new byte[8], java.util.Arrays.copyOfRange(header, header.length - 8, header.length));
    }

    @Test
    void expirySurvivesTheRoundTrip() throws IOException {
        long deadline = System.currentTimeMillis() + 60_000;
        Map<String, RdbReader.Record> back = roundTrip(List.of(
                new RdbReader.Record("expiring", "soon", deadline),
                new RdbReader.Record("permanent", "forever", 0)));

        assertEquals(deadline, back.get("expiring").expiresAtEpochMillis());
        assertEquals(0, back.get("permanent").expiresAtEpochMillis());
    }

    @Test
    void longValuesUseTheWiderLengthForms() throws IOException {
        String medium = "x".repeat(300);
        String large = "y".repeat(20_000);

        Map<String, RdbReader.Record> back = roundTrip(List.of(
                new RdbReader.Record("medium", medium, 0),
                new RdbReader.Record("large", large, 0)));

        assertEquals(medium, back.get("medium").value());
        assertEquals(large, back.get("large").value());
    }

    @Test
    void emptyKeyspaceWritesAValidFile() throws IOException {
        assertEquals(Map.of(), roundTrip(List.of()));
    }

    @Test
    void writeReplacesAnExistingFile() throws IOException {
        Path file = tempFile();
        RdbWriter.write(file, List.of(new RdbReader.Record("old", "gone", 0)));
        RdbWriter.write(file, List.of(new RdbReader.Record("new", "here", 0)));

        List<RdbReader.Record> back = RdbReader.read(file);
        assertEquals(1, back.size());
        assertEquals("new", back.getFirst().key());
    }

    @Test
    void snapshotSkipsNonStringTypes() {
        RedisStore store = new RedisStore();
        store.set("string", "kept");
        store.push("list", false, "dropped");
        store.sadd("set", "dropped");

        List<String> keys = store.snapshotStrings().stream().map(RdbReader.Record::key).toList();
        assertEquals(List.of("string"), keys);
    }

    @Test
    void snapshotConvertsTtlBackToAnAbsoluteInstant() {
        RedisStore store = new RedisStore();
        store.set("permanent", "v");
        store.set("expiring", "v", 60_000);

        Map<String, RdbReader.Record> byKey = store.snapshotStrings().stream()
                .collect(Collectors.toMap(RdbReader.Record::key, Function.identity()));

        assertEquals(0, byKey.get("permanent").expiresAtEpochMillis());
        long expiresAt = byKey.get("expiring").expiresAtEpochMillis();
        assertTrue(expiresAt > System.currentTimeMillis() + 55_000, "was " + expiresAt);
    }

    @Test
    void aStoreSurvivesASaveAndReload() throws IOException {
        RedisStore original = new RedisStore();
        original.set("name", "Subash");
        original.set("counter", "12345");
        original.set("expiring", "v", 60_000);

        Path file = tempFile();
        RdbWriter.write(file, original.snapshotStrings());

        RedisStore reloaded = new RedisStore();
        for (RdbReader.Record record : RdbReader.read(file)) {
            reloaded.restore(record.key(), record.value(), record.expiresAtEpochMillis());
        }

        assertEquals("Subash", reloaded.get("name"));
        assertEquals("12345", reloaded.get("counter"));
        assertTrue(reloaded.ttlMillis("expiring") > 55_000);
        assertNull(reloaded.get("nothing"));
    }
}
