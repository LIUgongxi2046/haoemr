package org.haoemr.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EncounterDomainSwitchRecordRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchCorrectionRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchVoidRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class EncounterDomainSwitchApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private EncounterDomainSwitchService switches;

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
        UUID outpatientEncounterId = UUID.randomUUID();
        UUID emergencyEncounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成域间切换患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1983, 5, 5)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-DSW', :source_key)
                """).param("tenant", TENANT).param("encounter", outpatientEncounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'EMERGENCY', 'IN_PROGRESS', now(), 'SYNTHETIC-DSW', :source_key)
                """).param("tenant", TENANT).param("encounter", emergencyEncounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, outpatientEncounterId, emergencyEncounterId);
    }

    private UUID seedOtherPatientEncounter(UUID otherPatientId) {
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'EMERGENCY', 'IN_PROGRESS', now(), 'SYNTHETIC-DSW', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", otherPatientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return encounterId;
    }

    private UUID seedInpatientEncounter(UUID patientId) {
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-DSW', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return encounterId;
    }

    private EncounterDomainSwitchWire record(
            Context context, UUID fromEncounterId, UUID toEncounterId,
            EncounterDomainSwitchRecordRequestWire.FromDomainValue from,
            EncounterDomainSwitchRecordRequestWire.ToDomainValue to) {
        return switches.record(identity(), "dsw-" + UUID.randomUUID(),
                new EncounterDomainSwitchRecordRequestWire(organization, facility, context.patientId(),
                        fromEncounterId, toEncounterId, from, to, "病情变化转急诊", Instant.now()));
    }

    @Test
    void givenDifferentDomains_whenRecording_thenRecorded() {
        Context context = seedContext();
        EncounterDomainSwitchWire recorded = record(context, context.outpatientEncounterId(),
                context.emergencyEncounterId(), EncounterDomainSwitchRecordRequestWire.FromDomainValue.OUTPATIENT,
                EncounterDomainSwitchRecordRequestWire.ToDomainValue.EMERGENCY);
        assertThat(recorded.fromDomain()).isEqualTo(EncounterDomainSwitchWire.FromDomainValue.OUTPATIENT);
        assertThat(recorded.toDomain()).isEqualTo(EncounterDomainSwitchWire.ToDomainValue.EMERGENCY);

        List<EncounterDomainSwitchWire> listed = switches.listSwitches(identity(), context.patientId());
        assertThat(listed).extracting(EncounterDomainSwitchWire::domainSwitchId).contains(recorded.domainSwitchId());
    }

    @Test
    void givenEmergencyAdmission_whenRecording_thenInpatientDomainIsSupportedAndTypeChecked() {
        Context context = seedContext();
        UUID inpatientEncounterId = seedInpatientEncounter(context.patientId());

        EncounterDomainSwitchWire recorded = record(context, context.emergencyEncounterId(), inpatientEncounterId,
                EncounterDomainSwitchRecordRequestWire.FromDomainValue.EMERGENCY,
                EncounterDomainSwitchRecordRequestWire.ToDomainValue.INPATIENT);

        assertThat(recorded.toDomain()).isEqualTo(EncounterDomainSwitchWire.ToDomainValue.INPATIENT);
        assertThatThrownBy(() -> record(context, context.outpatientEncounterId(), inpatientEncounterId,
                EncounterDomainSwitchRecordRequestWire.FromDomainValue.EMERGENCY,
                EncounterDomainSwitchRecordRequestWire.ToDomainValue.INPATIENT))
                .isInstanceOf(EncounterDomainSwitchException.class)
                .satisfies(error -> assertThat(((EncounterDomainSwitchException) error).code())
                        .isEqualTo("CONTEXT_NOT_PERMITTED"));
    }

    @Test
    void givenSameDomain_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, context.outpatientEncounterId(), context.emergencyEncounterId(),
                EncounterDomainSwitchRecordRequestWire.FromDomainValue.OUTPATIENT,
                EncounterDomainSwitchRecordRequestWire.ToDomainValue.OUTPATIENT))
                .isInstanceOf(EncounterDomainSwitchException.class)
                .satisfies(e -> assertThat(((EncounterDomainSwitchException) e).code())
                        .isEqualTo("ENCOUNTER_DOMAIN_SWITCH_SAME_DOMAIN"));
    }

    @Test
    void givenSameEncounter_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, context.outpatientEncounterId(), context.outpatientEncounterId(),
                EncounterDomainSwitchRecordRequestWire.FromDomainValue.OUTPATIENT,
                EncounterDomainSwitchRecordRequestWire.ToDomainValue.EMERGENCY))
                .isInstanceOf(EncounterDomainSwitchException.class)
                .satisfies(e -> assertThat(((EncounterDomainSwitchException) e).code())
                        .isEqualTo("ENCOUNTER_DOMAIN_SWITCH_SAME_ENCOUNTER"));
    }

    @Test
    void givenEncounterOfAnotherPatient_whenRecording_thenRejected() {
        Context context = seedContext();
        UUID otherPatientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '另一患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", otherPatientId)
                .param("birth", LocalDate.of(1990, 6, 6)).update();
        UUID otherEncounterId = seedOtherPatientEncounter(otherPatientId);
        assertThatThrownBy(() -> record(context, context.outpatientEncounterId(), otherEncounterId,
                EncounterDomainSwitchRecordRequestWire.FromDomainValue.OUTPATIENT,
                EncounterDomainSwitchRecordRequestWire.ToDomainValue.EMERGENCY))
                .isInstanceOf(EncounterDomainSwitchException.class)
                .satisfies(e -> assertThat(((EncounterDomainSwitchException) e).code())
                        .isEqualTo("CONTEXT_NOT_PERMITTED"));
    }

    @Test
    void givenSwitchIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        EncounterDomainSwitchWire recorded = record(context, context.outpatientEncounterId(),
                context.emergencyEncounterId(), EncounterDomainSwitchRecordRequestWire.FromDomainValue.OUTPATIENT,
                EncounterDomainSwitchRecordRequestWire.ToDomainValue.EMERGENCY);
        assertThatThrownBy(() -> jdbc.sql("""
                update encounter_domain_switch set reason = '篡改'
                where tenant_id = cast(:tenant as uuid) and domain_switch_id = :switch
                """).param("tenant", TENANT).param("switch", recorded.domainSwitchId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenDomainSwitch_whenCorrectingAndVoiding_thenOnlyActiveVersionIsListed() {
        Context context = seedContext();
        EncounterDomainSwitchWire recorded = record(context, context.outpatientEncounterId(),
                context.emergencyEncounterId(), EncounterDomainSwitchRecordRequestWire.FromDomainValue.OUTPATIENT,
                EncounterDomainSwitchRecordRequestWire.ToDomainValue.EMERGENCY);
        EncounterDomainSwitchWire corrected = switches.correct(identity(), "dsw-c-" + UUID.randomUUID(),
                recorded.domainSwitchId(), new EncounterDomainSwitchCorrectionRequestWire(
                        organization, facility, context.patientId(), context.outpatientEncounterId(),
                        context.emergencyEncounterId(), EncounterDomainSwitchCorrectionRequestWire.FromDomainValue.OUTPATIENT,
                        EncounterDomainSwitchCorrectionRequestWire.ToDomainValue.EMERGENCY,
                        "病情变化转急诊绿色通道", Instant.now(), recorded.rowVersion(), "流转原因补充更正"));
        assertThat(corrected.domainSwitchId()).isNotEqualTo(recorded.domainSwitchId());
        assertThat(switches.listSwitches(identity(), context.patientId()))
                .extracting(EncounterDomainSwitchWire::domainSwitchId)
                .contains(corrected.domainSwitchId()).doesNotContain(recorded.domainSwitchId());
        switches.voidSwitch(identity(), "dsw-v-" + UUID.randomUUID(), corrected.domainSwitchId(),
                new EncounterDomainSwitchVoidRequestWire(
                        organization, facility, context.patientId(), corrected.rowVersion(), "域切换重复记录"));
        assertThat(switches.listSwitches(identity(), context.patientId()))
                .extracting(EncounterDomainSwitchWire::domainSwitchId).doesNotContain(corrected.domainSwitchId());
    }

    private record Context(UUID patientId, UUID outpatientEncounterId, UUID emergencyEncounterId) {}
}
