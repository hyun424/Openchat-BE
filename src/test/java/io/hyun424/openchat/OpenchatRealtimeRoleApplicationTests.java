package io.hyun424.openchat;

import io.hyun424.openchat.chat.websocket.config.WebSocketConfig;
import io.hyun424.openchat.infra.lifecycle.GracefulShutdownListener;
import io.hyun424.openchat.infra.websocket.handler.ChatWebSocketHandler;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "app.role=realtime",
        "app.kafka.enabled=false",
        "app.redis.subscriber.enabled=false",
        "app.room-partition.assignment.enabled=false",
        "app.outbox.enabled=false",
        "spring.task.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:openchat-realtime-role-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=false"
})
class OpenchatRealtimeRoleApplicationTests {

    @Test
    void realtimeRoleStartsRealtimeCoreBeans(ApplicationContext context) {
        assertNotNull(context.getBean(WebSocketConfig.class));
        assertNotNull(context.getBean(ChatWebSocketHandler.class));
        assertNotNull(context.getBean(GracefulShutdownListener.class));
        assertTrue(context.getBean(RoomSessionRegistry.class).broadcastLaneCount() > 0);
    }
}
