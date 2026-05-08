package io.hyun424.openchat.chat.room.partition.assignment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.hyun424.openchat.chat.room.partition.config.RoomPartitionProperties;
import io.hyun424.openchat.chat.room.shard.ChatRedisChannelResolver;
import io.hyun424.openchat.infra.redis.config.RedisChatMessageDispatcher;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnExpression("'${app.role:combined}'.toLowerCase() != 'api'")
@ConditionalOnProperty(name = "app.room-partition.assignment.dynamic-subscribe-enabled", havingValue = "true")
public class DynamicRoomPartitionSubscriber {

    private final RedisMessageListenerContainer container;
    private final RedisChatMessageDispatcher dispatcher;
    private final ChatRedisChannelResolver channelResolver;
    private final RoomPartitionAssignmentService assignmentService;
    private final RoomPartitionProperties partitionProperties;
    private final RoomPartitionAssignmentProperties assignmentProperties;
    private final RoomSessionRegistry roomSessionRegistry;
    private final RealtimeNodeSubscriptionState subscriptionState;
    private final RealtimeNodeHeartbeatService heartbeatService;
    private final String nodeId;
    private final Map<Integer, PatternTopic> subscribedTopics = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> unsubscribeStartedAt = new ConcurrentHashMap<>();

    public DynamicRoomPartitionSubscriber(
            RedisMessageListenerContainer container,
            RedisChatMessageDispatcher dispatcher,
            ChatRedisChannelResolver channelResolver,
            RoomPartitionAssignmentService assignmentService,
            RoomPartitionProperties partitionProperties,
            RoomPartitionAssignmentProperties assignmentProperties,
            RoomSessionRegistry roomSessionRegistry,
            RealtimeNodeSubscriptionState subscriptionState,
            RealtimeNodeHeartbeatService heartbeatService,
            @Value("${app.instance-id:local}") String nodeId
    ) {
        this.container = container;
        this.dispatcher = dispatcher;
        this.channelResolver = channelResolver;
        this.assignmentService = assignmentService;
        this.partitionProperties = partitionProperties;
        this.assignmentProperties = assignmentProperties;
        this.roomSessionRegistry = roomSessionRegistry;
        this.subscriptionState = subscriptionState;
        this.heartbeatService = heartbeatService;
        this.nodeId = nodeId;
    }

    @Scheduled(fixedDelayString = "${app.room-partition.assignment.subscriber-refresh-ms:5000}")
    public void refreshSubscriptions() {
        if (!partitionProperties.enabled()) {
            return;
        }
        List<Integer> owned = assignmentService.ownedPartitions(nodeId, partitionProperties.partitionCount());
        if (owned.isEmpty() && assignmentService.activeNodes().isEmpty()) {
            log.warn("dynamic partition subscription skipped because assignment has no active nodes nodeId={}", nodeId);
            return;
        }
        Set<Integer> desired = owned.stream()
                .map(partitionProperties::normalizePartitionId)
                .collect(Collectors.toCollection(TreeSet::new));
        for (Integer partitionId : desired) {
            subscribeIfNeeded(partitionId);
        }
        for (Integer partitionId : Set.copyOf(subscribedTopics.keySet())) {
            if (!desired.contains(partitionId)) {
                unsubscribeWhenSafe(partitionId);
            }
        }
        replaceSubscriptionState();
    }

    private void subscribeIfNeeded(Integer partitionId) {
        subscribedTopics.computeIfAbsent(partitionId, key -> {
            PatternTopic topic = new PatternTopic(channelResolver.partitionPattern(key));
            container.addMessageListener(dispatcher.listener(), topic);
            subscriptionState.add(key);
            heartbeatService.publishHeartbeat();
            unsubscribeStartedAt.remove(key);
            log.info("dynamic partition subscribed nodeId={} partitionId={} topic={}", nodeId, key, topic.getTopic());
            return topic;
        });
    }

    private void unsubscribeWhenSafe(Integer partitionId) {
        int openSessions = roomSessionRegistry.openSessionCountForPartition(partitionId);
        if (openSessions > 0) {
            Instant startedAt = unsubscribeStartedAt.computeIfAbsent(partitionId, ignored -> Instant.now());
            long elapsedMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
            if (elapsedMs >= assignmentProperties.unsubscribeGraceMs()) {
                log.warn("dynamic partition unsubscribe skipped after grace because sessions remain nodeId={} partitionId={} openSessions={} elapsedMs={}",
                        nodeId, partitionId, openSessions, elapsedMs);
            }
            return;
        }
        if (!replacementOwnerReady(partitionId)) {
            Instant startedAt = unsubscribeStartedAt.computeIfAbsent(partitionId, ignored -> Instant.now());
            long elapsedMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
            if (elapsedMs >= assignmentProperties.unsubscribeGraceMs()) {
                log.warn("dynamic partition unsubscribe skipped after grace because replacement owner is not ready nodeId={} partitionId={} elapsedMs={}",
                        nodeId, partitionId, elapsedMs);
            }
            return;
        }
        PatternTopic topic = subscribedTopics.remove(partitionId);
        if (topic == null) {
            return;
        }
        container.removeMessageListener(dispatcher.listener(), topic);
        subscriptionState.remove(partitionId);
        heartbeatService.publishHeartbeat();
        unsubscribeStartedAt.remove(partitionId);
        log.info("dynamic partition unsubscribed nodeId={} partitionId={} topic={}", nodeId, partitionId, topic.getTopic());
    }

    private boolean replacementOwnerReady(Integer partitionId) {
        return assignmentService.assignmentFor(partitionId, partitionProperties.partitionCount())
                .filter(assignment -> !nodeId.equals(assignment.nodeId()))
                .map(RoomPartitionAssignment::ready)
                .orElse(false);
    }

    private void replaceSubscriptionState() {
        subscriptionState.replace(Set.copyOf(subscribedTopics.keySet()));
        heartbeatService.publishHeartbeat();
    }
}
