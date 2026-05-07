package io.hyun424.openchat.chat.room.partition.infra;

import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;

public interface RoomPartitionControlPublisher {

    boolean publish(RoomPartitionControlCommand command);
}
