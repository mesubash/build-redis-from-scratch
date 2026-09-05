package io.github.mesubash;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

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

    // null means the key is absent - the map refuses to store nulls, so there is no ambiguity
    public String get(String key) {
        long now = clock.getAsLong();

        // returning null from the remapping function removes the key , atomically for this key
        Entry entry = data.computeIfPresent(key, (k, e) -> e.isExpired(now) ? null : e);
        return entry == null ? null : entry.value();
    }

    private long expiryFrom(long ttlMillis) {
        long ttlNanos = ttlMillis * 1_000_000L;
        long expiry = clock.getAsLong() + ttlNanos;

        // an absurd ttl would wrap past Long.MAX_VALUE and land in the past
        return expiry < 0 ? NEVER : expiry;
    }
}
