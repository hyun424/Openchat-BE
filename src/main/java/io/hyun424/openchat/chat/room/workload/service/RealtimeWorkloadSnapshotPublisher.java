package io.hyun424.openchat.chat.room.workload.service;

import io.hyun424.openchat.chat.room.workload.infra.RealtimeWorkloadSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnBean(RealtimeWorkloadSnapshotRepository.class)
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api' && '${app.realtime-workload.publish-enabled:true}'.toLowerCase() == 'true'")
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
