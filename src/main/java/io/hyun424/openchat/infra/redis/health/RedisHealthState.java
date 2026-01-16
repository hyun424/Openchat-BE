package io.hyun424.openchat.infra.redis.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class RedisHealthState {

    private final AtomicBoolean available = new AtomicBoolean(true);

    public boolean isUp() {
        return isAvailable();
    }

    public boolean isAvailable() {
        return available.get();
    }

    public void markDown() {
        if (available.compareAndSet(true, false)) {
            log.error("[REDIS DOWN] switching to degraded mode");
        }
    }

    public void markUp() {
        if (available.compareAndSet(false, true)) {
            log.info("[REDIS UP] recovered");
        }
    }
}

