package io.hyun424.openchat.chat.room.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RoomLastMessageUpdateBufferTest {

    private final RoomService roomService = mock(RoomService.class);
    private final RoomLastMessageUpdateBuffer buffer = new RoomLastMessageUpdateBuffer(roomService);

    @Test
    @DisplayName("같은 방의 여러 pending 메시지 중 최신 timestamp만 flush한다")
    void flush_usesLatestMessagePerRoom() {
        buffer.enqueue(1L, 100L, "old", "sender-a");
        buffer.enqueue(1L, 200L, "new", "sender-b");

        buffer.flushPendingLastMessages();

        verify(roomService).updateLastMessage(1L, 200L, "new", "sender-b");
        verify(roomService, never()).updateLastMessage(1L, 100L, "old", "sender-a");
    }

    @Test
    @DisplayName("오래된 timestamp는 최신 pending 메시지를 덮지 않는다")
    void enqueue_olderMessageDoesNotReplaceLatest() {
        buffer.enqueue(1L, 200L, "new", "sender-b");
        buffer.enqueue(1L, 100L, "old", "sender-a");

        buffer.flushPendingLastMessages();

        verify(roomService).updateLastMessage(1L, 200L, "new", "sender-b");
        verify(roomService, never()).updateLastMessage(1L, 100L, "old", "sender-a");
    }

    @Test
    @DisplayName("한 방의 flush 실패가 다른 방 flush를 막지 않는다")
    void flush_failureDoesNotBlockOtherRooms() {
        doThrow(new RuntimeException("boom"))
                .doNothing()
                .when(roomService)
                .updateLastMessage(1L, 100L, "room-1", "sender-a");

        buffer.enqueue(1L, 100L, "room-1", "sender-a");
        buffer.enqueue(2L, 200L, "room-2", "sender-b");

        buffer.flushPendingLastMessages();
        buffer.flushPendingLastMessages();

        verify(roomService).updateLastMessage(2L, 200L, "room-2", "sender-b");
        verify(roomService, org.mockito.Mockito.times(2))
                .updateLastMessage(1L, 100L, "room-1", "sender-a");
    }
}
