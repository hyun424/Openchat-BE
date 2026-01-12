package io.hyun424.openchat.hotchat;

import io.hyun424.openchat.hotchat.dto.HotChatResult;
import io.hyun424.openchat.infra.time.BucketKeyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@Service
public class HotChatService {

    private final StringRedisTemplate redisTemplate;

    private static final int CANDIDATE_MULTIPLIER = 3;

    public List<HotChatResult> getHotChats(int windowMinutes, int limit) {

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
