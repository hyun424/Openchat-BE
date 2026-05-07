package io.hyun424.openchat.infra.websocket.session;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
