package io.github.mesubash;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// shared key-value state. one instance per server, used by every client thread
public class RedisStore {

    //ConcurrentHashMap over a synchronized map: unrelated keys don't contend,
    //and it gives us atomic putIfAbsent/compute for SET NX and expiry later
    private final Map<String, String> data = new ConcurrentHashMap<>();

    public void set(String key, String value) {
        data.put(key, value);
    }

    // null means the key is absent - the map refuses to store nulls, so there is no ambiguity
    public String get(String key) {
        return data.get(key);
    }
}
