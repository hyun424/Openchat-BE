package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 모든 채팅 메시지가 처음 들어오는 단일 진입점.
 * 메시지는 반드시 DB에 먼저 저장한 뒤 publish한다. 실시간 전송보다 영속성을 우선해야
 * "사용자가 본 메시지가 새로고침 후 사라지는" 상황을 막을 수 있기 때문이다.
 */
@Service
@Slf4j
public class ChatIngestService {

    private final MessageService messageService;
    private final ChatMessagePersistenceService persistenceService;
    private final RoomTrafficMonitor roomTrafficMonitor;
    private final ChatPipelineMetrics chatPipelineMetrics;

    private final ConcurrentHashMap<String, Long> clientMessageIdCache = new ConcurrentHashMap<>();
    private static final long CLIENT_MSG_CACHE_TTL_MS = 60_000;
    private final ScheduledExecutorService cacheCleaner;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public ChatIngestService(MessageService messageService,
                             ChatMessagePersistenceService persistenceService,
                             RoomTrafficMonitor roomTrafficMonitor,
                             ChatPipelineMetrics chatPipelineMetrics) {
        this.messageService = messageService;
        this.persistenceService = persistenceService;
        this.roomTrafficMonitor = roomTrafficMonitor;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.cacheCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ingest-cache-cleaner");
            t.setDaemon(true);
            return t;
        });
        startCacheCleaner();
    }

    private void startCacheCleaner() {
        cacheCleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            int removed = 0;
            for (var entry : clientMessageIdCache.entrySet()) {
                if (now - entry.getValue() > CLIENT_MSG_CACHE_TTL_MS) {
                    clientMessageIdCache.remove(entry.getKey());
                    removed++;
                }
            }
            if (removed > 0) {
                log.debug("[INGEST CACHE CLEANUP] removed {} expired entries", removed);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        cacheCleaner.shutdown();
    }

    public ChatIngestResult ingest(
            Long roomId,
            String senderId,
            String nickname,
            String content,
            String clientMessageId
    ) {
        long ingestStartNanos = System.nanoTime();
        String normalizedClientMessageId = normalizeClientMessageId(clientMessageId);

        Message duplicate = findDuplicateClientMessage(roomId, senderId, normalizedClientMessageId);
        if (duplicate != null) {
            chatPipelineMetrics.incrementCounter("ingest.dedupe_skip");
            ChatMessageDto duplicateDto = ChatMessageDto.from(duplicate);
            duplicateDto.setClientMessageId(normalizedClientMessageId);
            return new ChatIngestResult(duplicateDto, false, null);
        }

        roomTrafficMonitor.recordInboundMessage(roomId);

        String messageId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();

        log.debug("[INGEST START][{}] roomId={} senderId={} clientMessageId={}",
                instanceId, roomId, senderId, clientMessageId);

        PersistedChatMessage persisted;
        try {
            persisted = persistMessageAndOutbox(
                    roomId, senderId, nickname, content, normalizedClientMessageId, messageId, createdAt);
        } catch (DataIntegrityViolationException e) {
            Message concurrentDuplicate = findDuplicateAfterInsertRace(roomId, senderId, normalizedClientMessageId);
            if (concurrentDuplicate != null) {
                chatPipelineMetrics.incrementCounter("ingest.dedupe_unique_violation_recovered");
                ChatMessageDto duplicateDto = ChatMessageDto.from(concurrentDuplicate);
                duplicateDto.setClientMessageId(normalizedClientMessageId);
                return new ChatIngestResult(duplicateDto, false, null);
            }
            throw e;
        }
        rememberClientMessageId(roomId, senderId, normalizedClientMessageId);
        chatPipelineMetrics.recordStage("ingest.total", ingestStartNanos);

        log.debug("[INGEST DONE][{}] roomId={} messageId={} latency={}ms",
                instanceId, roomId, messageId, System.currentTimeMillis() - createdAt);
        return new ChatIngestResult(persisted.dto(), true, persisted.outboxEvent().getId());
    }

    private String normalizeClientMessageId(String clientMessageId) {
        return StringUtils.hasText(clientMessageId) ? clientMessageId.trim() : null;
    }

    private Message findDuplicateClientMessage(Long roomId, String senderId, String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }

        long cacheStartNanos = System.nanoTime();
        String cacheKey = clientMessageCacheKey(roomId, senderId, clientMessageId);
        if (clientMessageIdCache.containsKey(cacheKey)) {
            chatPipelineMetrics.recordStage("ingest.dedupe.cache_check", cacheStartNanos);
            log.info("[INGEST DEDUPE CACHE][{}] roomId={} senderId={} clientMessageId={}",
                    instanceId, roomId, senderId, clientMessageId);
            return messageService.findByClientMessageId(roomId, senderId, clientMessageId);
        }
        chatPipelineMetrics.recordStage("ingest.dedupe.cache_check", cacheStartNanos);

        long dbLookupStartNanos = System.nanoTime();
        Message existing = messageService.findByClientMessageId(roomId, senderId, clientMessageId);
        chatPipelineMetrics.recordStage("ingest.dedupe.db_lookup", dbLookupStartNanos);
        if (existing == null) {
            return null;
        }

        rememberClientMessageId(roomId, senderId, clientMessageId);
        log.info("[INGEST DEDUPE][{}] roomId={} senderId={} clientMessageId={} messageId={}",
                instanceId, roomId, senderId, clientMessageId, existing.getMessageId());
        return existing;
    }

    private Message findDuplicateAfterInsertRace(Long roomId, String senderId, String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }
        Message existing = messageService.findByClientMessageId(roomId, senderId, clientMessageId);
        if (existing != null) {
            rememberClientMessageId(roomId, senderId, clientMessageId);
            log.info("[INGEST DEDUPE UNIQUE][{}] roomId={} senderId={} clientMessageId={} messageId={}",
                    instanceId, roomId, senderId, clientMessageId, existing.getMessageId());
        }
        return existing;
    }

    private PersistedChatMessage persistMessageAndOutbox(Long roomId,
                                                         String senderId,
                                                         String nickname,
                                                         String content,
                                                         String clientMessageId,
                                                         String messageId,
                                                         long createdAt) {
        long startNanos = System.nanoTime();
        try {
            PersistedChatMessage persisted = persistenceService.persistWithOutbox(
                    roomId, senderId, nickname, content, clientMessageId, messageId, createdAt);
            log.debug("[DB+OUTBOX SAVED][{}] messageId={} dbId={} outboxId={}",
                    instanceId, messageId, persisted.message().getId(), persisted.outboxEvent().getId());
            return persisted;
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("ingest.persist.fail", startNanos);
            log.error("[DB+OUTBOX SAVE FAIL][{}] roomId={} senderId={} messageId={}",
                    instanceId, roomId, senderId, messageId, e);
            throw e;
        }
    }

    private void rememberClientMessageId(Long roomId, String senderId, String clientMessageId) {
        if (clientMessageId == null) {
            return;
        }
        clientMessageIdCache.put(
                clientMessageCacheKey(roomId, senderId, clientMessageId),
                System.currentTimeMillis());
    }

    private String clientMessageCacheKey(Long roomId, String senderId, String clientMessageId) {
        return roomId + ":" + senderId + ":" + clientMessageId;
    }

}
