package io.github.mesubash;

import java.util.List;

// fields are a flat [name, value, name, value] list, the same shape the client sent
public record StreamEntry(StreamId id, List<String> fields) {
}
