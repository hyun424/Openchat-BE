package io.hyun424.openchat.chat.room.hot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LatencySampleWindow {

    private final int windowSeconds;
    private final int maxSamples;
    private final List<Sample> samples = new ArrayList<>();

    LatencySampleWindow(int windowSeconds, int maxSamples) {
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds must be at least 1");
        }
        if (maxSamples < 1) {
            throw new IllegalArgumentException("maxSamples must be at least 1");
        }
        this.windowSeconds = windowSeconds;
        this.maxSamples = maxSamples;
    }

    synchronized void record(long nowMillis, long valueMillis) {
        evictExpired(nowMillis);
        if (samples.size() >= maxSamples) {
            samples.remove(0);
        }
        samples.add(new Sample(nowMillis, Math.max(0, valueMillis)));
    }

    synchronized long p95(long nowMillis) {
        evictExpired(nowMillis);
        if (samples.isEmpty()) {
            return 0;
        }

        List<Long> values = samples.stream()
                .map(Sample::valueMillis)
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = (int) Math.ceil(values.size() * 0.95) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private void evictExpired(long nowMillis) {
        long minMillis = nowMillis - windowSeconds * 1000L;
        samples.removeIf(sample -> sample.timestampMillis() < minMillis);
    }

    private record Sample(long timestampMillis, long valueMillis) {
    }
}
