package io.hyun424.openchat.chat.room.partition.controller;

import io.hyun424.openchat.chat.room.partition.service.RoomPartitionReconnectOperations;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionStateOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.TreeSet;

@Validated
@RestController
@RequestMapping("/api/internal/rooms/{roomId}/partitions")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.room-partition.admin-api-enabled", havingValue = "true")
public class RoomPartitionInternalController {

    private static final String DEFAULT_UPDATED_BY = "internal-api";
    private static final String DEFAULT_RECONNECT_REASON = "scale_down";
    private static final long DEFAULT_RETRY_AFTER_MS = 500L;

    private final RoomPartitionStateOperations stateOperations;
    private final RoomPartitionReconnectOperations reconnectOperations;

    @PostMapping("/scale-up")
    public ResponseEntity<PartitionAdminResponse> scaleUp(
            @PathVariable @Positive Long roomId,
            @Valid @RequestBody ScaleUpRequest request
    ) {
        stateOperations.scaleUp(roomId, request.targetPartitionCount(), updatedBy(request.updatedBy()));
        return ResponseEntity.ok(PartitionAdminResponse.accepted(roomId, "scale-up"));
    }

    @PostMapping("/drain")
    public ResponseEntity<PartitionAdminResponse> startDrain(
            @PathVariable @Positive Long roomId,
            @Valid @RequestBody DrainRequest request
    ) {
        stateOperations.startDrain(
                roomId,
                request.targetPartitionCount(),
                new TreeSet<>(request.drainingPartitions()),
                updatedBy(request.updatedBy())
        );
        return ResponseEntity.ok(PartitionAdminResponse.accepted(roomId, "drain"));
    }

    @PostMapping("/drain/reconnect")
    public ResponseEntity<ReconnectAdminResponse> reconnectDraining(
            @PathVariable @Positive Long roomId,
            @Valid @RequestBody ReconnectRequest request
    ) {
        RoomPartitionReconnectOperations.RoomPartitionReconnectResult result = reconnectOperations.reconnectDraining(
                roomId,
                reason(request.reason()),
                retryAfterMs(request.retryAfterMs()),
                request.limit()
        );
        return ResponseEntity.ok(new ReconnectAdminResponse(
                roomId,
                "drain-reconnect",
                result.accepted(),
                result.publishedCommands()
        ));
    }

    @PostMapping("/drain/complete")
    public ResponseEntity<PartitionAdminResponse> completeDrain(
            @PathVariable @Positive Long roomId,
            @Valid @RequestBody DrainCompleteRequest request
    ) {
        stateOperations.completeDrain(roomId, updatedBy(request.updatedBy()));
        return ResponseEntity.ok(PartitionAdminResponse.accepted(roomId, "drain-complete"));
    }

    private String updatedBy(String value) {
        return value == null || value.isBlank() ? DEFAULT_UPDATED_BY : value.trim();
    }

    private String reason(String value) {
        return value == null || value.isBlank() ? DEFAULT_RECONNECT_REASON : value.trim();
    }

    private long retryAfterMs(Long value) {
        return value == null ? DEFAULT_RETRY_AFTER_MS : value;
    }

    public record ScaleUpRequest(
            @Min(2) int targetPartitionCount,
            String updatedBy
    ) {
    }

    public record DrainRequest(
            @Min(1) int targetPartitionCount,
            @NotEmpty Set<@Min(0) Integer> drainingPartitions,
            String updatedBy
    ) {
    }

    public record ReconnectRequest(
            String reason,
            @Min(0) Long retryAfterMs,
            @Min(1) Integer limit
    ) {
    }

    public record DrainCompleteRequest(
            String updatedBy
    ) {
    }

    public record PartitionAdminResponse(
            Long roomId,
            String operation,
            boolean accepted
    ) {
        static PartitionAdminResponse accepted(Long roomId, String operation) {
            return new PartitionAdminResponse(roomId, operation, true);
        }
    }

    public record ReconnectAdminResponse(
            Long roomId,
            String operation,
            boolean accepted,
            int publishedCommands
    ) {
    }
}
