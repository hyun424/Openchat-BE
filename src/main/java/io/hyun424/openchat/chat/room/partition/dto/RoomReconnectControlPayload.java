package io.hyun424.openchat.chat.room.partition.dto;

public record RoomReconnectControlPayload(
        String type,
        Long roomId,
        String reason,
        long retryAfterMs,
        long routeVersion,
        String commandId
) {

    public static RoomReconnectControlPayload of(Long roomId,
                                                 String reason,
                                                 long retryAfterMs,
                                                 long routeVersion) {
        return of(roomId, reason, retryAfterMs, routeVersion, null);
    }

    public static RoomReconnectControlPayload of(Long roomId,
                                                 String reason,
                                                 long retryAfterMs,
                                                 long routeVersion,
                                                 String commandId) {
        return new RoomReconnectControlPayload(
                "room.reconnect",
                roomId,
                reason == null || reason.isBlank() ? "unknown" : reason,
                Math.max(0, retryAfterMs),
                Math.max(0, routeVersion),
                commandId == null || commandId.isBlank() ? null : commandId
        );
    }
}
