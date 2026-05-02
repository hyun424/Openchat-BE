package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.metrics.ChatPipelineMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class RoomFanoutDispatcher {

    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private final ChatFanoutService fanoutService;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final List<StripeWorker> workers;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public RoomFanoutDispatcher(ChatFanoutService fanoutService,
                                ChatPipelineMetrics chatPipelineMetrics,
                                @Value("${app.fanout.dispatcher.stripes:8}") int stripes,
                                @Value("${app.fanout.dispatcher.queue-capacity:100000}") int queueCapacity) {
        if (stripes <= 0) {
            throw new IllegalArgumentException("stripes must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.fanoutService = fanoutService;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.workers = createWorkers(stripes, queueCapacity);
        this.workers.forEach(StripeWorker::start);
    }

    public void enqueue(ChatMessageDto message) {
        long enqueueStartNanos = System.nanoTime();
        if (!accepting.get()) {
            recordEnqueueFailure(enqueueStartNanos);
            fanoutInCaller(message);
            return;
        }

        StripeWorker worker = workers.get(stripeIndexFor(message.getRoomId()));
        boolean accepted = worker.offer(new PendingFanout(message, System.nanoTime()));
        chatPipelineMetrics.recordStageNanos(
                "fanout.dispatcher.enqueue", System.nanoTime() - enqueueStartNanos);

        if (!accepted) {
            recordEnqueueFailure(enqueueStartNanos);
            fanoutInCaller(message);
        }
    }

    int stripeIndexFor(Long roomId) {
        return Math.floorMod(Long.hashCode(roomId), workers.size());
    }

    private List<StripeWorker> createWorkers(int stripes, int queueCapacity) {
        List<StripeWorker> createdWorkers = new ArrayList<>(stripes);
        for (int i = 0; i < stripes; i++) {
            BlockingQueue<PendingFanout> queue = new LinkedBlockingQueue<>(queueCapacity);
            chatPipelineMetrics.bindFanoutDispatcherQueue(i, queue);
            createdWorkers.add(new StripeWorker(i, queue));
        }
        return createdWorkers;
    }

    private void recordEnqueueFailure(long enqueueStartNanos) {
        chatPipelineMetrics.incrementCounter("openchat_fanout_dispatcher_enqueue_fail_total");
        chatPipelineMetrics.recordStageNanos(
                "fanout.dispatcher.enqueue.fail", System.nanoTime() - enqueueStartNanos);
    }

    private void fanoutInCaller(ChatMessageDto message) {
        long startNanos = System.nanoTime();
        fanoutService.fanout(message);
        chatPipelineMetrics.recordStageNanos(
                "fanout.dispatcher.fallback.total", System.nanoTime() - startNanos);
    }

    @PreDestroy
    public void shutdown() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }

        workers.forEach(StripeWorker::stopAccepting);
        long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MILLIS;
        for (StripeWorker worker : workers) {
            long remainingMillis = Math.max(0, deadline - System.currentTimeMillis());
            worker.awaitStop(remainingMillis);
        }
        workers.forEach(StripeWorker::forceStop);
    }

    private final class StripeWorker implements Runnable {

        private final int stripe;
        private final BlockingQueue<PendingFanout> queue;
        private final Thread thread;

        private StripeWorker(int stripe, BlockingQueue<PendingFanout> queue) {
            this.stripe = stripe;
            this.queue = queue;
            this.thread = new Thread(this, "room-fanout-dispatcher-" + stripe);
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private boolean offer(PendingFanout pendingFanout) {
            return queue.offer(pendingFanout);
        }

        private void stopAccepting() {
            thread.interrupt();
        }

        private void awaitStop(long timeoutMillis) {
            try {
                thread.join(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void forceStop() {
            if (thread.isAlive()) {
                log.warn("[FANOUT DISPATCHER] stripe={} did not stop gracefully", stripe);
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            while (accepting.get() || !queue.isEmpty()) {
                try {
                    PendingFanout pendingFanout = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (pendingFanout != null) {
                        dispatch(pendingFanout);
                    }
                } catch (InterruptedException e) {
                    if (accepting.get()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } catch (Exception e) {
                    log.error("[FANOUT DISPATCHER] stripe={} dispatch failed", stripe, e);
                }
            }
        }

        private void dispatch(PendingFanout pendingFanout) {
            chatPipelineMetrics.recordStageNanos(
                    "fanout.dispatcher.queue_wait",
                    System.nanoTime() - pendingFanout.enqueuedNanos());
            chatPipelineMetrics.recordSinceCreated(
                    "fanout.dispatcher.worker_start.since_created",
                    pendingFanout.message());

            long startNanos = System.nanoTime();
            fanoutService.fanout(pendingFanout.message());
            chatPipelineMetrics.recordStageNanos(
                    "fanout.dispatcher.worker.total",
                    System.nanoTime() - startNanos);
        }
    }

    private record PendingFanout(ChatMessageDto message, long enqueuedNanos) {
    }
}
