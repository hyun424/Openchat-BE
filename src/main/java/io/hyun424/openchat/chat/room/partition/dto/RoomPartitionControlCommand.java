package io.hyun424.openchat.chat.room.partition.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public record RoomPartitionControlCommand(
        String type,
        Long roomId,
        Integer partitionId,
        String reason,
        int limit,
        long retryAfterMs,
        long routeVersion,
        long requestedAt
) {

    public static final String TYPE_RECONNECT = "partition.reconnect";
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;
    private static final Set<String> ALLOWED_REASONS = Set.of(
            "scale_down",
            "partition_rebalance",
            "node_drain",
            "deploy",
            "unknown"
    );

    public static RoomPartitionControlCommand reconnect(Long roomId,
                                                        Integer partitionId,
                                                        String reason,
                                                        int limit,
                                                        long retryAfterMs,
                                                        long routeVersion) {
        return new RoomPartitionControlCommand(
                TYPE_RECONNECT,
                roomId,
                partitionId,
                safeReason(reason),
                boundedLimit(limit),
                Math.max(0, retryAfterMs),
                Math.max(0, routeVersion),
                Instant.now().toEpochMilli()
        );
    }

    @JsonIgnore
    public boolean isReconnect() {
        return TYPE_RECONNECT.equals(type);
    }

    @JsonIgnore
    public boolean isValidReconnect() {
        return isReconnect() && roomId != null && partitionId != null;
    }

    public static int boundedLimit(Integer limit) {
        int resolved = limit == null ? DEFAULT_LIMIT : limit;
        return Math.max(1, Math.min(MAX_LIMIT, resolved));
    }

    public static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        String normalized = reason.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_REASONS.contains(normalized) ? normalized : "unknown";
    }
}
