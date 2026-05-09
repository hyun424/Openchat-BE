package io.hyun424.openchat.chat.room.partition.commandlog;

import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionControlHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ReconnectCommandLogService {

    private static final String CONTRACT_VERSION = "openchat.reconnect-command-log.v1";

    private final ReconnectCommandLogRepository commandRepository;
    private final ReconnectCommandHandlingLogRepository handlingRepository;
    private final boolean enabled;
    private final boolean commandTraceEnabled;

    public ReconnectCommandLogService(ReconnectCommandLogRepository commandRepository,
                                      ReconnectCommandHandlingLogRepository handlingRepository,
                                      @Value("${app.room-partition.control.command-log.enabled:false}") boolean enabled,
                                      @Value("${app.room-partition.control.command-trace-enabled:false}") boolean commandTraceEnabled) {
        this.commandRepository = commandRepository;
        this.handlingRepository = handlingRepository;
        this.enabled = enabled;
        this.commandTraceEnabled = commandTraceEnabled;
    }

    @PostConstruct
    void validateConfiguration() {
        if (enabled && !commandTraceEnabled) {
            throw new IllegalStateException("app.room-partition.control.command-log.enabled requires command-trace-enabled=true");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void recordPublishAttempt(RoomPartitionControlCommand command, String operationId, String publisherNodeId) {
        if (!enabled || command == null || blank(command.commandId())) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            ReconnectCommandLog log = ReconnectCommandLog.attempted(command.commandId(), operationId, command, publisherNodeId, now);
            commandRepository.upsertPublishAttempt(log);
        } catch (Exception e) {
            log.warn("[RECONNECT COMMAND LOG PUBLISH ATTEMPT FAIL] commandId={} operationId={}",
                    command.commandId(), operationId, e);
        }
    }

    public void markPublishSucceeded(String commandId, Long receivers) {
        if (!enabled || blank(commandId)) {
            return;
        }
        try {
            commandRepository.markPublishSucceeded(commandId, receivers == null ? 0 : receivers, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[RECONNECT COMMAND LOG PUBLISH SUCCESS FAIL] commandId={}", commandId, e);
        }
    }

    public void markPublishFailed(String commandId, ReconnectCommandPublishStatus status, String error) {
        if (!enabled || blank(commandId)) {
            return;
        }
        try {
            ReconnectCommandPublishStatus resolved = status == ReconnectCommandPublishStatus.NO_RECEIVERS
                    ? ReconnectCommandPublishStatus.NO_RECEIVERS
                    : ReconnectCommandPublishStatus.FAILED;
            commandRepository.markPublishFailed(commandId, resolved, truncate(error), System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[RECONNECT COMMAND LOG PUBLISH FAIL MARK FAIL] commandId={} status={}", commandId, status, e);
        }
    }

    public void recordHandling(RoomPartitionControlCommand command,
                               String handlerNodeId,
                               RoomPartitionControlHandler.ReconnectHandlingResult result) {
        if (!enabled || command == null || blank(command.commandId()) || result == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            ReconnectCommandHandlingLog log =
                    ReconnectCommandHandlingLog.handled(command.commandId(), handlerNodeId, result, now, null);
            handlingRepository.upsertHandling(log);
        } catch (Exception e) {
            log.warn("[RECONNECT COMMAND LOG HANDLING FAIL] commandId={} handlerNodeId={}",
                    command.commandId(), handlerNodeId, e);
        }
    }

    @Transactional(readOnly = true)
    public Summary summarize(List<String> commandIds) {
        if (!enabled) {
            return Summary.disabled(commandIds);
        }
        List<String> expected = normalize(commandIds);
        if (expected.isEmpty()) {
            return new Summary(true, "audit_only", CONTRACT_VERSION, List.of(), List.of(), List.of(), List.of(), 0, null, List.of());
        }
        try {
            List<ReconnectCommandLog> rows = commandRepository.findByCommandIdIn(expected);
            Set<String> recorded = new LinkedHashSet<>();
            List<Record> records = new ArrayList<>();
            for (ReconnectCommandLog row : rows) {
                recorded.add(row.getCommandId());
                records.add(Record.from(row));
            }
            List<String> missing = expected.stream()
                    .filter(commandId -> !recorded.contains(commandId))
                    .toList();
            List<String> duplicates = duplicateIds(rows);
            String lastRecorded = records.isEmpty() ? null : records.get(records.size() - 1).commandId();
            return new Summary(true, "audit_only", CONTRACT_VERSION, expected, List.copyOf(recorded), missing, duplicates, rows.size(), lastRecorded, records);
        } catch (Exception e) {
            log.warn("[RECONNECT COMMAND LOG SUMMARY FAIL] commandIds={}", expected, e);
            return new Summary(true, "audit_only", CONTRACT_VERSION, expected, List.of(), expected, List.of(), 0, null, List.of());
        }
    }

    private List<String> normalize(List<String> commandIds) {
        if (commandIds == null) {
            return List.of();
        }
        return commandIds.stream()
                .filter(commandId -> !blank(commandId))
                .distinct()
                .toList();
    }

    private List<String> duplicateIds(List<ReconnectCommandLog> rows) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (ReconnectCommandLog row : rows) {
            if (!seen.add(row.getCommandId())) {
                duplicates.add(row.getCommandId());
            }
        }
        return List.copyOf(duplicates);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }

    public record Summary(
            boolean enabled,
            String mode,
            String contractVersion,
            List<String> expectedCommandIds,
            List<String> recordedCommandIds,
            List<String> missingCommandIds,
            List<String> duplicateCommandIds,
            int recordCount,
            String lastRecordedCommandId,
            List<Record> records
    ) {
        static Summary disabled(List<String> expectedCommandIds) {
            return new Summary(false, "audit_only", CONTRACT_VERSION, expectedCommandIds == null ? List.of() : expectedCommandIds, List.of(), List.of(), List.of(), 0, null, List.of());
        }
    }

    public record Record(
            String commandId,
            String operationId,
            String commandType,
            String targetNodeId,
            ReconnectCommandPublishStatus publishStatus,
            Long redisReceivers,
            Long createdAt,
            Long publishedAt,
            Long failedAt
    ) {
        static Record from(ReconnectCommandLog log) {
            return new Record(
                    log.getCommandId(),
                    log.getOperationId(),
                    log.getCommandType(),
                    log.getTargetNodeId(),
                    log.getPublishStatus(),
                    log.getRedisReceivers(),
                    log.getCreatedAt(),
                    log.getPublishedAt(),
                    log.getFailedAt()
            );
        }
    }
}
