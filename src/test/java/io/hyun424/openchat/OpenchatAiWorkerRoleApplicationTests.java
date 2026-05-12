package io.hyun424.openchat;

import io.hyun424.openchat.chat.fanout.ChatFanoutService;
import io.hyun424.openchat.chat.outbox.OutboxEventWorker;
import io.hyun424.openchat.chat.outbox.OutboxPublishedMarker;
import io.hyun424.openchat.chat.outbox.PostCommitLivePublishService;
import io.hyun424.openchat.chat.room.metadata.RoomMetadataUpdateBuffer;
import io.hyun424.openchat.chat.room.partition.assignment.DynamicRoomPartitionSubscriber;
import io.hyun424.openchat.chat.room.partition.assignment.RealtimeNodeHeartbeatScheduler;
import io.hyun424.openchat.chat.room.partition.lifecycle.RoomPartitionLifecycleScheduler;
import io.hyun424.openchat.chat.room.summary.MockRoomSegmentSummarizer;
import io.hyun424.openchat.chat.room.summary.RoomSummaryController;
import io.hyun424.openchat.chat.room.summary.RoomSummaryWorker;
import io.hyun424.openchat.chat.room.lifecycle.RoomLifecycleConsumer;
import io.hyun424.openchat.chat.room.workload.service.RealtimeWorkloadSnapshotPublisher;
import io.hyun424.openchat.chat.subscribe.ChatKafkaConsumer;
import io.hyun424.openchat.chat.subscribe.ChatRedisSubscriber;
import io.hyun424.openchat.chat.websocket.config.WebSocketConfig;
import io.hyun424.openchat.chat.websocket.listener.WebSocketEventListener;
import io.hyun424.openchat.infra.lifecycle.GracefulShutdownListener;
import io.hyun424.openchat.infra.redis.config.RedisChatMessageDispatcher;
import io.hyun424.openchat.infra.websocket.handler.ChatWebSocketHandler;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "app.role=ai-worker",
        "app.kafka.enabled=true",
        "app.websocket.enabled=true",
        "app.redis.subscriber.enabled=true",
        "app.room-partition.assignment.enabled=true",
        "app.room-partition.assignment.dynamic-subscribe-enabled=true",
        "app.room-partition.lifecycle.enabled=true",
        "app.realtime-workload.publish-enabled=true",
        "app.outbox.enabled=true",
        "spring.task.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:openchat-ai-worker-role-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=false"
})
class OpenchatAiWorkerRoleApplicationTests {

    @Test
    void aiWorkerRoleDoesNotStartRealtimeSideEffects(ApplicationContext context) {
        assertMissingBean(context, WebSocketConfig.class);
        assertMissingBean(context, ChatWebSocketHandler.class);
        assertMissingBean(context, WebSocketEventListener.class);
        assertMissingBean(context, ChatFanoutService.class);
        assertMissingBean(context, ChatKafkaConsumer.class);
        assertMissingBean(context, RoomLifecycleConsumer.class);
        assertMissingBean(context, ChatRedisSubscriber.class);
        assertMissingBean(context, RedisChatMessageDispatcher.class);
        assertMissingBean(context, RedisMessageListenerContainer.class);
        assertMissingBean(context, PostCommitLivePublishService.class);
        assertMissingBean(context, OutboxPublishedMarker.class);
        assertMissingBean(context, OutboxEventWorker.class);
        assertMissingBean(context, RoomMetadataUpdateBuffer.class);
        assertMissingBean(context, RealtimeWorkloadSnapshotPublisher.class);
        assertMissingBean(context, DynamicRoomPartitionSubscriber.class);
        assertMissingBean(context, RealtimeNodeHeartbeatScheduler.class);
        assertMissingBean(context, RoomPartitionLifecycleScheduler.class);
        assertMissingBean(context, RoomSummaryController.class);
        assertMissingBean(context, GracefulShutdownListener.class);
        assertMissingBean(context, HealthIndicator.class, "websocket");
        assertEquals(0, context.getBean(RoomSessionRegistry.class).broadcastLaneCount());
        assertNotNull(context.getBean(RoomSummaryWorker.class));
        assertNotNull(context.getBean(MockRoomSegmentSummarizer.class));
    }

    private static void assertMissingBean(ApplicationContext context, Class<?> beanType) {
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(beanType));
    }

    private static void assertMissingBean(ApplicationContext context, Class<?> beanType, String beanName) {
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(beanName, beanType));
    }
}
