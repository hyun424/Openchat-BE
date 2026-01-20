# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run with default profile (uses H2 in-memory DB)
./gradlew bootRun

# Run with Kafka messaging
./gradlew bootRun --args='--spring.profiles.active=kafka'

# Run with Redis Pub/Sub messaging
./gradlew bootRun --args='--spring.profiles.active=redis'

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "io.hyun424.openchat.SomeTestClass"
```

## Architecture Overview

OpenChat is a real-time chat application built with Spring Boot 3.5, WebSocket, and a pluggable message bus (Kafka or Redis Pub/Sub).

### Message Flow Pipeline

```
WebSocket Client → ChatWebSocketHandler → ChatIngestService → ChatMessagePublisher
                                                                      ↓
                                               [Kafka topic OR Redis Pub/Sub channel]
                                                                      ↓
                         WebSocketOutboundSender ← ChatFanoutService ← ChatKafkaConsumer/ChatRedisSubscriber
                                   ↓
                         RoomSessionRegistry.sendToRoom()
```

### Key Components

**Ingest Layer** (`chat/ingest/`):
- `ChatIngestService`: Entry point for all incoming messages. Persists to DB, publishes to message bus, and updates HotChat buckets in Redis.

**Publish Layer** (`chat/publish/`):
- `ChatMessagePublisher`: Interface for message publishing
- `ChatKafkaPublishService`: Kafka implementation (active with `kafka` profile)
- `ChatRedisPublishService`: Redis Pub/Sub implementation (active with `redis` profile)
- `NoopChatMessagePublisher`: Fallback when no messaging profile is active

**Subscribe Layer** (`chat/subscribe/`):
- `ChatKafkaConsumer`: Consumes from `chat-message` topic, delegates to fanout
- `ChatRedisSubscriber`: Subscribes to `chat:room:{roomId}` channels

**Fanout Layer** (`chat/fanout/`):
- `ChatFanoutService`: Deduplicates messages using Redis keys (`dedupe:chat:{messageId}`) and delegates to outbound sender
- `WebSocketOutboundSender`: Sends messages to all WebSocket sessions in a room via `RoomSessionRegistry`

**Session Management** (`infra/websocket/session/`):
- `RoomSessionRegistry`: In-memory registry mapping roomId → Set<WebSocketSession>. Handles room-level broadcast.

### Profile-Based Configuration

- **No profile**: Uses `NoopChatMessagePublisher` (messages not distributed across instances)
- **`kafka` profile**: Uses Kafka for message distribution. Requires `application-kafka.properties`
- **`redis` profile**: Uses Redis Pub/Sub for message distribution. Requires `application-redis.properties`

### Infrastructure Dependencies

- **MySQL**: Primary database (configurable in `application.properties`)
- **Redis**: Used for session deduplication, HotChat scoring buckets, and optionally Pub/Sub messaging
- **Kafka**: Optional message bus for multi-instance deployments

### Authentication

JWT-based authentication via `JwtProvider`. WebSocket connections authenticated through `JwtHandshakeInterceptor` which extracts userId and nickname into session attributes.

### HotChat Feature

Tracks trending chat rooms using Redis sorted sets with time-bucketed keys (`hotchat:bucket:{yyyyMMddHHmm}`). `HotChatService` aggregates scores across a sliding window to determine hot rooms.
