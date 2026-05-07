package io.hyun424.openchat.chat.room.partition;

public interface RoomPartitionControlPublisher {

    boolean publish(RoomPartitionControlCommand command);
}
