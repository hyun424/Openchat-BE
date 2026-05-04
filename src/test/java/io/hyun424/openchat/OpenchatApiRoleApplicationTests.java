package io.hyun424.openchat;

import io.hyun424.openchat.chat.outbox.OutboxEventWorker;
import io.hyun424.openchat.chat.outbox.OutboxPublishedMarker;
import io.hyun424.openchat.chat.outbox.PostCommitLivePublishService;
import io.hyun424.openchat.chat.room.metadata.RoomMetadataUpdateBuffer;
import io.hyun424.openchat.chat.websocket.config.WebSocketConfig;
import io.hyun424.openchat.infra.websocket.handler.ChatWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "app.role=api",
        "app.kafka.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:openchat-api-role-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=false"
})
class OpenchatApiRoleApplicationTests {

    @Test
    void apiRoleDoesNotStartRealtimeOnlyBeans(ApplicationContext context) {
        assertMissingBean(context, WebSocketConfig.class);
        assertMissingBean(context, ChatWebSocketHandler.class);
        assertMissingBean(context, PostCommitLivePublishService.class);
        assertMissingBean(context, OutboxPublishedMarker.class);
        assertMissingBean(context, RoomMetadataUpdateBuffer.class);
        assertMissingBean(context, RedisMessageListenerContainer.class);
        assertMissingBean(context, OutboxEventWorker.class);
        assertMissingBean(context, HealthIndicator.class, "websocket");
    }

    private static void assertMissingBean(ApplicationContext context, Class<?> beanType) {
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(beanType));
    }

    private static void assertMissingBean(ApplicationContext context, Class<?> beanType, String beanName) {
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(beanName, beanType));
    }
}
