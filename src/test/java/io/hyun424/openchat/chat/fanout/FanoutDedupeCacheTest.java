package io.hyun424.openchat.chat.fanout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FanoutDedupeCacheTest {

    @Test
    void hasProcessedLocally_dedupesLegacyMessageUntilTtlExpires() {
        FanoutDedupeCache cache = new FanoutDedupeCache(10, false);
        try {
            assertFalse(cache.hasProcessedLocally("message-1", null));
            assertTrue(cache.hasProcessedLocally("message-1", null));

            assertEquals(1, cache.evictExpired(System.currentTimeMillis() + 20));
            assertFalse(cache.hasProcessedLocally("message-1", null));
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void hasProcessedLocally_keepsPartitionKeysSeparate() {
        FanoutDedupeCache cache = new FanoutDedupeCache(60_000, false);
        try {
            assertFalse(cache.hasProcessedLocally("message-1", 0));
            assertFalse(cache.hasProcessedLocally("message-1", 1));
            assertTrue(cache.hasProcessedLocally("message-1", 1));
            assertEquals(2, cache.size());
        } finally {
            cache.shutdown();
        }
    }
}
