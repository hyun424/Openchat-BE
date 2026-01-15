package io.hyun424.openchat.chat.consume;

import io.hyun424.openchat.chat.fanout.ChatFanoutService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Profile("kafka")
@RequiredArgsConstructor
public class ChatKafkaConsumer {

    private final ChatFanoutService fanoutService;

    @KafkaListener(
            topics = "chat-message",
            groupId = "chat-fanout-group"
    )
    public void consume(ChatMessageDto message) {

        log.info(
                "[KAFKA CONSUME] roomId={} messageId={}",
                message.getRoomId(),
                message.getMessageId()
        );

        fanoutService.fanout(message);
    }
}
