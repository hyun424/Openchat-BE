package io.hyun424.openchat.chat.room.partition.service;

import io.hyun424.openchat.chat.room.hot.RoomScaleTier;
import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionRoute;
import io.hyun424.openchat.chat.room.partition.metrics.RoomPartitionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomPartitionRoutingServiceTest {

    @Test
    void disabledMode_returnsLegacyRouteWithoutStateLookup() {
        RoomPartitionStateReader stateService = mock(RoomPartitionStateReader.class);
        RoomPartitionRoutingService service = service(false, stateService);

        RoomPartitionRoute route = service.route(1L, "user-1");

        assertFalse(route.partitioned());
        assertEquals(0, route.partitionId());
        assertEquals(1, route.partitionCount());
        assertEquals(0, route.version());
        verify(stateService, never()).partitionCountForRoom(1L);
    }

    @Test
    void enabledMode_usesStateRouteAndIncludesRouteVersionInWsUrl() {
        RoomPartitionStateReader stateService = mock(RoomPartitionStateReader.class);
        when(stateService.partitionCountForRoom(1L)).thenReturn(4);
        when(stateService.routePartition(1L, "user-1")).thenReturn(2);
        when(stateService.versionForRoom(1L)).thenReturn(7);
        RoomPartitionRoutingService service = service(true, stateService);

        RoomPartitionRoute route = service.route(1L, "user-1");

        assertTrue(route.partitioned());
        assertEquals(2, route.partitionId());
        assertEquals(4, route.partitionCount());
        assertEquals(7, route.version());
        assertEquals("/ws/chat?roomId=1&partitionId=2&routeVersion=7", route.wsUrl());
    }

    @Test
    void publishPartitions_usesStateServiceWhenEnabled() {
        RoomPartitionStateReader stateService = mock(RoomPartitionStateReader.class);
        when(stateService.publishPartitions(1L)).thenReturn(java.util.List.of(0, 1, 2));
        RoomPartitionRoutingService service = service(true, stateService);

        assertEquals(java.util.List.of(0, 1, 2), service.publishPartitions(1L));
    }

    private RoomPartitionRoutingService service(boolean enabled, RoomPartitionStateReader stateService) {
        RoomPartitionProperties properties = new RoomPartitionProperties(
                enabled,
                4,
                Set.of(0, 1, 2, 3),
                RoomScaleTier.CRITICAL,
                16
        );
        return new RoomPartitionRoutingService(
                properties,
                stateService,
                new RoomPartitionMetrics(new SimpleMeterRegistry())
        );
    }
}
