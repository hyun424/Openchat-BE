package io.hyun424.openchat.infra.websocket.session;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionStateTrackerWorkloadSnapshotTest {

    @Test
    void workloadSnapshotCountsOpenActiveAndPassiveSessions() {
        SessionStateTracker tracker = new SessionStateTracker(60_000);
        WebSocketSession active = session("active", true);
        WebSocketSession passive = session("passive", true);
        WebSocketSession closed = session("closed", false);
        tracker.register(1L, 0, active.getId());
        tracker.register(1L, 0, passive.getId());
        tracker.register(1L, 0, closed.getId());
        tracker.markPassive(1L, passive.getId(), 10L);

        RoomSessionWorkloadSnapshot snapshot = tracker.workloadSnapshot(List.of(active, passive, closed), 3);

        assertEquals(2, snapshot.totalSessions());
        assertEquals(1, snapshot.activeSessions());
        assertEquals(1, snapshot.passiveSessions());
        assertEquals(3, snapshot.broadcastQueueDepth());
    }

    private WebSocketSession session(String id, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        return session;
    }
}
