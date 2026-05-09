package io.hyun424.openchat.chat.room.partition.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoomPartitionControlCommandTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reconnectCommandGeneratesTraceableCommandId() {
        RoomPartitionControlCommand command = RoomPartitionControlCommand.reconnect(
                1L,
                2,
                "scale_down",
                100,
                500,
                8
        );

        assertFalse(command.commandId().isBlank());
    }

    @Test
    void nodeReconnectCommandGeneratesTraceableCommandId() {
        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                "node-a",
                "node_drain",
                100,
                500
        );

        assertFalse(command.commandId().isBlank());
    }

    @Test
    void commandIdSurvivesRedisJsonRoundTrip() throws Exception {
        RoomPartitionControlCommand command = RoomPartitionControlCommand.nodeReconnect(
                "node-a",
                "node_drain",
                100,
                500
        );

        RoomPartitionControlCommand decoded = objectMapper.readValue(
                objectMapper.writeValueAsString(command),
                RoomPartitionControlCommand.class
        );

        assertEquals(command.commandId(), decoded.commandId());
    }

    @Test
    void legacyJsonWithoutCommandIdRemainsReadable() throws Exception {
        String legacyJson = """
                {
                  "type": "node.reconnect",
                  "roomId": null,
                  "partitionId": null,
                  "reason": "node_drain",
                  "limit": 100,
                  "retryAfterMs": 500,
                  "routeVersion": 0,
                  "requestedAt": 1770000000000,
                  "nodeId": "node-a"
                }
                """;

        RoomPartitionControlCommand decoded = objectMapper.readValue(legacyJson, RoomPartitionControlCommand.class);

        assertEquals("node-a", decoded.nodeId());
        assertNull(decoded.commandId());
    }
}
