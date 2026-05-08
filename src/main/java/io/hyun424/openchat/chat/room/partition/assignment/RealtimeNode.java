package io.hyun424.openchat.chat.room.partition.assignment;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

public record RealtimeNode(
        String nodeId,
        String role,
        String host,
        int port,
        Instant startedAt,
        Instant reportedAt,
        boolean draining
) {
    public RealtimeNode {
        nodeId = normalize(nodeId, "unknown");
        role = normalize(role, "realtime");
        host = normalize(host, "unknown");
        port = Math.max(0, port);
        Instant now = Instant.now();
        startedAt = startedAt == null ? now : startedAt;
        reportedAt = reportedAt == null ? now : reportedAt;
    }

    @JsonIgnore
    public boolean active() {
        return !draining && !nodeId.isBlank();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
