package io.hyun424.openchat.chat.room.partition.domain;

public enum RoomPartitionStatus {
    ACTIVE,
    SCALING_UP,
    DRAINING
}
