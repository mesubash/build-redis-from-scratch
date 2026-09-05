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

    private record Entry(Object value, long expiresAtNanos) {
        boolean isExpired(long now) {
            return now >= expiresAtNanos;
        }
        String asString(){
            if (value instanceof String s){
                return s;
            }
            throw new WrongTypeException();
        }

        @SuppressWarnings("unchecked")
        List<String> asList(){
            if (value instanceof List<?> list){
                return (List<String>) list;
            }
            throw new WrongTypeException();
        }

    }

    //ConcurrentHashMap over a synchronized map: unrelated keys don't contend,
    //and compute() lets us check-and-remove an expired key atomically
    private final Map<String, Entry> data = new ConcurrentHashMap<>();

    //nanoTime is monotonic - wall clock jumps backwards and would resurrect expired keys
    private final LongSupplier clock;

    // ponytail: one monitor for every list, so a push wakes all waiters and most go back to
    // sleep. per-key monitors if a real workload ever makes the herd measurable
    private final Object listMonitor = new Object();

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
        return entry == null ? null : entry.asString();
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

    // string or list, decided by what's stored
    public String type(String key) {
        Entry entry = live(key);

        if (entry == null) {
            return "none";
        }
        return entry.value() instanceof List ? "list" : "string";
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

    // read-modify-write must happen inside compute(), or concurrent increments get lost.
    // throws NumberFormatException if the stored value isn't an integer,
    // ArithmeticException on overflow
    public long increment(String key, long delta) {
        long now = clock.getAsLong();
        long[] result = new long[1];

        data.compute(key, (k, existing) -> {
            //an expired key is a missing key, so start from 0 rather than the stale value
            boolean usable = existing != null && !existing.isExpired(now);

            long current = usable ? Long.parseLong(existing.asString()) : 0L;
            result[0] = Math.addExact(current, delta);

            // a live key keeps its ttl, incrementing must not make a counter immortal
            long expiry = usable ? existing.expiresAtNanos() : NEVER;
            return new Entry(Long.toString(result[0]), expiry);
        });
        return result[0];
    }

    // all list access happens inside compute(), the map protects the mapping, not the list
    public long push(String key, boolean left, String... values){
        long now = clock.getAsLong();
        long[] length = new long[1];

        data.compute(key, (k, existing) -> {
            boolean usable = existing != null && !existing.isExpired(now);
            List<String> list = usable ? existing.asList() : new ArrayList<>();

            for ( String value : values ) {
                if (left) {
                    list.addFirst(value);
                } else {
                    list.addLast(value);
                }
            }

            length[0] = list.size();
            long expiry = usable ? existing.expiresAtNanos() : NEVER;
            return new Entry(list, expiry);
        });

        // a blocked BLPOP is waiting for exactly this
        synchronized (listMonitor) {
            listMonitor.notifyAll();
        }

        return length[0];
    }

    // blocks until one of the keys has an element or the timeout passes.
    // returns [key, value], or null on timeout. timeoutMillis of 0 waits forever
    public String[] blockingPop(String[] keys, long timeoutMillis) throws InterruptedException {
        long deadline = timeoutMillis == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMillis;

        synchronized (listMonitor) {
            while (true) {
                // keys are checked in order, so an earlier key wins when both have data
                for (String key : keys) {
                    String value = lpop(key);
                    if (value != null) {
                        return new String[]{key, value};
                    }
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }

                // wait() releases the monitor, so a pushing thread can get in and notify us.
                // the surrounding loop re-checks because notifyAll wakes every waiter
                listMonitor.wait(Math.min(remaining, 100));
            }
        }
    }

    public List<String> lrange(String key, long start, long stop){
        long now = clock.getAsLong();
        List<String> result = new ArrayList<>();

        data.computeIfPresent(key, (k,existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            List<String> list = existing.asList();

            int size = list.size();
            int from = normalise(start, size);
            int to = normalise(stop, size);
            if (to > size - 1){
                to = size - 1;
            }

            // a copy, never a view onto the list that lives in the map
            for (int i = from; i <= to; i++){
                result.add(list.get(i));
            }
            return existing;

        });
        return result;
    }

    public long llen(String key) {
        long now = clock.getAsLong();
        long[] size = new long[1];

        data.computeIfPresent(key, (k, existing) ->{
            if (existing.isExpired(now)) {
                return null;
            }
            size[0] = existing.asList().size();
            return existing;
        });
        return size[0];
    }

    public String lpop(String key) {
        long now = clock.getAsLong();
        String[] popped = new String[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            List<String> list = existing.asList();
            popped[0] = list.removeFirst();

            //redis has no empty lists, the key goes away with the last element
            return list.isEmpty() ? null : existing;
        });
        return popped[0];
    }

    //negative indices count from the end, then clamp to 0
    private static int normalise( long index, int size ){
        long resolved = index < 0 ? size + index : index;
        if (resolved < 0) return 0;
        return (int) Math.min(resolved, Integer.MAX_VALUE);
    }

    private long expiryFrom(long ttlMillis) {
        long ttlNanos = ttlMillis * 1_000_000L;
        long expiry = clock.getAsLong() + ttlNanos;

        // an absurd ttl would wrap past Long.MAX_VALUE and land in the past
        return expiry < 0 ? NEVER : expiry;
    }
}
