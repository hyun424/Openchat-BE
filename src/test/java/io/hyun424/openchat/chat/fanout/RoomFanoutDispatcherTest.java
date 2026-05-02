package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.metrics.ChatPipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RoomFanoutDispatcherTest {

    private final ChatFanoutService fanoutService = mock(ChatFanoutService.class);
    private final ChatPipelineMetrics chatPipelineMetrics =
            new ChatPipelineMetrics(new SimpleMeterRegistry());
    private RoomFanoutDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    @Test
    @DisplayName("같은 room 메시지는 enqueue 순서대로 같은 worker에서 처리한다")
    void enqueue_sameRoom_processesInOrder() {
        dispatcher = new RoomFanoutDispatcher(fanoutService, chatPipelineMetrics, 4, 100);
        ChatMessageDto first = message("message-1", 1L);
        ChatMessageDto second = message("message-2", 1L);
        ChatMessageDto third = message("message-3", 1L);

        dispatcher.enqueue(first);
        dispatcher.enqueue(second);
        dispatcher.enqueue(third);

        ArgumentCaptor<ChatMessageDto> captor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(fanoutService, timeout(1_000).times(3)).fanout(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ChatMessageDto::getMessageId)
                .containsExactly("message-1", "message-2", "message-3");
    }

    @Test
    @DisplayName("같은 room은 같은 stripe로 가고 서로 다른 room은 roomId hash로 분산된다")
    void stripeIndexFor_mapsByRoomId() {
        dispatcher = new RoomFanoutDispatcher(fanoutService, chatPipelineMetrics, 8, 100);

        assertThat(dispatcher.stripeIndexFor(1L)).isEqualTo(dispatcher.stripeIndexFor(1L));
        assertThat(dispatcher.stripeIndexFor(1L)).isNotEqualTo(dispatcher.stripeIndexFor(2L));
    }

    @Test
    @DisplayName("shutdown 이후 enqueue는 메시지를 버리지 않고 caller thread에서 fanout한다")
    void enqueue_afterShutdown_fallsBackToCallerFanout() {
        dispatcher = new RoomFanoutDispatcher(fanoutService, chatPipelineMetrics, 2, 100);
        dispatcher.shutdown();
        ChatMessageDto message = message("message-1", 1L);

        dispatcher.enqueue(message);

        verify(fanoutService).fanout(message);
    }

    @Test
    @DisplayName("다른 room 메시지는 각 roomId에 대응하는 stripe로 계산된다")
    void stripeIndexFor_usesRoomIdHash() {
        dispatcher = new RoomFanoutDispatcher(fanoutService, chatPipelineMetrics, 8, 100);

        List<Integer> stripes = List.of(
                dispatcher.stripeIndexFor(1L),
                dispatcher.stripeIndexFor(2L),
                dispatcher.stripeIndexFor(3L),
                dispatcher.stripeIndexFor(4L));

        assertThat(stripes).containsExactly(1, 2, 3, 4);
    }

    private ChatMessageDto message(String messageId, Long roomId) {
        return ChatMessageDto.builder()
                .messageId(messageId)
                .roomId(roomId)
                .senderId("user1")
                .senderName("tester")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
