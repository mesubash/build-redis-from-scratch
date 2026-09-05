package io.github.mesubash;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        RedisSortedSet asSortedSet(){
            if (value instanceof RedisSortedSet sorted){
                return sorted;
            }
            throw new WrongTypeException();
        }

        @SuppressWarnings("unchecked")
        Map<String, String> asHash(){
            if (value instanceof Map<?, ?> map){
                return (Map<String, String>) map;
            }
            throw new WrongTypeException();
        }

        @SuppressWarnings("unchecked")
        Set<String> asSet(){
            if (value instanceof Set<?> set){
                return (Set<String>) set;
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
    private final Object dataMonitor = new Object();

    // bumped on every write, so WATCH can tell whether a key changed under it
    private final Map<String, Long> versions = new ConcurrentHashMap<>();

    public long version(String key) {
        return versions.getOrDefault(key, 0L);
    }

    private void touch(String key) {
        versions.merge(key, 1L, Long::sum);
    }

    private void wakeWaiters() {
        synchronized (dataMonitor) {
            dataMonitor.notifyAll();
        }
    }

    // blocks until something is written or the timeout passes. the caller re-checks either way
    public void awaitWrite(long timeoutMillis) throws InterruptedException {
        synchronized (dataMonitor) {
            // capped so a caller with a long timeout still rechecks periodically
            dataMonitor.wait(Math.min(Math.max(timeoutMillis, 1), 100));
        }
    }

    public RedisStore() {
        this(System::nanoTime);
    }

    //package-private so tests can move time without sleeping
    RedisStore(LongSupplier clock) {
        this.clock = clock;
    }

    public void set(String key, String value) {
        data.put(key, new Entry(value, NEVER));
        touch(key);
    }

    public void set(String key, String value, long ttlMillis) {
        data.put(key, new Entry(value, expiryFrom(ttlMillis)));
        touch(key);
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
        if (existed) {
            touch(key);
        }
        return existed;
    }

    // string or list, decided by what's stored
    public String type(String key) {
        Entry entry = live(key);

        if (entry == null) {
            return "none";
        }
        return switch (entry.value()) {
            case RedisStream ignored -> "stream";
            case RedisSortedSet ignored -> "zset";
            case Map<?, ?> ignored -> "hash";
            case Set<?> ignored -> "set";
            case List<?> ignored -> "list";
            default -> "string";
        };
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
        if (applied[0]) {
            touch(key);
        }
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
        if (removed[0]) {
            touch(key);
        }
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
        if (stored[0]) {
            touch(key);
        }
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
        touch(key);
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
        if (value[0] != null) {
            touch(key);
        }
        return value[0];
    }

    public void clear() {
        // every watched key has effectively changed
        for (String key : data.keySet()) {
            touch(key);
        }
        data.clear();
    }

    // used when loading an rdb file: the deadline arrives as an absolute wall-clock instant,
    // which has to be turned into one on our monotonic clock
    public void restore(String key, String value, long expiresAtEpochMillis) {
        if (expiresAtEpochMillis == 0) {
            set(key, value);
            return;
        }

        long remaining = expiresAtEpochMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            // already expired when the file was loaded, so it never enters the keyspace
            return;
        }
        set(key, value, remaining);
    }

    // string keys only, with deadlines converted back to absolute wall-clock instants.
    // ponytail: lists, hashes, sets, zsets and streams are skipped - writing those needs
    // redis's listpack and quicklist encodings, which is a stage of its own
    public List<RdbReader.Record> snapshotStrings() {
        List<RdbReader.Record> records = new ArrayList<>();

        for (String key : keys("*")) {
            String value;
            try {
                value = get(key);
            } catch (WrongTypeException e) {
                continue;
            }
            if (value == null) {
                continue;
            }

            long remaining = ttlMillis(key);
            long expiresAt = remaining < 0 ? 0 : System.currentTimeMillis() + remaining;
            records.add(new RdbReader.Record(key, value, expiresAt));
        }
        return records;
    }

    // a page of keys plus the cursor to continue from, 0 meaning the scan is finished
    public record ScanPage(long nextCursor, List<String> keys) {
    }

    // ponytail: cursor is an index into the sorted keyspace, so it costs a sort per call.
    // real redis walks hash buckets in reverse-binary order, which survives resizing without
    // sorting - worth doing if the keyspace ever gets big enough to notice
    public ScanPage scan(long cursor, String pattern, int count) {
        List<String> allKeys = new ArrayList<>(data.keySet());
        allKeys.sort(null);

        Pattern regex = globToRegex(pattern);
        List<String> page = new ArrayList<>();

        int at = (int) Math.min(Math.max(cursor, 0), allKeys.size());
        while (at < allKeys.size() && page.size() < count) {
            String key = allKeys.get(at);
            at++;

            // expired keys are skipped but still consume a step, so a page can come back short
            if (regex.matcher(key).matches() && exists(key)) {
                page.add(key);
            }
        }

        return new ScanPage(at >= allKeys.size() ? 0 : at, page);
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
        touch(key);
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
        touch(key);

        // a blocked BLPOP is waiting for exactly this
        wakeWaiters();

        return length[0];
    }

    // blocks until one of the keys has an element or the timeout passes.
    // returns [key, value], or null on timeout. timeoutMillis of 0 waits forever
    public String[] blockingPop(String[] keys, long timeoutMillis) throws InterruptedException {
        long deadline = timeoutMillis == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMillis;

        synchronized (dataMonitor) {
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
                dataMonitor.wait(Math.min(remaining, 100));
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
        if (popped[0] != null) {
            touch(key);
        }
        return popped[0];
    }

    // returns how many fields were new rather than overwritten
    public long hset(String key, List<String> fieldValuePairs) {
        long now = clock.getAsLong();
        long[] added = new long[1];

        data.compute(key, (k, existing) -> {
            boolean usable = existing != null && !existing.isExpired(now);
            Map<String, String> hash = usable ? existing.asHash() : new LinkedHashMap<>();

            for (int i = 0; i < fieldValuePairs.size(); i += 2) {
                if (hash.put(fieldValuePairs.get(i), fieldValuePairs.get(i + 1)) == null) {
                    added[0]++;
                }
            }

            long expiry = usable ? existing.expiresAtNanos() : NEVER;
            return new Entry(hash, expiry);
        });

        touch(key);
        return added[0];
    }

    public String hget(String key, String field) {
        Map<String, String> hash = readHash(key);
        return hash == null ? null : hash.get(field);
    }

    // flat [field, value, field, value] so the dispatcher can encode it directly
    public List<String> hgetall(String key) {
        Map<String, String> hash = readHash(key);
        if (hash == null) {
            return List.of();
        }
        List<String> flat = new ArrayList<>();
        hash.forEach((field, value) -> {
            flat.add(field);
            flat.add(value);
        });
        return flat;
    }

    public List<String> hkeys(String key) {
        Map<String, String> hash = readHash(key);
        return hash == null ? List.of() : new ArrayList<>(hash.keySet());
    }

    public List<String> hvals(String key) {
        Map<String, String> hash = readHash(key);
        return hash == null ? List.of() : new ArrayList<>(hash.values());
    }

    public long hlen(String key) {
        Map<String, String> hash = readHash(key);
        return hash == null ? 0 : hash.size();
    }

    public boolean hexists(String key, String field) {
        Map<String, String> hash = readHash(key);
        return hash != null && hash.containsKey(field);
    }

    public long hdel(String key, String... fields) {
        long now = clock.getAsLong();
        long[] removed = new long[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            Map<String, String> hash = existing.asHash();
            for (String field : fields) {
                if (hash.remove(field) != null) {
                    removed[0]++;
                }
            }
            // an empty hash is no hash at all
            return hash.isEmpty() ? null : existing;
        });

        if (removed[0] > 0) {
            touch(key);
        }
        return removed[0];
    }

    // a copy taken inside compute, so callers never hold the live map
    private Map<String, String> readHash(String key) {
        long now = clock.getAsLong();
        Map<String, String>[] copy = new Map[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            copy[0] = new LinkedHashMap<>(existing.asHash());
            return existing;
        });
        return copy[0];
    }

    public long sadd(String key, String... members) {
        long now = clock.getAsLong();
        long[] added = new long[1];

        data.compute(key, (k, existing) -> {
            boolean usable = existing != null && !existing.isExpired(now);
            Set<String> set = usable ? existing.asSet() : new LinkedHashSet<>();

            for (String member : members) {
                if (set.add(member)) {
                    added[0]++;
                }
            }

            long expiry = usable ? existing.expiresAtNanos() : NEVER;
            return new Entry(set, expiry);
        });

        touch(key);
        return added[0];
    }

    public long srem(String key, String... members) {
        long now = clock.getAsLong();
        long[] removed = new long[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            Set<String> set = existing.asSet();
            for (String member : members) {
                if (set.remove(member)) {
                    removed[0]++;
                }
            }
            return set.isEmpty() ? null : existing;
        });

        if (removed[0] > 0) {
            touch(key);
        }
        return removed[0];
    }

    public List<String> smembers(String key) {
        Set<String> set = readSet(key);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    public boolean sismember(String key, String member) {
        Set<String> set = readSet(key);
        return set != null && set.contains(member);
    }

    public long scard(String key) {
        Set<String> set = readSet(key);
        return set == null ? 0 : set.size();
    }

    private Set<String> readSet(String key) {
        long now = clock.getAsLong();
        Set<String>[] copy = new Set[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            copy[0] = new LinkedHashSet<>(existing.asSet());
            return existing;
        });
        return copy[0];
    }

    // returns how many members were new rather than rescored
    public long zadd(String key, List<String> scoreMemberPairs) {
        long now = clock.getAsLong();
        long[] added = new long[1];

        data.compute(key, (k, existing) -> {
            boolean usable = existing != null && !existing.isExpired(now);
            RedisSortedSet sorted = usable ? existing.asSortedSet() : new RedisSortedSet();

            for (int i = 0; i < scoreMemberPairs.size(); i += 2) {
                double score = Double.parseDouble(scoreMemberPairs.get(i));
                if (sorted.add(scoreMemberPairs.get(i + 1), score)) {
                    added[0]++;
                }
            }

            long expiry = usable ? existing.expiresAtNanos() : NEVER;
            return new Entry(sorted, expiry);
        });

        touch(key);
        return added[0];
    }

    public long zrem(String key, String... members) {
        long now = clock.getAsLong();
        long[] removed = new long[1];

        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            RedisSortedSet sorted = existing.asSortedSet();
            for (String member : members) {
                if (sorted.remove(member)) {
                    removed[0]++;
                }
            }
            return sorted.isEmpty() ? null : existing;
        });

        if (removed[0] > 0) {
            touch(key);
        }
        return removed[0];
    }

    public Double zscore(String key, String member) {
        Double[] score = new Double[1];
        withSortedSet(key, sorted -> score[0] = sorted.score(member));
        return score[0];
    }

    public Long zrank(String key, String member) {
        Long[] rank = new Long[1];
        withSortedSet(key, sorted -> rank[0] = sorted.rank(member));
        return rank[0];
    }

    public long zcard(String key) {
        long[] size = new long[1];
        withSortedSet(key, sorted -> size[0] = sorted.size());
        return size[0];
    }

    public List<String> zrange(String key, long start, long stop) {
        List<String> members = new ArrayList<>();
        withSortedSet(key, sorted -> members.addAll(sorted.range(start, stop)));
        return members;
    }

    // reads happen inside compute so a writer can't mutate the set mid-scan
    private void withSortedSet(String key, java.util.function.Consumer<RedisSortedSet> reader) {
        long now = clock.getAsLong();
        data.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(now)) {
                return null;
            }
            reader.accept(existing.asSortedSet());
            return existing;
        });
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

        touch(key);

        // a blocked XREAD is waiting for exactly this
        wakeWaiters();
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
