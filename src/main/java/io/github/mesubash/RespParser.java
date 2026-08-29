package io.github.mesubash;


import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// Parses RESP bytes into commands. Owns no socket - callers feed it bytes.
public class RespParser {

    //same cap real redis uses for proto-max-bulk-len
    private static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    private static final int MAX_ARRAY_SIZE = 1024 * 1024;

    private byte[] buffer = new byte[0];

    // cursor into buffer, only meaningful during a next() call
    private int pos;

    public void append(byte[] data, int length){
        byte[] grown = Arrays.copyOf(buffer, buffer.length + length);
        System.arraycopy(data, 0, grown, buffer.length, length);
        buffer = grown;
    }

    // returns null when a complete command hasn't arrived yet
    public String[] next() {
        pos = 0;
        String[] command = parseArray();

        if (command == null) {
            return null;
        }

        //consume exactly what we parsed, keep the rest for next time
        buffer = Arrays.copyOfRange(buffer, pos, buffer.length);
        return command;
    }

    private String[] parseArray() {
        if (pos >= buffer.length) {
            return null;
        }

        if (buffer[pos] != '*') {
            throw new IllegalStateException("expected array, got byte " + buffer[pos]);
        }
        pos ++;

        Integer count = readNumber();
        if (count == null) {
            return null;

        }
        if(count < 0 || count > MAX_ARRAY_SIZE) {
            throw new IllegalStateException("bad array size " + count);
        }

        String[] args = new String[count];
        for (int i = 0; i < count; i++) {
            String arg = parseBulkString();
            if(arg == null) {
                return null;
            }
            args[i] = arg;
        }
        return args;
    }

    private String parseBulkString() {
        if (pos >= buffer.length) {
            return null;
        }
        if (buffer[pos] != '$') {
            throw new IllegalStateException("expected bulk string, got byte " + buffer[pos]);
        }
        pos++;

        Integer length = readNumber();
        if (length == null) {
            return null;
        }

        if (length < 0 || length > MAX_BULK_LENGTH) {
            throw new IllegalStateException("bad bulk length " + length);
        }

        //payload plus its trailing CRLF must all be here
        if (pos + length + 2 > buffer.length) {
            return null;
        }

        String value = new String( buffer , pos, length, StandardCharsets.UTF_8);

        pos += length + 2;
        return value;
    }

    // reads digits up to the next CRLF, null if that CRLF hasn't arrived
    private Integer readNumber() {
        int lineEnd = indexOfCrlf(pos);
        if (lineEnd < 0) {
            return null;
        }
        String digits = new String (buffer, pos, lineEnd - pos, StandardCharsets.UTF_8);
        pos = lineEnd + 2;

        try{
            return Integer.parseInt( digits );

        } catch (NumberFormatException e) {
            throw new IllegalStateException("expected number, got '" + digits + "'");
        }
    }

    private int indexOfCrlf(int from) {
        for (int i = from; i + 1 < buffer.length; i++) {
            if (buffer[i] == '\r' && buffer[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

}
