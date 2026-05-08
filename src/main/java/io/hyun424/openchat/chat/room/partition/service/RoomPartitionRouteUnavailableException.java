package io.hyun424.openchat.chat.room.partition.service;

public class RoomPartitionRouteUnavailableException extends RuntimeException {

    private static final long DEFAULT_RETRY_AFTER_MS = 500L;

    private final String reason;
    private final long retryAfterMs;

    public RoomPartitionRouteUnavailableException(String reason) {
        this(reason, DEFAULT_RETRY_AFTER_MS, null);
    }

    public RoomPartitionRouteUnavailableException(String reason, Throwable cause) {
        this(reason, DEFAULT_RETRY_AFTER_MS, cause);
    }

    public RoomPartitionRouteUnavailableException(String reason, long retryAfterMs, Throwable cause) {
        super(reason, cause);
        this.reason = reason == null || reason.isBlank() ? "assignment_unavailable" : reason;
        this.retryAfterMs = Math.max(0, retryAfterMs);
    }

    public String reason() {
        return reason;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
