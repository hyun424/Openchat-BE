package io.hyun424.openchat.chat.room.partition.lifecycle;

import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionReconnectService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RoomPartitionRedistributionServiceTest {

    @Test
    void reconnectsOldPartitionsWhenScalingFromOneToFour() {
        RoomPartitionReconnectService reconnectService = mock(RoomPartitionReconnectService.class);
        doReturn(true).when(reconnectService).requestReconnect(1L, 0, "partition_rebalance", 50, 500L, 2L);
        RoomPartitionRedistributionService service = service(reconnectService, true);

        var result = service.redistribute(1L, 1, 4, 2L);

        assertEquals(1, result.attemptedCommands());
        assertEquals(1, result.publishedCommands());
        verify(reconnectService).requestReconnect(1L, 0, "partition_rebalance", 50, 500L, 2L);
    }

    @Test
    void reconnectsAllOldPartitionsWhenScalingFromTwoToFour() {
        RoomPartitionReconnectService reconnectService = mock(RoomPartitionReconnectService.class);
        doReturn(true).when(reconnectService).requestReconnect(1L, 0, "partition_rebalance", 50, 500L, 3L);
        doReturn(true).when(reconnectService).requestReconnect(1L, 1, "partition_rebalance", 50, 500L, 3L);
        RoomPartitionRedistributionService service = service(reconnectService, true);

        var result = service.redistribute(1L, 2, 4, 3L);

        assertEquals(2, result.publishedCommands());
        verify(reconnectService).requestReconnect(1L, 0, "partition_rebalance", 50, 500L, 3L);
        verify(reconnectService).requestReconnect(1L, 1, "partition_rebalance", 50, 500L, 3L);
    }

    @Test
    void disabledRedistributionDoesNotPublish() {
        RoomPartitionReconnectService reconnectService = mock(RoomPartitionReconnectService.class);
        RoomPartitionRedistributionService service = service(reconnectService, false);

        var result = service.redistribute(1L, 1, 4, 2L);

        assertEquals(0, result.attemptedCommands());
        verifyNoInteractions(reconnectService);
    }

    @Test
    void publishFailureDoesNotThrow() {
        RoomPartitionReconnectService reconnectService = mock(RoomPartitionReconnectService.class);
        doReturn(false).when(reconnectService).requestReconnect(1L, 0, "partition_rebalance", 50, 500L, 2L);
        RoomPartitionRedistributionService service = service(reconnectService, true);

        var result = service.redistribute(1L, 1, 4, 2L);

        assertEquals(1, result.attemptedCommands());
        assertEquals(0, result.publishedCommands());
    }

    private RoomPartitionRedistributionService service(RoomPartitionReconnectService reconnectService, boolean enabled) {
        return new RoomPartitionRedistributionService(
                reconnectService,
                properties(enabled),
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
    }

    private RoomPartitionLifecycleProperties properties(boolean redistributionEnabled) {
        return new RoomPartitionLifecycleProperties(
                true,
                5_000,
                15_000,
                1,
                "auto-partition-lifecycle",
                new RoomPartitionLifecycleProperties.ScaleUp(true, 1_000, 1, 0),
                new RoomPartitionLifecycleProperties.ScaleDown(true, 1_000, 1, 0, 0, true),
                new RoomPartitionLifecycleProperties.Redistribution(redistributionEnabled, 50, 500),
                new RoomPartitionLifecycleProperties.Drain(1, 50, 500)
        );
    }
}
