package io.hyun424.openchat.chat.room.partition.assignment;

import java.time.Instant;
import java.util.Set;

import io.hyun424.openchat.global.role.RuntimeCapability;
import io.hyun424.openchat.global.role.RuntimeRole;

public record RealtimeNode(
        String nodeId,
        String role,
        String wsUrl,
        boolean draining,
        Instant reportedAt,
        Instant expiresAt,
        Set<Integer> subscribedPartitions,
        int openSessions
) {

    public RealtimeNode(String nodeId,
                        String role,
                        String wsUrl,
                        boolean draining,
                        Instant reportedAt,
                        Instant expiresAt,
                        Set<Integer> subscribedPartitions) {
        this(nodeId, role, wsUrl, draining, reportedAt, expiresAt, subscribedPartitions, 0);
    }

    public boolean activeAt(Instant now) {
        return nodeId != null
                && !nodeId.isBlank()
                && wsUrl != null
                && !wsUrl.isBlank()
                && reportedAt != null
                && expiresAt != null
                && expiresAt.isAfter(now)
                && !draining
                && hasRealtimeCapability(role);
    }

    private static boolean hasRealtimeCapability(String role) {
        try {
            return RuntimeRole.parse(role).hasCapability(RuntimeCapability.REALTIME);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
