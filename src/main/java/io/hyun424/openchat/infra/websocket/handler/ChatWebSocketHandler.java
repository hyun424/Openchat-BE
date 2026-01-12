package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.ingest.ChatIngestService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.service.MessageService;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final MessageService messageService;
    private final RoomMemberService roomMemberService;

    /**
     * roomId -> sessions
     * TODO: (확장) 서버 다중 인스턴스/오토스케일링 시 Redis Pub/Sub로 대체
     */
    private final RoomSessionRegistry roomSessionRegistry;
    private final ChatIngestService chatIngestService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long roomId = extractRoomId(session);

        String userId = (String) session.getAttributes().get("userId");
        String nickname = (String) session.getAttributes().get("nickname");

        if (userId == null) {
            throw new IllegalStateException("WebSocket user not authenticated (missing userId)");
        }
        if (nickname == null || nickname.isBlank()) {
            // ✅ 최선: nickname은 서버(JWT)에서 확정되어야 한다. 없으면 연결 자체를 막는다.
            throw new IllegalStateException("WebSocket user not initialized (missing nickname)");
        }

        // 방 입장 검증 (이미 네가 해두었던 흐름 유지)
        roomMemberService.getJoinedAtOrThrow(roomId, userId);

        roomSessionRegistry.add(roomId, session);


        log.info("[ChatWebSocket] CONNECT roomId={}, userId={}, nickname={}, sessionId={}",
                roomId, userId, nickname, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long roomId = extractRoomId(session);

        String senderId = (String) session.getAttributes().get("userId");
        String nickname = (String) session.getAttributes().get("nickname");

        if (senderId == null || nickname == null || nickname.isBlank()) {
            log.warn("WS_MSG_REJECT invalid session={}", session.getId());
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            JsonNode contentNode = node.get("content");

            if (contentNode == null || contentNode.asText().isBlank()) {
                return;
            }

            String content = contentNode.asText();

            // ✅ 1) DB 저장 (여기서 saved가 생김)
            Message saved = messageService.save(roomId, senderId, nickname, content);

            // ✅ 2) DTO 생성
            ChatMessageDto dto = ChatMessageDto.from(saved);

            // ✅ 3) Redis로만 보냄 (fan-out은 subscriber가 담당)
            chatIngestService.ingest(dto);

            log.debug("WS_MSG_INGESTED roomId={}, senderId={}, messageId={}",
                    roomId, senderId, dto.getMessageId());

        } catch (Exception e) {
            log.error("WS_MSG_ERROR session={}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long roomId = extractRoomId(session);

        roomSessionRegistry.remove(roomId, session);

        log.info("[ChatWebSocket] DISCONNECT roomId={}, sessionId={}, status={}",
                roomId, session.getId(), status);
    }

    private Long extractRoomId(WebSocketSession session) {
        // 네 프로젝트가 이미 query param roomId를 쓰고 있으니 그대로 간다.
        // ws://.../ws/chat?token=JWT&roomId=xxx
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) throw new IllegalStateException("Missing query string");

        // 아주 단순 파싱 (MVP). TODO: UriComponentsBuilder로 교체 가능
        for (String kv : query.split("&")) {
            String[] parts = kv.split("=");
            if (parts.length == 2 && parts[0].equals("roomId")) {
                return Long.parseLong(parts[1]);
            }
        }
        throw new IllegalStateException("Missing roomId");
    }
}
