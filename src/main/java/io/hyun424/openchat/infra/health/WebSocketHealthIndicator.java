package io.hyun424.openchat.infra.health;

import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("websocket")
@RequiredArgsConstructor
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.REALTIME)
@ConditionalOnProperty(name = "app.websocket.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketHealthIndicator implements HealthIndicator {

    private final RoomSessionRegistry roomSessionRegistry;

    @Override
    public Health health() {
        int totalSessions = roomSessionRegistry.getTotalSessionCount();
        int activeRooms = roomSessionRegistry.getRoomCount();
        boolean accepting = roomSessionRegistry.isAcceptingConnections();

        Health.Builder builder = accepting ? Health.up() : Health.down();

        return builder
                .withDetail("totalSessions", totalSessions)
                .withDetail("activeRooms", activeRooms)
                .withDetail("acceptingConnections", accepting)
                .build();
    }
}
