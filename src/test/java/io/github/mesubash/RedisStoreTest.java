package io.github.mesubash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedisStoreTest {

    // mutable fake clock, so expiry is tested at exact instants instead of by sleeping
    private long now = 0;
    private final RedisStore store = new RedisStore(() -> now);

    private static long millis(long ms) {
        return ms * 1_000_000L;
    }

    @Test
    void storesAndReadsBack() {
        store.set("name", "Subash");
        assertEquals("Subash", store.get("name"));
    }

    @Test
    void missingKeyIsNull() {
        assertNull(store.get("nothing"));
    }

    @Test
    void valueIsReadableBeforeExpiry() {
        store.set("foo", "bar", 100);
        now = millis(99);
        assertEquals("bar", store.get("foo"));
    }

    @Test
    void valueIsGoneAtTheExpiryInstant() {
        store.set("foo", "bar", 100);
        now = millis(100);
        assertNull(store.get("foo"));
    }

    @Test
    void valueIsGoneAfterExpiry() {
        store.set("foo", "bar", 100);
        now = millis(5000);
        assertNull(store.get("foo"));
    }

    @Test
    void secondsTtlIsConvertedCorrectly() {
        store.set("foo", "bar", 1000);
        now = millis(999);
        assertEquals("bar", store.get("foo"));
        now = millis(1000);
        assertNull(store.get("foo"));
    }

    @Test
    void noTtlMeansNeverExpires() {
        store.set("permanent", "value");
        now = Long.MAX_VALUE - 1;
        assertEquals("value", store.get("permanent"));
    }

    @Test
    void plainSetClearsAnExistingTtl() {
        store.set("temp", "v", 100);
        store.set("temp", "v2");
        now = millis(5000);
        assertEquals("v2", store.get("temp"));
    }

    @Test
    void setWithTtlReplacesAPermanentKey() {
        store.set("k", "v");
        store.set("k", "v2", 100);
        now = millis(200);
        assertNull(store.get("k"));
    }

    @Test
    void expiredKeyIsRemovedNotJustHidden() {
        store.set("foo", "bar", 100);
        now = millis(200);
        assertNull(store.get("foo"));

        // a hidden-but-present entry would come back when the clock rewinds
        now = 0;
        assertNull(store.get("foo"));
    }

    @Test
    void absurdTtlSaturatesInsteadOfWrapping() {
        // now + ttl would overflow past Long.MAX_VALUE and land in the past
        store.set("k", "v", Long.MAX_VALUE / 1_000_000);
        now = millis(5000);
        assertEquals("v", store.get("k"));
    }
}
