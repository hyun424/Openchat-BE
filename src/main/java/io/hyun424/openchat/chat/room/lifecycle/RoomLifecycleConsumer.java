package io.hyun424.openchat.chat.room.lifecycle;

import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class RoomLifecycleConsumer {

    private final RoomSessionRegistry roomSessionRegistry;

    @KafkaListener(topics = KafkaRoomLifecyclePublisher.ROOM_LIFECYCLE_TOPIC)
    public void consume(RoomLifecycleEvent event, Acknowledgment ack) {
        try {
            if (event == null || event.getRoomId() == null) {
                return;
            }
            if (!"ROOM_ENDED".equals(event.getType())) {
                return;
            }
            roomSessionRegistry.closeAllSessionsInRoom(event.getRoomId());
            log.debug("[ROOM LIFECYCLE SUB] roomId={} reason={}", event.getRoomId(), event.getReason());
        } finally {
            ack.acknowledge();
        }
    }
}
