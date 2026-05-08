package io.hyun424.openchat.infra.redis.config;

import io.hyun424.openchat.chat.subscribe.ChatRedisSubscriber;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMessageDispatcher {

    private final ChatRedisSubscriber subscriber;
    private final RedisHealthState redisHealthState;
    private final MessageListener listener = this::onMessage;

    public MessageListener listener() {
        return listener;
    }

    private void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel());
            String body = new String(message.getBody());
            subscriber.onMessage(body, channel);
            redisHealthState.markUp();
        } catch (Exception e) {
            log.error("[REDIS SUB] message handling failed", e);
        }
    }
}
