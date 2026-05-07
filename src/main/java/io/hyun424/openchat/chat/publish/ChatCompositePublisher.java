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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Default publisher: Redis for real-time, Kafka for durability.
 * Active when spring.kafka.bootstrap-servers is configured.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ChatCompositePublisher implements ChatMessagePublisher {

    private static final String KAFKA_TOPIC = "chat-message";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, ChatMessageDto> kafkaTemplate;
    private final RedisHealthState redisHealthState;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final ChatRedisChannelResolver channelResolver;
    private final RoomShardMetrics roomShardMetrics;
    private final RoomPartitionMetrics roomPartitionMetrics;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public ChatCompositePublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            KafkaTemplate<String, ChatMessageDto> kafkaTemplate,
            RedisHealthState redisHealthState,
            ChatPipelineMetrics chatPipelineMetrics,
            ChatRedisChannelResolver channelResolver,
            RoomShardMetrics roomShardMetrics,
            RoomPartitionMetrics roomPartitionMetrics
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.redisHealthState = redisHealthState;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.channelResolver = channelResolver;
        this.roomShardMetrics = roomShardMetrics;
        this.roomPartitionMetrics = roomPartitionMetrics;
        log.info("ChatCompositePublisher initialized (Redis + Kafka)");
    }

    @Override
    public CompletableFuture<Void> publish(ChatMessageDto message) {
        // 1. Redis Pub/Sub - real-time delivery (fire-and-forget)
        publishToRedis(message);

        // 2. Kafka - durability & ordering guarantee
        return publishToKafka(message);
    }

    private void publishToRedis(ChatMessageDto message) {
        // Skip if Redis is known to be down
        if (!redisHealthState.isUp()) {
            log.debug("[REDIS PUB SKIP][{}] Redis down, roomId={}", instanceId, message.getRoomId());
            return;
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
                log.debug("[REDIS PUB][{}] channel={} messageId={}",
                        instanceId, resolvedChannel.channel(), message.getMessageId());
            }
        } catch (Exception e) {
            chatPipelineMetrics.incrementCounter("publish.redis.fail");
            // Mark Redis as down, Kafka will handle durability
            redisHealthState.markDown();
            log.warn("[REDIS PUB FAIL][{}] roomId={} messageId={} - marking Redis down",
                    instanceId, message.getRoomId(), message.getMessageId());
        }
    }

    private CompletableFuture<Void> publishToKafka(ChatMessageDto message) {
        String key = String.valueOf(message.getRoomId());
        long startNanos = System.nanoTime();
        try {
            return kafkaTemplate.send(KAFKA_TOPIC, key, message)
                    .handle((result, ex) -> {
                        if (ex != null) {
                            chatPipelineMetrics.recordStage("publish.kafka.fail", startNanos);
                            log.error("[KAFKA PUB FAIL][{}] roomId={} messageId={}",
                                    instanceId, message.getRoomId(), message.getMessageId(), ex);
                            throw new ChatPublishException("Kafka publish failed", ex);
                        }

                        chatPipelineMetrics.recordStage("publish.kafka.ack", startNanos);
                        log.debug("[KAFKA PUB][{}] partition={} offset={} messageId={}",
                                instanceId,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                message.getMessageId());
                        return null;
                    });
        } catch (Exception e) {
            chatPipelineMetrics.recordStage("publish.kafka.fail", startNanos);
            log.error("[KAFKA PUB FAIL][{}] roomId={} messageId={}",
                    instanceId, message.getRoomId(), message.getMessageId(), e);
            return CompletableFuture.failedFuture(new ChatPublishException("Kafka publish failed", e));
        }
    }
}
