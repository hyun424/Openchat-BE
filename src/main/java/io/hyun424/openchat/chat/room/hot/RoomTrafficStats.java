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
    private final AtomicInteger activeSessions = new AtomicInteger(0);
    private final AtomicLong lastActivityMillis = new AtomicLong(0);
    private volatile boolean mainExposed;
    private volatile RoomHotState state = RoomHotState.NORMAL;
    private volatile RoomScaleTier scaleTier = RoomScaleTier.SMALL;
    private volatile long lastStateChangedMillis;
    private volatile long lastCandidateChangedMillis;
    private volatile RoomHotState lastCandidateState = RoomHotState.NORMAL;
    private volatile long lastScaleTierChangedMillis;
    private volatile long lastScaleCandidateChangedMillis;
    private volatile RoomScaleTier lastScaleCandidateTier = RoomScaleTier.SMALL;

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
        this.lastScaleTierChangedMillis = nowMillis;
        this.lastScaleCandidateChangedMillis = nowMillis;
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

    void recordOutboundFanout(long nowMillis, int recipientCount, int activeSessionCount) {
        activeSessions.set(Math.max(0, activeSessionCount));
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

    RoomTrafficSnapshot snapshot(long nowMillis, RoomPartitionAdvisor partitionAdvisor) {
        long inboundMessagesPerSecond = inboundMessages.ratePerSecond(nowMillis);
        long actualDeliveryWorkPerSecond = outboundFanout.ratePerSecond(nowMillis);
        int activeSessionCount = activeSessions.get();
        long conceptualRoomWorkPerSecond = safeMultiply(inboundMessagesPerSecond, activeSessionCount);
        long scaleDecisionWorkPerSecond = Math.max(actualDeliveryWorkPerSecond, conceptualRoomWorkPerSecond);
        long roomWorkPerSecond = actualDeliveryWorkPerSecond;
        return new RoomTrafficSnapshot(
                roomId,
                connectedSessions.get(),
                joins.ratePerSecond(nowMillis),
                inboundMessagesPerSecond,
                actualDeliveryWorkPerSecond,
                deliveryLag.p95(nowMillis),
                laneQueueWait.p95(nowMillis),
                state,
                activeSessionCount,
                roomWorkPerSecond,
                actualDeliveryWorkPerSecond,
                conceptualRoomWorkPerSecond,
                scaleDecisionWorkPerSecond,
                scaleTier,
                partitionAdvisor.recommendedPartitionCount(roomWorkPerSecond, activeSessionCount),
                partitionAdvisor.effectivePartitionCount(roomWorkPerSecond, activeSessionCount)
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

    RoomScaleTier scaleTier() {
        return scaleTier;
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

    void transitionScaleTierTo(RoomScaleTier nextTier, long nowMillis) {
        if (scaleTier == nextTier) {
            return;
        }
        scaleTier = nextTier;
        lastScaleTierChangedMillis = nowMillis;
        lastScaleCandidateTier = nextTier;
        lastScaleCandidateChangedMillis = nowMillis;
    }

    long millisSinceScaleTierChange(long nowMillis) {
        return nowMillis - lastScaleTierChangedMillis;
    }

    boolean scaleCandidateStable(RoomScaleTier candidate, long nowMillis, long requiredMillis) {
        if (lastScaleCandidateTier != candidate) {
            lastScaleCandidateTier = candidate;
            lastScaleCandidateChangedMillis = nowMillis;
            return requiredMillis <= 0;
        }
        return nowMillis - lastScaleCandidateChangedMillis >= requiredMillis;
    }

    private long safeMultiply(long left, int right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        long result = left * (long) right;
        if (result / right != left) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private void markActive(long nowMillis) {
        lastActivityMillis.set(nowMillis);
    }
}
