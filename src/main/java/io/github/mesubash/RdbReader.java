package io.github.mesubash;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Reads the subset of the RDB format that holds plain string keys, as real redis writes it.
public class RdbReader {

    // opcodes, in the order they appear in a file
    private static final int AUX = 0xFA;
    private static final int RESIZE_DB = 0xFB;
    private static final int EXPIRE_MS = 0xFC;
    private static final int EXPIRE_SECONDS = 0xFD;
    private static final int SELECT_DB = 0xFE;
    private static final int EOF = 0xFF;

    private static final int TYPE_STRING = 0;

    // expiresAtEpochMillis is 0 when the key never expires
    public record Record(String key, String value, long expiresAtEpochMillis) {
    }

    private final InputStream in;

    private RdbReader(InputStream in) {
        this.in = in;
    }

    // a missing file is not an error - redis starts with an empty keyspace
    public static List<Record> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        return readBytes(Files.readAllBytes(file));
    }

    // the same parser, for a snapshot that arrived over a socket rather than from disk
    public static List<Record> readBytes(byte[] snapshot) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(snapshot)) {
            return new RdbReader(stream).parse();
        }
    }

    private List<Record> parse() throws IOException {
        byte[] magic = in.readNBytes(9);
        String header = new String(magic, StandardCharsets.US_ASCII);
        if (!header.startsWith("REDIS")) {
            throw new IOException("not an rdb file, header was '" + header + "'");
        }

        List<Record> records = new ArrayList<>();
        long pendingExpiry = 0;

        while (true) {
            int opcode = in.read();
            if (opcode < 0 || opcode == EOF) {
                return records;
            }

            switch (opcode) {
                case AUX -> {
                    // version, creation time and so on - read past both strings
                    readString();
                    readString();
                }
                case SELECT_DB -> readLength();
                case RESIZE_DB -> {
                    readLength();
                    readLength();
                }
                case EXPIRE_MS -> pendingExpiry = readLittleEndian(8);
                case EXPIRE_SECONDS -> pendingExpiry = readLittleEndian(4) * 1000L;
                case TYPE_STRING -> {
                    records.add(new Record(readString(), readString(), pendingExpiry));
                    pendingExpiry = 0;
                }
                default -> throw new IOException(
                        "unsupported rdb value type 0x" + Integer.toHexString(opcode));
            }
        }
    }

    // the top two bits of the first byte say how the length itself is encoded
    private long readLength() throws IOException {
        int first = readByte();
        int type = (first & 0xC0) >> 6;

        return switch (type) {
            case 0 -> first & 0x3F;
            case 1 -> ((long) (first & 0x3F) << 8) | readByte();
            case 2 -> first == 0x81 ? readBigEndian(8) : readBigEndian(4);
            // 0xC0 marks a special encoding, which only readString knows how to handle
            default -> -(first & 0x3F) - 1;
        };
    }

    private String readString() throws IOException {
        long length = readLength();

        if (length >= 0) {
            return new String(readExactly((int) length), StandardCharsets.UTF_8);
        }

        // negative length means the low bits were an encoding marker, not a size
        int encoding = (int) (-length - 1);
        return switch (encoding) {
            case 0 -> Long.toString((byte) readByte());
            case 1 -> Long.toString((short) readLittleEndian(2));
            case 2 -> Long.toString((int) readLittleEndian(4));
            default -> throw new IOException(
                    "lzf-compressed strings are not supported (encoding " + encoding + ")");
        };
    }

    private long readLittleEndian(int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value |= ((long) readByte()) << (8 * i);
        }
        return value;
    }

    private long readBigEndian(int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value = (value << 8) | readByte();
        }
        return value;
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("rdb file ended mid-value");
        }
        return b;
    }

    private byte[] readExactly(int count) throws IOException {
        byte[] bytes = in.readNBytes(count);
        if (bytes.length != count) {
            throw new EOFException("rdb file ended mid-string");
        }
        return bytes;
    }
}
