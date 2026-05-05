package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWebSocketMessageParserTest {

    private final ChatWebSocketMessageParser parser = new ChatWebSocketMessageParser(new ObjectMapper());

    @Test
    void parse_legacyPayload_defaultsToChatMessage() throws Exception {
        ChatWebSocketMessage message = parser.parse(new TextMessage(
                "{\"content\":\"hello\",\"clientMessageId\":\"c1\",\"clientSentAt\":1000}"
        ));

        assertEquals(ChatWebSocketMessage.TYPE_CHAT_MESSAGE, message.type());
        assertTrue(message.isChatMessage());
        assertEquals("hello", message.content());
        assertEquals("c1", message.clientMessageId());
        assertEquals(1000L, message.clientSentAt());
        assertNull(message.roomId());
    }

    @Test
    void parse_roomControlPayload_readsRoomAndSequence() throws Exception {
        ChatWebSocketMessage message = parser.parse(new TextMessage(
                "{\"type\":\"room.active.heartbeat\",\"roomId\":1,\"lastSeenSequence\":123}"
        ));

        assertEquals(ChatWebSocketMessage.TYPE_ROOM_ACTIVE_HEARTBEAT, message.type());
        assertTrue(message.isRoomControlMessage());
        assertTrue(message.marksActive());
        assertEquals(1L, message.roomId());
        assertEquals(123L, message.lastSeenSequence());
        assertNull(message.content());
    }
}
