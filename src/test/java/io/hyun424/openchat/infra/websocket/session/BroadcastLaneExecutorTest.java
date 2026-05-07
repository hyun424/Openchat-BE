package io.hyun424.openchat.infra.websocket.session;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastLaneExecutorTest {

    @Test
    void enqueuesTaskOnLaneExecutorAndReportsQueueDepth() throws Exception {
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(new SimpleMeterRegistry());
        BroadcastLaneExecutor executor = new BroadcastLaneExecutor(2, 16, 500, metrics);
        CountDownLatch ran = new CountDownLatch(1);
        BroadcastTask task = new BroadcastTask(1L, 0, "single", new TextMessage("{}"), 2, List.of(), List.of(), System.nanoTime());

        executor.enqueue(task, ignored -> ran.countDown());

        assertTrue(ran.await(500, TimeUnit.MILLISECONDS));
        assertEquals(2, executor.laneCount());
        assertEquals(0, executor.totalQueueDepth());
        executor.shutdown();
        metrics.shutdown();
    }
    @Test
    void queueFullReturnsFalseAndDoesNotRunDroppedTask() throws Exception {
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(new SimpleMeterRegistry());
        BroadcastLaneExecutor executor = new BroadcastLaneExecutor(1, 1, 500, metrics);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicBoolean droppedTaskRan = new AtomicBoolean(false);
        BroadcastTask first = new BroadcastTask(1L, 0, "single", new TextMessage("{}"), 2, List.of(), List.of(), System.nanoTime());
        BroadcastTask queued = new BroadcastTask(1L, 0, "single", new TextMessage("{}"), 2, List.of(), List.of(), System.nanoTime());
        BroadcastTask dropped = new BroadcastTask(1L, 0, "single", new TextMessage("{}"), 2, List.of(), List.of(), System.nanoTime());

        assertTrue(executor.enqueue(first, ignored -> {
            firstStarted.countDown();
            try {
                releaseFirst.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(firstStarted.await(500, TimeUnit.MILLISECONDS));
        assertTrue(executor.enqueue(queued, ignored -> secondStarted.countDown()));

        assertFalse(executor.enqueue(dropped, ignored -> droppedTaskRan.set(true)));

        Thread.sleep(100);
        assertFalse(droppedTaskRan.get());
        releaseFirst.countDown();
        assertTrue(secondStarted.await(500, TimeUnit.MILLISECONDS));
        executor.shutdown();
        metrics.shutdown();
    }

}
