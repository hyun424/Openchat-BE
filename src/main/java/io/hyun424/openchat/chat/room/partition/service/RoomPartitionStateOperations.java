package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;

import java.util.Set;

public interface RoomPartitionStateOperations {

    RoomPartitionState scaleUp(Long roomId, int targetPartitionCount, String updatedBy);

    RoomPartitionState startDrain(Long roomId, int targetPartitionCount, Set<Integer> drainingPartitions, String updatedBy);

    RoomPartitionState completeDrain(Long roomId, String updatedBy);
}
