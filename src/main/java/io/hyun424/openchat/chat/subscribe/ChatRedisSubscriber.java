package io.hyun424.openchat.chat.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.fanout.ChatFanoutService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.shard.ChatRedisChannelResolver;
import io.hyun424.openchat.chat.room.shard.RoomShardMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatRedisSubscriber {

    private final ObjectMapper redisObjectMapper;
    private final ChatFanoutService chatFanoutService;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final ChatRedisChannelResolver channelResolver;
    private final RoomShardMetrics roomShardMetrics;

    public ChatRedisSubscriber(
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            ChatFanoutService chatFanoutService,
            ChatPipelineMetrics chatPipelineMetrics,
            ChatRedisChannelResolver channelResolver,
            RoomShardMetrics roomShardMetrics
    ) {
        this.redisObjectMapper = redisObjectMapper;
        this.chatFanoutService = chatFanoutService;
        this.chatPipelineMetrics = chatPipelineMetrics;
        this.channelResolver = channelResolver;
        this.roomShardMetrics = roomShardMetrics;
    }

    public void onMessage(String messageJson, String channel) {
        long totalStartNanos = System.nanoTime();
        try {
            roomShardMetrics.recordSubscribe(channelResolver.modeForChannel(channel));
            long deserializeStartNanos = System.nanoTime();
            ChatMessageDto message =
                    redisObjectMapper.readValue(messageJson, ChatMessageDto.class);
            chatPipelineMetrics.recordStage("subscribe.redis.deserialize", deserializeStartNanos);
            chatPipelineMetrics.recordSinceCreated("subscribe.redis.before_fanout.since_created", message);

            log.debug("[Redis SUBSCRIBE] channel={} messageId={}",
                    channel, message.getMessageId());

            long fanoutStartNanos = System.nanoTime();
            chatFanoutService.fanout(message);
            chatPipelineMetrics.recordStage("subscribe.redis.fanout_call", fanoutStartNanos);
            chatPipelineMetrics.recordStage("subscribe.redis.total", totalStartNanos);

        } catch (Exception e) {
            chatPipelineMetrics.recordStage("subscribe.redis.fail", totalStartNanos);
            log.error("[Redis SUBSCRIBE ERROR]", e);
        }
    }

}
