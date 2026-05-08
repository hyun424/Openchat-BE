package io.hyun424.openchat.chat.room.partition.assignment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.room-partition.assignment")
public class RoomPartitionAssignmentProperties {

    private boolean enabled = false;
    private long heartbeatIntervalMs = 5000;
    private long retentionMs = 15000;
    private String wsUrl = "ws://127.0.0.1:8080";
    private boolean dynamicSubscribeEnabled = false;
    private long subscriberRefreshMs = 5000;
    private long unsubscribeGraceMs = 30000;
    private boolean nodeDrainEnabled = false;
    private int nodeDrainReconnectLimit = 200;
    private long nodeDrainRetryAfterMs = 500;
    private long nodeDrainReadinessTimeoutMs = 15000;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long heartbeatIntervalMs() {
        return Math.max(1000, heartbeatIntervalMs);
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public long retentionMs() {
        return Math.max(heartbeatIntervalMs() * 2, retentionMs);
    }

    public void setRetentionMs(long retentionMs) {
        this.retentionMs = retentionMs;
    }

    public String wsUrl() {
        return wsUrl == null || wsUrl.isBlank() ? "ws://127.0.0.1:8080" : wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public boolean dynamicSubscribeEnabled() {
        return dynamicSubscribeEnabled;
    }

    public void setDynamicSubscribeEnabled(boolean dynamicSubscribeEnabled) {
        this.dynamicSubscribeEnabled = dynamicSubscribeEnabled;
    }

    public long subscriberRefreshMs() {
        return Math.max(1000, subscriberRefreshMs);
    }

    public void setSubscriberRefreshMs(long subscriberRefreshMs) {
        this.subscriberRefreshMs = subscriberRefreshMs;
    }

    public long unsubscribeGraceMs() {
        return Math.max(1000, unsubscribeGraceMs);
    }

    public void setUnsubscribeGraceMs(long unsubscribeGraceMs) {
        this.unsubscribeGraceMs = unsubscribeGraceMs;
    }

    public boolean nodeDrainEnabled() {
        return nodeDrainEnabled;
    }

    public void setNodeDrainEnabled(boolean nodeDrainEnabled) {
        this.nodeDrainEnabled = nodeDrainEnabled;
    }

    public int nodeDrainReconnectLimit() {
        return Math.max(1, nodeDrainReconnectLimit);
    }

    public void setNodeDrainReconnectLimit(int nodeDrainReconnectLimit) {
        this.nodeDrainReconnectLimit = nodeDrainReconnectLimit;
    }

    public long nodeDrainRetryAfterMs() {
        return Math.max(0, nodeDrainRetryAfterMs);
    }

    public void setNodeDrainRetryAfterMs(long nodeDrainRetryAfterMs) {
        this.nodeDrainRetryAfterMs = nodeDrainRetryAfterMs;
    }

    public long nodeDrainReadinessTimeoutMs() {
        return Math.max(0, nodeDrainReadinessTimeoutMs);
    }

    public void setNodeDrainReadinessTimeoutMs(long nodeDrainReadinessTimeoutMs) {
        this.nodeDrainReadinessTimeoutMs = nodeDrainReadinessTimeoutMs;
    }
}
