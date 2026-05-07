package io.hyun424.openchat.chat.fanout;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

class FanoutBatchScheduler {

    private final ScheduledExecutorService batchExecutor;

    FanoutBatchScheduler() {
        this.batchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fanout-batch-flusher");
            t.setDaemon(true);
            return t;
        });
    }

    void schedule(FanoutBufferKey key, long delayMillis, Consumer<FanoutBufferKey> flush) {
        if (batchExecutor.isShutdown()) {
            flush.accept(key);
            return;
        }
        try {
            batchExecutor.schedule(() -> flush.accept(key), Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            flush.accept(key);
        }
    }

    boolean awaitIdle(BooleanSupplier idle, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (idle.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    void shutdown() {
        batchExecutor.shutdown();
        try {
            if (!batchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                batchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
