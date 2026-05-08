package io.hyun424.openchat.chat.room.partition.assignment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.room-partition.assignment.enabled", havingValue = "true")
public class RealtimeNodeHeartbeatScheduler {

    private final RealtimeNodeHeartbeatService heartbeatService;

    public RealtimeNodeHeartbeatScheduler(RealtimeNodeHeartbeatService heartbeatService) {
        this.heartbeatService = heartbeatService;
    }

    @Scheduled(fixedDelayString = "${app.room-partition.assignment.heartbeat-interval-ms:5000}")
    public void heartbeat() {
        heartbeatService.publishHeartbeat();
    }
}
