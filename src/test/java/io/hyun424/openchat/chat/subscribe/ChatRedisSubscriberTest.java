package io.hyun424.openchat.chat.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.fanout.RoomFanoutDispatcher;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.metrics.ChatPipelineMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatRedisSubscriberTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RoomFanoutDispatcher dispatcher = mock(RoomFanoutDispatcher.class);
    private final ChatPipelineMetrics chatPipelineMetrics = mock(ChatPipelineMetrics.class);
    private final ChatRedisSubscriber subscriber =
            new ChatRedisSubscriber(objectMapper, dispatcher, chatPipelineMetrics);

    @Test
    @DisplayName("Redis 메시지를 deserialize한 뒤 fanout 직접 실행 대신 dispatcher에 enqueue한다")
    void onMessage_enqueuesToDispatcher() throws Exception {
        ChatMessageDto message = ChatMessageDto.builder()
                .messageId("message-1")
                .roomId(1L)
                .senderId("user1")
                .senderName("tester")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();

        subscriber.onMessage(objectMapper.writeValueAsString(message), "chat:room:1");

        ArgumentCaptor<ChatMessageDto> captor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(dispatcher).enqueue(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo("message-1");
        verify(chatPipelineMetrics).recordStageNanos(eq("subscribe.fanout_call"), anyLong());
    }
}
