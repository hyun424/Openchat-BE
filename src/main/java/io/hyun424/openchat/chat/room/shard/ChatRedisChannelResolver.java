package io.hyun424.openchat.chat.room.shard;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.room.partition.assignment.RoomPartitionAssignmentProperties;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRoutingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatRedisChannelResolver {

    static final String LEGACY_MODE = "legacy";
    static final String SHARD_MODE = "shard";
    static final String PARTITION_MODE = "partition";
    static final String UNKNOWN_MODE = "unknown";

    private static final String LEGACY_PREFIX = "chat:room:";
    private static final String SHARD_PREFIX = "chat:room-shard:";
    private static final String PARTITION_PREFIX = "chat:room-partition:";

    private final RoomShardProperties properties;
    private final RoomShardResolver roomShardResolver;
    private final RoomPartitionProperties partitionProperties;
    private final RoomPartitionRoutingService partitionRoutingService;
    private final RoomPartitionAssignmentProperties assignmentProperties;

    public ChatRedisChannelResolver(RoomShardProperties properties,
                                    RoomShardResolver roomShardResolver,
                                    RoomPartitionProperties partitionProperties,
                                    RoomPartitionRoutingService partitionRoutingService,
                                    RoomPartitionAssignmentProperties assignmentProperties) {
        this.properties = properties;
        this.roomShardResolver = roomShardResolver;
        this.partitionProperties = partitionProperties;
        this.partitionRoutingService = partitionRoutingService;
        this.assignmentProperties = assignmentProperties;
    }

    public ResolvedChannel publishChannel(ChatMessageDto message) {
        return publishChannels(message).get(0);
    }

    public List<ResolvedChannel> publishChannels(ChatMessageDto message) {
        Long roomId = message != null ? message.getRoomId() : null;
        if (!properties.enabled()) {
            return List.of(new ResolvedChannel(legacyChannel(roomId), LEGACY_MODE));
        }
        List<Integer> partitions = partitionRoutingService.publishPartitions(roomId);
        if (!partitions.isEmpty()) {
            return partitions.stream()
                    .map(partitionId -> new ResolvedChannel(partitionChannel(roomId, partitionId), PARTITION_MODE))
                    .toList();
        }
        int shardId = roomShardResolver.resolveShardId(roomId);
        return List.of(new ResolvedChannel(shardChannel(shardId), SHARD_MODE));
    }

    public List<String> subscribePatterns() {
        List<String> patterns = new ArrayList<>();
        if (!properties.enabled()) {
            patterns.add(LEGACY_PREFIX + "*");
            return patterns;
        }
        if (partitionProperties.enabled() && !assignmentProperties.dynamicSubscribeEnabled()) {
            for (Integer partitionId : partitionProperties.ownedPartitions()) {
                patterns.add(partitionPattern(partitionId));
            }
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
        if (channel.startsWith(PARTITION_PREFIX)) {
            return PARTITION_MODE;
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

    public String partitionChannel(Long roomId, int partitionId) {
        return PARTITION_PREFIX + roomId + ":" + partitionProperties.normalizePartitionId(partitionId);
    }

    public String partitionPattern(int partitionId) {
        return PARTITION_PREFIX + "*:" + partitionProperties.normalizePartitionId(partitionId);
    }

    public Integer partitionIdForChannel(String channel) {
        return partitionRoutingService.partitionIdForChannel(channel);
    }

    public record ResolvedChannel(String channel, String mode) {
    }
}
