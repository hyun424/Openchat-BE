package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.room.hot.RoomHotState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FanoutBatchWindowPolicyTest {

    @Test
    void windowFor_usesStateSpecificWindows() {
        FanoutBatchWindowPolicy policy = new FanoutBatchWindowPolicy(20, 30, 100, 250);

        assertEquals(20, policy.windowFor(RoomHotState.NORMAL));
        assertEquals(20, policy.windowFor(RoomHotState.WATCHED));
        assertEquals(30, policy.windowFor(RoomHotState.WARM));
        assertEquals(100, policy.windowFor(RoomHotState.HOT));
        assertEquals(250, policy.windowFor(RoomHotState.SUPER_HOT));
    }

    @Test
    void constructor_keepsWindowsMonotonic() {
        FanoutBatchWindowPolicy policy = new FanoutBatchWindowPolicy(20, 1, 1, 1);

        assertEquals(20, policy.windowFor(RoomHotState.WARM));
        assertEquals(20, policy.windowFor(RoomHotState.HOT));
        assertEquals(20, policy.windowFor(RoomHotState.SUPER_HOT));
    }
}
