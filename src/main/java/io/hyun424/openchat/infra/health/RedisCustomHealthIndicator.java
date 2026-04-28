package io.hyun424.openchat.infra.health;

import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component("redis")
@RequiredArgsConstructor
public class RedisCustomHealthIndicator implements HealthIndicator {

    private final RedisHealthState redisHealthState;
    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public Health health() {
        if (!redisHealthState.isUp()) {
            return Health.down()
                    .withDetail("circuitBreaker", "OPEN")
                    .build();
        }

        try {
            long start = System.currentTimeMillis();
            String pong = redisConnectionFactory.getConnection().ping();
            long latency = System.currentTimeMillis() - start;

            return Health.up()
                    .withDetail("ping", pong)
                    .withDetail("latencyMs", latency)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
