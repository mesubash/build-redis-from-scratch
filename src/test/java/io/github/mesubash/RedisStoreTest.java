package io.github.mesubash;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void existsReflectsPresence() {
        store.set("k", "v");
        assertTrue(store.exists("k"));
        assertFalse(store.exists("missing"));
    }

    @Test
    void existsIgnoresExpiredKeys() {
        store.set("k", "v", 100);
        now = millis(200);
        assertFalse(store.exists("k"));
    }

    @Test
    void deleteReportsWhetherItRemovedSomething() {
        store.set("k", "v");
        assertTrue(store.delete("k"));
        assertFalse(store.delete("k"));
        assertNull(store.get("k"));
    }

    @Test
    void deleteDoesNotCountAnExpiredKey() {
        store.set("k", "v", 100);
        now = millis(200);
        assertFalse(store.delete("k"));
    }

    @Test
    void typeIsStringOrNone() {
        store.set("k", "v");
        assertEquals("string", store.type("k"));
        assertEquals("none", store.type("missing"));
    }

    @Test
    void typeIgnoresExpiredKeys() {
        store.set("k", "v", 100);
        now = millis(200);
        assertEquals("none", store.type("k"));
    }

    @Test
    void keysListsEverything() {
        store.set("name", "Subash");
        store.set("city", "Kathmandu");
        assertEquals(Set.of("name", "city"), new HashSet<>(store.keys("*")));
    }

    @Test
    void keysOnEmptyStoreIsEmpty() {
        assertEquals(List.of(), store.keys("*"));
    }

    @Test
    void keysFiltersByPattern() {
        store.set("user:1", "a");
        store.set("user:2", "b");
        store.set("city", "c");
        assertEquals(Set.of("user:1", "user:2"), new HashSet<>(store.keys("user:*")));
        assertEquals(Set.of("user:1", "user:2"), new HashSet<>(store.keys("user:?")));
    }

    @Test
    void keysExcludesExpiredKeys() {
        store.set("permanent", "v");
        store.set("temp", "v", 100);
        now = millis(200);
        assertEquals(List.of("permanent"), store.keys("*"));
    }

    @Test
    void keysPatternDoesNotTreatDotAsWildcard() {
        // Pattern.quote means a literal dot only matches a dot
        store.set("a.b", "v");
        store.set("axb", "v");
        assertEquals(List.of("a.b"), store.keys("a.b"));
    }
}
