package io.hyun424.openchat.infra.websocket.session;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
public class BroadcastLaneExecutor {

    public static final int DEFAULT_BROADCAST_LANES = 16;
    public static final int DEFAULT_BROADCAST_QUEUE_CAPACITY_PER_LANE = 4096;
    public static final int DEFAULT_BROADCAST_SHUTDOWN_TIMEOUT_MS = 5000;

    private final ThreadPoolExecutor[] laneExecutors;
    private final int shutdownTimeoutMillis;
    private final ChatPipelineMetrics metrics;

    public BroadcastLaneExecutor(int configuredLaneCount,
                                 int queueCapacityPerLane,
                                 int shutdownTimeoutMillis,
                                 ChatPipelineMetrics metrics) {
        int laneCount = resolveBroadcastLaneCount(configuredLaneCount);
        int boundedQueueCapacity = Math.max(1, queueCapacityPerLane);
        this.shutdownTimeoutMillis = Math.max(1, shutdownTimeoutMillis);
        this.metrics = metrics;
        this.laneExecutors = new ThreadPoolExecutor[laneCount];

        AtomicInteger threadCounter = new AtomicInteger(0);
        for (int i = 0; i < laneCount; i++) {
            int laneIndex = i;
            this.laneExecutors[i] = new ThreadPoolExecutor(
                    1, 1,
                    60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(boundedQueueCapacity),
                    r -> {
                        Thread t = new Thread(r, "ws-broadcast-lane-" + laneIndex + "-" + threadCounter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }
    }

    public int laneCount() {
        return laneExecutors.length;
    }

    public int totalQueueDepth() {
        int total = 0;
        for (ThreadPoolExecutor executor : laneExecutors) {
            total += executor.getQueue().size();
        }
        return total;
    }

    public boolean enqueue(BroadcastTask task, Consumer<BroadcastTask> taskRunner) {
        ThreadPoolExecutor executor = laneExecutors[task.laneIndex()];
        long enqueueStartNanos = System.nanoTime();
        try {
            executor.execute(() -> taskRunner.accept(task));
            metrics.recordStage("ws.broadcast.lane.enqueue", enqueueStartNanos);
            metrics.recordDistribution("openchat_pipeline_broadcast_lane_queue_size",
                    "lane-" + task.laneIndex(), executor.getQueue().size());
            return true;
        } catch (RejectedExecutionException e) {
            metrics.recordStage("ws.broadcast.lane.enqueue.fail", enqueueStartNanos);
            metrics.incrementCounter("ws.broadcast.lane.enqueue.fail");
            log.warn("[WS BROADCAST LANE FULL] lane={} roomId={} sessions={} - task dropped to preserve lane ordering",
                    task.laneIndex(), task.roomId(), task.sessions().size());
            return false;
        }
    }

    public void shutdown() {
        log.info("[WS REGISTRY] Shutting down broadcast lane executors");
        for (ThreadPoolExecutor executor : laneExecutors) {
            executor.shutdown();
        }
        long timeoutPerLane = Math.max(1, shutdownTimeoutMillis / Math.max(1, laneExecutors.length));
        for (ThreadPoolExecutor executor : laneExecutors) {
            try {
                if (!executor.awaitTermination(timeoutPerLane, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private int resolveBroadcastLaneCount(int configuredLaneCount) {
        if (configuredLaneCount > 0) {
            return configuredLaneCount;
        }
        return DEFAULT_BROADCAST_LANES;
    }
}
