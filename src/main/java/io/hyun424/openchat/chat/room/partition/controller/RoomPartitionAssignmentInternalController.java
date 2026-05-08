package io.hyun424.openchat.chat.room.partition.controller;

import java.util.List;
import java.util.Map;

import io.hyun424.openchat.chat.room.partition.assignment.RealtimeNode;
import io.hyun424.openchat.chat.room.partition.assignment.RealtimeNodeDrainService;
import io.hyun424.openchat.chat.room.partition.assignment.RealtimeNodeRegistry;
import io.hyun424.openchat.chat.room.partition.assignment.RoomPartitionAssignment;
import io.hyun424.openchat.chat.room.partition.assignment.RoomPartitionAssignmentService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/internal/room-partition")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {
        "app.room-partition.admin-api-enabled",
        "app.room-partition.assignment.enabled"
}, havingValue = "true")
public class RoomPartitionAssignmentInternalController {

    private final RealtimeNodeRegistry registry;
    private final RoomPartitionAssignmentService assignmentService;
    private final RealtimeNodeDrainService nodeDrainService;

    @GetMapping("/nodes")
    public ResponseEntity<NodeRegistryResponse> nodes() {
        return ResponseEntity.ok(new NodeRegistryResponse(
                registry.nodes(),
                assignmentService.activeNodes()
        ));
    }

    @GetMapping("/assignments")
    public ResponseEntity<AssignmentResponse> assignments(
            @RequestParam(defaultValue = "1") @Min(1) int partitionCount
    ) {
        Map<Integer, RoomPartitionAssignment> assignments = assignmentService.assignments(partitionCount);
        return ResponseEntity.ok(new AssignmentResponse(partitionCount, assignments));
    }

    @PostMapping("/nodes/{nodeId}/drain")
    public ResponseEntity<NodeDrainResponse> markDraining(
            @PathVariable String nodeId,
            @RequestParam(required = false) @Min(1) Integer limit,
            @RequestParam(required = false) @Min(0) Long retryAfterMs
    ) {
        RealtimeNodeDrainService.NodeDrainResult result = nodeDrainService.startDrain(nodeId, limit, retryAfterMs);
        return ResponseEntity.ok(new NodeDrainResponse(result.nodeId(), result.draining(), result.reconnectPublished()));
    }

    @PostMapping("/nodes/{nodeId}/undrain")
    public ResponseEntity<NodeDrainResponse> unmarkDraining(@PathVariable String nodeId) {
        RealtimeNodeDrainService.NodeDrainResult result = nodeDrainService.stopDrain(nodeId);
        return ResponseEntity.ok(new NodeDrainResponse(result.nodeId(), result.draining(), result.reconnectPublished()));
    }

    public record NodeRegistryResponse(
            List<RealtimeNode> nodes,
            List<RealtimeNode> activeNodes
    ) {
    }

    public record AssignmentResponse(
            int partitionCount,
            Map<Integer, RoomPartitionAssignment> assignments
    ) {
    }

    public record NodeDrainResponse(
            String nodeId,
            boolean draining,
            boolean reconnectPublished
    ) {
    }
}
