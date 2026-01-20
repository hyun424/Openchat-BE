package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.ingest.ChatIngestService;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import io.hyun424.openchat.chat.message.entity.Message;


@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final RoomMemberService roomMemberService;
    private final RoomSessionRegistry roomSessionRegistry;
    private final ChatIngestService chatIngestService;
    private final MessageService messageService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long roomId = extractRoomId(session);

        String userId = (String) session.getAttributes().get("userId");
        String nickname = (String) session.getAttributes().get("nickname");

        if (userId == null || nickname == null || nickname.isBlank()) {
            throw new IllegalStateException("Invalid WebSocket authentication");
        }

        roomMemberService.getJoinedAtOrThrow(roomId, userId);
        roomSessionRegistry.add(roomId, session);

        log.info("[WS CONNECT] roomId={} userId={} session={}",
                roomId, userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {

        Long roomId = extractRoomId(session);
        String senderId = (String) session.getAttributes().get("userId");
        String nickname = (String) session.getAttributes().get("nickname");

        try {
            JsonNode node = objectMapper.readTree(textMessage.getPayload());

            String content = node.get("content").asText();
            String clientMessageId = node.has("clientMessageId")
                    ? node.get("clientMessageId").asText()
                    : null;

            // 🔥 DB 저장 / fan-out 전부 Ingest에게 위임
            chatIngestService.ingest(
                    roomId,
                    senderId,
                    nickname,
                    content,
                    clientMessageId
            );

        } catch (Exception e) {
            log.error("[WS_MSG_ERROR]", e);
        }
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long roomId = extractRoomId(session);
        roomSessionRegistry.remove(roomId, session);

        log.info("[WS DISCONNECT] roomId={} session={}", roomId, session.getId());
    }

    private Long extractRoomId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) throw new IllegalStateException("Missing query");

        for (String kv : query.split("&")) {
            String[] parts = kv.split("=");
            if (parts.length == 2 && parts[0].equals("roomId")) {
                return Long.parseLong(parts[1]);
            }
        }
        throw new IllegalStateException("Missing roomId");
    }
}
