package io.hyun424.openchat.chat.room.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnMissingBean(KafkaRoomLifecyclePublisher.class)
public class NoopRoomLifecyclePublisher implements RoomLifecyclePublisher {

    @Override
    public void publishRoomEnded(Long roomId, String reason) {
        log.debug("[ROOM LIFECYCLE NOOP] roomId={} reason={}", roomId, reason);
    }
}
