package io.hyun424.openchat.infra.redis.config;

import io.hyun424.openchat.chat.subscribe.ChatRedisSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@RequiredArgsConstructor
public class RedisSubscriberConfig {

    private final ChatRedisSubscriber subscriber;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory
    ) {
        RedisMessageListenerContainer container =
                new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        container.addMessageListener(
                (message, pattern) -> {
                    String channel = new String(message.getChannel());
                    String body = new String(message.getBody());
                    subscriber.onMessage(body, channel);
                },
                new PatternTopic("chat:room:*") // ✅ 패턴 구독
        );

        return container;
    }
}
