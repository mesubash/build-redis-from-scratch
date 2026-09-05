package io.github.mesubash;

// A stream id is milliseconds plus a sequence number, so many entries can share a millisecond.
public record StreamId(long ms, long seq) implements Comparable<StreamId> {

    public static final StreamId MIN = new StreamId(0, 0);
    public static final StreamId MAX = new StreamId(Long.MAX_VALUE, Long.MAX_VALUE);

    // "5-3" exact, or "5" with the sequence defaulted - start of a range wants 0, end wants max
    public static StreamId parse(String raw, long defaultSeq) {
        int dash = raw.indexOf('-');
        if (dash < 0) {
            return new StreamId(Long.parseLong(raw), defaultSeq);
        }
        return new StreamId(
                Long.parseLong(raw.substring(0, dash)),
                Long.parseLong(raw.substring(dash + 1)));
    }

    @Override
    public int compareTo(StreamId other) {
        int byMs = Long.compare(ms, other.ms);
        return byMs != 0 ? byMs : Long.compare(seq, other.seq);
    }

    @Override
    public String toString() {
        return ms + "-" + seq;
    }
}
