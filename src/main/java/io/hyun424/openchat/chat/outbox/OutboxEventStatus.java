package io.hyun424.openchat.chat.outbox;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
}
