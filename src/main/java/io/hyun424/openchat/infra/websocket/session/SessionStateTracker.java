package io.hyun424.openchat.infra.websocket.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class SessionStateTracker {

    private final ConcurrentMap<String, RoomSessionState> sessionStatesById = new ConcurrentHashMap<>();
    private final long activeTtlMillis;

    public SessionStateTracker(long activeTtlMillis) {
        this.activeTtlMillis = Math.max(1, activeTtlMillis);
    }

    public void register(Long roomId, Integer partitionId, String sessionId) {
        sessionStatesById.put(sessionId, RoomSessionState.active(roomId, partitionId, nowMillis()));
    }

    public void remove(String sessionId) {
        sessionStatesById.remove(sessionId);
    }

    public void removeAll(Collection<String> sessionIds) {
        for (String sessionId : sessionIds) {
            remove(sessionId);
        }
    }

    public void markActive(Long roomId, String sessionId, Long lastSeenSequence) {
        updateSessionState(roomId, sessionId, lastSeenSequence, true);
    }

    public void markPassive(Long roomId, String sessionId, Long lastSeenSequence) {
        updateSessionState(roomId, sessionId, lastSeenSequence, false);
    }

    public List<String> openSessionIds(Long roomId, Integer partitionId, Set<WebSocketSession> sessions) {
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<String> sessionIds = new ArrayList<>(sessions.size());
        for (WebSocketSession session : sessions) {
            if (!matchesPartition(session, partitionId) || !session.isOpen()) {
                continue;
            }
            sessionIds.add(session.getId());
        }
        return sessionIds;
    }

    public int openSessionCountForPartition(Integer partitionId, Collection<WebSocketSession> sessions) {
        if (partitionId == null || sessions.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (WebSocketSession session : sessions) {
            if (session.isOpen() && matchesPartition(session, partitionId)) {
                count++;
            }
        }
        return count;
    }

    public List<OpenSessionInfo> openSessions(Collection<WebSocketSession> sessions) {
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<OpenSessionInfo> openSessions = new ArrayList<>(sessions.size());
        for (WebSocketSession session : sessions) {
            RoomSessionState state = sessionStatesById.get(session.getId());
            if (session.isOpen() && state != null) {
                openSessions.add(new OpenSessionInfo(session.getId(), state.roomId(), state.partitionId()));
            }
        }
        return openSessions;
    }

    public boolean matchesPartition(WebSocketSession session, Integer partitionId) {
        if (partitionId == null) {
            return true;
        }
        RoomSessionState state = sessionStatesById.get(session.getId());
        return state != null && state.matchesPartition(partitionId);
    }

    public boolean isActiveSession(WebSocketSession session, long now) {
        RoomSessionState state = sessionStatesById.get(session.getId());
        if (state == null) {
            return true;
        }
        return state.isActive(now, activeTtlMillis);
    }

    public RoomSessionWorkloadSnapshot workloadSnapshot(Collection<WebSocketSession> sessions, int broadcastQueueDepth) {
        if (sessions.isEmpty()) {
            return RoomSessionWorkloadSnapshot.empty(broadcastQueueDepth);
        }
        long now = nowMillis();
        int total = 0;
        int active = 0;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            total++;
            if (isActiveSession(session, now)) {
                active++;
            }
        }
        return new RoomSessionWorkloadSnapshot(total, active, Math.max(0, total - active), broadcastQueueDepth);
    }

    private void updateSessionState(Long roomId, String sessionId, Long lastSeenSequence, boolean active) {
        if (sessionId == null) {
            return;
        }
        long now = nowMillis();
        sessionStatesById.compute(sessionId, (ignored, existing) -> {
            RoomSessionState state = existing == null ? RoomSessionState.active(roomId, null, now) : existing;
            if (!state.roomId().equals(roomId)) {
                log.warn("[WS SESSION STATE ROOM MISMATCH] sessionId={} stateRoomId={} requestedRoomId={}",
                        sessionId, state.roomId(), roomId);
                return state;
            }
            state.mark(active, lastSeenSequence, now);
            return state;
        });
    }

    private long nowMillis() {
        return System.currentTimeMillis();
    }

    public record OpenSessionInfo(
            String sessionId,
            Long roomId,
            Integer partitionId
    ) {
    }
}
