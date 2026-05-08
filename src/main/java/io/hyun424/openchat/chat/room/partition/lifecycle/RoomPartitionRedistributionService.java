package io.hyun424.openchat.chat.room.partition.lifecycle;

import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionReconnectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.IntStream;

@Slf4j
@Service
public class RoomPartitionRedistributionService {

    private static final String OPERATION = "redistribute";
    private static final String REASON = "partition_rebalance";

    private final RoomPartitionReconnectService reconnectService;
    private final RoomPartitionLifecycleProperties properties;
    private final RoomPartitionMetrics metrics;

    public RoomPartitionRedistributionService(RoomPartitionReconnectService reconnectService,
                                              RoomPartitionLifecycleProperties properties,
                                              RoomPartitionMetrics metrics) {
        this.reconnectService = reconnectService;
        this.properties = properties;
        this.metrics = metrics;
    }

    public RedistributionResult redistribute(Long roomId,
                                             int currentPartitions,
                                             int targetPartitions,
                                             long routeVersion) {
        if (!properties.redistribution().enabled()) {
            record("skip_disabled");
            return new RedistributionResult(false, 0, 0);
        }
        if (targetPartitions <= currentPartitions || currentPartitions <= 0) {
            record("skip_no_target");
            return new RedistributionResult(false, 0, 0);
        }

        int attempted = Math.max(0, currentPartitions);
        int published = 0;
        for (int partitionId : IntStream.range(0, currentPartitions).toArray()) {
            try {
                boolean accepted = reconnectService.requestReconnect(
                        roomId,
                        partitionId,
                        REASON,
                        properties.redistribution().limitPerPartition(),
                        properties.redistribution().retryAfterMillis(),
                        routeVersion
                );
                if (accepted) {
                    published++;
                }
            } catch (RuntimeException e) {
                log.warn("partition redistribution publish failed roomId={} partitionId={} targetPartitions={} routeVersion={}",
                        roomId, partitionId, targetPartitions, routeVersion, e);
            }
        }

        record(published == attempted ? "success" : "publish_failed");
        log.info("partition redistribution requested roomId={} currentPartitions={} targetPartitions={} attempted={} published={}",
                roomId, currentPartitions, targetPartitions, attempted, published);
        return new RedistributionResult(published > 0, attempted, published);
    }

    private void record(String result) {
        metrics.recordLifecycleEvent(OPERATION, result);
    }

    public record RedistributionResult(boolean accepted, int attemptedCommands, int publishedCommands) {
    }
}
