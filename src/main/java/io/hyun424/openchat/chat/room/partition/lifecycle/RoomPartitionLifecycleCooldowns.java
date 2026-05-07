package io.hyun424.openchat.chat.room.partition.lifecycle;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RoomPartitionLifecycleCooldowns {

    private static final String PREFIX = "openchat:room-partition:lifecycle:cooldown:";

    private final StringRedisTemplate redisTemplate;

    public RoomPartitionLifecycleCooldowns(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean acquire(String operation, Long roomId, long ttlMillis) {
        if (ttlMillis <= 0) {
            return true;
        }
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                PREFIX + operation + ":" + roomId,
                "1",
                Duration.ofMillis(ttlMillis)
        );
        return Boolean.TRUE.equals(acquired);
    }
}
