package io.hyun424.openchat.chat.room.hot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class RoomTrafficStats {

    private final Long roomId;
    private final SlidingWindowCounter joins;
    private final SlidingWindowCounter inboundMessages;
    private final SlidingWindowCounter outboundFanout;
    private final LatencySampleWindow deliveryLag;
    private final LatencySampleWindow laneQueueWait;
    private final AtomicInteger connectedSessions = new AtomicInteger(0);
    private final AtomicLong lastActivityMillis = new AtomicLong(0);
    private volatile boolean mainExposed;
    private volatile RoomHotState state = RoomHotState.NORMAL;
    private volatile long lastStateChangedMillis;
    private volatile long lastCandidateChangedMillis;
    private volatile RoomHotState lastCandidateState = RoomHotState.NORMAL;

    RoomTrafficStats(Long roomId, int windowSeconds, int maxLatencySamples, long nowMillis) {
        this.roomId = roomId;
        this.joins = new SlidingWindowCounter(windowSeconds);
        this.inboundMessages = new SlidingWindowCounter(windowSeconds);
        this.outboundFanout = new SlidingWindowCounter(windowSeconds);
        this.deliveryLag = new LatencySampleWindow(windowSeconds, maxLatencySamples);
        this.laneQueueWait = new LatencySampleWindow(windowSeconds, maxLatencySamples);
        this.lastActivityMillis.set(nowMillis);
        this.lastStateChangedMillis = nowMillis;
        this.lastCandidateChangedMillis = nowMillis;
    }

    void recordJoin(long nowMillis, int sessionCount) {
        connectedSessions.set(Math.max(0, sessionCount));
        joins.add(nowMillis, 1);
        markActive(nowMillis);
    }

    void recordLeave(long nowMillis, int sessionCount) {
        connectedSessions.set(Math.max(0, sessionCount));
        markActive(nowMillis);
    }

    void recordInbound(long nowMillis) {
        inboundMessages.add(nowMillis, 1);
        markActive(nowMillis);
    }

    void recordOutboundFanout(long nowMillis, int recipientCount) {
        if (recipientCount > 0) {
            outboundFanout.add(nowMillis, recipientCount);
        }
        markActive(nowMillis);
    }

    void recordDeliveryLag(long nowMillis, long lagMillis) {
        deliveryLag.record(nowMillis, lagMillis);
        markActive(nowMillis);
    }

    void recordLaneQueueWait(long nowMillis, long waitMillis) {
        laneQueueWait.record(nowMillis, waitMillis);
        markActive(nowMillis);
    }

    void markMainExposed(long nowMillis, boolean mainExposed) {
        this.mainExposed = mainExposed;
        markActive(nowMillis);
    }

    RoomTrafficSnapshot snapshot(long nowMillis) {
        return new RoomTrafficSnapshot(
                roomId,
                connectedSessions.get(),
                joins.ratePerSecond(nowMillis),
                inboundMessages.ratePerSecond(nowMillis),
                outboundFanout.ratePerSecond(nowMillis),
                deliveryLag.p95(nowMillis),
                laneQueueWait.p95(nowMillis),
                state
        );
    }

    boolean isInactive(long nowMillis, long inactiveTtlMillis) {
        return connectedSessions.get() == 0 && nowMillis - lastActivityMillis.get() > inactiveTtlMillis;
    }

    boolean isMainExposed() {
        return mainExposed;
    }

    RoomHotState state() {
        return state;
    }

    void transitionTo(RoomHotState nextState, long nowMillis) {
        if (state == nextState) {
            return;
        }
        state = nextState;
        lastStateChangedMillis = nowMillis;
        lastCandidateState = nextState;
        lastCandidateChangedMillis = nowMillis;
    }

    long millisSinceStateChange(long nowMillis) {
        return nowMillis - lastStateChangedMillis;
    }

    boolean candidateStable(RoomHotState candidate, long nowMillis, long requiredMillis) {
        if (lastCandidateState != candidate) {
            lastCandidateState = candidate;
            lastCandidateChangedMillis = nowMillis;
            return requiredMillis <= 0;
        }
        return nowMillis - lastCandidateChangedMillis >= requiredMillis;
    }

    private void markActive(long nowMillis) {
        lastActivityMillis.set(nowMillis);
    }
}
