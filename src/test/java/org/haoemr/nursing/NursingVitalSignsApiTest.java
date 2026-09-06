package org.haoemr.nursing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.VitalSignRecordRequestWire;
import org.haoemr.contracts.VitalSignRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NursingVitalSignsApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private NursingService nursing;

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
                values (cast(:tenant as uuid), :patient, '合成体征患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-VITALS', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    @Test
    void givenActiveEncounter_whenRecordingVitalSigns_thenStoredAndListed() {
        Context context = seedContext();
        VitalSignRecordWire recorded = nursing.recordVitalSigns(identity(), "vitals-" + UUID.randomUUID(),
                new VitalSignRecordRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        null, Instant.now(), VitalSignRecordRequestWire.SourceValue.MANUAL,
                        37.2, 88, 16, 120, 80, 98.0));
        assertThat(recorded.temperature()).isEqualTo(37.2);
        assertThat(recorded.pulse()).isEqualTo(88);
        assertThat(recorded.source()).isEqualTo(VitalSignRecordWire.SourceValue.MANUAL);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<VitalSignRecordWire> listed = nursing.listVitalSigns(
                identity(), organization, facility, context.patientId(), context.encounterId());
        assertThat(listed).extracting(VitalSignRecordWire::vitalSignRecordId)
                .contains(recorded.vitalSignRecordId());
    }

    @Test
    void givenOutOfRangeVitalSign_whenRecording_thenDatabaseRejects() {
        Context context = seedContext();
        assertThatThrownBy(() -> nursing.recordVitalSigns(identity(), "vitals-" + UUID.randomUUID(),
                new VitalSignRecordRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        null, Instant.now(), VitalSignRecordRequestWire.SourceValue.MANUAL,
                        50.0, 88, 16, 120, 80, 98.0)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenReplayedKey_whenRecording_thenIdempotencyReplay() {
        Context context = seedContext();
        String key = "vitals-replay-" + UUID.randomUUID();
        VitalSignRecordRequestWire request = new VitalSignRecordRequestWire(
                organization, facility, context.patientId(), context.encounterId(), null,
                Instant.now(), VitalSignRecordRequestWire.SourceValue.MANUAL, 37.0, 80, 16, 120, 80, 98.0);
        nursing.recordVitalSigns(identity(), key, request);
        assertThatThrownBy(() -> nursing.recordVitalSigns(identity(), key, request))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code()).isEqualTo("IDEMPOTENCY_REPLAY"));
    }

    @Test
    void givenVitalSignRecord_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        VitalSignRecordWire recorded = nursing.recordVitalSigns(identity(), "vitals-" + UUID.randomUUID(),
                new VitalSignRecordRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        null, Instant.now(), VitalSignRecordRequestWire.SourceValue.MANUAL,
                        36.8, 78, 15, 118, 76, 98.0));
        assertThatThrownBy(() -> jdbc.sql("""
                update vital_sign_record set temperature = 39.0
                where tenant_id = cast(:tenant as uuid) and vital_sign_record_id = :record
                """).param("tenant", TENANT).param("record", recorded.vitalSignRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenProductionRoleEnforcement_whenClinicianWritesNursingFacts_thenNurseRoleIsRequired() {
        Context context = seedContext();
        nursing.requireClinicalOperationRoles = true;
        try {
            assertThatThrownBy(() -> nursing.recordVitalSigns(identity(), "vitals-role-" + UUID.randomUUID(),
                    new VitalSignRecordRequestWire(
                            organization, facility, context.patientId(), context.encounterId(), null,
                            Instant.now(), VitalSignRecordRequestWire.SourceValue.MANUAL,
                            36.8, 78, 15, 118, 76, 98.0)))
                    .isInstanceOf(NursingException.class)
                    .satisfies(error -> assertThat(((NursingException) error).code())
                            .isEqualTo("NURSING_ROLE_REQUIRED"));
        } finally {
            nursing.requireClinicalOperationRoles = false;
        }
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
