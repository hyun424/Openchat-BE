package io.hyun424.openchat.infra.redis.config;

import io.hyun424.openchat.chat.subscribe.ChatRedisSubscriber;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisSubscriberConfig {

    private final ChatRedisSubscriber subscriber;
    private final RedisHealthState redisHealthState;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Non-blocking task executor for message handling
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("redis-sub-");
        executor.initialize();
        container.setTaskExecutor(executor);

        // Error handler - mark Redis as down but don't crash
        container.setErrorHandler(t -> {
            log.error("[REDIS SUB ERROR] {}", t.getMessage());
            redisHealthState.markDown();
        });

        // Recovery settings
        container.setRecoveryInterval(5000L); // 5초마다 재연결 시도

        container.addMessageListener(
                (message, pattern) -> {
                    try {
                        String channel = new String(message.getChannel());
                        String body = new String(message.getBody());
                        subscriber.onMessage(body, channel);
                        redisHealthState.markUp(); // 메시지 받으면 healthy
                    } catch (Exception e) {
                        log.error("[REDIS SUB] message handling failed", e);
                    }
                },
                new PatternTopic("chat:room:*")
        );

        log.info("RedisMessageListenerContainer configured with recovery interval 5s");
        return container;
    }
}
