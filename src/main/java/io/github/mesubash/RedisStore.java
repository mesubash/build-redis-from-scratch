package io.github.mesubash;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

// shared key-value state. one instance per server, used by every client thread
public class RedisStore {

    private static final long NEVER = Long.MAX_VALUE;

    private record Entry(String value, long expiresAtNanos) {
        boolean isExpired(long now) {
            return now >= expiresAtNanos;
        }
    }

    //ConcurrentHashMap over a synchronized map: unrelated keys don't contend,
    //and compute() lets us check-and-remove an expired key atomically
    private final Map<String, Entry> data = new ConcurrentHashMap<>();

    //nanoTime is monotonic - wall clock jumps backwards and would resurrect expired keys
    private final LongSupplier clock;

    public RedisStore() {
        this(System::nanoTime);
    }

    //package-private so tests can move time without sleeping
    RedisStore(LongSupplier clock) {
        this.clock = clock;
    }

    public void set(String key, String value) {
        data.put(key, new Entry(value, NEVER));
    }

    public void set(String key, String value, long ttlMillis) {
        data.put(key, new Entry(value, expiryFrom(ttlMillis)));
    }

    //the only place expiry is decided - every command goes through this
    private Entry live(String key) {
        long now = clock.getAsLong();
        return data.computeIfPresent(key, (k, e)-> e.isExpired(now) ? null : e);
    }

    // null means the key is absent - the map refuses to store nulls, so there is no ambiguity
    public String get(String key) {
        Entry entry = live(key);
        return entry == null ? null : entry.value();
    }

    public boolean exists(String key) {
        return live(key) != null;
    }

    // true only if a live key was removed, so an expired key doesn't inflate DEL's count
    public boolean delete(String key) {
        boolean existed = exists(key);

        data.remove(key);
        return existed;
    }

    // everything is a string until lists arrive
    public String type(String key) {
        return exists(key) ? "string" : "none";
    }

    // snapshot, weakly consistent - deliberately not locking the whole map
    public List<String> keys(String pattern) {
        Pattern regex = globToRegex(pattern);
        List<String> matches = new ArrayList<>();

        for (String key : data.keySet()){
            if (regex.matcher(key).matches() && exists(key)) {
                matches.add(key);
            }
        }
        return matches;

    }

    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private long expiryFrom(long ttlMillis) {
        long ttlNanos = ttlMillis * 1_000_000L;
        long expiry = clock.getAsLong() + ttlNanos;

        // an absurd ttl would wrap past Long.MAX_VALUE and land in the past
        return expiry < 0 ? NEVER : expiry;
    }
}
