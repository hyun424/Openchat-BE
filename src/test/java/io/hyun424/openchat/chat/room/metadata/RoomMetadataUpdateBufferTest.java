package io.hyun424.openchat.chat.room.metadata;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.hotchat.HotChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomMetadataUpdateBufferTest {

    @Mock private RoomService roomService;
    @Mock private HotChatService hotChatService;
    @Mock private ChatPipelineMetrics chatPipelineMetrics;

    @Test
    @DisplayName("같은 room의 여러 메시지는 최신 timestamp 1건만 flush한다")
    void flush_latestMessagePerRoomOnly() {
        RoomMetadataUpdateBuffer buffer = new RoomMetadataUpdateBuffer(
                roomService,
                hotChatService,
                chatPipelineMetrics
        );
        buffer.enqueue(message("message-old", 1L, 1000L, "old"));
        buffer.enqueue(message("message-new", 1L, 2000L, "new"));
        buffer.enqueue(message("message-stale", 1L, 1500L, "stale"));

        buffer.flush();

        verify(roomService).updateLastMessage(1L, 2000L, "new", "User");
        verify(hotChatService).recordMessageActivity(1L, "message-new");
        verify(roomService, never()).updateLastMessage(eq(1L), eq(1000L), anyString(), anyString());
        verify(roomService, never()).updateLastMessage(eq(1L), eq(1500L), anyString(), anyString());
    }

    @Test
    @DisplayName("한 room flush 실패가 다음 flush에서 재시도된다")
    void flush_failureRequeues() {
        RoomMetadataUpdateBuffer buffer = new RoomMetadataUpdateBuffer(
                roomService,
                hotChatService,
                chatPipelineMetrics
        );
        doThrow(new RuntimeException("db down"))
                .doNothing()
                .when(roomService).updateLastMessage(1L, 1000L, "hello", "User");

        buffer.enqueue(message("message-1", 1L, 1000L, "hello"));
        buffer.flush();
        buffer.flush();

        verify(roomService, times(2)).updateLastMessage(1L, 1000L, "hello", "User");
        verify(hotChatService).recordMessageActivity(1L, "message-1");
    }

    private ChatMessageDto message(String messageId, Long roomId, Long createdAt, String content) {
        return ChatMessageDto.builder()
                .id(createdAt)
                .sequence(createdAt)
                .messageId(messageId)
                .roomId(roomId)
                .senderId("user-1")
                .senderName("User")
                .message(content)
                .createdAt(createdAt)
                .build();
    }
}
