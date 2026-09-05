package io.github.mesubash;

import java.util.ArrayList;
import java.util.List;

// A wrapper rather than a bare List, so a stream is distinguishable from a list value.
public class RedisStream {

    private final List<StreamEntry> entries = new ArrayList<>();

    public List<StreamEntry> entries() {
        return entries;
    }

    public StreamId lastId() {
        return entries.isEmpty() ? StreamId.MIN : entries.getLast().id();
    }

    public void append(StreamEntry entry) {
        entries.add(entry);
    }

    // entries are appended in order, so a scan is enough at this size
    // ponytail: linear scan, binary search if a stream ever gets long enough to matter
    public List<StreamEntry> range(StreamId from, StreamId to, boolean exclusiveStart) {
        List<StreamEntry> found = new ArrayList<>();
        for (StreamEntry entry : entries) {
            int fromCmp = entry.id().compareTo(from);
            boolean afterStart = exclusiveStart ? fromCmp > 0 : fromCmp >= 0;
            if (afterStart && entry.id().compareTo(to) <= 0) {
                found.add(entry);
            }
        }
        return found;
    }
}
