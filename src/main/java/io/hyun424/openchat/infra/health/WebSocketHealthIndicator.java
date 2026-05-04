package io.hyun424.openchat.infra.health;

import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("websocket")
@RequiredArgsConstructor
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api' && '${app.websocket.enabled:true}'.toLowerCase() == 'true'")
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
