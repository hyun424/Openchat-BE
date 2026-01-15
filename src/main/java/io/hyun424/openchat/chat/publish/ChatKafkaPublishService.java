package io.hyun424.openchat.chat.publish;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("kafka")
public class ChatKafkaPublishService implements ChatMessagePublisher {

    private final KafkaTemplate<String, ChatMessageDto> kafkaTemplate;

    public ChatKafkaPublishService(KafkaTemplate<String, ChatMessageDto> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(ChatMessageDto message) {
        kafkaTemplate.send(
                "chat-message",
                String.valueOf(message.getRoomId()), // ✅ Long → String
                message
        );
    }
}


