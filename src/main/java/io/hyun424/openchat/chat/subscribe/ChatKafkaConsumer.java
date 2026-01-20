package io.hyun424.openchat.chat.subscribe;

import io.hyun424.openchat.chat.fanout.ChatFanoutService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer for fan-out.
 * Only active when chat.fanout.mode=kafka (Kafka-only fan-out mode).
 *
 * In default mode (Redis fan-out), Kafka is used only for durability,
 * NOT for triggering fan-out (Redis subscriber handles that).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chat.fanout.mode", havingValue = "kafka")
public class ChatKafkaConsumer {

    private final ChatFanoutService fanoutService;

    @KafkaListener(topics = "chat-message", groupId = "chat-fanout-group")
    public void consume(ChatMessageDto message, Acknowledgment ack) {
        log.info("[KAFKA CONSUME] roomId={} messageId={}",
                message.getRoomId(), message.getMessageId());

        fanoutService.fanout(message);

        ack.acknowledge();
    }
}

