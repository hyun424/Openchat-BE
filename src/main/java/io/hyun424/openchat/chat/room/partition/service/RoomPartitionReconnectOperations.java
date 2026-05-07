package io.hyun424.openchat.chat.room.partition.service;

public interface RoomPartitionReconnectOperations {

    RoomPartitionReconnectResult reconnectDraining(
            Long roomId,
            String reason,
            long retryAfterMs,
            Integer limit
    );

    record RoomPartitionReconnectResult(
            Long roomId,
            String reason,
            boolean accepted,
            int publishedCommands
    ) {
    }
}
