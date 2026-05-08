package io.hyun424.openchat.chat.room.partition.assignment;

import java.util.List;

public record RoomPartitionAssignment(
        int partitionId,
        String nodeId,
        String wsUrl,
        String assignmentVersion,
        boolean ready,
        String readinessReason,
        List<String> alternateSubscribedNodeIds
) {
}
