package io.hyun424.openchat.infra.redis.config;

import io.hyun424.openchat.chat.room.partition.infra.RoomPartitionControlChannelResolver;
import io.hyun424.openchat.chat.room.partition.infra.RoomPartitionControlSubscriber;
import io.hyun424.openchat.chat.room.shard.ChatRedisChannelResolver;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api'")
@ConditionalOnProperty(name = "app.redis.subscriber.enabled", havingValue = "true", matchIfMissing = true)
public class RedisSubscriberConfig {

    private final RedisChatMessageDispatcher chatMessageDispatcher;
    private final RoomPartitionControlSubscriber controlSubscriber;
    private final RedisHealthState redisHealthState;
    private final ChatRedisChannelResolver channelResolver;
    private final RoomPartitionControlChannelResolver controlChannelResolver;

    @Value("${app.instance-id:local}")
    private String instanceId;

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

        for (String topicPattern : channelResolver.subscribePatterns()) {
            container.addMessageListener(
                    (message, pattern) -> {
                        try {
                            chatMessageDispatcher.listener().onMessage(message, pattern);
                        } catch (Exception e) {
                            log.error("[REDIS SUB] message handling failed", e);
                        }
                    },
                    new PatternTopic(topicPattern)
            );
        }

        container.addMessageListener(
                (message, pattern) -> {
                    try {
                        String channel = new String(message.getChannel());
                        String body = new String(message.getBody());
                        controlSubscriber.onMessage(body, channel);
                        redisHealthState.markUp();
                    } catch (Exception e) {
                        log.error("[REDIS CONTROL SUB] message handling failed", e);
                    }
                },
                new PatternTopic(controlChannelResolver.subscribePattern())
        );
        container.addMessageListener(
                (message, pattern) -> {
                    try {
                        String channel = new String(message.getChannel());
                        String body = new String(message.getBody());
                        controlSubscriber.onMessage(body, channel);
                        redisHealthState.markUp();
                    } catch (Exception e) {
                        log.error("[REDIS NODE CONTROL SUB] message handling failed", e);
                    }
                },
                new PatternTopic(controlChannelResolver.nodeChannel(instanceId))
        );

        log.info("RedisMessageListenerContainer configured with recovery interval 5s topics={} controlTopic={} nodeControlTopic={}",
                channelResolver.subscribePatterns(),
                controlChannelResolver.subscribePattern(),
                controlChannelResolver.nodeChannel(instanceId));
        return container;
    }
}
