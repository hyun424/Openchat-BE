package io.hyun424.openchat.chat.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.fanout.ChatFanoutService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatRedisSubscriber {

    private final ObjectMapper redisObjectMapper;
    private final ChatFanoutService chatFanoutService;

    public ChatRedisSubscriber(
            @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper,
            ChatFanoutService chatFanoutService
    ) {
        this.redisObjectMapper = redisObjectMapper;
        this.chatFanoutService = chatFanoutService;
    }

    public void onMessage(String messageJson, String channel) {
        try {
            ChatMessageDto message =
                    redisObjectMapper.readValue(messageJson, ChatMessageDto.class);

            Long roomId = extractRoomId(channel);

            log.info("[Redis SUBSCRIBE] channel={} messageId={}",
                    channel, message.getMessageId());

            chatFanoutService.fanout(roomId, message);

        } catch (Exception e) {
            log.error("[Redis SUBSCRIBE ERROR]", e);
        }
    }

    private Long extractRoomId(String channel) {
        return Long.parseLong(channel.split(":")[2]);
    }
}
