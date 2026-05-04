package io.hyun424.openchat.chat.room.hot;

final class SlidingWindowCounter {

    private final long[] bucketSeconds;
    private final long[] counts;

    SlidingWindowCounter(int windowSeconds) {
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds must be at least 1");
        }
        this.bucketSeconds = new long[windowSeconds];
        this.counts = new long[windowSeconds];
    }

    synchronized void add(long nowMillis, long delta) {
        long second = nowMillis / 1000;
        int index = (int) (second % counts.length);
        if (bucketSeconds[index] != second) {
            bucketSeconds[index] = second;
            counts[index] = 0;
        }
        counts[index] += delta;
    }

    synchronized long count(long nowMillis) {
        long currentSecond = nowMillis / 1000;
        long minSecond = currentSecond - counts.length + 1;
        long total = 0;
        for (int i = 0; i < counts.length; i++) {
            if (bucketSeconds[i] >= minSecond && bucketSeconds[i] <= currentSecond) {
                total += counts[i];
            }
        }
        return total;
    }

    long ratePerSecond(long nowMillis) {
        long total = count(nowMillis);
        if (total == 0) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil((double) total / counts.length));
    }
}
