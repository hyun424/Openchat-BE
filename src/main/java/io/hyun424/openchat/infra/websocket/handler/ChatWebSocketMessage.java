package io.hyun424.openchat.infra.websocket.handler;

/**
 * WebSocket에서 받은 채팅 입력값.
 * 핸들러가 JSON 구조를 직접 다루지 않도록 분리해 메시지 처리 흐름을 단순하게 유지한다.
 */
record ChatWebSocketMessage(String content, String clientMessageId) {
}
