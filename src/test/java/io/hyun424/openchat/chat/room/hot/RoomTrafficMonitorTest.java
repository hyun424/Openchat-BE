package io.hyun424.openchat.chat.room.hot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomTrafficMonitorTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RoomTrafficMonitor monitor = new RoomTrafficMonitor(
            null,
            testProperties(),
            now::get,
            false
    );

    @Test
    void inboundMessagePromotesRoomToWatched() {
        monitor.recordInboundMessage(1L);

        monitor.refresh();

        assertEquals(RoomHotState.WATCHED, monitor.state(1L));
        assertEquals(1, monitor.snapshot(1L).inboundMessagesPerSecond());
    }

    @Test
    void mainExposurePromotesRoomToWarmBeforeLagAppears() {
        monitor.markMainExposed(1L, true);

        monitor.refresh();

        assertEquals(RoomHotState.WARM, monitor.state(1L));
    }

    @Test
    void deliveryLagPromotesRoomToHotAndSuperHot() {
        monitor.recordDeliveryLag(1L, now.get() - 150);
        monitor.refresh();

        assertEquals(RoomHotState.HOT, monitor.state(1L));

        monitor.recordDeliveryLag(1L, now.get() - 350);
        monitor.refresh();

        assertEquals(RoomHotState.SUPER_HOT, monitor.state(1L));
    }

    @Test
    void outboundFanoutRateCanPromoteRoomToHot() {
        monitor.recordOutboundFanout(1L, 100_000);

        monitor.refresh();

        assertEquals(RoomHotState.SUPER_HOT, monitor.state(1L));
        assertEquals(10_000, monitor.snapshot(1L).outboundFanoutPerSecond());
    }

    private RoomHotStateProperties testProperties() {
        return new RoomHotStateProperties(
                10,
                128,
                300_000,
                0,
                0,
                50,
                1_000,
                5,
                50,
                5_000,
                30,
                100,
                10_000,
                500,
                300,
                10_000
        );
    }
}
