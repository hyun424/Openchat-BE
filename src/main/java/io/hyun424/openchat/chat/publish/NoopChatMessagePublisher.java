package io.hyun424.openchat.chat.publish;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!redis & !kafka")
public class NoopChatMessagePublisher implements ChatMessagePublisher {

    @Override
    public void publish(ChatMessageDto message) {
        // intentionally noop
    }
}
