package io.hyun424.openchat.chat.room.partition.assignment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RealtimeNodeRegistryPropertiesTest {

    @Test
    void defaultsAreDisabledAndNormalizeLowerBounds() {
        RealtimeNodeRegistryProperties properties = new RealtimeNodeRegistryProperties(
                false,
                "node-1",
                "",
                "host-a",
                -1,
                1,
                1
        );

        assertFalse(properties.enabled());
        assertEquals("node-1", properties.nodeId());
        assertEquals("realtime", properties.role());
        assertEquals("host-a", properties.host());
        assertEquals(0, properties.port());
        assertEquals(1_000, properties.heartbeatIntervalMillis());
        assertEquals(2_000, properties.retentionMillis());
    }
}
