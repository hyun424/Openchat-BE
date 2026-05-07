package io.hyun424.openchat.chat.room.partition.service;

public interface RoomPartitionReconnectOperations {

    RoomPartitionReconnectService.RoomPartitionReconnectResult reconnectDraining(
            Long roomId,
            String reason,
            long retryAfterMs,
            Integer limit
    );
}
