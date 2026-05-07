package io.hyun424.openchat.chat.room.partition;

public class NoopRoomPartitionControlPublisher implements RoomPartitionControlPublisher {

    private final RoomPartitionMetrics metrics;

    public NoopRoomPartitionControlPublisher(RoomPartitionMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean publish(RoomPartitionControlCommand command) {
        metrics.recordControlPublish(command == null ? "unknown" : command.type(), "publish_failed");
        return false;
    }
}
