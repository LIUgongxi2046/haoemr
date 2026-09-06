package org.haoemr.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "haoemr.outbox.enabled=false",
        "haoemr.outbox.batch-size=500",
        "haoemr.outbox.max-attempts=2"
})
@ActiveProfiles("dev-synthetic")
@Import(OutboxDispatcherTest.FailureConsumerConfiguration.class)
final class OutboxDispatcherTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OutboxDispatcher dispatcher;

    @BeforeEach
    @AfterEach
    void cleanOutboxTestEvents() {
        jdbc.sql("""
                delete from outbox_replay_audit audit using outbox_event event
                where audit.tenant_id = event.tenant_id and audit.event_id = event.event_id
                  and event.aggregate_type = 'OUTBOX_TEST'
                """).update();
        jdbc.sql("""
                delete from outbox_consumer_receipt receipt using outbox_event event
                where receipt.tenant_id = event.tenant_id and receipt.event_id = event.event_id
                  and event.aggregate_type = 'OUTBOX_TEST'
                """).update();
        jdbc.sql("""
                delete from clinical_event_projection projection using outbox_event event
                where projection.tenant_id = event.tenant_id and projection.event_id = event.event_id
                  and event.aggregate_type = 'OUTBOX_TEST'
                """).update();
        jdbc.sql("delete from outbox_event where aggregate_type = 'OUTBOX_TEST'").update();
    }

    @Test
    void givenOrderedEvents_whenDispatching_thenProjectionReceiptAndAggregateOrderAreDurable() {
        UUID aggregateId = UUID.randomUUID();
        UUID first = insertEvent(aggregateId, 1, "OutboxOrderFirst");
        UUID second = insertEvent(aggregateId, 2, "OutboxOrderSecond");

        dispatcher.dispatchBatch();

        assertThat(state(first)).isEqualTo("PUBLISHED");
        assertThat(state(second)).isEqualTo("PENDING");
        dispatcher.dispatchBatch();
        assertThat(state(second)).isEqualTo("PUBLISHED");
        assertThat(projectionCount(first)).isEqualTo(1);
        assertThat(projectionCount(second)).isEqualTo(1);
        assertThat(receiptCount(first)).isEqualTo(2);
        assertThat(receiptKinds(first)).containsExactlyInAnyOrder(
                "INTERNAL_PROJECTION", "EXTERNAL_HOSPITAL_DELIVERY");
    }

    @Test
    void givenAConsumerFailure_whenRetryingAndReplaying_thenEffectsAreDeduplicatedAndAudited() {
        UUID eventId = insertEvent(UUID.randomUUID(), 1, "FailTwiceThenSucceed");

        dispatcher.dispatchBatch();
        makeAvailableNow(eventId);
        dispatcher.dispatchBatch();

        assertThat(state(eventId)).isEqualTo("DEAD_LETTER");
        assertThat(projectionCount(eventId)).isEqualTo(1);
        dispatcher.replayDeadLetter(TENANT, eventId, USER, "synthetic retry after verified handler recovery");
        // Production may legitimately contain more than one batch of older pending events.
        // Pin this isolated replay fixture to the front instead of depending on global queue depth.
        makeAvailableNow(eventId);
        dispatcher.dispatchBatch();

        assertThat(state(eventId)).isEqualTo("PUBLISHED");
        assertThat(projectionCount(eventId)).isEqualTo(1);
        assertThat(receiptCount(eventId)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from outbox_replay_audit where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenAnExpiredLease_whenReclaiming_thenTheNextWorkerGetsANewFence() {
        UUID eventId = insertEvent(UUID.randomUUID(), 1, "ExpiredLeaseRecovered");
        jdbc.sql("""
                update outbox_event
                set dispatch_state = 'IN_FLIGHT', lease_owner = :owner,
                  lease_until = now() - interval '1 minute', fencing_token = 7, attempt = 1
                where tenant_id = :tenant and event_id = :event
                """).param("owner", UUID.randomUUID()).param("tenant", TENANT).param("event", eventId).update();

        assertThat(dispatcher.reclaimExpiredLeases()).isGreaterThanOrEqualTo(1);
        jdbc.sql("""
                update outbox_event
                set available_at = now() - interval '1 day'
                where tenant_id = :tenant and event_id = :event
                """).param("tenant", TENANT).param("event", eventId).update();
        dispatcher.dispatchBatch();

        assertThat(state(eventId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("select fencing_token from outbox_event where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).query(Long.class).single()).isEqualTo(8);
    }

    private UUID insertEvent(UUID aggregateId, long aggregateVersion, String eventType) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload, available_at)
                values (:tenant, :event, 'OUTBOX_TEST', :aggregate, :version,
                  :event_type, 1, cast(:payload as jsonb), '-infinity'::timestamptz)
                """).param("tenant", TENANT).param("event", eventId).param("aggregate", aggregateId)
                .param("version", aggregateVersion).param("event_type", eventType)
                .param("payload", "{\"synthetic\":true,\"event_id\":\"" + eventId + "\"}").update();
        return eventId;
    }

    private void makeAvailableNow(UUID eventId) {
        jdbc.sql("update outbox_event set available_at = '-infinity'::timestamptz where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).update();
    }

    private String state(UUID eventId) {
        return jdbc.sql("select dispatch_state from outbox_event where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).query(String.class).single();
    }

    private long projectionCount(UUID eventId) {
        return jdbc.sql("select count(*) from clinical_event_projection where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).query(Long.class).single();
    }

    private long receiptCount(UUID eventId) {
        return jdbc.sql("select count(*) from outbox_consumer_receipt where tenant_id = :tenant and event_id = :event")
                .param("tenant", TENANT).param("event", eventId).query(Long.class).single();
    }

    private List<String> receiptKinds(UUID eventId) {
        return jdbc.sql("""
                select delivery_kind from outbox_consumer_receipt
                where tenant_id = :tenant and event_id = :event
                order by delivery_kind
                """).param("tenant", TENANT).param("event", eventId).query(String.class).list();
    }

    @TestConfiguration
    static class FailureConsumerConfiguration {
        @Bean
        OutboxConsumer controlledFailureConsumer() {
            Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
            return new OutboxConsumer() {
                @Override
                public DeliveryKind deliveryKind() {
                    return DeliveryKind.EXTERNAL_HOSPITAL_DELIVERY;
                }

                @Override
                public String consumerName() {
                    return "controlled-failure-consumer-v1";
                }

                @Override
                public void consume(OutboxMessage message) {
                    if (message.eventType().equals("FailTwiceThenSucceed")
                            && attempts.computeIfAbsent(message.eventId(), ignored -> new AtomicInteger())
                                    .incrementAndGet() <= 2) {
                        throw new IllegalStateException("synthetic controlled failure");
                    }
                }
            };
        }
    }
}
