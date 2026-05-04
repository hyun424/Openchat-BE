package io.hyun424.openchat.chat.room.metadata;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.hotchat.HotChatService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api'")
public class RoomMetadataUpdateBuffer {

    private final RoomService roomService;
    private final HotChatService hotChatService;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final ConcurrentHashMap<Long, PendingRoomMetadata> pendingByRoom = new ConcurrentHashMap<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    @Value("${app.room-metadata.enabled:true}")
    private boolean enabled = true;

    public void enqueue(ChatMessageDto message) {
        if (!enabled) {
            return;
        }
        if (message == null || message.getRoomId() == null || message.getCreatedAt() == null) {
            return;
        }
        PendingRoomMetadata pending = PendingRoomMetadata.from(message);
        pendingByRoom.merge(message.getRoomId(), pending, (oldValue, newValue) ->
                newValue.createdAt() >= oldValue.createdAt() ? newValue : oldValue);
        chatPipelineMetrics.recordDistribution("openchat_room_metadata_pending_count", "room", pendingByRoom.size());
    }

    @Scheduled(fixedDelayString = "${app.room-metadata.flush-interval-ms:1000}")
    public void flush() {
        if (!enabled) {
            return;
        }
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<PendingRoomMetadata> batch = drain();
            for (PendingRoomMetadata pending : batch) {
                flushOne(pending);
            }
        } finally {
            flushing.set(false);
        }
    }

    private List<PendingRoomMetadata> drain() {
        List<PendingRoomMetadata> batch = new ArrayList<>(pendingByRoom.size());
        for (Long roomId : pendingByRoom.keySet()) {
            PendingRoomMetadata pending = pendingByRoom.remove(roomId);
            if (pending != null) {
                batch.add(pending);
            }
        }
        return batch;
    }

    private void flushOne(PendingRoomMetadata pending) {
        long totalStartNanos = System.nanoTime();
        try {
            long roomStartNanos = System.nanoTime();
            roomService.updateLastMessage(
                    pending.roomId(),
                    pending.createdAt(),
                    pending.message(),
                    pending.senderName()
            );
            chatPipelineMetrics.recordStage("room_metadata.last_message", roomStartNanos);

            long hotchatStartNanos = System.nanoTime();
            hotChatService.recordMessageActivity(pending.roomId(), pending.messageId());
            chatPipelineMetrics.recordStage("room_metadata.hotchat", hotchatStartNanos);

            chatPipelineMetrics.recordStage("room_metadata.flush", totalStartNanos);
            chatPipelineMetrics.incrementCounter("room_metadata.flush.success");
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("room_metadata.flush.fail", totalStartNanos);
            chatPipelineMetrics.incrementCounter("room_metadata.flush.fail");
            requeueIfStillLatest(pending);
            log.warn("[ROOM METADATA FLUSH FAIL] roomId={} messageId={}",
                    pending.roomId(), pending.messageId(), e);
        }
    }

    private void requeueIfStillLatest(PendingRoomMetadata failed) {
        pendingByRoom.merge(failed.roomId(), failed, (oldValue, newValue) ->
                oldValue.createdAt() >= newValue.createdAt() ? oldValue : newValue);
    }

    @PreDestroy
    public void shutdown() {
        flush();
    }

    private record PendingRoomMetadata(
            Long roomId,
            Long createdAt,
            String message,
            String senderName,
            String messageId
    ) {
        private static PendingRoomMetadata from(ChatMessageDto message) {
            return new PendingRoomMetadata(
                    message.getRoomId(),
                    message.getCreatedAt(),
                    message.getMessage(),
                    message.getSenderName(),
                    message.getMessageId()
            );
        }
    }
}
