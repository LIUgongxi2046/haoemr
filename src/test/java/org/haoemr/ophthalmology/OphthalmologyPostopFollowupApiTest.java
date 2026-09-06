package org.haoemr.ophthalmology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.OphthalmologyPostopFollowupCreateRequestWire;
import org.haoemr.contracts.OphthalmologyPostopFollowupWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class OphthalmologyPostopFollowupApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private OphthalmologyPostopFollowupService followups;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext() {
        return seedContext("OD");
    }

    private Context seedContext(String surgicalEye) {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成眼科术后随访患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1972, 3, 12)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-OPHFUP', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        UUID preopVerificationId = UUID.randomUUID();
        jdbc.sql("""
                insert into ophthalmology_preop_verification(
                  tenant_id, verification_id, patient_id, encounter_id, facility_id, surgical_eye,
                  verified_by, witnessed_by, verified_at)
                values (cast(:tenant as uuid), :verification, :patient, :encounter, cast(:facility as uuid), :eye,
                  cast(:user as uuid), '018f0000-0000-7000-8000-00000000aa06'::uuid, now())
                """).param("tenant", TENANT).param("verification", preopVerificationId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY).param("eye", surgicalEye)
                .param("user", USER).update();
        return new Context(patientId, encounterId, preopVerificationId);
    }

    private OphthalmologyPostopFollowupWire record(
            Context context, String surgicalEye, double iop, String complicationNote) {
        return followups.record(identity(), "ophfup-" + UUID.randomUUID(),
                new OphthalmologyPostopFollowupCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), context.preopVerificationId(),
                        OphthalmologyPostopFollowupCreateRequestWire.SurgicalEyeValue.valueOf(surgicalEye),
                        Instant.now(), iop, complicationNote, Instant.now()));
    }

    @Test
    void givenFollowup_whenRecording_thenRecorded() {
        Context context = seedContext();
        OphthalmologyPostopFollowupWire recorded = record(context, "OD", 16.0, null);
        assertThat(recorded.iopMmhg()).isEqualTo(16.0);
        assertThat(recorded.surgicalEye()).isEqualTo(
                OphthalmologyPostopFollowupWire.SurgicalEyeValue.OD);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<OphthalmologyPostopFollowupWire> listed = followups.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(OphthalmologyPostopFollowupWire::followupId)
                .contains(recorded.followupId());
    }

    @Test
    void givenElevatedIopWithNote_whenRecording_thenAccepted() {
        Context context = seedContext("OS");
        OphthalmologyPostopFollowupWire recorded = record(context, "OS", 28.0, "高眼压，予降眼压处理并复查");
        assertThat(recorded.complicationNote()).isEqualTo("高眼压，予降眼压处理并复查");
    }

    @Test
    void givenElevatedIopWithoutNote_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, "OU", 30.0, null))
                .isInstanceOf(OphthalmologyPostopFollowupException.class)
                .satisfies(e -> assertThat(((OphthalmologyPostopFollowupException) e).code())
                        .isEqualTo("OPHTHALMOLOGY_POSTOP_IOP_COMPLICATION_NOTE_REQUIRED"));
    }

    @Test
    void givenOutOfRangeIop_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, "OD", 95.0, "过高"))
                .isInstanceOf(OphthalmologyPostopFollowupException.class)
                .satisfies(e -> assertThat(((OphthalmologyPostopFollowupException) e).code())
                        .isEqualTo("OPHTHALMOLOGY_POSTOP_FOLLOWUP_REQUEST_INVALID"));
    }

    @Test
    void givenFollowup_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        OphthalmologyPostopFollowupWire recorded = record(context, "OD", 15.0, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update ophthalmology_postop_followup set iop_mmhg = 50
                where tenant_id = cast(:tenant as uuid) and followup_id = :followup
                """).param("tenant", TENANT).param("followup", recorded.followupId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId, UUID preopVerificationId) {}
}
