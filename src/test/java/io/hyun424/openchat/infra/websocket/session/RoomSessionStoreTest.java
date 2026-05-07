package io.hyun424.openchat.infra.websocket.session;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomSessionStoreTest {

    @Test
    void removeOriginalSessionRemovesDecoratedStoredSession() {
        RoomSessionStore store = new RoomSessionStore();
        WebSocketSession original = mockSession("session-1", true);

        WebSocketSession stored = store.add(1L, original);
        RoomSessionStore.RemoveResult result = store.remove(1L, original);

        assertNotSame(original, stored);
        assertTrue(result.removed());
        assertEquals(0, result.remainingRoomCount());
        assertEquals(0, store.count(1L));
    }

    @Test
    void closeAllSessionsInRoomRemovesRoomAndSessionLookup() {
        RoomSessionStore store = new RoomSessionStore();
        WebSocketSession session = mockSession("session-1", true);
        store.add(1L, session);

        RoomSessionStore.CloseAllResult result = store.closeAllSessionsInRoom(1L, CloseStatus.NORMAL);

        assertEquals(1, result.sessionIds().size());
        assertEquals(1, result.closedCount());
        assertEquals(0, store.count(1L));
        assertEquals(0, store.totalSessionCount());
    }

    private WebSocketSession mockSession(String sessionId, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/chat?roomId=1"));
        when(session.getAttributes()).thenReturn(Map.of());
        when(session.isOpen()).thenReturn(open);
        return session;
    }
}
