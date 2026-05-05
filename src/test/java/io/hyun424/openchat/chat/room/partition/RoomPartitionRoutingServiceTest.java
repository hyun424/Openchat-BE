package io.hyun424.openchat.chat.room.partition;

import io.hyun424.openchat.chat.room.hot.RoomHotState;
import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.hot.RoomTrafficMonitor;
import io.hyun424.openchat.chat.room.hot.RoomTrafficSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomPartitionRoutingServiceTest {

    @Test
    void disabledMode_returnsLegacyRoute() {
        RoomTrafficMonitor monitor = mock(RoomTrafficMonitor.class);
        when(monitor.snapshot(1L)).thenReturn(snapshot(RoomScaleTier.CRITICAL, 8));
        RoomPartitionRoutingService service = service(false, 8, RoomScaleTier.CRITICAL, monitor);

        RoomPartitionRoute route = service.route(1L, "user-1");

        assertFalse(route.partitioned());
        assertEquals(0, route.partitionId());
        assertEquals(1, route.partitionCount());
    }

    @Test
    void criticalRoom_usesConfiguredPartitionLimit() {
        RoomTrafficMonitor monitor = mock(RoomTrafficMonitor.class);
        when(monitor.snapshot(1L)).thenReturn(snapshot(RoomScaleTier.CRITICAL, 8));
        RoomPartitionRoutingService service = service(true, 4, RoomScaleTier.CRITICAL, monitor);

        RoomPartitionRoute route = service.route(1L, "user-1");

        assertTrue(route.partitioned());
        assertEquals(4, route.partitionCount());
        assertTrue(route.partitionId() >= 0 && route.partitionId() < 4);
    }

    @Test
    void sameUser_getsStablePartition() {
        RoomTrafficMonitor monitor = mock(RoomTrafficMonitor.class);
        when(monitor.snapshot(1L)).thenReturn(snapshot(RoomScaleTier.CRITICAL, 4));
        RoomPartitionRoutingService service = service(true, 4, RoomScaleTier.CRITICAL, monitor);

        int first = service.route(1L, "user-1").partitionId();
        int second = service.route(1L, "user-1").partitionId();

        assertEquals(first, second);
    }

    @Test
    void belowThreshold_doesNotPartition() {
        RoomTrafficMonitor monitor = mock(RoomTrafficMonitor.class);
        when(monitor.snapshot(1L)).thenReturn(snapshot(RoomScaleTier.HOT, 4));
        RoomPartitionRoutingService service = service(true, 4, RoomScaleTier.CRITICAL, monitor);

        assertFalse(service.route(1L, "user-1").partitioned());
        assertTrue(service.publishPartitions(1L).isEmpty());
    }

    @Test
    void publishPartitions_returnsAllRoomPartitions() {
        RoomTrafficMonitor monitor = mock(RoomTrafficMonitor.class);
        when(monitor.snapshot(1L)).thenReturn(snapshot(RoomScaleTier.CRITICAL, 3));
        RoomPartitionRoutingService service = service(true, 4, RoomScaleTier.CRITICAL, monitor);

        assertEquals(java.util.List.of(0, 1, 2), service.publishPartitions(1L));
    }

    private RoomPartitionRoutingService service(boolean enabled,
                                                int partitionCount,
                                                RoomScaleTier threshold,
                                                RoomTrafficMonitor monitor) {
        RoomPartitionProperties properties = new RoomPartitionProperties(
                enabled,
                partitionCount,
                Set.of(0, 1, 2, 3),
                threshold,
                16
        );
        return new RoomPartitionRoutingService(
                properties,
                monitor,
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
    }

    private RoomTrafficSnapshot snapshot(RoomScaleTier tier, int effectivePartitions) {
        return new RoomTrafficSnapshot(
                1L,
                0,
                0,
                0,
                0,
                0,
                0,
                RoomHotState.NORMAL,
                0,
                0,
                tier,
                effectivePartitions,
                effectivePartitions
        );
    }
}
