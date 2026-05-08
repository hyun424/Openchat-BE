package io.hyun424.openchat.infra.websocket.session;

final class RoomSessionState {
    private final Long roomId;
    private final Integer partitionId;
    private volatile boolean activeDeclared;
    private volatile Long lastSeenSequence;
    private volatile long lastActiveSignalAt;
    private volatile long lastControlAt;

    private RoomSessionState(Long roomId, Integer partitionId, boolean activeDeclared, long now) {
        this.roomId = roomId;
        this.partitionId = partitionId;
        this.activeDeclared = activeDeclared;
        this.lastActiveSignalAt = now;
        this.lastControlAt = now;
    }

    static RoomSessionState active(Long roomId, Integer partitionId, long now) {
        return new RoomSessionState(roomId, partitionId, true, now);
    }

    Long roomId() {
        return roomId;
    }

    Integer partitionId() {
        return partitionId;
    }

    void mark(boolean active, Long lastSeenSequence, long now) {
        this.activeDeclared = active;
        this.lastSeenSequence = lastSeenSequence;
        this.lastControlAt = now;
        if (active) {
            this.lastActiveSignalAt = now;
        }
    }

    boolean isActive(long now, long activeTtlMillis) {
        return activeDeclared && now - lastActiveSignalAt <= activeTtlMillis;
    }

    boolean matchesPartition(Integer requestedPartitionId) {
        return requestedPartitionId == null
                || (partitionId != null && partitionId.equals(requestedPartitionId));
    }
}
