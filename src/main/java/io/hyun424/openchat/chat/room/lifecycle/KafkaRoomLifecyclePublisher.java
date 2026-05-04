package io.hyun424.openchat.chat.room.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaRoomLifecyclePublisher implements RoomLifecyclePublisher {

    public static final String ROOM_LIFECYCLE_TOPIC = "room-lifecycle";
    private static final String TYPE_ROOM_ENDED = "ROOM_ENDED";

    private final KafkaTemplate<?, ?> kafkaTemplate;

    @Value("${app.instance-id:local}")
    private String instanceId;

    public KafkaRoomLifecyclePublisher(KafkaTemplate<?, ?> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void publishRoomEnded(Long roomId, String reason) {
        RoomLifecycleEvent event = new RoomLifecycleEvent(
                TYPE_ROOM_ENDED,
                roomId,
                reason,
                System.currentTimeMillis()
        );

        try {
            ((KafkaTemplate) kafkaTemplate).send(ROOM_LIFECYCLE_TOPIC, String.valueOf(roomId), event);
            log.debug("[ROOM LIFECYCLE PUB][{}] roomId={} reason={}", instanceId, roomId, reason);
        } catch (Exception e) {
            log.error("[ROOM LIFECYCLE PUB FAIL][{}] roomId={} reason={}", instanceId, roomId, reason, e);
        }
    }
}
