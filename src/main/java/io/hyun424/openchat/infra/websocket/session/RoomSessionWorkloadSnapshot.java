package io.hyun424.openchat.infra.websocket.session;

public record RoomSessionWorkloadSnapshot(
        int totalSessions,
        int activeSessions,
        int passiveSessions,
        int broadcastQueueDepth
) {
    public static RoomSessionWorkloadSnapshot empty(int broadcastQueueDepth) {
        return new RoomSessionWorkloadSnapshot(0, 0, 0, Math.max(0, broadcastQueueDepth));
    }
}
