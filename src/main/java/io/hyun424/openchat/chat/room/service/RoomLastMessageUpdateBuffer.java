package io.hyun424.openchat.chat.room.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoomLastMessageUpdateBuffer {

    private final RoomService roomService;
    private final ConcurrentMap<Long, PendingLastMessage> pendingMessages = new ConcurrentHashMap<>();

    public void enqueue(Long roomId, Long timestamp, String message, String senderName) {
        if (roomId == null || timestamp == null) {
            return;
        }

        PendingLastMessage pending = new PendingLastMessage(timestamp, message, senderName);
        pendingMessages.merge(roomId, pending, (existing, incoming) ->
                incoming.isSameOrNewerThan(existing) ? incoming : existing);
    }

    @Scheduled(fixedDelayString = "${app.room.last-message.flush-interval-ms:1000}")
    public void flushPendingLastMessages() {
        pendingMessages.forEach((roomId, pending) -> {
            if (!pendingMessages.remove(roomId, pending)) {
                return;
            }
            flushSingle(roomId, pending);
        });
    }

    @PreDestroy
    public void flushBeforeShutdown() {
        flushPendingLastMessages();
    }

    private void flushSingle(Long roomId, PendingLastMessage pending) {
        try {
            roomService.updateLastMessage(
                    roomId,
                    pending.timestamp(),
                    pending.message(),
                    pending.senderName());
        } catch (Exception e) {
            requeueFailed(roomId, pending);
            log.warn("[ROOM LAST MESSAGE FLUSH FAIL] roomId={} timestamp={}",
                    roomId, pending.timestamp(), e);
        }
    }

    private void requeueFailed(Long roomId, PendingLastMessage failed) {
        pendingMessages.merge(roomId, failed, (existing, incoming) ->
                incoming.isNewerThan(existing) ? incoming : existing);
    }

    private record PendingLastMessage(Long timestamp, String message, String senderName) {

        private boolean isSameOrNewerThan(PendingLastMessage other) {
            return timestamp >= other.timestamp;
        }

        private boolean isNewerThan(PendingLastMessage other) {
            return timestamp > other.timestamp;
        }
    }
}
