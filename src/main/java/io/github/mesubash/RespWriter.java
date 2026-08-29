package io.github.mesubash;


import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

// Encodes Java values as RESP2 bytes. Owns no socket = callers write what it returns
public class RespWriter {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.UTF_8);

    // never build one of these from user data, it has no length prefix
    public static byte[] simpleString(String value) {
        return ("+" + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] error(String message) {
        return ("-" + message + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] integer(long value) {
        return (":" + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] bulkString(String value) {
        if (value == null) {
            return NULL_BULK;
        }

        // the declared length is bytes, not characters
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("$" + payload.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(payload);
        out.writeBytes(CRLF);
        return out.toByteArray();
    }

    // elements are already encoded, so nesting costs nothing
    public static byte[] array(byte[]... elements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("*" + elements.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (byte[] element : elements) {
            out.writeBytes(element);
        }
        return out.toByteArray();
    }
}


