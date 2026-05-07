package io.hyun424.openchat.chat.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.shard.ChatRedisChannelResolver;
import io.hyun424.openchat.chat.room.shard.RoomShardMetrics;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Redis-only publisher: Active when Redis is configured but Kafka is not.
 * Gracefully degrades when Redis is unavailable - messages are still saved to DB.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "spring.data.redis.host")
@ConditionalOnMissingBean(ChatCompositePublisher.class)
public class ChatRedisOnlyPublisher implements ChatMessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisHealthState redisHealthState;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final ChatRedisChannelResolver channelResolver;
    private final RoomShardMetrics roomShardMetrics;
    private final RoomPartitionMetrics roomPartitionMetrics;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public ChatRedisOnlyPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RedisHealthState redisHealthState,
            ChatPipelineMetrics chatPipelineMetrics,
            ChatRedisChannelResolver channelResolver,
            RoomShardMetrics roomShardMetrics,
            RoomPartitionMetrics roomPartitionMetrics
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisHealthState = redisHealthState;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.channelResolver = channelResolver;
        this.roomShardMetrics = roomShardMetrics;
        this.roomPartitionMetrics = roomPartitionMetrics;
        log.info("ChatRedisOnlyPublisher initialized (Redis only, no Kafka)");
    }

    @Override
    public CompletableFuture<Void> publish(ChatMessageDto message) {
        // Skip if Redis is known to be down - message already saved to DB
        if (!redisHealthState.isUp()) {
            log.debug("[REDIS PUB SKIP][{}] Redis is down, roomId={} messageId={}",
                    instanceId, message.getRoomId(), message.getMessageId());
            return CompletableFuture.failedFuture(new ChatPublishException("Redis is down"));
        }

        try {
            long serializeStartNanos = System.nanoTime();
            String payload = objectMapper.writeValueAsString(message);
            chatPipelineMetrics.recordStage("publish.redis.serialize", serializeStartNanos);
            for (ChatRedisChannelResolver.ResolvedChannel resolvedChannel : channelResolver.publishChannels(message)) {
                long publishStartNanos = System.nanoTime();
                redisTemplate.convertAndSend(resolvedChannel.channel(), payload);
                roomShardMetrics.recordPublish(resolvedChannel.mode());
                roomPartitionMetrics.recordPublish(resolvedChannel.mode());
                chatPipelineMetrics.recordStage("publish.redis.convert_and_send", publishStartNanos);
                chatPipelineMetrics.recordSinceCreated("publish.redis.after_send.since_created", message);
                log.debug("[REDIS PUB][{}] channel={} roomId={} messageId={}",
                        instanceId, resolvedChannel.channel(), message.getRoomId(), message.getMessageId());
            }
        } catch (Exception e) {
            chatPipelineMetrics.incrementCounter("publish.redis.fail");
            // Mark Redis as down for future requests
            redisHealthState.markDown();
            log.error("[REDIS PUB FAIL][{}] roomId={} messageId={} - marking Redis down",
                    instanceId, message.getRoomId(), message.getMessageId(), e);
            return CompletableFuture.failedFuture(new ChatPublishException("Redis publish failed", e));
        }
        return CompletableFuture.completedFuture(null);
    }
}
