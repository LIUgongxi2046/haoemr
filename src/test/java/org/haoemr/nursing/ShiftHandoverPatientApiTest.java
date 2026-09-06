package org.haoemr.nursing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ShiftHandoverCreateRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientCreateRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientCorrectionRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientVoidRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientWire;
import org.haoemr.contracts.ShiftHandoverWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ShiftHandoverPatientApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";
    private static final String COLLABORATOR_ROLE = "018f0000-0000-7000-8000-00000000aa07";
    private static final String WARD = "018f0000-0000-7000-8000-00000000bb01";

    @Autowired
    private NursingService nursing;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);
    private final UUID ward = UUID.fromString(WARD);

    private ClinicalIdentity outgoing() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ShiftHandoverWire createDraftHandover() {
        return nursing.createHandover(outgoing(), "handover-" + UUID.randomUUID(),
                new ShiftHandoverCreateRequestWire(organization, facility, ward,
                        Instant.now().minusSeconds(60), Instant.now(), UUID.fromString(COLLABORATOR),
                        "交接内容：本班重点患者见患者级交接清单"));
    }

    private UUID seedAdmittedPatient() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID bedId = UUID.randomUUID();
        UUID admissionId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成交接患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(
                  tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-HANDOVER', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                insert into clinical_bed(tenant_id, bed_id, ward_id, bed_label, status)
                values (cast(:tenant as uuid), :bed, cast(:ward as uuid), :label, 'ACTIVE')
                """).param("tenant", TENANT).param("bed", bedId).param("ward", WARD)
                .param("label", "B-" + bedId.toString().substring(0, 8)).update();
        jdbc.sql("""
                insert into inpatient_admission(
                  tenant_id, admission_id, encounter_id, patient_id, facility_id, ward_id,
                  current_bed_id, attending_user_id, status, admitted_at, admission_no,
                  department_id, admission_source, admission_type, condition_level,
                  admitting_diagnosis_text, payment_method_code, identity_verification_method,
                  contact_name, contact_relationship, contact_phone)
                values (cast(:tenant as uuid), :admission, :encounter, :patient, cast(:facility as uuid),
                  cast(:ward as uuid), :bed, cast(:user as uuid), 'ADMITTED', now(), :admission_no,
                  (select department_id from clinical_ward where tenant_id = cast(:tenant as uuid)
                    and ward_id = cast(:ward as uuid)),
                  'OUTPATIENT', 'ELECTIVE', 'GENERAL', '测试入院诊断', 'SELF_PAY', 'OTHER',
                  '测试联系人', '其他', '00000000')
                """).param("tenant", TENANT).param("admission", admissionId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", FACILITY).param("ward", WARD)
                .param("bed", bedId).param("user", USER)
                .param("admission_no", "TEST-" + admissionId.toString().substring(0, 8)).update();
        return patientId;
    }

    private UUID seedPatientOnly() {
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成未入院患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1981, 2, 2)).update();
        return patientId;
    }

    @Test
    void givenAdmittedPatient_whenAddingAndListing_thenHandoverListRecorded() {
        UUID patientId = seedAdmittedPatient();
        ShiftHandoverWire handover = createDraftHandover();

        ShiftHandoverPatientWire item = nursing.addHandoverPatient(outgoing(), "hsp-" + UUID.randomUUID(),
                new ShiftHandoverPatientCreateRequestWire(
                        organization, facility, ward, handover.handoverId(), patientId,
                        "2 床术后观察，生命体征平稳，待执行医嘱 2 项", true));
        assertThat(item.handoverId()).isEqualTo(handover.handoverId());
        assertThat(item.patientId()).isEqualTo(patientId);
        assertThat(item.riskFlag()).isTrue();

        List<ShiftHandoverPatientWire> listed = nursing.listHandoverPatients(outgoing(), handover.handoverId());
        assertThat(listed).extracting(ShiftHandoverPatientWire::shiftHandoverPatientId)
                .contains(item.shiftHandoverPatientId());
    }

    @Test
    void givenPatientNotAdmittedToWard_whenAdding_thenRejected() {
        UUID patientId = seedPatientOnly();
        ShiftHandoverWire handover = createDraftHandover();
        assertThatThrownBy(() -> nursing.addHandoverPatient(outgoing(), "hsp-" + UUID.randomUUID(),
                new ShiftHandoverPatientCreateRequestWire(
                        organization, facility, ward, handover.handoverId(), patientId,
                        "该患者未入住本病区", false)))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code())
                        .isEqualTo("SHIFT_HANDOVER_PATIENT_NOT_ADMITTED"));
    }

    @Test
    void givenHandoverPatientItem_whenTampered_thenDatabaseRejectsMutation() {
        UUID patientId = seedAdmittedPatient();
        ShiftHandoverWire handover = createDraftHandover();
        ShiftHandoverPatientWire item = nursing.addHandoverPatient(outgoing(), "hsp-" + UUID.randomUUID(),
                new ShiftHandoverPatientCreateRequestWire(
                        organization, facility, ward, handover.handoverId(), patientId, "交接摘要内容", false));
        assertThatThrownBy(() -> jdbc.sql("""
                update shift_handover_patient set summary = '篡改'
                where tenant_id = cast(:tenant as uuid) and shift_handover_patient_id = :item
                """).param("tenant", TENANT).param("item", item.shiftHandoverPatientId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenHandoverPatient_whenCorrectingAndVoiding_thenRiskFlowUsesActiveVersion() {
        UUID patientId = seedAdmittedPatient();
        ShiftHandoverWire handover = createDraftHandover();
        ShiftHandoverPatientWire item = nursing.addHandoverPatient(outgoing(), "hsp-" + UUID.randomUUID(),
                new ShiftHandoverPatientCreateRequestWire(
                        organization, facility, ward, handover.handoverId(), patientId, "病情稳定待复查", false));
        ShiftHandoverPatientWire corrected = nursing.correctHandoverPatient(outgoing(), "hsp-c-" + UUID.randomUUID(),
                item.shiftHandoverPatientId(), new ShiftHandoverPatientCorrectionRequestWire(
                        organization, facility, ward, handover.handoverId(), patientId, item.rowVersion(),
                        "血氧下降，接班后立即复查并通知医生", true, "交接风险重新评估"));
        assertThat(corrected.shiftHandoverPatientId()).isNotEqualTo(item.shiftHandoverPatientId());
        assertThat(corrected.riskFlag()).isTrue();
        nursing.voidHandoverPatient(outgoing(), "hsp-v-" + UUID.randomUUID(),
                corrected.shiftHandoverPatientId(), new ShiftHandoverPatientVoidRequestWire(
                        organization, facility, ward, handover.handoverId(), patientId,
                        corrected.rowVersion(), "交接患者重复登记"));
        assertThat(nursing.listHandoverPatients(outgoing(), handover.handoverId())).isEmpty();
    }
}
