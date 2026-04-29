package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

/**
 * WebSocket 에러 응답 생성 책임을 모은다.
 * 에러 JSON 포맷을 한 곳에 두면 핸들러와 테스트가 메시지 처리 로직에만 집중할 수 있다.
 */
@Slf4j
class WebSocketErrorSender {

    private final ObjectMapper objectMapper;

    WebSocketErrorSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void sendError(WebSocketSession session, String message) {
        try {
            String errorJson = objectMapper.writeValueAsString(
                    Map.of("type", "ERROR", "message", message)
            );
            session.sendMessage(new TextMessage(errorJson));
        } catch (IOException e) {
            log.error("[WS_SEND_ERROR_FAILED]", e);
        }
    }
}
