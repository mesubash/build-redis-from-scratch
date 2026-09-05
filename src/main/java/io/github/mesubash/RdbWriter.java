package io.github.mesubash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

// Writes the string subset of the RDB format, in a shape real redis-server can load.
public class RdbWriter {

    private static final String MAGIC = "REDIS0011";

    // written to a temp file first, so a crash mid-write can't leave a half snapshot behind
    public static void write(Path file, List<RdbReader.Record> records) throws IOException {
        byte[] snapshot = toBytes(records);

        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);

        Path temp = Files.createTempFile(directory, "dump", ".rdb");
        Files.write(temp, snapshot);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // the same bytes a replica receives during a full resync
    public static byte[] toBytes(List<RdbReader.Record> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC.getBytes(StandardCharsets.US_ASCII));

        writeAux(out, "redis-ver", "7.0.0");

        out.write(0xFE);
        out.write(0);

        // table size hints: total keys, and how many of them have an expiry
        out.write(0xFB);
        writeLength(out, records.size());
        writeLength(out, (int) records.stream().filter(r -> r.expiresAtEpochMillis() > 0).count());

        for (RdbReader.Record record : records) {
            if (record.expiresAtEpochMillis() > 0) {
                out.write(0xFC);
                writeLittleEndian(out, record.expiresAtEpochMillis(), 8);
            }
            out.write(0x00);
            writeString(out, record.key());
            writeString(out, record.value());
        }

        out.write(0xFF);
        // redis skips checksum verification when it is zero, which saves implementing crc64
        out.writeBytes(new byte[8]);

        return out.toByteArray();
    }

    private static void writeAux(ByteArrayOutputStream out, String key, String value) {
        out.write(0xFA);
        writeString(out, key);
        writeString(out, value);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLength(out, bytes.length);
        out.writeBytes(bytes);
    }

    // the two length forms the reader understands, picked by size
    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 1 << 6) {
            out.write(length);
        } else if (length < 1 << 14) {
            out.write(0x40 | (length >> 8));
            out.write(length & 0xFF);
        } else {
            out.write(0x80);
            for (int i = 3; i >= 0; i--) {
                out.write((length >> (8 * i)) & 0xFF);
            }
        }
    }

    private static void writeLittleEndian(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }
}
