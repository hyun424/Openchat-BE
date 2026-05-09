package io.hyun424.openchat.chat.room.partition.commandlog;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconnectCommandHandlingLogRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReconnectCommandHandlingLogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertHandling(ReconnectCommandHandlingLog log) {
        jdbcTemplate.update("""
                        INSERT INTO reconnect_command_handling_log (
                            command_id, handler_node_id, status, open_sessions_before, targeted_sessions,
                            sent_sessions, failed_sessions, remaining_open_sessions, handled_at, last_error
                        )
                        VALUES (
                            :commandId, :handlerNodeId, :status, :openSessionsBefore, :targetedSessions,
                            :sentSessions, :failedSessions, :remainingOpenSessions, :handledAt, :lastError
                        )
                        ON DUPLICATE KEY UPDATE
                            status = IF(
                                CASE VALUES(status)
                                    WHEN 'SENT' THEN 3
                                    WHEN 'PARTIAL' THEN 2
                                    WHEN 'FAILED' THEN 1
                                    ELSE 0
                                END >= CASE status
                                    WHEN 'SENT' THEN 3
                                    WHEN 'PARTIAL' THEN 2
                                    WHEN 'FAILED' THEN 1
                                    ELSE 0
                                END,
                                VALUES(status),
                                status
                            ),
                            open_sessions_before = GREATEST(open_sessions_before, VALUES(open_sessions_before)),
                            targeted_sessions = GREATEST(targeted_sessions, VALUES(targeted_sessions)),
                            sent_sessions = GREATEST(sent_sessions, VALUES(sent_sessions)),
                            failed_sessions = GREATEST(failed_sessions, VALUES(failed_sessions)),
                            remaining_open_sessions = LEAST(remaining_open_sessions, VALUES(remaining_open_sessions)),
                            handled_at = GREATEST(handled_at, VALUES(handled_at)),
                            last_error = COALESCE(VALUES(last_error), last_error)
                        """,
                new MapSqlParameterSource()
                        .addValue("commandId", log.getCommandId())
                        .addValue("handlerNodeId", log.getHandlerNodeId())
                        .addValue("status", log.getStatus().name())
                        .addValue("openSessionsBefore", log.getOpenSessionsBefore())
                        .addValue("targetedSessions", log.getTargetedSessions())
                        .addValue("sentSessions", log.getSentSessions())
                        .addValue("failedSessions", log.getFailedSessions())
                        .addValue("remainingOpenSessions", log.getRemainingOpenSessions())
                        .addValue("handledAt", log.getHandledAt())
                        .addValue("lastError", log.getLastError()));
    }
}
