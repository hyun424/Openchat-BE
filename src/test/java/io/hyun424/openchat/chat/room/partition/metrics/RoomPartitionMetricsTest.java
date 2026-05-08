package io.hyun424.openchat.chat.room.partition.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomPartitionMetricsTest {

    @Test
    void reconnectControlSentSuccessCountIncreasesOnlyForSuccess() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RoomPartitionMetrics metrics = new RoomPartitionMetrics(registry);

        metrics.recordReconnectControlSent("scale_down", "success");
        metrics.recordReconnectControlSent("scale_down", "failed");
        metrics.recordReconnectControlSent("deploy", "success");

        assertEquals(2, metrics.reconnectControlSentSuccessCount());
        assertEquals(1, registry.get("openchat_room_reconnect_control_sent_total")
                .tag("reason", "scale_down")
                .tag("result", "failed")
                .counter()
                .count());
    }
}
