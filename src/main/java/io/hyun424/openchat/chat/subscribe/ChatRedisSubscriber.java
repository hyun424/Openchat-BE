package io.hyun424.openchat.chat.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.fanout.RoomFanoutDispatcher;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.metrics.ChatPipelineMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatRedisSubscriber {

    private final ObjectMapper redisObjectMapper;
    private final RoomFanoutDispatcher roomFanoutDispatcher;
    private final ChatPipelineMetrics chatPipelineMetrics;

    public ChatRedisSubscriber(
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            RoomFanoutDispatcher roomFanoutDispatcher,
            ChatPipelineMetrics chatPipelineMetrics
    ) {
        this.redisObjectMapper = redisObjectMapper;
        this.roomFanoutDispatcher = roomFanoutDispatcher;
        this.chatPipelineMetrics = chatPipelineMetrics;
    }

    public void onMessage(String messageJson, String channel) {
        long listenerStartNanos = System.nanoTime();
        long deserializeStartNanos = System.nanoTime();
        try {
            ChatMessageDto message =
                    redisObjectMapper.readValue(messageJson, ChatMessageDto.class);
            chatPipelineMetrics.recordStageNanos(
                    "subscribe.deserialize", System.nanoTime() - deserializeStartNanos);
            chatPipelineMetrics.recordSinceCreated("subscribe.after_deserialize.since_created", message);
            chatPipelineMetrics.recordSinceCreated("subscribe.since_created", message);

            log.debug("[Redis SUBSCRIBE] channel={} messageId={}",
                    channel, message.getMessageId());

            chatPipelineMetrics.recordSinceCreated("subscribe.before_fanout.since_created", message);
            long fanoutCallStartNanos = System.nanoTime();
            roomFanoutDispatcher.enqueue(message);
            chatPipelineMetrics.recordStageNanos(
                    "subscribe.fanout_call", System.nanoTime() - fanoutCallStartNanos);

        } catch (Exception e) {
            log.error("[Redis SUBSCRIBE ERROR]", e);
        } finally {
            chatPipelineMetrics.recordStageNanos(
                    "subscribe.listener.total", System.nanoTime() - listenerStartNanos);
        }
    }

}
