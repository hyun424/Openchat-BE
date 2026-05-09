package io.hyun424.openchat.chat.room.partition.commandlog;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Repository
public class ReconnectCommandLogRepository {

    private static final RowMapper<ReconnectCommandLog> ROW_MAPPER = new RowMapper<>() {
        @Override
        public ReconnectCommandLog mapRow(ResultSet rs, int rowNum) throws SQLException {
            return ReconnectCommandLog.fromRow(
                    rs.getLong("id"),
                    rs.getString("command_id"),
                    rs.getString("operation_id"),
                    rs.getString("command_type"),
                    nullableLong(rs, "room_id"),
                    nullableInteger(rs, "partition_id"),
                    rs.getString("target_node_id"),
                    rs.getString("publisher_node_id"),
                    rs.getString("reason"),
                    nullableLong(rs, "route_version"),
                    rs.getInt("limit_value"),
                    rs.getLong("retry_after_ms"),
                    ReconnectCommandPublishStatus.valueOf(rs.getString("publish_status")),
                    nullableLong(rs, "redis_receivers"),
                    nullableLong(rs, "created_at"),
                    nullableLong(rs, "published_at"),
                    nullableLong(rs, "failed_at"),
                    rs.getString("last_error")
            );
        }
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReconnectCommandLogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertPublishAttempt(ReconnectCommandLog log) {
        jdbcTemplate.update("""
                        INSERT INTO reconnect_command_log (
                            command_id, operation_id, command_type, room_id, partition_id, target_node_id,
                            publisher_node_id, reason, route_version, limit_value, retry_after_ms,
                            publish_status, created_at
                        )
                        VALUES (
                            :commandId, :operationId, :commandType, :roomId, :partitionId, :targetNodeId,
                            :publisherNodeId, :reason, :routeVersion, :limitValue, :retryAfterMs,
                            :publishStatus, :createdAt
                        )
                        ON DUPLICATE KEY UPDATE
                            operation_id = VALUES(operation_id),
                            command_type = VALUES(command_type),
                            room_id = VALUES(room_id),
                            partition_id = VALUES(partition_id),
                            target_node_id = VALUES(target_node_id),
                            publisher_node_id = VALUES(publisher_node_id),
                            reason = VALUES(reason),
                            route_version = VALUES(route_version),
                            limit_value = VALUES(limit_value),
                            retry_after_ms = VALUES(retry_after_ms)
                        """,
                new MapSqlParameterSource()
                        .addValue("commandId", log.getCommandId())
                        .addValue("operationId", log.getOperationId())
                        .addValue("commandType", log.getCommandType())
                        .addValue("roomId", log.getRoomId())
                        .addValue("partitionId", log.getPartitionId())
                        .addValue("targetNodeId", log.getTargetNodeId())
                        .addValue("publisherNodeId", log.getPublisherNodeId())
                        .addValue("reason", log.getReason())
                        .addValue("routeVersion", log.getRouteVersion())
                        .addValue("limitValue", log.getLimitValue())
                        .addValue("retryAfterMs", log.getRetryAfterMs())
                        .addValue("publishStatus", log.getPublishStatus().name())
                        .addValue("createdAt", log.getCreatedAt()));
    }

    public void markPublishSucceeded(String commandId, long receivers, long now) {
        jdbcTemplate.update("""
                        UPDATE reconnect_command_log
                        SET publish_status = :status,
                            redis_receivers = :receivers,
                            published_at = :publishedAt,
                            failed_at = NULL,
                            last_error = NULL
                        WHERE command_id = :commandId
                          AND publish_status IN ('ATTEMPTED', 'SUCCEEDED')
                        """,
                Map.of(
                        "status", ReconnectCommandPublishStatus.SUCCEEDED.name(),
                        "receivers", Math.max(0, receivers),
                        "publishedAt", now,
                        "commandId", commandId
                ));
    }

    public void markPublishFailed(String commandId, ReconnectCommandPublishStatus status, String error, long now) {
        jdbcTemplate.update("""
                        UPDATE reconnect_command_log
                        SET publish_status = :status,
                            failed_at = :failedAt,
                            last_error = :lastError
                        WHERE command_id = :commandId
                          AND publish_status <> 'SUCCEEDED'
                        """,
                new MapSqlParameterSource()
                        .addValue("status", status.name())
                        .addValue("failedAt", now)
                        .addValue("lastError", error)
                        .addValue("commandId", commandId));
    }

    public List<ReconnectCommandLog> findByCommandIdIn(Collection<String> commandIds) {
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT id, command_id, operation_id, command_type, room_id, partition_id, target_node_id,
                               publisher_node_id, reason, route_version, limit_value, retry_after_ms,
                               publish_status, redis_receivers, created_at, published_at, failed_at, last_error
                        FROM reconnect_command_log
                        WHERE command_id IN (:commandIds)
                        ORDER BY created_at ASC, id ASC
                        """,
                new MapSqlParameterSource("commandIds", commandIds),
                ROW_MAPPER);
    }

    public List<String> findExpiredCommandIds(long cutoffCreatedAt, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT command_id
                        FROM reconnect_command_log
                        WHERE created_at < :cutoffCreatedAt
                        ORDER BY created_at ASC, id ASC
                        LIMIT :limit
                        """,
                new MapSqlParameterSource()
                        .addValue("cutoffCreatedAt", cutoffCreatedAt)
                        .addValue("limit", Math.max(1, limit)),
                String.class);
    }

    public int deleteByCommandIds(Collection<String> commandIds) {
        if (commandIds == null || commandIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update("""
                        DELETE FROM reconnect_command_log
                        WHERE command_id IN (:commandIds)
                        """,
                new MapSqlParameterSource("commandIds", commandIds));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
