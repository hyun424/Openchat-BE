package io.hyun424.openchat.hotchat;

import io.hyun424.openchat.hotchat.dto.HotChatResult;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import io.hyun424.openchat.infra.time.BucketKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class HotChatService {

    private final StringRedisTemplate redisTemplate;
    private final RedisHealthState redisHealthState;

    private static final int CANDIDATE_MULTIPLIER = 3;

    /**
     * 채팅 전송 후처리 경로에서 호출한다.
     * Redis 장애가 있어도 메시지 DB 저장/ack 의미를 깨지 않도록 예외는 삼키고 Redis down만 표시한다.
     */
    public void recordMessageActivity(Long roomId, String messageId) {
        if (!redisHealthState.isUp()) {
            return;
        }

        try {
            String bucketKey = BucketKeyUtil.currentBucketKey();
            redisTemplate.opsForZSet()
                    .incrementScore(bucketKey, "room:" + roomId, 1);
            redisTemplate.expire(bucketKey, Duration.ofMinutes(7));
        } catch (Exception e) {
            redisHealthState.markDown();
            log.warn("[HOTCHAT FAIL] roomId={} messageId={} - marking Redis down", roomId, messageId, e);
        }
    }

    public List<HotChatResult> getHotChats(int windowMinutes, int limit) {
        // Skip if Redis is down
        if (!redisHealthState.isUp()) {
            return List.of();
        }

        try {
            return fetchHotChats(windowMinutes, limit);
        } catch (Exception e) {
            redisHealthState.markDown();
            log.warn("[HOTCHAT] Redis operation failed, marking down", e);
            return List.of();
        }
    }

    private List<HotChatResult> fetchHotChats(int windowMinutes, int limit) {
        int topK = limit * CANDIDATE_MULTIPLIER;
        Map<String, Double> scoreMap = new HashMap<>();

        for (int i = 0; i < windowMinutes; i++) {
            String bucketKey = BucketKeyUtil.bucketKeyMinutesAgo(i);

            Set<ZSetOperations.TypedTuple<String>> topRooms =
                    redisTemplate.opsForZSet()
                            .reverseRangeWithScores(bucketKey, 0, topK - 1);

            if (topRooms == null) continue;

            for (var tuple : topRooms) {
                scoreMap.merge(
                        tuple.getValue(),
                        tuple.getScore(),
                        Double::sum
                );
            }
        }

        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new HotChatResult(e.getKey(), e.getValue()))
                .toList();
    }
}
