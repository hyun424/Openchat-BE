package io.hyun424.openchat.infra.websocket.session;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionStateTrackerTest {

    @Test
    void openSessionIdsFiltersByPartitionAndOpenState() {
        SessionStateTracker tracker = new SessionStateTracker(60_000);
        WebSocketSession partition0 = mockSession("partition-0", true);
        WebSocketSession partition1 = mockSession("partition-1", true);
        WebSocketSession closedPartition1 = mockSession("closed-partition-1", false);
        tracker.register(1L, 0, "partition-0");
        tracker.register(1L, 1, "partition-1");
        tracker.register(1L, 1, "closed-partition-1");

        assertEquals(
                Set.of("partition-1"),
                Set.copyOf(tracker.openSessionIds(1L, 1, Set.of(partition0, partition1, closedPartition1)))
        );
    }

    @Test
    void activeTtlAndPassiveDeclarationControlActiveState() throws Exception {
        SessionStateTracker tracker = new SessionStateTracker(20);
        WebSocketSession session = mockSession("session-1", true);
        tracker.register(1L, 0, "session-1");

        assertTrue(tracker.isActiveSession(session, System.currentTimeMillis()));
        tracker.markPassive(1L, "session-1", 10L);
        assertFalse(tracker.isActiveSession(session, System.currentTimeMillis()));
        tracker.markActive(1L, "session-1", 11L);
        assertTrue(tracker.isActiveSession(session, System.currentTimeMillis()));
        Thread.sleep(30);
        assertFalse(tracker.isActiveSession(session, System.currentTimeMillis()));
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
