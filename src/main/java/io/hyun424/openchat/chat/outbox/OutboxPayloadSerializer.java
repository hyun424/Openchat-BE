package io.hyun424.openchat.chat.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.springframework.stereotype.Component;

@Component
public class OutboxPayloadSerializer {

    private final ObjectMapper objectMapper;

    public OutboxPayloadSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(ChatMessageDto message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    public ChatMessageDto deserialize(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, ChatMessageDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize outbox payload", e);
        }
    }
}
