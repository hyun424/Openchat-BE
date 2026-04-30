package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class ChatFanoutServiceTest {

    private final ChatOutboundSender outboundSender = mock(ChatOutboundSender.class);
    private final ChatFanoutService fanoutService =
            new ChatFanoutService(outboundSender);

    @AfterEach
    void tearDown() {
        fanoutService.shutdown();
    }

    @Test
    @DisplayName("같은 messageId가 두 번 들어오면 현재 인스턴스에서는 한 번만 전송한다")
    void fanout_sameMessageId_sendsOnce() {
        ChatMessageDto message = message("message-1");

        fanoutService.fanout(message);
        fanoutService.fanout(message);

        verify(outboundSender, times(1)).send(message);
    }

    @Test
    @DisplayName("서로 다른 인스턴스는 같은 messageId라도 각자 local 세션에 전송한다")
    void fanout_sameMessageIdDifferentInstances_eachSendsLocally() {
        ChatOutboundSender anotherOutboundSender = mock(ChatOutboundSender.class);
        ChatFanoutService anotherFanoutService = new ChatFanoutService(anotherOutboundSender);
        ChatMessageDto message = message("message-2");

        fanoutService.fanout(message);
        anotherFanoutService.fanout(message);

        verify(outboundSender).send(message);
        verify(anotherOutboundSender).send(message);
        anotherFanoutService.shutdown();
    }

    private ChatMessageDto message(String messageId) {
        return ChatMessageDto.builder()
                .messageId(messageId)
                .roomId(1L)
                .senderId("user1")
                .senderName("tester")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
