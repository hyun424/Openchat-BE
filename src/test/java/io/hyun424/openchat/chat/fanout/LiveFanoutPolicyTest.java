package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.room.hot.RoomHotState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LiveFanoutPolicyTest {

    @Test
    void apply_whenDisabled_returnsAllMessagesComplete() {
        LiveFanoutPolicy policy = new LiveFanoutPolicy(false, 1, 1);
        List<ChatMessageDto> messages = List.of(message("m1", 1L), message("m2", 2L));

        LiveBatch batch = policy.apply(1L, messages, RoomHotState.HOT);

        assertEquals(messages, batch.messages());
        assertTrue(batch.realtimeComplete());
        assertEquals(0, batch.omittedCount());
        assertEquals(2L, batch.lastSequence());
        assertFalse(batch.controlled());
    }

    @Test
    void apply_forNormalState_returnsAllMessagesComplete() {
        LiveFanoutPolicy policy = new LiveFanoutPolicy(true, 1, 1);
        List<ChatMessageDto> messages = List.of(message("m1", 1L), message("m2", 2L));

        LiveBatch batch = policy.apply(1L, messages, RoomHotState.NORMAL);

        assertEquals(messages, batch.messages());
        assertTrue(batch.realtimeComplete());
        assertFalse(batch.controlled());
    }

    @Test
    void apply_forHotState_capsVisibleMessages() {
        AtomicLong now = new AtomicLong(1_000);
        LiveFanoutPolicy policy = new LiveFanoutPolicy(true, 1, 1, now::get);
        ChatMessageDto first = message("m1", 1L);
        ChatMessageDto second = message("m2", 2L);

        LiveBatch batch = policy.apply(1L, List.of(first, second), RoomHotState.HOT);

        assertEquals(List.of(first), batch.messages());
        assertFalse(batch.realtimeComplete());
        assertEquals(1, batch.omittedCount());
        assertEquals(2L, batch.lastSequence());
        assertTrue(batch.controlled());
    }

    @Test
    void apply_carriesOmittedCountUntilNextVisibleMessage() {
        AtomicLong now = new AtomicLong(1_000);
        LiveFanoutPolicy policy = new LiveFanoutPolicy(true, 1, 1, now::get);

        LiveBatch first = policy.apply(1L, List.of(message("m1", 1L), message("m2", 2L)), RoomHotState.HOT);
        LiveBatch second = policy.apply(1L, List.of(message("m3", 3L)), RoomHotState.HOT);
        now.set(2_000);
        ChatMessageDto fourth = message("m4", 4L);
        LiveBatch third = policy.apply(1L, List.of(fourth), RoomHotState.HOT);

        assertEquals(1, first.omittedCount());
        assertTrue(second.messages().isEmpty());
        assertEquals(1, second.omittedCount());
        assertEquals(List.of(fourth), third.messages());
        assertEquals(1, third.omittedCount());
        assertFalse(third.realtimeComplete());
    }

    private ChatMessageDto message(String messageId, Long id) {
        return ChatMessageDto.builder()
                .id(id)
                .sequence(id)
                .messageId(messageId)
                .roomId(1L)
                .message("hello")
                .build();
    }
}
