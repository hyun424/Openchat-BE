package io.hyun424.openchat.chat.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.redis.health.RedisHealthState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Profile("redis")
@RequiredArgsConstructor
public class ChatRedisPublishService implements ChatMessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisHealthState redisHealthState;

    @Override
    public void publish(ChatMessageDto message) {
        String channel = "chat:room:" + message.getRoomId();

        try {
            String payload = objectMapper.writeValueAsString(message);

            redisTemplate.convertAndSend(channel, payload);
            redisHealthState.markUp();

            log.info(
                    "[REDIS PUBLISH] channel={} roomId={} messageId={}",
                    channel,
                    message.getRoomId(),
                    message.getMessageId()
            );

        } catch (Exception e) {
            redisHealthState.markDown();

            log.error(
                    "[REDIS PUBLISH FAIL] roomId={} messageId={} senderId={}",
                    message.getRoomId(),
                    message.getMessageId(),
                    message.getSenderId(),
                    e
            );

            // Redis 장애 시 WS 직접 fan-out 금지
            // 이유: 멀티 서버 환경에서 중복 전송 및 순서 불일치 발생 가능
            // 메시지 유실은 허용하되 서버 안정성을 우선한다
            // TODO: Kafka Publisher fallback or local buffer 고려
        }
    }
}
