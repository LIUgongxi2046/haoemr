package org.haoemr.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.AuditEventWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class AuditEventApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa09";

    @Autowired
    private AuditEventService events;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenAuditEvents_whenListing_thenReturnsEventsOrderedNewestFirst() {
        String action = "AUDIT_TEST_" + UUID.randomUUID().toString().substring(0, 8);
        insertEvent(action, "TEST_RESOURCE", UUID.randomUUID());
        insertEvent(action, "TEST_RESOURCE", UUID.randomUUID());

        List<AuditEventWire> listed = events.list(identity(), action, "TEST_RESOURCE", null, null, null);
        assertThat(listed).isNotEmpty();
        assertThat(listed).allSatisfy(event -> {
            assertThat(event.actionCode()).isEqualTo(action);
            assertThat(event.resourceType()).isEqualTo("TEST_RESOURCE");
            assertThat(event.eventHash()).isNotBlank();
        });
    }

    @Test
    void givenUnknownAction_whenListing_thenEmpty() {
        List<AuditEventWire> listed = events.list(identity(), "NO_SUCH_ACTION_" + UUID.randomUUID(), null, null, null, null);
        assertThat(listed).isEmpty();
    }

    private void insertEvent(String action, String resourceType, UUID resourceId) {
        String eventHash = UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, :resource_type, :resource,
                  :trace, null, :hash, '{}'::jsonb)
                """).param("tenant", tenant).param("audit", UUID.randomUUID())
                .param("actor", UUID.fromString(USER)).param("action", action)
                .param("resource_type", resourceType).param("resource", resourceId)
                .param("trace", UUID.randomUUID().toString()).param("hash", eventHash).update();
    }
}
