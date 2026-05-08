package io.hyun424.openchat.chat.room.partition.infra;

import org.springframework.stereotype.Component;

@Component
public class RoomPartitionControlChannelResolver {

    private static final String PREFIX = "openchat:room-partition-control:";
    private static final String PATTERN = PREFIX + "*";
    private static final String NODE_PREFIX = "openchat:room-partition-node-control:";

    public String channel(Long roomId) {
        return PREFIX + roomId;
    }

    public String subscribePattern() {
        return PATTERN;
    }

    public String nodeChannel(String nodeId) {
        return NODE_PREFIX + nodeId;
    }
}
