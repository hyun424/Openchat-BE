package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.outbox.OutboxEvent;
import io.hyun424.openchat.chat.outbox.OutboxEventRepository;
import io.hyun424.openchat.chat.outbox.OutboxEventStatus;
import io.hyun424.openchat.chat.outbox.OutboxPayloadSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatMessagePersistenceService {

    private final MessageRepository messageRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPayloadSerializer payloadSerializer;
    private final ChatPipelineMetrics chatPipelineMetrics;

    @Value("${app.outbox.initial-retry-delay-ms:1000}")
    private long initialRetryDelayMs;

    @Transactional
    public PersistedChatMessage persistWithOutbox(Long roomId,
                                                  String senderId,
                                                  String nickname,
                                                  String content,
                                                  String clientMessageId,
                                                  String messageId,
                                                  long createdAt) {
        long totalStartNanos = System.nanoTime();
        long dbStartNanos = System.nanoTime();
        chatPipelineMetrics.incrementCounter("ingest.db_insert.attempted");
        Message saved;
        try {
            saved = messageRepository.save(Message.builder()
                    .messageId(messageId)
                    .roomId(roomId)
                    .senderId(senderId)
                    .clientMessageId(StringUtils.hasText(clientMessageId) ? clientMessageId : null)
                    .senderNickname(nickname)
                    .content(content)
                    .createdAt(createdAt)
                    .build());
            chatPipelineMetrics.incrementCounter("ingest.db_insert.success");
        } catch (RuntimeException e) {
            chatPipelineMetrics.incrementCounter("ingest.db_insert.fail");
            chatPipelineMetrics.recordStage("ingest.db_save.fail", dbStartNanos);
            throw e;
        }
        chatPipelineMetrics.recordStage("ingest.db_save", dbStartNanos);

        ChatMessageDto dto = ChatMessageDto.from(saved);
        dto.setClientMessageId(clientMessageId);

        long outboxStartNanos = System.nanoTime();
        long outboxCreatedAt = System.currentTimeMillis();
        OutboxEvent event = outboxEventRepository.save(OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(OutboxEvent.CHAT_MESSAGE_CREATED)
                .aggregateType(OutboxEvent.AGGREGATE_MESSAGE)
                .aggregateId(saved.getId())
                .roomId(roomId)
                .messageId(messageId)
                .payloadJson(payloadSerializer.serialize(dto))
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .nextRetryAt(outboxCreatedAt + Math.max(0, initialRetryDelayMs))
                .createdAt(outboxCreatedAt)
                .build());
        chatPipelineMetrics.recordStage("ingest.outbox_save", outboxStartNanos);
        chatPipelineMetrics.recordStage("ingest.persist.total", totalStartNanos);

        return new PersistedChatMessage(saved, dto, event);
    }
}
