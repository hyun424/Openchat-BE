package io.hyun424.openchat.chat.fanout;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FanoutBatchSchedulerTest {

    @Test
    void schedule_runsDelayedFlush() throws Exception {
        FanoutBatchScheduler scheduler = new FanoutBatchScheduler();
        AtomicReference<FanoutBufferKey> flushed = new AtomicReference<>();
        FanoutBufferKey key = new FanoutBufferKey(1L, 0);
        try {
            scheduler.schedule(key, 1, flushed::set);

            assertTrue(scheduler.awaitIdle(() -> flushed.get() != null, 500));
            assertEquals(key, flushed.get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void schedule_afterShutdown_runsFlushInline() {
        FanoutBatchScheduler scheduler = new FanoutBatchScheduler();
        AtomicInteger count = new AtomicInteger();

        scheduler.shutdown();
        scheduler.schedule(new FanoutBufferKey(1L, null), 100, ignored -> count.incrementAndGet());

        assertEquals(1, count.get());
    }

    @Test
    void awaitIdle_returnsFalseOnTimeout() throws Exception {
        FanoutBatchScheduler scheduler = new FanoutBatchScheduler();
        try {
            assertFalse(scheduler.awaitIdle(() -> false, 20));
        } finally {
            scheduler.shutdown();
        }
    }
}
