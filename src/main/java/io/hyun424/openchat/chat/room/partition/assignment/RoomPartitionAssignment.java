package io.hyun424.openchat.chat.room.partition.assignment;

public record RoomPartitionAssignment(
        int partitionId,
        String nodeId,
        String wsUrl,
        String assignmentVersion
) {
}
