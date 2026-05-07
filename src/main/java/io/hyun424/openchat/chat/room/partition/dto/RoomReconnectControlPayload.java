package io.hyun424.openchat.chat.room.partition.dto;

public record RoomReconnectControlPayload(
        String type,
        Long roomId,
        String reason,
        long retryAfterMs,
        long routeVersion
) {

    public static RoomReconnectControlPayload of(Long roomId,
                                                 String reason,
                                                 long retryAfterMs,
                                                 long routeVersion) {
        return new RoomReconnectControlPayload(
                "room.reconnect",
                roomId,
                reason == null || reason.isBlank() ? "unknown" : reason,
                Math.max(0, retryAfterMs),
                Math.max(0, routeVersion)
        );
    }
}
