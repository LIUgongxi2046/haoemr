package org.haoemr.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
final class ClinicalEventProjectionConsumer implements OutboxConsumer {

    private final JdbcClient jdbc;

    ClinicalEventProjectionConsumer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String consumerName() {
        return "clinical-event-projection-v1";
    }

    @Override
    public void consume(OutboxMessage message) {
        jdbc.sql("""
                insert into clinical_event_projection(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, :aggregate_type, :aggregate, :version,
                  :event_type, :schema_version, cast(:payload as jsonb))
                on conflict (tenant_id, event_id) do nothing
                """)
                .param("tenant", message.tenantId()).param("event", message.eventId())
                .param("aggregate_type", message.aggregateType()).param("aggregate", message.aggregateId())
                .param("version", message.aggregateVersion()).param("event_type", message.eventType())
                .param("schema_version", message.schemaVersion()).param("payload", message.payload()).update();
    }
}
