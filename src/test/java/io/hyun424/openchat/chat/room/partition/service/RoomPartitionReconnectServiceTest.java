package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionState;
import io.hyun424.openchat.chat.room.partition.domain.RoomPartitionStatus;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.infra.RoomPartitionControlPublisher;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.policy.RoomPartitionPolicy;
import io.hyun424.openchat.chat.room.partition.repository.RoomPartitionStateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomPartitionReconnectServiceTest {

    @Test
    @DisplayName("draining partition마다 Redis control command를 publish한다")
    void reconnectDraining_publishesCommandPerDrainingPartition() {
        RoomPartitionStateRepository repository = mock(RoomPartitionStateRepository.class);
        RoomPartitionControlPublisher publisher = mock(RoomPartitionControlPublisher.class);
        RoomPartitionPolicy policy = mock(RoomPartitionPolicy.class);
        RoomPartitionReconnectService service = new RoomPartitionReconnectService(
                repository,
                publisher,
                policy,
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
        RoomPartitionState state = new RoomPartitionState(
                1L,
                4,
                8,
                RoomPartitionStatus.DRAINING,
                "1,2",
                Instant.parse("2026-05-07T00:00:00Z"),
                "test"
        );
        when(repository.findById(1L)).thenReturn(Optional.of(state));
        when(policy.drainingPartitions(state)).thenReturn(Set.of(1, 2));
        when(publisher.publish(any())).thenReturn(true);

        RoomPartitionReconnectOperations.RoomPartitionReconnectResult result =
                service.reconnectDraining(1L, "scale_down", 500, 25);

        assertTrue(result.accepted());
        assertEquals(2, result.publishedCommands());
        ArgumentCaptor<RoomPartitionControlCommand> captor = forClass(RoomPartitionControlCommand.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
        assertEquals(java.util.List.of(1, 2), captor.getAllValues().stream()
                .map(RoomPartitionControlCommand::partitionId)
                .sorted()
                .toList());
        assertEquals(25, captor.getAllValues().get(0).limit());
        assertEquals(8L, captor.getAllValues().get(0).routeVersion());
    }

    @Test
    @DisplayName("state가 없으면 command를 publish하지 않는다")
    void reconnectDraining_withoutState_doesNotPublish() {
        RoomPartitionStateRepository repository = mock(RoomPartitionStateRepository.class);
        RoomPartitionControlPublisher publisher = mock(RoomPartitionControlPublisher.class);
        RoomPartitionReconnectService service = new RoomPartitionReconnectService(
                repository,
                publisher,
                mock(RoomPartitionPolicy.class),
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
        when(repository.findById(1L)).thenReturn(Optional.empty());

        RoomPartitionReconnectOperations.RoomPartitionReconnectResult result =
                service.reconnectDraining(1L, "scale_down", 500, null);

        assertFalse(result.accepted());
        assertEquals(0, result.publishedCommands());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("알 수 없는 reconnect reason은 unknown으로 낮은 cardinality를 유지한다")
    void requestReconnect_unknownReason_isNormalized() {
        RoomPartitionStateRepository repository = mock(RoomPartitionStateRepository.class);
        RoomPartitionControlPublisher publisher = mock(RoomPartitionControlPublisher.class);
        RoomPartitionReconnectService service = new RoomPartitionReconnectService(
                repository,
                publisher,
                mock(RoomPartitionPolicy.class),
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
        when(publisher.publish(any())).thenReturn(true);

        service.requestReconnect(1L, 0, "room-1-custom-reason", 100, 500, 2);

        ArgumentCaptor<RoomPartitionControlCommand> captor = forClass(RoomPartitionControlCommand.class);
        verify(publisher).publish(captor.capture());
        assertEquals("unknown", captor.getValue().reason());
    }
}
