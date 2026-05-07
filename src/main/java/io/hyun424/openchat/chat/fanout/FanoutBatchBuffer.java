package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class FanoutBatchBuffer {

    private final ConcurrentLinkedQueue<ChatMessageDto> messages = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final AtomicInteger size = new AtomicInteger(0);

    void add(ChatMessageDto message) {
        messages.add(message);
        size.incrementAndGet();
    }

    boolean markScheduled() {
        return flushScheduled.compareAndSet(false, true);
    }

    void clearScheduled() {
        flushScheduled.set(false);
    }

    boolean isScheduled() {
        return flushScheduled.get();
    }

    int size() {
        return size.get();
    }

    List<ChatMessageDto> drain(int maxMessages) {
        List<ChatMessageDto> drained = new ArrayList<>(Math.min(maxMessages, size()));
        for (int i = 0; i < maxMessages; i++) {
            ChatMessageDto message = messages.poll();
            if (message == null) {
                break;
            }
            size.decrementAndGet();
            drained.add(message);
        }
        return drained;
    }
}
