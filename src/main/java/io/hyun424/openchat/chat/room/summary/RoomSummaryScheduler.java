package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.AI_WORKER)
@ConditionalOnProperty(name = "app.room-summary.worker.enabled", havingValue = "true")
public class RoomSummaryScheduler {

    private final RoomSummaryWorker worker;
    private final RoomSummaryJobPlanner planner;

    @Scheduled(fixedDelayString = "${app.room-summary.worker.interval-ms:5000}")
    public void poll() {
        int enqueued = planner.enqueueActiveRoomJobs();
        int processed = worker.runOnce();
        if (enqueued > 0 || processed > 0) {
            log.info("Room summary worker tick enqueued={}, processed={}", enqueued, processed);
        }
    }
}
