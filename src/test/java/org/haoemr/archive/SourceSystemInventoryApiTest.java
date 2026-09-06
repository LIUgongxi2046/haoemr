package org.haoemr.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SourceSystemInventoryRegisterRequestWire;
import org.haoemr.contracts.SourceSystemInventoryTransitionRequestWire;
import org.haoemr.contracts.SourceSystemInventoryWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class SourceSystemInventoryApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private SourceSystemInventoryService sources;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private SourceSystemInventoryWire register(String code) {
        return sources.register(identity(), "src-" + UUID.randomUUID(),
                new SourceSystemInventoryRegisterRequestWire(organization, facility, code,
                        "历史源系统-" + code, SourceSystemInventoryRegisterRequestWire.SystemTypeValue.EMR,
                        Instant.now()));
    }

    private SourceSystemInventoryWire configure(UUID id, long expectedRowVersion) {
        return sources.configure(identity(), "cfg-" + UUID.randomUUID(), id,
                new SourceSystemInventoryTransitionRequestWire(organization, facility, expectedRowVersion));
    }

    private SourceSystemInventoryWire activate(UUID id, long expectedRowVersion) {
        return sources.activate(identity(), "act-" + UUID.randomUUID(), id,
                new SourceSystemInventoryTransitionRequestWire(organization, facility, expectedRowVersion));
    }

    private SourceSystemInventoryWire retire(UUID id, long expectedRowVersion) {
        return sources.retire(identity(), "ret-" + UUID.randomUUID(), id,
                new SourceSystemInventoryTransitionRequestWire(organization, facility, expectedRowVersion));
    }

    @Test
    void givenSource_whenRegistering_thenRegistered() {
        SourceSystemInventoryWire registered = register("SRC-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(registered.connectionStatus())
                .isEqualTo(SourceSystemInventoryWire.ConnectionStatusValue.REGISTERED);
    }

    @Test
    void givenRegistered_whenConfiguring_thenConfigured() {
        SourceSystemInventoryWire registered = register("SRC-" + UUID.randomUUID().toString().substring(0, 8));
        SourceSystemInventoryWire configured = configure(registered.sourceSystemId(), registered.rowVersion());
        assertThat(configured.connectionStatus())
                .isEqualTo(SourceSystemInventoryWire.ConnectionStatusValue.CONFIGURED);
    }

    @Test
    void givenConfigured_whenActivating_thenActive() {
        SourceSystemInventoryWire registered = register("SRC-" + UUID.randomUUID().toString().substring(0, 8));
        SourceSystemInventoryWire configured = configure(registered.sourceSystemId(), registered.rowVersion());
        SourceSystemInventoryWire active = activate(configured.sourceSystemId(), configured.rowVersion());
        assertThat(active.connectionStatus()).isEqualTo(SourceSystemInventoryWire.ConnectionStatusValue.ACTIVE);
    }

    @Test
    void givenActive_whenRetiring_thenRetired() {
        SourceSystemInventoryWire registered = register("SRC-" + UUID.randomUUID().toString().substring(0, 8));
        SourceSystemInventoryWire configured = configure(registered.sourceSystemId(), registered.rowVersion());
        SourceSystemInventoryWire active = activate(configured.sourceSystemId(), configured.rowVersion());
        SourceSystemInventoryWire retired = retire(active.sourceSystemId(), active.rowVersion());
        assertThat(retired.connectionStatus()).isEqualTo(SourceSystemInventoryWire.ConnectionStatusValue.RETIRED);
    }

    @Test
    void givenRegistered_whenActivatingDirectly_thenRejected() {
        SourceSystemInventoryWire registered = register("SRC-" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> activate(registered.sourceSystemId(), registered.rowVersion()))
                .isInstanceOf(SourceSystemInventoryException.class)
                .satisfies(e -> assertThat(((SourceSystemInventoryException) e).code())
                        .isEqualTo("SOURCE_SYSTEM_STATE_INVALID"));
    }

    @Test
    void givenRetired_whenConfiguring_thenRejected() {
        SourceSystemInventoryWire registered = register("SRC-" + UUID.randomUUID().toString().substring(0, 8));
        SourceSystemInventoryWire configured = configure(registered.sourceSystemId(), registered.rowVersion());
        SourceSystemInventoryWire active = activate(configured.sourceSystemId(), configured.rowVersion());
        SourceSystemInventoryWire retired = retire(active.sourceSystemId(), active.rowVersion());
        assertThatThrownBy(() -> configure(retired.sourceSystemId(), retired.rowVersion()))
                .isInstanceOf(SourceSystemInventoryException.class)
                .satisfies(e -> assertThat(((SourceSystemInventoryException) e).code())
                        .isEqualTo("SOURCE_SYSTEM_STATE_INVALID"));
    }

    @Test
    void givenSource_whenTampered_thenDatabaseRejectsMutation() {
        SourceSystemInventoryWire registered = register("SRC-" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> jdbc.sql("""
                update source_system_inventory set source_code = 'FORGED'
                where tenant_id = cast(:tenant as uuid) and source_system_id = :source
                """).param("tenant", TENANT).param("source", registered.sourceSystemId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
