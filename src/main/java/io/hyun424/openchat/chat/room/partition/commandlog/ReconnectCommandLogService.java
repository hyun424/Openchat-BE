package io.hyun424.openchat.chat.room.partition.commandlog;

import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionControlCommand;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionControlHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReconnectCommandLogService {

    private static final String CONTRACT_VERSION = "openchat.reconnect-command-log.v1";

    private final ReconnectCommandLogRepository commandRepository;
    private final ReconnectCommandHandlingLogRepository handlingRepository;
    private final ReconnectCommandLogRetentionProperties retentionProperties;
    private final boolean enabled;
    private final boolean commandTraceEnabled;

    public ReconnectCommandLogService(ReconnectCommandLogRepository commandRepository,
                                      ReconnectCommandHandlingLogRepository handlingRepository,
                                      ReconnectCommandLogRetentionProperties retentionProperties,
                                      @Value("${app.room-partition.control.command-log.enabled:false}") boolean enabled,
                                      @Value("${app.room-partition.control.command-trace-enabled:false}") boolean commandTraceEnabled) {
        this.commandRepository = commandRepository;
        this.handlingRepository = handlingRepository;
        this.retentionProperties = retentionProperties;
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

    @Transactional
    public CleanupResult cleanupExpired(long nowMillis) {
        if (!enabled || !retentionProperties.enabled()) {
            return CleanupResult.skipped(enabled, retentionProperties.enabled());
        }
        long cutoffCreatedAt = nowMillis - retentionProperties.retentionMs();
        List<String> expiredCommandIds = commandRepository.findExpiredCommandIds(
                cutoffCreatedAt,
                retentionProperties.cleanupLimit()
        );
        if (expiredCommandIds.isEmpty()) {
            return new CleanupResult(true, true, cutoffCreatedAt, 0, 0, 0);
        }
        int deletedHandlingRows = handlingRepository.deleteByCommandIds(expiredCommandIds);
        int deletedCommandRows = commandRepository.deleteByCommandIds(expiredCommandIds);
        return new CleanupResult(
                true,
                true,
                cutoffCreatedAt,
                expiredCommandIds.size(),
                deletedHandlingRows,
                deletedCommandRows
        );
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
            return new Summary(true, "audit_only", CONTRACT_VERSION, List.of(), List.of(), List.of(), List.of(), 0, null, List.of(), DeliveryEvidence.empty());
        }
        List<ReconnectCommandLog> rows;
        try {
            rows = commandRepository.findByCommandIdIn(expected);
        } catch (Exception e) {
            log.warn("[RECONNECT COMMAND LOG SUMMARY FAIL] commandIds={}", expected, e);
            return new Summary(true, "audit_only", CONTRACT_VERSION, expected, List.of(), expected, List.of(), 0, null, List.of(), DeliveryEvidence.empty());
        }

        List<String> recordedCommandIds = rows.stream()
                .map(ReconnectCommandLog::getCommandId)
                .filter(commandId -> !blank(commandId))
                .distinct()
                .toList();
        Map<String, List<ReconnectCommandHandlingLog>> handlingByCommandId = findHandlingByCommandId(recordedCommandIds);
        Set<String> recorded = new LinkedHashSet<>();
        List<Record> records = new ArrayList<>();
        for (ReconnectCommandLog row : rows) {
            recorded.add(row.getCommandId());
            records.add(Record.from(row, handlingByCommandId.getOrDefault(row.getCommandId(), List.of())));
        }
        List<String> missing = expected.stream()
                .filter(commandId -> !recorded.contains(commandId))
                .toList();
        List<String> duplicates = duplicateIds(rows);
        String lastRecorded = records.isEmpty() ? null : records.get(records.size() - 1).commandId();
        return new Summary(true, "audit_only", CONTRACT_VERSION, expected, List.copyOf(recorded), missing, duplicates, rows.size(), lastRecorded, records, DeliveryEvidence.from(records, missing));
    }

    private Map<String, List<ReconnectCommandHandlingLog>> findHandlingByCommandId(List<String> commandIds) {
        if (commandIds == null || commandIds.isEmpty()) {
            return Map.of();
        }
        try {
            return handlingRepository.findByCommandIdIn(commandIds).stream()
                    .collect(Collectors.groupingBy(
                            ReconnectCommandHandlingLog::getCommandId,
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
        } catch (Exception e) {
            log.warn("[RECONNECT COMMAND LOG HANDLING SUMMARY FAIL] commandIds={}", commandIds, e);
            return Map.of();
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
            List<Record> records,
            DeliveryEvidence deliveryEvidence
    ) {
        static Summary disabled(List<String> expectedCommandIds) {
            return new Summary(false, "audit_only", CONTRACT_VERSION, expectedCommandIds == null ? List.of() : expectedCommandIds, List.of(), List.of(), List.of(), 0, null, List.of(), DeliveryEvidence.disabled());
        }
    }

    public record CleanupResult(
            boolean enabled,
            boolean retentionEnabled,
            Long cutoffCreatedAt,
            int candidateCommandCount,
            int deletedHandlingRows,
            int deletedCommandRows
    ) {
        static CleanupResult skipped(boolean enabled, boolean retentionEnabled) {
            return new CleanupResult(enabled, retentionEnabled, null, 0, 0, 0);
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
            Long failedAt,
            CommandDeliveryEvidence deliveryEvidence
    ) {
        static Record from(ReconnectCommandLog log, List<ReconnectCommandHandlingLog> handlingLogs) {
            return new Record(
                    log.getCommandId(),
                    log.getOperationId(),
                    log.getCommandType(),
                    log.getTargetNodeId(),
                    log.getPublishStatus(),
                    log.getRedisReceivers(),
                    log.getCreatedAt(),
                    log.getPublishedAt(),
                    log.getFailedAt(),
                    CommandDeliveryEvidence.from(log, handlingLogs)
            );
        }
    }

    public record DeliveryEvidence(
            boolean enabled,
            String mode,
            boolean complete,
            int commandCount,
            int strictEligibleCommandCount,
            List<String> missingHandlers,
            List<String> failedHandlers
    ) {
        static DeliveryEvidence from(List<Record> records, List<String> missingCommandIds) {
            List<Record> safeRecords = records == null ? List.of() : records;
            List<String> safeMissingCommandIds = missingCommandIds == null ? List.of() : missingCommandIds;
            List<String> missingHandlers = safeRecords.stream()
                    .flatMap(record -> record.deliveryEvidence().missingHandlers().stream())
                    .distinct()
                    .toList();
            List<String> failedHandlers = safeRecords.stream()
                    .flatMap(record -> record.deliveryEvidence().failedHandlers().stream())
                    .distinct()
                    .toList();
            boolean complete = safeMissingCommandIds.isEmpty()
                    && !safeRecords.isEmpty()
                    && safeRecords.stream().allMatch(record -> record.deliveryEvidence().complete());
            int strictEligible = (int) safeRecords.stream()
                    .filter(record -> record.deliveryEvidence().strictEligible())
                    .count();
            return new DeliveryEvidence(true, "audit_only", complete, safeRecords.size(), strictEligible, missingHandlers, failedHandlers);
        }

        static DeliveryEvidence empty() {
            return new DeliveryEvidence(true, "audit_only", false, 0, 0, List.of(), List.of());
        }

        static DeliveryEvidence disabled() {
            return new DeliveryEvidence(false, "audit_only", false, 0, 0, List.of(), List.of());
        }
    }

    public record CommandDeliveryEvidence(
            boolean strictEligible,
            String strictEligibilityReason,
            boolean complete,
            List<String> expectedHandlers,
            List<String> actualHandlers,
            List<String> missingHandlers,
            List<String> failedHandlers,
            List<HandlingRecord> handlingRecords
    ) {
        static CommandDeliveryEvidence from(ReconnectCommandLog commandLog, List<ReconnectCommandHandlingLog> handlingLogs) {
            List<String> expectedHandlers = blank(commandLog.getTargetNodeId())
                    ? List.of()
                    : List.of(commandLog.getTargetNodeId());
            boolean strictEligible = !expectedHandlers.isEmpty();
            String strictEligibilityReason = strictEligible ? "target_node" : "missing_target_node";
            List<HandlingRecord> handlingRecords = (handlingLogs == null ? List.<ReconnectCommandHandlingLog>of() : handlingLogs).stream()
                    .sorted(Comparator.comparing(ReconnectCommandHandlingLog::getHandlerNodeId, Comparator.nullsLast(String::compareTo)))
                    .map(HandlingRecord::from)
                    .toList();
            Set<String> actual = handlingRecords.stream()
                    .map(HandlingRecord::handlerNodeId)
                    .filter(handlerNodeId -> !blank(handlerNodeId))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> failed = handlingRecords.stream()
                    .filter(record -> record.status() == ReconnectCommandHandlingStatus.PARTIAL || record.status() == ReconnectCommandHandlingStatus.FAILED)
                    .map(HandlingRecord::handlerNodeId)
                    .filter(handlerNodeId -> !blank(handlerNodeId))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> missing = expectedHandlers.stream()
                    .filter(handlerNodeId -> !actual.contains(handlerNodeId))
                    .toList();
            boolean publishSucceeded = commandLog.getPublishStatus() == ReconnectCommandPublishStatus.SUCCEEDED;
            boolean complete = publishSucceeded && missing.isEmpty() && failed.isEmpty();
            return new CommandDeliveryEvidence(
                    strictEligible,
                    strictEligibilityReason,
                    complete,
                    expectedHandlers,
                    List.copyOf(actual),
                    missing,
                    List.copyOf(failed),
                    handlingRecords
            );
        }
    }

    public record HandlingRecord(
            String handlerNodeId,
            ReconnectCommandHandlingStatus status,
            int openSessionsBefore,
            int targetedSessions,
            int sentSessions,
            int failedSessions,
            int remainingOpenSessions,
            Long handledAt
    ) {
        static HandlingRecord from(ReconnectCommandHandlingLog log) {
            return new HandlingRecord(
                    log.getHandlerNodeId(),
                    log.getStatus(),
                    log.getOpenSessionsBefore(),
                    log.getTargetedSessions(),
                    log.getSentSessions(),
                    log.getFailedSessions(),
                    log.getRemainingOpenSessions(),
                    log.getHandledAt()
            );
        }
    }
}
