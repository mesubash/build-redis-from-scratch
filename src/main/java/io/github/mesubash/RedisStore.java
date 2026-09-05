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

        RedisStream asStream(){
            if (value instanceof RedisStream stream){
                return stream;
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
        if (entry.value() instanceof RedisStream) {
            return "stream";
        }
        return entry.value() instanceof List ? "list" : "string";
    }

    // -2 when the key is gone, -1 when it has no ttl, otherwise milliseconds left
    public long ttlMillis(String key) {
        Entry entry = live(key);
        if (entry == null) {
            return -2;
        }
        if (entry.expiresAtNanos() == NEVER) {
            return -1;
        }
        long remaining = entry.expiresAtNanos() - clock.getAsLong();
        return Math.max(0, remaining / 1_000_000L);
    }

    // false when the key doesn't exist. keeps the value, replaces the deadline
    public boolean expire(String key, long ttlMillis) {
        long now = clock.getAsLong();
        boolean[] applied = new boolean[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            applied[0] = true;
            return new Entry(existing.value(), expiryFrom(ttlMillis));
        });
        return applied[0];
    }

    // true only if there was a ttl to remove
    public boolean persist(String key) {
        long now = clock.getAsLong();
        boolean[] removed = new boolean[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            if (existing.expiresAtNanos() == NEVER) {
                return existing;
            }
            removed[0] = true;
            return new Entry(existing.value(), NEVER);
        });
        return removed[0];
    }

    // set only if absent, the atomic version of a check followed by a write
    public boolean setIfAbsent(String key, String value) {
        long now = clock.getAsLong();
        boolean[] stored = new boolean[1];

        data.compute(key, (k, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                return existing;
            }
            stored[0] = true;
            return new Entry(value, NEVER);
        });
        return stored[0];
    }

    // returns the new length
    public long append(String key, String suffix) {
        long now = clock.getAsLong();
        long[] length = new long[1];

        data.compute(key, (k, existing) -> {
            boolean usable = existing != null && !existing.isExpired(now);
            String combined = usable ? existing.asString() + suffix : suffix;
            length[0] = combined.length();

            long expiry = usable ? existing.expiresAtNanos() : NEVER;
            return new Entry(combined, expiry);
        });
        return length[0];
    }

    // read and delete in one atomic step
    public String getDelete(String key) {
        long now = clock.getAsLong();
        String[] value = new String[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            value[0] = existing.asString();
            return null;
        });
        return value[0];
    }

    public void clear() {
        data.clear();
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

    // index into a list, negative counts from the end. null when out of range
    public String lindex(String key, long index) {
        long now = clock.getAsLong();
        String[] value = new String[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            List<String> list = existing.asList();
            long resolved = index < 0 ? list.size() + index : index;
            if (resolved >= 0 && resolved < list.size()) {
                value[0] = list.get((int) resolved);
            }
            return existing;
        });
        return value[0];
    }

    public String lpop(String key) {
        return pop(key, true);
    }

    public String rpop(String key) {
        return pop(key, false);
    }

    private String pop(String key, boolean fromHead) {
        long now = clock.getAsLong();
        String[] popped = new String[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            List<String> list = existing.asList();
            popped[0] = fromHead ? list.removeFirst() : list.removeLast();

            //redis has no empty lists, the key goes away with the last element
            return list.isEmpty() ? null : existing;
        });
        return popped[0];
    }

    // returns the id actually used. throws IllegalArgumentException with redis's own wording
    public String xadd(String key, String rawId, List<String> fields) {
        long now = clock.getAsLong();
        String[] assigned = new String[1];

        data.compute(key, (k, existing) -> {
            boolean usable = existing != null && !existing.isExpired(now);
            RedisStream stream = usable ? existing.asStream() : new RedisStream();

            StreamId id = resolveId(rawId, stream.lastId());
            if (id.compareTo(StreamId.MIN) <= 0) {
                throw new IllegalArgumentException(
                        "ERR The ID specified in XADD must be greater than 0-0");
            }
            if (id.compareTo(stream.lastId()) <= 0) {
                throw new IllegalArgumentException(
                        "ERR The ID specified in XADD is equal or smaller than the target stream top item");
            }

            stream.append(new StreamEntry(id, List.copyOf(fields)));
            assigned[0] = id.toString();

            long expiry = usable ? existing.expiresAtNanos() : NEVER;
            return new Entry(stream, expiry);
        });

        return assigned[0];
    }

    // "*" picks everything, "5-*" picks only the sequence
    private static StreamId resolveId(String rawId, StreamId lastId) {
        if (rawId.equals("*")) {
            long ms = System.currentTimeMillis();
            return ms == lastId.ms() ? new StreamId(ms, lastId.seq() + 1) : new StreamId(ms, 0);
        }
        if (rawId.endsWith("-*")) {
            long ms = Long.parseLong(rawId.substring(0, rawId.length() - 2));
            // a sequence restarts at 0 for a new millisecond, and at 1 for time 0
            long seq = ms == lastId.ms() ? lastId.seq() + 1 : (ms == 0 ? 1 : 0);
            return new StreamId(ms, seq);
        }
        try {
            return StreamId.parse(rawId, 0);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ERR Invalid stream ID specified as stream command argument");
        }
    }

    public List<StreamEntry> xrange(String key, String rawStart, String rawEnd) {
        StreamId from = rawStart.equals("-") ? StreamId.MIN : StreamId.parse(rawStart, 0);
        StreamId to = rawEnd.equals("+") ? StreamId.MAX : StreamId.parse(rawEnd, Long.MAX_VALUE);
        return readRange(key, from, to, false);
    }

    // XREAD is exclusive - it wants what arrived after the id the client already has
    public List<StreamEntry> xread(String key, String rawAfter) {
        StreamId after = rawAfter.equals("$")
                ? lastStreamId(key)
                : StreamId.parse(rawAfter, 0);
        return readRange(key, after, StreamId.MAX, true);
    }

    public long xlen(String key) {
        long now = clock.getAsLong();
        long[] size = new long[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            size[0] = existing.asStream().entries().size();
            return existing;
        });
        return size[0];
    }

    public StreamId lastStreamId(String key) {
        long now = clock.getAsLong();
        StreamId[] last = {StreamId.MIN};

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            last[0] = existing.asStream().lastId();
            return existing;
        });
        return last[0];
    }

    private List<StreamEntry> readRange(String key, StreamId from, StreamId to, boolean exclusive) {
        long now = clock.getAsLong();
        List<StreamEntry> result = new ArrayList<>();

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            result.addAll(existing.asStream().range(from, to, exclusive));
            return existing;
        });
        return result;
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
