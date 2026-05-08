package io.hyun424.openchat.chat.room.partition.assignment;

import java.util.List;
import java.util.Set;

public interface RealtimeNodeRegistry {

    void heartbeat(RealtimeNode node);

    List<RealtimeNode> nodes();

    Set<String> drainingNodeIds();

    void markDraining(String nodeId, boolean draining);
}
