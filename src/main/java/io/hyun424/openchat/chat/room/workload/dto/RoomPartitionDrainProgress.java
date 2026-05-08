package io.hyun424.openchat.chat.room.workload.dto;

public record RoomPartitionDrainProgress(
        Long roomId,
        int partitionId,
        int openSessions,
        String sourceNodeId
) {
    public RoomPartitionDrainProgress {
        partitionId = Math.max(0, partitionId);
        openSessions = Math.max(0, openSessions);
        sourceNodeId = sourceNodeId == null || sourceNodeId.isBlank() ? "unknown" : sourceNodeId;
    }
}
