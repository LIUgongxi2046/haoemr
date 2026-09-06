package org.haoemr.ent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EntAirwayRiskHandoverCreateRequestWire;
import org.haoemr.contracts.EntAirwayRiskHandoverWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class EntAirwayRiskHandoverApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";

    @Autowired
    private EntAirwayRiskHandoverService handovers;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成耳鼻喉患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1985, 3, 3)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-ENT', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private EntAirwayRiskHandoverWire record(Context context, UUID toProviderId) {
        return handovers.record(identity(), "airway-" + UUID.randomUUID(),
                new EntAirwayRiskHandoverCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), EntAirwayRiskHandoverCreateRequestWire.AirwayRiskLevelValue.HIGH,
                        "保持气道开放并备吸引装置", toProviderId, Instant.now()));
    }

    @Test
    void givenHandover_whenRecording_thenRecorded() {
        Context context = seedContext();
        EntAirwayRiskHandoverWire recorded = record(context, UUID.fromString(COLLABORATOR));
        assertThat(recorded.airwayRiskLevel()).isEqualTo(EntAirwayRiskHandoverWire.AirwayRiskLevelValue.HIGH);
        assertThat(recorded.fromProviderId()).isEqualTo(UUID.fromString(USER));
        assertThat(recorded.toProviderId()).isEqualTo(UUID.fromString(COLLABORATOR));

        List<EntAirwayRiskHandoverWire> listed = handovers.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(EntAirwayRiskHandoverWire::handoverId).contains(recorded.handoverId());
    }

    @Test
    void givenSelfHandover_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, UUID.fromString(USER)))
                .isInstanceOf(EntAirwayRiskHandoverException.class)
                .satisfies(e -> assertThat(((EntAirwayRiskHandoverException) e).code())
                        .isEqualTo("SELF_HANDOVER_FORBIDDEN"));
    }

    @Test
    void givenSameProviderBypass_whenInserting_thenDatabaseRejects() {
        Context context = seedContext();
        assertThatThrownBy(() -> jdbc.sql("""
                insert into ent_airway_risk_handover(
                  tenant_id, handover_id, patient_id, encounter_id, facility_id, airway_risk_level,
                  airway_precautions, from_provider_id, to_provider_id, handed_over_at)
                values (cast(:tenant as uuid), :handover, :patient, :encounter, cast(:facility as uuid),
                  'HIGH', '保持气道开放', cast(:user as uuid), cast(:user as uuid), now())
                """).param("tenant", TENANT).param("handover", UUID.randomUUID())
                .param("patient", context.patientId()).param("encounter", context.encounterId())
                .param("facility", FACILITY).param("user", USER).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenHandover_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        EntAirwayRiskHandoverWire recorded = record(context, UUID.fromString(COLLABORATOR));
        assertThatThrownBy(() -> jdbc.sql("""
                update ent_airway_risk_handover set airway_precautions = '篡改'
                where tenant_id = cast(:tenant as uuid) and handover_id = :handover
                """).param("tenant", TENANT).param("handover", recorded.handoverId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
