package io.hyun424.openchat.infra.websocket.handler;

/**
 * WebSocket에서 받은 채팅 입력값.
 * 핸들러가 JSON 구조를 직접 다루지 않도록 분리해 메시지 처리 흐름을 단순하게 유지한다.
 */
record ChatWebSocketMessage(String type,
                            String content,
                            String clientMessageId,
                            Long clientSentAt,
                            Long roomId,
                            Long lastSeenSequence) {

    static final String TYPE_CHAT_MESSAGE = "chat.message";
    static final String TYPE_LEGACY_TEXT = "text";
    static final String TYPE_ROOM_ACTIVE = "room.active";
    static final String TYPE_ROOM_ACTIVE_HEARTBEAT = "room.active.heartbeat";
    static final String TYPE_ROOM_PASSIVE = "room.passive";

    boolean isChatMessage() {
        return type == null || type.isBlank() || TYPE_CHAT_MESSAGE.equals(type) || TYPE_LEGACY_TEXT.equals(type);
    }

    boolean isRoomControlMessage() {
        return TYPE_ROOM_ACTIVE.equals(type)
                || TYPE_ROOM_ACTIVE_HEARTBEAT.equals(type)
                || TYPE_ROOM_PASSIVE.equals(type);
    }

    boolean marksActive() {
        return TYPE_ROOM_ACTIVE.equals(type) || TYPE_ROOM_ACTIVE_HEARTBEAT.equals(type);
    }
}
