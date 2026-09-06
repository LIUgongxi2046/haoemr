package org.haoemr.surgery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SurgicalProcedureScheduleRequestWire;
import org.haoemr.contracts.SurgicalProcedureTransitionRequestWire;
import org.haoemr.contracts.SurgicalProcedureWire;
import org.haoemr.contracts.SurgicalTimeOutVerificationRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class SurgicalProcedureApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";

    @Autowired
    private SurgicalProcedureService procedures;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);
    private final UUID surgeon = UUID.fromString(USER);
    private final UUID anesthesiologist = UUID.fromString(COLLABORATOR);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, surgeon, List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity verifierIdentity() {
        return new ClinicalIdentity(tenant, anesthesiologist, List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成手术患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1970, 3, 3)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-SURGERY', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private SurgicalProcedureWire schedule(
            Context context, String procedureName, String bodySite, String laterality,
            UUID surgeonId, UUID anesthesiologistId) {
        return procedures.schedule(identity(), "surg-" + UUID.randomUUID(),
                new SurgicalProcedureScheduleRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), procedureName,
                        SurgicalProcedureScheduleRequestWire.BodySiteValue.valueOf(bodySite),
                        SurgicalProcedureScheduleRequestWire.LateralityValue.valueOf(laterality),
                        surgeonId, anesthesiologistId, Instant.now()));
    }

    private SurgicalProcedureWire transition(Context context, SurgicalProcedureWire procedure, String transition) {
        return transition(identity(), context, procedure, transition);
    }

    private SurgicalProcedureWire transition(
            ClinicalIdentity actor, Context context, SurgicalProcedureWire procedure, String transition) {
        return procedures.transition(actor, "surg-t-" + UUID.randomUUID(), procedure.surgicalProcedureId(),
                new SurgicalProcedureTransitionRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), procedure.rowVersion(),
                        SurgicalProcedureTransitionRequestWire.TransitionValue.valueOf(transition)));
    }

    private SurgicalProcedureWire verifyTimeOut(Context context, SurgicalProcedureWire procedure) {
        return procedures.verifyTimeOut(verifierIdentity(), "surg-verify-" + UUID.randomUUID(), procedure.surgicalProcedureId(),
                new SurgicalTimeOutVerificationRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), procedure.rowVersion(), "独立核对患者、术式、部位侧别、器械与影像资料"));
    }

    @Test
    void givenProcedure_whenTimeOutAndComplete_thenLifecycleRecorded() {
        Context context = seedContext();
        SurgicalProcedureWire scheduled = schedule(context, "左下肢清创术", "LOWER_EXTREMITY", "LEFT",
                surgeon, anesthesiologist);
        assertThat(scheduled.status()).isEqualTo(SurgicalProcedureWire.StatusValue.SCHEDULED);

        assertThatThrownBy(() -> transition(context, scheduled, "TIME_OUT"))
                .isInstanceOf(SurgicalProcedureException.class)
                .satisfies(e -> assertThat(((SurgicalProcedureException) e).code())
                        .isEqualTo("SURGICAL_TIME_OUT_SECOND_PERSON_REQUIRED"));
        SurgicalProcedureWire verified = verifyTimeOut(context, scheduled);
        assertThat(verified.timeOutVerifiedBy()).isEqualTo(anesthesiologist);
        assertThat(verified.timeOutVerifiedAt()).isNotNull();
        assertThat(verified.timeOutVerificationNote()).contains("独立核对");

        assertThatThrownBy(() -> transition(verifierIdentity(), context, verified, "TIME_OUT"))
                .isInstanceOf(SurgicalProcedureException.class)
                .satisfies(e -> assertThat(((SurgicalProcedureException) e).code())
                        .isEqualTo("SURGICAL_TIME_OUT_SELF_VERIFICATION_FORBIDDEN"));

        SurgicalProcedureWire timeOut = transition(context, verified, "TIME_OUT");
        assertThat(timeOut.status()).isEqualTo(SurgicalProcedureWire.StatusValue.TIME_OUT_COMPLETED);
        assertThat(timeOut.timeOutAt()).isNotNull();

        SurgicalProcedureWire completed = transition(context, timeOut, "COMPLETE");
        assertThat(completed.status()).isEqualTo(SurgicalProcedureWire.StatusValue.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();

        List<SurgicalProcedureWire> listed = procedures.listProcedures(identity(), context.patientId());
        assertThat(listed).extracting(SurgicalProcedureWire::surgicalProcedureId)
                .contains(scheduled.surgicalProcedureId());
    }

    @Test
    void givenSameSurgeonAndAnesthesiologist_whenScheduling_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> schedule(context, "腹部探查术", "ABDOMEN", "NONE", surgeon, surgeon))
                .isInstanceOf(SurgicalProcedureException.class)
                .satisfies(e -> assertThat(((SurgicalProcedureException) e).code())
                        .isEqualTo("SURGICAL_PROCEDURE_REQUEST_INVALID"));
    }

    @Test
    void givenPairedSiteWithoutLaterality_whenScheduling_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> schedule(context, "右下肢清创术", "LOWER_EXTREMITY", "NONE",
                surgeon, anesthesiologist))
                .isInstanceOf(SurgicalProcedureException.class)
                .satisfies(e -> assertThat(((SurgicalProcedureException) e).code())
                        .isEqualTo("SURGICAL_PROCEDURE_REQUEST_INVALID"));
    }

    @Test
    void givenInvalidTransition_whenCompletingBeforeTimeOut_thenRejected() {
        Context context = seedContext();
        SurgicalProcedureWire scheduled = schedule(context, "颈部肿物切除术", "NECK", "NONE",
                surgeon, anesthesiologist);
        assertThatThrownBy(() -> transition(context, scheduled, "COMPLETE"))
                .isInstanceOf(SurgicalProcedureException.class)
                .satisfies(e -> assertThat(((SurgicalProcedureException) e).code())
                        .isEqualTo("SURGICAL_PROCEDURE_STATE_INVALID"));
    }

    @Test
    void givenProcedureIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        SurgicalProcedureWire scheduled = schedule(context, "阑尾切除术", "ABDOMEN", "NONE",
                surgeon, anesthesiologist);
        assertThatThrownBy(() -> jdbc.sql("""
                update surgical_procedure set procedure_name = '篡改'
                where tenant_id = cast(:tenant as uuid) and surgical_procedure_id = :procedure
                """).param("tenant", TENANT).param("procedure", scheduled.surgicalProcedureId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
