package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FanoutBatchBufferTest {

    @Test
    void addAndDrain_tracksSizeAndOrder() {
        FanoutBatchBuffer buffer = new FanoutBatchBuffer();
        ChatMessageDto first = message("m1", 1L);
        ChatMessageDto second = message("m2", 2L);

        buffer.add(first);
        buffer.add(second);

        assertEquals(2, buffer.size());
        assertEquals(List.of(first), buffer.drain(1));
        assertEquals(1, buffer.size());
        assertEquals(List.of(second), buffer.drain(10));
        assertEquals(0, buffer.size());
    }

    @Test
    void scheduledFlag_allowsSingleOwnerUntilCleared() {
        FanoutBatchBuffer buffer = new FanoutBatchBuffer();

        assertTrue(buffer.markScheduled());
        assertTrue(buffer.isScheduled());
        assertFalse(buffer.markScheduled());

        buffer.clearScheduled();
        assertFalse(buffer.isScheduled());
        assertTrue(buffer.markScheduled());
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
