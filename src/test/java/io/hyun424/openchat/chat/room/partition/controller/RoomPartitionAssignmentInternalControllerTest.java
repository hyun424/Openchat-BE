package io.hyun424.openchat.chat.room.partition.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hyun424.openchat.chat.room.partition.assignment.RealtimeNodeDrainService;
import io.hyun424.openchat.chat.room.partition.assignment.RealtimeNodeRegistry;
import io.hyun424.openchat.chat.room.partition.assignment.RoomPartitionAssignmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomPartitionAssignmentInternalControllerTest {

    private RealtimeNodeRegistry registry;
    private RoomPartitionAssignmentService assignmentService;
    private RealtimeNodeDrainService nodeDrainService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = mock(RealtimeNodeRegistry.class);
        assignmentService = mock(RoomPartitionAssignmentService.class);
        nodeDrainService = mock(RealtimeNodeDrainService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RoomPartitionAssignmentInternalController(registry, assignmentService, nodeDrainService))
                .build();
    }

    @Test
    void drainResponseIncludesRetryContractFields() throws Exception {
        when(nodeDrainService.startDrain("node-a", 10, 250L)).thenReturn(new RealtimeNodeDrainService.NodeDrainResult(
                "node-a",
                "node_drain:node-a",
                true,
                "reconnect_published",
                true,
                10,
                12,
                "node_drain",
                true,
                "poll_status",
                "ready"
        ));

        mockMvc.perform(post("/api/internal/room-partition/nodes/node-a/drain")
                        .queryParam("limit", "10")
                        .queryParam("retryAfterMs", "250"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("node-a"))
                .andExpect(jsonPath("$.status").value("reconnect_published"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.nextAction").value("poll_status"))
                .andExpect(jsonPath("$.readinessReason").value("ready"));

        verify(nodeDrainService).startDrain("node-a", 10, 250L);
    }

    @Test
    void drainStatusUsesObservationEndpointWithoutStartingDrain() throws Exception {
        when(nodeDrainService.drainStatus("node-a")).thenReturn(new RealtimeNodeDrainService.NodeDrainResult(
                "node-a",
                "node_drain:node-a",
                true,
                "sessions_remaining",
                false,
                0,
                12,
                "node_drain",
                true,
                "retry_reconnect",
                "ready"
        ));

        mockMvc.perform(get("/api/internal/room-partition/nodes/node-a/drain/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("node-a"))
                .andExpect(jsonPath("$.status").value("sessions_remaining"))
                .andExpect(jsonPath("$.reconnectPublished").value(false))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.nextAction").value("retry_reconnect"))
                .andExpect(jsonPath("$.readinessReason").value("ready"));

        verify(nodeDrainService).drainStatus("node-a");
    }
}
