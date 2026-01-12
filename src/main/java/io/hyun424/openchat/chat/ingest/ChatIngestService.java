package io.hyun424.openchat.chat.ingest;

import io.hyun424.openchat.chat.publish.ChatPublishService;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.time.BucketKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatIngestService {

    private final ChatPublishService chatPublishService;
    private final StringRedisTemplate redisTemplate;

    public void ingest(ChatMessageDto message) {

        // ✅ messageId 생성 (최초 진입 지점)
        if (message.getMessageId() == null) {
            message.setMessageId(UUID.randomUUID().toString());
        }

        log.info(
                "[Chat Ingest] room={} senderId={} messageId={} clientMessageId={} message={}",
                message.getRoomId(),
                message.getSenderId(),
                message.getMessageId(),
                message.getClientMessageId(),
                message.getMessage()
        );

        // ✅ HotChat bucket 기록 (실시간, 독립)
        try {
            String bucketKey = BucketKeyUtil.currentBucketKey();

            redisTemplate.opsForZSet()
                    .incrementScore(bucketKey, "room:" + message.getRoomId(), 1);

            // window=5분 기준 + 여유
            redisTemplate.expire(bucketKey, Duration.ofMinutes(7));

        } catch (Exception e) {
            // HotChat은 보조 지표이므로 실패해도 흐름 차단 ❌
            log.warn("[HotChat Bucket FAIL] roomId={} messageId={}",
                    message.getRoomId(),
                    message.getMessageId(),
                    e
            );
        }

        // ✅ fan-out (Redis Pub/Sub)
        chatPublishService.publish(message);
    }
}
