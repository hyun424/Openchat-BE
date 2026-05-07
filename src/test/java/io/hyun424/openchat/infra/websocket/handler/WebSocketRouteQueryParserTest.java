package io.hyun424.openchat.infra.websocket.handler;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketRouteQueryParserTest {

    private final WebSocketRouteQueryParser parser = new WebSocketRouteQueryParser();

    @Test
    void roomId_readsRoomIdFromQuery() {
        WebSocketSession session = session("ws://localhost/ws/chat?roomId=12&partitionId=3");

        assertEquals(12L, parser.roomId(session));
    }

    @Test
    void roomId_throwsWhenMissing() {
        WebSocketSession session = session("ws://localhost/ws/chat?partitionId=3");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> parser.roomId(session));
        assertEquals("Missing roomId", exception.getMessage());
    }

    @Test
    void partitionId_readsPartitionIdWhenPresent() {
        WebSocketSession session = session("ws://localhost/ws/chat?roomId=12&partitionId=3");

        assertEquals(3, parser.partitionId(session));
    }

    @Test
    void partitionId_returnsNullWhenAbsent() {
        WebSocketSession session = session("ws://localhost/ws/chat?roomId=12");

        assertNull(parser.partitionId(session));
    }

    @Test
    void partitionId_returnsZeroWhenInvalid() {
        WebSocketSession session = session("ws://localhost/ws/chat?roomId=12&partitionId=abc");

        assertEquals(0, parser.partitionId(session));
    }

    private WebSocketSession session(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create(uri));
        return session;
    }
}
