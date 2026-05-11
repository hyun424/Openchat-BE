package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.room.workload.infra.RealtimeWorkloadSnapshotRepository;
import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.REALTIME)
@ConditionalOnBean(RealtimeWorkloadSnapshotRepository.class)
@ConditionalOnProperty(name = "app.realtime-workload.publish-enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeWorkloadSnapshotPublisher {

    private final LocalRealtimeWorkloadSnapshotFactory snapshotFactory;
    private final RealtimeWorkloadSnapshotRepository snapshotRepository;

    public RealtimeWorkloadSnapshotPublisher(LocalRealtimeWorkloadSnapshotFactory snapshotFactory,
                                             RealtimeWorkloadSnapshotRepository snapshotRepository) {
        this.snapshotFactory = snapshotFactory;
        this.snapshotRepository = snapshotRepository;
    }

    @Scheduled(fixedDelayString = "${app.realtime-workload.publish-interval-ms:5000}")
    public void publish() {
        snapshotRepository.save(snapshotFactory.create(System.currentTimeMillis()));
    }
}
