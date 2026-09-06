package org.haoemr.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "haoemr.outbox.enabled=false")
@ActiveProfiles("dev-synthetic")
final class OutboxExternalDeliveryGateTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OutboxDispatcher dispatcher;

    private UUID eventId;

    @AfterEach
    void cleanFixture() {
        if (eventId == null) return;
        jdbc.sql("delete from outbox_consumer_receipt where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).update();
        jdbc.sql("delete from clinical_event_projection where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).update();
        jdbc.sql("delete from outbox_event where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).update();
    }

    @Test
    void localProjectionAloneCannotMarkHospitalDeliveryPublished() {
        eventId = UUID.randomUUID();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload, available_at)
                values (:tenant, :event, 'OUTBOX_EXTERNAL_GATE_TEST', :aggregate, 1,
                  'HospitalChannelNotConfigured', 1, cast(:payload as jsonb), '-infinity'::timestamptz)
                """).param("tenant", TENANT).param("event", eventId).param("aggregate", UUID.randomUUID())
                .param("payload", "{\"test\":\"no-external-delivery\"}").update();

        OutboxDispatcher.DispatchResult result = dispatcher.dispatchBatch();

        assertThat(result.skippedMissingExternalDeliveryConsumer()).isTrue();
        assertThat(jdbc.sql("select dispatch_state from outbox_event where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("select count(*) from clinical_event_projection where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).query(Long.class).single()).isZero();
    }
}
