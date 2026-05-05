package io.hyun424.openchat.chat.room.shard;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatRedisChannelResolver {

    static final String LEGACY_MODE = "legacy";
    static final String SHARD_MODE = "shard";
    static final String UNKNOWN_MODE = "unknown";

    private static final String LEGACY_PREFIX = "chat:room:";
    private static final String SHARD_PREFIX = "chat:room-shard:";

    private final RoomShardProperties properties;
    private final RoomShardResolver roomShardResolver;

    public ChatRedisChannelResolver(RoomShardProperties properties, RoomShardResolver roomShardResolver) {
        this.properties = properties;
        this.roomShardResolver = roomShardResolver;
    }

    public ResolvedChannel publishChannel(ChatMessageDto message) {
        Long roomId = message != null ? message.getRoomId() : null;
        if (!properties.enabled()) {
            return new ResolvedChannel(legacyChannel(roomId), LEGACY_MODE);
        }
        int shardId = roomShardResolver.resolveShardId(roomId);
        return new ResolvedChannel(shardChannel(shardId), SHARD_MODE);
    }

    public List<String> subscribePatterns() {
        List<String> patterns = new ArrayList<>();
        if (!properties.enabled()) {
            patterns.add(LEGACY_PREFIX + "*");
            return patterns;
        }
        for (Integer shardId : properties.ownedShards()) {
            patterns.add(shardChannel(shardId));
        }
        if (properties.legacySubscribeEnabled()) {
            patterns.add(LEGACY_PREFIX + "*");
        }
        return patterns;
    }

    public String modeForChannel(String channel) {
        if (channel == null) {
            return UNKNOWN_MODE;
        }
        if (channel.startsWith(SHARD_PREFIX)) {
            return SHARD_MODE;
        }
        if (channel.startsWith(LEGACY_PREFIX)) {
            return LEGACY_MODE;
        }
        return UNKNOWN_MODE;
    }

    public String legacyChannel(Long roomId) {
        return LEGACY_PREFIX + roomId;
    }

    public String shardChannel(int shardId) {
        return SHARD_PREFIX + properties.normalizeShardId(shardId);
    }

    public record ResolvedChannel(String channel, String mode) {
    }
}
