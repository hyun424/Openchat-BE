package io.hyun424.openchat.infra.websocket.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;

/**
 * WebSocket payload 파싱과 sanitize를 담당한다.
 * JSON 파싱 규칙이 핸들러에 섞이면 실제 메시지 처리 순서가 잘 보이지 않기 때문에 별도 클래스로 둔다.
 */
class ChatWebSocketMessageParser {

    private static final PolicyFactory SANITIZER = Sanitizers.FORMATTING;

    private final ObjectMapper objectMapper;

    ChatWebSocketMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ChatWebSocketMessage parse(TextMessage textMessage) throws IOException {
        JsonNode node = objectMapper.readTree(textMessage.getPayload());
        String content = node.has("content") ? node.get("content").asText() : null;
        String clientMessageId = node.has("clientMessageId")
                ? node.get("clientMessageId").asText()
                : null;
        return new ChatWebSocketMessage(sanitize(content), clientMessageId);
    }

    private String sanitize(String content) {
        if (content == null) {
            return null;
        }
        return SANITIZER.sanitize(content);
    }
}
