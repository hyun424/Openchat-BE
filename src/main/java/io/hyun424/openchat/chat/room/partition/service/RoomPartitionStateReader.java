package io.hyun424.openchat.chat.room.partition.service;

import java.util.List;

public interface RoomPartitionStateReader {

    int partitionCountForRoom(Long roomId);

    int routePartition(Long roomId, String userId);

    int versionForRoom(Long roomId);

    List<Integer> publishPartitions(Long roomId);
}
