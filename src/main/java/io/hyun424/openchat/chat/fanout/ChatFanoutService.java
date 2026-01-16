package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.infra.websocket.handler.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;


@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFanoutService {

    private final ChatOutboundSender outboundSender;
    private final StringRedisTemplate redisTemplate;

    public void fanout(ChatMessageDto message) {
        String dedupeKey = "dedupe:chat:" + message.getMessageId();

        // 🔥 서버 dedupe (1분 TTL)
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(dedupeKey, "1", Duration.ofMinutes(1));

        if (Boolean.FALSE.equals(first)) {
            log.debug("[DEDUPED] messageId={}", message.getMessageId());
            return;
        }

        try {
            outboundSender.send(message.getRoomId(), message);

            log.info(
                    "[FANOUT] roomId={} messageId={}",
                    message.getRoomId(),
                    message.getMessageId()
            );

        } catch (Exception e) {
            log.error(
                    "[FANOUT FAIL] roomId={} messageId={}",
                    message.getRoomId(),
                    message.getMessageId(),
                    e
            );
        }
    }
}
