package io.hyun424.openchat.chat.room.partition.assignment;

import java.util.List;

class NoopRealtimeNodeRegistry implements RealtimeNodeRegistry {

    @Override
    public boolean heartbeat(RealtimeNode node) {
        return false;
    }

    @Override
    public List<RealtimeNode> activeNodes() {
        return List.of();
    }
}
