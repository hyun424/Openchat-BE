package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatBatchMessageDto;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;

import java.util.List;

@Slf4j
public class WebSocketPayloadSerializer {

    private final ObjectMapper objectMapper;
    private final ChatPipelineMetrics metrics;

    public WebSocketPayloadSerializer(ObjectMapper objectMapper, ChatPipelineMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public TextMessage serializeMessage(Long roomId, ChatMessageDto message) {
        long startNanos = System.nanoTime();
        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));
            metrics.recordStage("ws.serialize.single", startNanos);
            return textMessage;
        } catch (Exception e) {
            metrics.recordStage("ws.serialize.single.fail", startNanos);
            log.error("[WS SERIALIZE FAIL] roomId={} messageId={}", roomId, message.getMessageId(), e);
            return null;
        }
    }

    public TextMessage serializeBatchMessage(Long roomId,
                                             List<ChatMessageDto> messages,
                                             boolean realtimeComplete,
                                             int omittedCount,
                                             Long lastSequence) {
        long startNanos = System.nanoTime();
        try {
            Long resolvedLastSequence = lastSequence != null ? lastSequence : sequenceOf(messages.get(messages.size() - 1));
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(
                    ChatBatchMessageDto.from(roomId, messages, realtimeComplete, omittedCount, resolvedLastSequence)));
            metrics.recordStage("ws.serialize.batch", startNanos);
            return textMessage;
        } catch (Exception e) {
            metrics.recordStage("ws.serialize.batch.fail", startNanos);
            log.error("[WS BATCH SERIALIZE FAIL] roomId={} count={}", roomId, messages.size(), e);
            return null;
        }
    }

    public TextMessage serializeControl(String sessionId, Object payload, String payloadType) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            metrics.incrementCounter("ws.control." + payloadType + ".serialize.fail");
            log.warn("[WS CONTROL SERIALIZE FAIL] sessionId={} type={}", sessionId, payloadType, e);
            return null;
        }
    }

    private Long sequenceOf(ChatMessageDto message) {
        return message.getSequence() != null ? message.getSequence() : message.getId();
    }
}
