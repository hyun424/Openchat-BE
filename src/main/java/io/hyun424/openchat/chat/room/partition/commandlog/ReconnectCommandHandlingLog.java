package io.hyun424.openchat.chat.room.partition.commandlog;

import io.hyun424.openchat.chat.room.partition.service.RoomPartitionControlHandler;
import lombok.Getter;

@Getter
public class ReconnectCommandHandlingLog {

    private Long id;
    private String commandId;
    private String handlerNodeId;
    private ReconnectCommandHandlingStatus status;
    private int openSessionsBefore;
    private int targetedSessions;
    private int sentSessions;
    private int failedSessions;
    private int remainingOpenSessions;
    private Long handledAt;
    private String lastError;

    private ReconnectCommandHandlingLog() {
    }

    public static ReconnectCommandHandlingLog handled(String commandId,
                                                      String handlerNodeId,
                                                      RoomPartitionControlHandler.ReconnectHandlingResult result,
                                                      long now,
                                                      String error) {
        ReconnectCommandHandlingLog log = new ReconnectCommandHandlingLog();
        log.commandId = safe(commandId, 80, "unknown");
        log.handlerNodeId = safe(handlerNodeId, 120, "unknown");
        log.apply(result, now, error);
        return log;
    }

    public void apply(RoomPartitionControlHandler.ReconnectHandlingResult result, long now, String error) {
        this.openSessionsBefore = Math.max(0, result.openSessionsBefore());
        this.targetedSessions = Math.max(0, result.targetedSessions());
        this.sentSessions = Math.max(0, result.sentSessions());
        this.failedSessions = Math.max(0, result.failedSessions());
        this.remainingOpenSessions = Math.max(0, result.remainingOpenSessions());
        this.status = statusOf(result);
        this.handledAt = now;
        this.lastError = truncate(error);
    }

    private ReconnectCommandHandlingStatus statusOf(RoomPartitionControlHandler.ReconnectHandlingResult result) {
        if (result.openSessionsBefore() <= 0 || result.targetedSessions() <= 0) {
            return ReconnectCommandHandlingStatus.NO_TARGET;
        }
        if (result.sentSessions() > 0 && result.failedSessions() <= 0) {
            return ReconnectCommandHandlingStatus.SENT;
        }
        if (result.sentSessions() > 0) {
            return ReconnectCommandHandlingStatus.PARTIAL;
        }
        return ReconnectCommandHandlingStatus.FAILED;
    }

    private static String safe(String value, int max, String fallback) {
        String resolved = value == null || value.isBlank() ? fallback : value;
        return resolved.length() <= max ? resolved : resolved.substring(0, max);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }

    static ReconnectCommandHandlingLog fromRow(Long id,
                                               String commandId,
                                               String handlerNodeId,
                                               ReconnectCommandHandlingStatus status,
                                               int openSessionsBefore,
                                               int targetedSessions,
                                               int sentSessions,
                                               int failedSessions,
                                               int remainingOpenSessions,
                                               Long handledAt,
                                               String lastError) {
        ReconnectCommandHandlingLog log = new ReconnectCommandHandlingLog();
        log.id = id;
        log.commandId = commandId;
        log.handlerNodeId = handlerNodeId;
        log.status = status;
        log.openSessionsBefore = openSessionsBefore;
        log.targetedSessions = targetedSessions;
        log.sentSessions = sentSessions;
        log.failedSessions = failedSessions;
        log.remainingOpenSessions = remainingOpenSessions;
        log.handledAt = handledAt;
        log.lastError = lastError;
        return log;
    }
}
