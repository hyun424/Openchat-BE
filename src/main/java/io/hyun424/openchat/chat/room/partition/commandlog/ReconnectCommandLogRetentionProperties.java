package io.hyun424.openchat.chat.room.partition.commandlog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.room-partition.control.command-log.retention")
public class ReconnectCommandLogRetentionProperties {

    private static final long MIN_RETENTION_MS = 3_600_000;

    private boolean enabled = false;
    private long retentionMs = 2_592_000_000L;
    private int cleanupLimit = 1000;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long retentionMs() {
        return Math.max(MIN_RETENTION_MS, retentionMs);
    }

    public void setRetentionMs(long retentionMs) {
        this.retentionMs = retentionMs;
    }

    public int cleanupLimit() {
        return Math.max(1, cleanupLimit);
    }

    public void setCleanupLimit(int cleanupLimit) {
        this.cleanupLimit = cleanupLimit;
    }
}
