package io.hyun424.openchat.infra.redis.config;

import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisConfig {

    private final RedisHealthState redisHealthState;
    private RedisConnectionFactory connectionFactory;

    public RedisConfig(RedisHealthState redisHealthState) {
        this.redisHealthState = redisHealthState;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);

        // Initial health check
        checkRedisHealth();

        return template;
    }

    // Periodic health check - recovers when Redis comes back up
    @Scheduled(fixedRate = 30000)
    public void checkRedisHealth() {
        if (connectionFactory == null) return;

        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
            redisHealthState.markUp();
        } catch (Exception e) {
            redisHealthState.markDown();
            log.warn("[REDIS HEALTH] connection failed: {}", e.getMessage());
        }
    }
}
