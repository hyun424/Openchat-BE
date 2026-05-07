package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.room.hot.RoomHotState;

class FanoutBatchWindowPolicy {

    private final long batchWindowMillis;
    private final long warmBatchWindowMillis;
    private final long hotBatchWindowMillis;
    private final long superHotBatchWindowMillis;

    FanoutBatchWindowPolicy(long batchWindowMillis,
                            long warmBatchWindowMillis,
                            long hotBatchWindowMillis,
                            long superHotBatchWindowMillis) {
        this.batchWindowMillis = Math.max(1, batchWindowMillis);
        this.warmBatchWindowMillis = Math.max(this.batchWindowMillis, warmBatchWindowMillis);
        this.hotBatchWindowMillis = Math.max(this.warmBatchWindowMillis, hotBatchWindowMillis);
        this.superHotBatchWindowMillis = Math.max(this.hotBatchWindowMillis, superHotBatchWindowMillis);
    }

    long windowFor(RoomHotState state) {
        return switch (state) {
            case WARM -> warmBatchWindowMillis;
            case HOT -> hotBatchWindowMillis;
            case SUPER_HOT -> superHotBatchWindowMillis;
            case NORMAL, WATCHED -> batchWindowMillis;
        };
    }
}
