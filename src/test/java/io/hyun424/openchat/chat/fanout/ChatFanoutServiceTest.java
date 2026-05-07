package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.hot.RoomHotState;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void fanout_sameMessageId_sendsOnce() throws Exception {
        ChatMessageDto message = message("message-1");

        fanoutService.fanout(message);
        fanoutService.fanout(message);
        assertTrue(fanoutService.awaitBatchIdle(500));

        verify(outboundSender, times(1)).send(message);
    }

    @Test
    @DisplayName("partition fanout은 같은 messageId라도 partition별로 한 번씩 전송한다")
    void fanout_sameMessageIdDifferentPartitions_sendsOncePerPartition() throws Exception {
        ChatMessageDto message = message("message-partition");

        fanoutService.fanout(message, 0);
        fanoutService.fanout(message, 1);
        fanoutService.fanout(message, 1);
        assertTrue(fanoutService.awaitBatchIdle(500));

        verify(outboundSender).send(message, 0);
        verify(outboundSender).send(message, 1);
        verify(outboundSender, times(2)).send(eq(message), anyInt());
    }

    @Test
    @DisplayName("서로 다른 인스턴스는 같은 messageId라도 각자 local 세션에 전송한다")
    void fanout_sameMessageIdDifferentInstances_eachSendsLocally() throws Exception {
        ChatOutboundSender anotherOutboundSender = mock(ChatOutboundSender.class);
        ChatFanoutService anotherFanoutService = new ChatFanoutService(anotherOutboundSender);
        ChatMessageDto message = message("message-2");

        fanoutService.fanout(message);
        anotherFanoutService.fanout(message);
        assertTrue(fanoutService.awaitBatchIdle(500));
        assertTrue(anotherFanoutService.awaitBatchIdle(500));

        verify(outboundSender).send(message);
        verify(anotherOutboundSender).send(message);
        anotherFanoutService.shutdown();
    }

    @Test
    @DisplayName("같은 방 메시지가 batch window 안에 여러 개 모이면 batch envelope 전송 경로를 사용한다")
    void fanout_sameRoomMessagesInWindow_sendsBatch() throws Exception {
        ChatMessageDto first = message("message-3", 10L);
        ChatMessageDto second = message("message-4", 11L);

        fanoutService.fanout(first);
        fanoutService.fanout(second);
        assertTrue(fanoutService.awaitBatchIdle(500));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundSender).sendBatch(eq(1L), captor.capture(), eq(true), eq(0), eq(11L));
        verify(outboundSender, never()).send(any(ChatMessageDto.class));
        assertEquals(List.of(first, second), captor.getValue());
    }

    @Test
    @DisplayName("batch window 동안 메시지가 하나뿐이면 기존 단건 전송 형식을 유지한다")
    void fanout_singleMessage_sendsSinglePayload() throws Exception {
        ChatMessageDto message = message("message-5", 12L);

        fanoutService.fanout(message);
        assertTrue(fanoutService.awaitBatchIdle(500));

        verify(outboundSender).send(message);
        verify(outboundSender, never()).sendBatch(anyLong(), anyList());
    }

    @Test
    @DisplayName("batch가 꺼져 있으면 fanout 호출 시 바로 단건 전송한다")
    void fanout_batchDisabled_sendsImmediately() {
        ChatFanoutService disabledBatchFanout = new ChatFanoutService(outboundSender, false, 20, 64);
        ChatMessageDto message = message("message-6", 13L);

        disabledBatchFanout.fanout(message);

        verify(outboundSender).send(message);
        disabledBatchFanout.shutdown();
    }

    @Test
    @DisplayName("hot room은 상태별 batch window를 사용해 더 오래 모은 뒤 batch로 전송한다")
    void fanout_hotRoom_usesAdaptiveBatchWindow() throws Exception {
        RoomTrafficMonitor roomTrafficMonitor = mock(RoomTrafficMonitor.class);
        when(roomTrafficMonitor.state(1L)).thenReturn(RoomHotState.HOT);
        ChatFanoutService adaptiveFanout = new ChatFanoutService(
                outboundSender,
                true,
                20,
                30,
                120,
                150,
                64,
                16,
                ChatPipelineMetrics.noop(),
                roomTrafficMonitor
        );
        ChatMessageDto first = message("message-7", 14L);
        ChatMessageDto second = message("message-8", 15L);

        adaptiveFanout.fanout(first);
        Thread.sleep(40);
        adaptiveFanout.fanout(second);
        assertTrue(adaptiveFanout.awaitBatchIdle(500));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundSender).sendBatch(eq(1L), captor.capture(), eq(true), eq(0), eq(15L));
        assertEquals(List.of(first, second), captor.getValue());
        adaptiveFanout.shutdown();
    }

    @Test
    @DisplayName("hot room은 live cap을 넘긴 메시지를 batch envelope에서 생략 표시한다")
    void fanout_hotRoom_appliesControlledRealtimeCap() throws Exception {
        RoomTrafficMonitor roomTrafficMonitor = mock(RoomTrafficMonitor.class);
        when(roomTrafficMonitor.state(1L)).thenReturn(RoomHotState.HOT);
        ChatFanoutService controlledFanout = new ChatFanoutService(
                outboundSender,
                true,
                1,
                1,
                1,
                1,
                64,
                16,
                true,
                1,
                1,
                ChatPipelineMetrics.noop(),
                roomTrafficMonitor
        );
        ChatMessageDto first = message("message-9", 21L);
        ChatMessageDto second = message("message-10", 22L);

        controlledFanout.fanout(first);
        controlledFanout.fanout(second);
        assertTrue(controlledFanout.awaitBatchIdle(500));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundSender).sendBatch(eq(1L), captor.capture(), eq(false), eq(1), eq(22L));
        assertEquals(List.of(first), captor.getValue());
        controlledFanout.shutdown();
    }

    private ChatMessageDto message(String messageId) {
        return message(messageId, 1L);
    }

    private ChatMessageDto message(String messageId, Long id) {
        return ChatMessageDto.builder()
                .id(id)
                .sequence(id)
                .messageId(messageId)
                .roomId(1L)
                .senderId("user1")
                .senderName("tester")
                .message("hello")
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
