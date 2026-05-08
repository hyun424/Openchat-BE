package io.hyun424.openchat.chat.room.partition.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomPartitionLifecyclePropertiesTest {

    @Test
    void defaultsAreProductionDisabled() {
        RoomPartitionLifecycleProperties properties = new RoomPartitionLifecycleProperties(
                false,
                5_000,
                15_000,
                1,
                "auto-partition-lifecycle",
                null,
                null,
                null,
                null
        );

        assertFalse(properties.enabled());
        assertTrue(properties.scaleUp().enabled());
        assertTrue(properties.scaleDown().enabled());
        assertTrue(properties.redistribution().enabled());
        assertEquals("auto-partition-lifecycle", properties.updatedBy());
    }

    @Test
    void normalizesLowerBounds() {
        RoomPartitionLifecycleProperties properties = new RoomPartitionLifecycleProperties(
                true,
                1,
                1,
                0,
                "",
                new RoomPartitionLifecycleProperties.ScaleUp(true, 1, 0, -1),
                new RoomPartitionLifecycleProperties.ScaleDown(true, 1, 0, -1, -1, true),
                new RoomPartitionLifecycleProperties.Redistribution(true, 0, -1),
                new RoomPartitionLifecycleProperties.Drain(0, 0, -1)
        );

        assertEquals(1_000, properties.intervalMillis());
        assertEquals(1_000, properties.leaseTtlMillis());
        assertEquals(1, properties.maxActionsPerRun());
        assertEquals(1_000, properties.scaleUp().stableWindowMillis());
        assertEquals(1, properties.scaleUp().minObservations());
        assertEquals(0, properties.scaleUp().cooldownMillis());
        assertEquals(1_000, properties.scaleDown().stableWindowMillis());
        assertEquals(1, properties.scaleDown().minObservations());
        assertEquals(0, properties.scaleDown().cooldownMillis());
        assertEquals(0, properties.scaleDown().minPartitionAgeMillis());
        assertEquals(1, properties.redistribution().limitPerPartition());
        assertEquals(0, properties.redistribution().retryAfterMillis());
        assertEquals(1, properties.drain().completeEmptyObservations());
        assertEquals(1, properties.drain().reconnectLimitPerPartition());
    }
}
