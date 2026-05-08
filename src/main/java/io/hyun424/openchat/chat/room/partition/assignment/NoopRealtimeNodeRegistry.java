package io.hyun424.openchat.chat.room.partition.assignment;

import java.util.List;
import java.util.Set;

final class NoopRealtimeNodeRegistry implements RealtimeNodeRegistry {

    @Override
    public void heartbeat(RealtimeNode node) {
    }

    @Override
    public List<RealtimeNode> nodes() {
        return List.of();
    }

    @Override
    public Set<String> drainingNodeIds() {
        return Set.of();
    }

    @Override
    public void markDraining(String nodeId, boolean draining) {
    }
}
