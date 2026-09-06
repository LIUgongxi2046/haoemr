package org.haoemr.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SourceFieldMappingDeactivateRequestWire;
import org.haoemr.contracts.SourceFieldMappingRegisterRequestWire;
import org.haoemr.contracts.SourceFieldMappingWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class SourceFieldMappingApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private SourceFieldMappingService mappings;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedSource(String status) {
        UUID sourceId = UUID.randomUUID();
        jdbc.sql("""
                insert into source_system_inventory(
                  tenant_id, source_system_id, source_code, display_name, system_type,
                  connection_status, registered_by, registered_at)
                values (cast(:tenant as uuid), :source, :code, '源系统', 'EMR', :status,
                  cast(:actor as uuid), now())
                """).param("tenant", TENANT).param("source", sourceId)
                .param("code", "SRC-" + UUID.randomUUID().toString().substring(0, 8)).param("status", status)
                .param("actor", USER).update();
        return sourceId;
    }

    private SourceFieldMappingWire register(UUID sourceId, String sourceField, String targetEntity, String targetField) {
        return mappings.register(identity(), "map-" + UUID.randomUUID(),
                new SourceFieldMappingRegisterRequestWire(organization, facility, sourceId,
                        sourceField, targetEntity, targetField, Instant.now()));
    }

    @Test
    void givenConfiguredSource_whenRegisteringMapping_thenActive() {
        UUID sourceId = seedSource("CONFIGURED");
        SourceFieldMappingWire mapping = register(sourceId, "PAT_NAME", "patient", "display_name");
        assertThat(mapping.status()).isEqualTo(SourceFieldMappingWire.StatusValue.ACTIVE);
        assertThat(mapping.targetEntity()).isEqualTo("patient");

        List<SourceFieldMappingWire> listed = mappings.list(identity(), sourceId);
        assertThat(listed).extracting(SourceFieldMappingWire::mappingId).contains(mapping.mappingId());
    }

    @Test
    void givenDuplicateMapping_whenRegistering_thenRejected() {
        UUID sourceId = seedSource("CONFIGURED");
        register(sourceId, "PAT_NAME", "patient", "display_name");
        assertThatThrownBy(() -> register(sourceId, "PAT_NAME", "patient", "display_name"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenRegisteredSource_whenRegisteringMapping_thenRejected() {
        UUID sourceId = seedSource("REGISTERED");
        assertThatThrownBy(() -> register(sourceId, "PAT_NAME", "patient", "display_name"))
                .isInstanceOf(SourceFieldMappingException.class)
                .satisfies(e -> assertThat(((SourceFieldMappingException) e).code())
                        .isEqualTo("SOURCE_SYSTEM_NOT_CONFIGURED"));
    }

    @Test
    void givenMapping_whenDeactivating_thenInactive() {
        UUID sourceId = seedSource("CONFIGURED");
        SourceFieldMappingWire mapping = register(sourceId, "PAT_NAME", "patient", "display_name");
        SourceFieldMappingWire deactivated = mappings.deactivate(identity(), "deact-" + UUID.randomUUID(),
                mapping.mappingId(), new SourceFieldMappingDeactivateRequestWire(organization, facility, mapping.rowVersion()));
        assertThat(deactivated.status()).isEqualTo(SourceFieldMappingWire.StatusValue.INACTIVE);
    }

    @Test
    void givenMapping_whenTampered_thenDatabaseRejectsMutation() {
        UUID sourceId = seedSource("CONFIGURED");
        SourceFieldMappingWire mapping = register(sourceId, "PAT_NAME", "patient", "display_name");
        assertThatThrownBy(() -> jdbc.sql("""
                update source_field_mapping set target_field = 'FORGED'
                where tenant_id = cast(:tenant as uuid) and mapping_id = :mapping
                """).param("tenant", TENANT).param("mapping", mapping.mappingId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
