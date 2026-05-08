package io.hyun424.openchat.chat.room.partition.assignment;

public record PartitionAssignment(
        int partitionId,
        String nodeId,
        String host,
        int port
) {
    public static PartitionAssignment of(int partitionId, RealtimeNode node) {
        return new PartitionAssignment(partitionId, node.nodeId(), node.host(), node.port());
    }
}
