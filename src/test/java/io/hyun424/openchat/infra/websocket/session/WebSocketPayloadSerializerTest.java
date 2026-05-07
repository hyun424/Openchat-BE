package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketPayloadSerializerTest {

    @Test
    void serializesSingleAndBatchPayloadWithoutChangingWireShape() {
        ChatPipelineMetrics metrics = new ChatPipelineMetrics(new SimpleMeterRegistry());
        WebSocketPayloadSerializer serializer = new WebSocketPayloadSerializer(new ObjectMapper(), metrics);

        TextMessage single = serializer.serializeMessage(1L, message(1L, "message-1"));
        TextMessage batch = serializer.serializeBatchMessage(
                1L,
                List.of(message(1L, "message-1"), message(2L, "message-2")),
                false,
                3,
                4L
        );

        assertNotNull(single);
        assertTrue(single.getPayload().contains("\"messageId\":\"message-1\""));
        assertNotNull(batch);
        assertTrue(batch.getPayload().contains("\"type\":\"chat.batch\""));
        assertTrue(batch.getPayload().contains("\"realtimeComplete\":false"));
        assertTrue(batch.getPayload().contains("\"omittedCount\":3"));
        assertTrue(batch.getPayload().contains("\"lastSequence\":4"));
        metrics.shutdown();
    }

    private ChatMessageDto message(Long id, String messageId) {
        return ChatMessageDto.builder()
                .id(id)
                .sequence(id)
                .messageId(messageId)
                .clientMessageId("client-message-1")
                .roomId(1L)
                .senderId("user-1")
                .senderName("User 1")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
