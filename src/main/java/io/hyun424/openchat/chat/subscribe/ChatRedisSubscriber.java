package io.hyun424.openchat.chat.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.fanout.ChatFanoutService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatRedisSubscriber {

    private final ObjectMapper redisObjectMapper;
    private final ChatFanoutService chatFanoutService;
    private final ChatPipelineMetrics chatPipelineMetrics;

    public ChatRedisSubscriber(
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            ChatFanoutService chatFanoutService,
            ChatPipelineMetrics chatPipelineMetrics
    ) {
        this.redisObjectMapper = redisObjectMapper;
        this.chatFanoutService = chatFanoutService;
        this.chatPipelineMetrics = chatPipelineMetrics;
    }

    public void onMessage(String messageJson, String channel) {
        long totalStartNanos = System.nanoTime();
        try {
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
