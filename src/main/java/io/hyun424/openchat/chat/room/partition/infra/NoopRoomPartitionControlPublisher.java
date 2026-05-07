package io.hyun424.openchat.chat.room.partition.infra;

import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import org.springframework.stereotype.Component;

@Component
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
