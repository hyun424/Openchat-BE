package io.hyun424.openchat.chat.room.partition.assignment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class RealtimeNodeRegistryProperties {

    private final boolean enabled;
    private final String nodeId;
    private final String role;
    private final String host;
    private final int port;
    private final long heartbeatIntervalMillis;
    private final long retentionMillis;

    public RealtimeNodeRegistryProperties(
            @Value("${app.room-partition.node-registry.enabled:false}") boolean enabled,
            @Value("${app.room-partition.node-registry.node-id:}") String nodeId,
            @Value("${app.room-partition.node-registry.role:realtime}") String role,
            @Value("${app.room-partition.node-registry.host:}") String host,
            @Value("${app.room-partition.node-registry.port:0}") int port,
            @Value("${app.room-partition.node-registry.heartbeat-interval-ms:5000}") long heartbeatIntervalMillis,
            @Value("${app.room-partition.node-registry.retention-ms:15000}") long retentionMillis
    ) {
        this.enabled = enabled;
        this.host = normalize(host, defaultHost());
        this.nodeId = normalize(nodeId, this.host + "-" + processId());
        this.role = normalize(role, "realtime");
        this.port = Math.max(0, port);
        this.heartbeatIntervalMillis = Math.max(1_000L, heartbeatIntervalMillis);
        this.retentionMillis = Math.max(this.heartbeatIntervalMillis * 2, retentionMillis);
    }

    public boolean enabled() {
        return enabled;
    }

    public String nodeId() {
        return nodeId;
    }

    public String role() {
        return role;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public long heartbeatIntervalMillis() {
        return heartbeatIntervalMillis;
    }

    public long retentionMillis() {
        return retentionMillis;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String defaultHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    private static String processId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }
}
