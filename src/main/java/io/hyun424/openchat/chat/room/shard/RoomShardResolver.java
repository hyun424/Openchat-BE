package io.hyun424.openchat.chat.room.shard;

import io.hyun424.openchat.chat.room.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RoomShardResolver {

    private final RoomShardProperties properties;
    private final RoomRepository roomRepository;

    public int resolveShardId(Long roomId) {
        if (roomId == null) {
            return 0;
        }
        return properties.normalizeShardId(roomRepository.findShardIdById(roomId).orElse(0));
    }

    public int normalizeShardId(Integer shardId) {
        return properties.normalizeShardId(shardId);
    }
}
