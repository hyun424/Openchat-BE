package io.hyun424.openchat.global.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 사용자별 Rate Limiting
 * - Redis 사용 시: 분산 환경 지원
 * - Redis 실패 시: 로컬 메모리 fallback
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redisTemplate;

    // Redis 실패 시 fallback용 로컬 캐시
    private final Map<String, TokenBucket> localBuckets = new ConcurrentHashMap<>();

    /**
     * 요청 허용 여부 확인
     * @param key 사용자 식별자 (예: userId, IP)
     * @param limit 윈도우 내 최대 요청 수
     * @param windowSeconds 윈도우 크기 (초)
     * @return true면 허용, false면 차단
     */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        try {
            return tryAcquireWithRedis(key, limit, windowSeconds);
        } catch (Exception e) {
            log.warn("[RATE_LIMIT] Redis failed, using local fallback: {}", e.getMessage());
            return tryAcquireLocal(key, limit, windowSeconds);
        }
    }

    /**
     * Redis 기반 Sliding Window Counter
     */
    private boolean tryAcquireWithRedis(String key, int limit, int windowSeconds) {
        String redisKey = "rate:" + key;

        Long count = redisTemplate.opsForValue().increment(redisKey);

        if (count == null) {
            return true;
        }

        // 첫 요청이면 TTL 설정
        if (count == 1) {
            redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
        }

        return count <= limit;
    }

    /**
     * 로컬 메모리 fallback (Token Bucket)
     */
    private boolean tryAcquireLocal(String key, int limit, int windowSeconds) {
        TokenBucket bucket = localBuckets.computeIfAbsent(key,
                k -> new TokenBucket(limit, windowSeconds));
        return bucket.tryAcquire();
    }

    /**
     * 간단한 Token Bucket 구현
     */
    private static class TokenBucket {
        private final int capacity;
        private final double refillRatePerSecond;
        private double tokens;
        private long lastRefillTime;

        TokenBucket(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.refillRatePerSecond = (double) capacity / windowSeconds;
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            double elapsed = (now - lastRefillTime) / 1000.0;
            tokens = Math.min(capacity, tokens + elapsed * refillRatePerSecond);
            lastRefillTime = now;
        }
    }
}
