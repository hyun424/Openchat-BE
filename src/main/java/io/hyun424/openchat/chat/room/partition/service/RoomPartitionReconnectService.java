package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.infra.RoomPartitionControlPublisher;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoomPartitionReconnectService implements RoomPartitionReconnectOperations {

    private final RoomPartitionStateRepository stateRepository;
    private final RoomPartitionControlPublisher controlPublisher;
    private final RoomPartitionMetrics metrics;

    @Autowired
    public RoomPartitionReconnectService(RoomPartitionStateRepository stateRepository,
                                         RoomPartitionControlPublisher controlPublisher,
                                         RoomPartitionMetrics metrics) {
        this.stateRepository = stateRepository;
        this.controlPublisher = controlPublisher;
        this.metrics = metrics;
    }

    @Override
    public RoomPartitionReconnectResult reconnectDraining(Long roomId,
                                                          String reason,
                                                          long retryAfterMs,
                                                          Integer limit) {
        String safeReason = RoomPartitionControlCommand.safeReason(reason);
        metrics.recordReconnectRequested(safeReason);

        Optional<RoomPartitionState> state = stateRepository.findById(roomId);
        if (state.isEmpty()) {
            return new RoomPartitionReconnectResult(roomId, safeReason, false, 0);
        }

        int publishedCommands = 0;
        for (Integer partitionId : drainingPartitions(state.get())) {
            boolean published = requestReconnect(
                    roomId,
                    partitionId,
                    safeReason,
                    limit,
                    retryAfterMs,
                    state.get().getVersion()
            );
            if (published) {
                publishedCommands++;
            }
        }
        return new RoomPartitionReconnectResult(roomId, safeReason, publishedCommands > 0, publishedCommands);
    }

    public boolean requestReconnect(Long roomId,
                                    Integer partitionId,
                                    String reason,
                                    Integer limit,
                                    long retryAfterMs,
                                    long routeVersion) {
        RoomPartitionControlCommand command = RoomPartitionControlCommand.reconnect(
                roomId,
                partitionId,
                reason,
                RoomPartitionControlCommand.boundedLimit(limit),
                retryAfterMs,
                routeVersion
        );
        return controlPublisher.publish(command);
    }

    private Set<Integer> drainingPartitions(RoomPartitionState state) {
        String raw = state.getDrainingPartitions();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        return Math.floorMod(Integer.parseInt(value), Math.max(1, state.getPartitionCount()));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    public record RoomPartitionReconnectResult(
            Long roomId,
            String reason,
            boolean accepted,
            int publishedCommands
    ) {
    }
}
