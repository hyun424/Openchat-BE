package io.hyun424.openchat.chat.room.partition.assignment;

import java.util.List;

public interface RealtimeNodeRegistry {

    boolean heartbeat(RealtimeNode node);

    List<RealtimeNode> activeNodes();
}
