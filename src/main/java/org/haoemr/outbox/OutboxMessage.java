package org.haoemr.outbox;

import java.util.UUID;

public record OutboxMessage(
        UUID tenantId,
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        String eventType,
        int schemaVersion,
        String payload,
        int attempt,
        long fencingToken) {
}
