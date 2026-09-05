package io.github.mesubash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

// Members ordered by score, ties broken lexicographically - the ordering redis guarantees.
public class RedisSortedSet {

    private final Map<String, Double> scores = new HashMap<>();

    // the comparator reads `scores`, so a score must be written before a member is added here
    // and the member removed from here before its score changes
    private final NavigableSet<String> ordered = new TreeSet<>((a, b) -> {
        int byScore = Double.compare(scores.get(a), scores.get(b));
        return byScore != 0 ? byScore : a.compareTo(b);
    });

    // true when the member is new rather than rescored
    public boolean add(String member, double score) {
        boolean isNew = !scores.containsKey(member);
        if (!isNew) {
            ordered.remove(member);
        }
        scores.put(member, score);
        ordered.add(member);
        return isNew;
    }

    public boolean remove(String member) {
        if (!scores.containsKey(member)) {
            return false;
        }
        ordered.remove(member);
        scores.remove(member);
        return true;
    }

    public Double score(String member) {
        return scores.get(member);
    }

    public Long rank(String member) {
        if (!scores.containsKey(member)) {
            return null;
        }
        return (long) ordered.headSet(member).size();
    }

    public int size() {
        return ordered.size();
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    // inclusive at both ends, negative indices count from the end - same rules as LRANGE
    public List<String> range(long start, long stop) {
        List<String> members = new ArrayList<>(ordered);
        int size = members.size();

        int from = clamp(start, size);
        int to = clamp(stop, size);
        if (to > size - 1) {
            to = size - 1;
        }
        if (from > to) {
            return List.of();
        }
        return new ArrayList<>(members.subList(from, to + 1));
    }

    private static int clamp(long index, int size) {
        long resolved = index < 0 ? size + index : index;
        if (resolved < 0) {
            return 0;
        }
        return (int) Math.min(resolved, Integer.MAX_VALUE);
    }
}
