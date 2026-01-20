package io.hyun424.openchat.infra.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoomSessionRegistry {

    private final ObjectMapper objectMapper;

    private final ConcurrentMap<Long, Set<WebSocketSession>> roomSessions =
            new ConcurrentHashMap<>();

    public void add(Long roomId, WebSocketSession session) {
        roomSessions
                .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    public void remove(Long roomId, WebSocketSession session) {
        Set<WebSocketSession> set = roomSessions.get(roomId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
    }

    public Set<WebSocketSession> getSessions(Long roomId) {
        return roomSessions.getOrDefault(roomId, Collections.emptySet());
    }

    public int count(Long roomId) {
        return getSessions(roomId).size();
    }

    /**
     * Broadcast message to all sessions in a room.
     * Dead sessions are collected and removed AFTER iteration to avoid
     * ConcurrentModification issues that could skip live sessions.
     */
    public void sendToRoom(Long roomId, ChatMessageDto message) {
        Set<WebSocketSession> sessions = getSessions(roomId);

        if (sessions.isEmpty()) {
            log.debug("[WS BROADCAST] roomId={} - no sessions", roomId);
            return;
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("[WS SERIALIZE FAIL] roomId={} messageId={}",
                    roomId, message.getMessageId(), e);
            return;
        }

        // Collect dead sessions separately to avoid modifying set during iteration
        Set<WebSocketSession> deadSessions = ConcurrentHashMap.newKeySet();
        int successCount = 0;

        for (WebSocketSession session : sessions) {
            try {
                if (!session.isOpen()) {
                    deadSessions.add(session);
                    continue;
                }
                session.sendMessage(new TextMessage(payload));
                successCount++;
            } catch (Exception e) {
                log.warn("[WS SEND FAIL] roomId={} sessionId={}", roomId, session.getId(), e);
                deadSessions.add(session);
            }
        }

        // Clean up dead sessions after iteration
        for (WebSocketSession dead : deadSessions) {
            remove(roomId, dead);
        }

        log.debug("[WS BROADCAST] roomId={} messageId={} sent={} dead={}",
                roomId, message.getMessageId(), successCount, deadSessions.size());
    }
}
