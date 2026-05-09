package io.hyun424.openchat.chat.room.partition.commandlog;

import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import lombok.Getter;

@Getter
public class ReconnectCommandLog {

    private Long id;
    private String commandId;
    private String operationId;
    private String commandType;
    private Long roomId;
    private Integer partitionId;
    private String targetNodeId;
    private String publisherNodeId;
    private String reason;
    private Long routeVersion;
    private int limitValue;
    private long retryAfterMs;
    private ReconnectCommandPublishStatus publishStatus;
    private Long redisReceivers;
    private Long createdAt;
    private Long publishedAt;
    private Long failedAt;
    private String lastError;

    private ReconnectCommandLog() {
    }

    public static ReconnectCommandLog attempted(String commandId,
                                                String operationId,
                                                RoomPartitionControlCommand command,
                                                String publisherNodeId,
                                                long now) {
        ReconnectCommandLog log = new ReconnectCommandLog();
        log.commandId = commandId;
        log.operationId = safe(operationId, 120, "unknown");
        log.commandType = safe(command.type(), 40, "unknown");
        log.roomId = command.roomId();
        log.partitionId = command.partitionId();
        log.targetNodeId = safeNullable(command.nodeId(), 120);
        log.publisherNodeId = safe(publisherNodeId, 120, "unknown");
        log.reason = safe(command.reason(), 80, "unknown");
        log.routeVersion = command.routeVersion();
        log.limitValue = Math.max(0, command.limit());
        log.retryAfterMs = Math.max(0, command.retryAfterMs());
        log.publishStatus = ReconnectCommandPublishStatus.ATTEMPTED;
        log.createdAt = now;
        return log;
    }

    public void markAttempted(String operationId, RoomPartitionControlCommand command, String publisherNodeId, long now) {
        this.operationId = safe(operationId, 120, "unknown");
        this.commandType = safe(command.type(), 40, "unknown");
        this.roomId = command.roomId();
        this.partitionId = command.partitionId();
        this.targetNodeId = safeNullable(command.nodeId(), 120);
        this.publisherNodeId = safe(publisherNodeId, 120, "unknown");
        this.reason = safe(command.reason(), 80, "unknown");
        this.routeVersion = command.routeVersion();
        this.limitValue = Math.max(0, command.limit());
        this.retryAfterMs = Math.max(0, command.retryAfterMs());
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.publishStatus == null) {
            this.publishStatus = ReconnectCommandPublishStatus.ATTEMPTED;
        }
    }

    public void markSucceeded(long receivers, long now) {
        this.publishStatus = ReconnectCommandPublishStatus.SUCCEEDED;
        this.redisReceivers = Math.max(0, receivers);
        this.publishedAt = now;
        this.lastError = null;
    }

    public void markFailed(ReconnectCommandPublishStatus status, String error, long now) {
        if (status != ReconnectCommandPublishStatus.NO_RECEIVERS && status != ReconnectCommandPublishStatus.FAILED) {
            status = ReconnectCommandPublishStatus.FAILED;
        }
        this.publishStatus = status;
        this.failedAt = now;
        this.lastError = truncate(error);
    }

    private static String safe(String value, int max, String fallback) {
        String resolved = value == null || value.isBlank() ? fallback : value;
        return resolved.length() <= max ? resolved : resolved.substring(0, max);
    }

    private static String safeNullable(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }

    static ReconnectCommandLog fromRow(Long id,
                                       String commandId,
                                       String operationId,
                                       String commandType,
                                       Long roomId,
                                       Integer partitionId,
                                       String targetNodeId,
                                       String publisherNodeId,
                                       String reason,
                                       Long routeVersion,
                                       int limitValue,
                                       long retryAfterMs,
                                       ReconnectCommandPublishStatus publishStatus,
                                       Long redisReceivers,
                                       Long createdAt,
                                       Long publishedAt,
                                       Long failedAt,
                                       String lastError) {
        ReconnectCommandLog log = new ReconnectCommandLog();
        log.id = id;
        log.commandId = commandId;
        log.operationId = operationId;
        log.commandType = commandType;
        log.roomId = roomId;
        log.partitionId = partitionId;
        log.targetNodeId = targetNodeId;
        log.publisherNodeId = publisherNodeId;
        log.reason = reason;
        log.routeVersion = routeVersion;
        log.limitValue = limitValue;
        log.retryAfterMs = retryAfterMs;
        log.publishStatus = publishStatus;
        log.redisReceivers = redisReceivers;
        log.createdAt = createdAt;
        log.publishedAt = publishedAt;
        log.failedAt = failedAt;
        log.lastError = lastError;
        return log;
    }
}
