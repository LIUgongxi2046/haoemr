package org.haoemr.executioncenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.SpecialtyExecutionCaseCreateRequestWire;
import org.haoemr.contracts.SpecialtyExecutionCaseTransitionRequestWire;
import org.haoemr.contracts.SpecialtyExecutionCaseWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class SpecialtyExecutionCaseApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID REVIEWER = UUID.fromString("018f0000-0000-7000-8000-00000000aa06");
    private static final UUID REVIEWER_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa07");
    private static final UUID PATIENT = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID ENCOUNTER = UUID.fromString("018f0000-0000-7000-8000-000000000101");

    @Autowired SpecialtyExecutionCaseService cases;
    @Autowired ExecutionWorklistService worklists;
    @Autowired JdbcClient jdbc;

    private ClinicalIdentity author() { return new ClinicalIdentity(TENANT, USER, List.of(ROLE)); }
    private ClinicalIdentity reviewer() { return new ClinicalIdentity(TENANT, REVIEWER, List.of(REVIEWER_ROLE)); }

    @Test
    void pathologyLifecycleRequiresTwoIdentifiersAndIndependentCompletionAndKeepsImmutableEvents() {
        SpecialtyExecutionCaseWire draft = cases.create(author(), "path-create-" + UUID.randomUUID(),
                new SpecialtyExecutionCaseCreateRequestWire(
                        ORGANIZATION, FACILITY, PATIENT, ENCOUNTER,
                        SpecialtyExecutionCaseCreateRequestWire.DomainValue.PATHOLOGY,
                        "左肺上叶切除标本病理", SpecialtyExecutionCaseCreateRequestWire.PriorityValue.URGENT,
                        null, readyPathologyPayload()));
        assertThat(draft.status()).isEqualTo(SpecialtyExecutionCaseWire.StatusValue.DRAFT);
        assertThat(draft.businessNumber()).startsWith("BL");
        assertThat(jdbc.sql("""
                select lab_specimen_id from specialty_execution_case
                where tenant_id = :tenant and specialty_execution_case_id = :case
                """).param("tenant", TENANT).param("case", draft.specialtyExecutionCaseId())
                .query(UUID.class).single()).isEqualTo(UUID.fromString(draft.payload().get("lab_specimen_id").toString()));

        SpecialtyExecutionCaseWire ready = transition(author(), draft, "MARK_READY");
        SpecialtyExecutionCaseWire started = transition(author(), ready, "START");
        SpecialtyExecutionCaseWire review = transition(author(), started, "REQUEST_REVIEW");

        assertThatThrownBy(() -> transition(author(), review, "COMPLETE"))
                .isInstanceOf(ExecutionWorklistException.class)
                .satisfies(error -> assertThat(((ExecutionWorklistException) error).code())
                        .isEqualTo("INDEPENDENT_REVIEW_REQUIRED"));

        SpecialtyExecutionCaseWire completed = transition(reviewer(), review, "COMPLETE");
        assertThat(completed.status()).isEqualTo(SpecialtyExecutionCaseWire.StatusValue.COMPLETED);
        assertThat(completed.events()).extracting(event -> event.eventType().name())
                .containsExactly("CREATED", "READY", "STARTED", "REVIEW_REQUESTED", "COMPLETED");
        assertThat(worklists.list(author(), ORGANIZATION, FACILITY, "PATHOLOGY"))
                .extracting(ExecutionWorklistItem::patientId).contains(PATIENT);

        UUID eventId = completed.events().getFirst().specialtyExecutionEventId();
        assertThatThrownBy(() -> jdbc.sql("""
                update specialty_execution_case_event set note = '篡改'
                where tenant_id = :tenant and specialty_execution_event_id = :event
                """).param("tenant", TENANT).param("event", eventId).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void markReadyRejectsMissingOrDuplicatedPatientIdentifiers() {
        Map<String, Object> invalid = readyPathologyPayload();
        invalid.put("patient_identifier_two", invalid.get("patient_identifier_one"));
        SpecialtyExecutionCaseWire draft = cases.create(author(), "path-invalid-" + UUID.randomUUID(),
                new SpecialtyExecutionCaseCreateRequestWire(
                        ORGANIZATION, FACILITY, PATIENT, ENCOUNTER,
                        SpecialtyExecutionCaseCreateRequestWire.DomainValue.PATHOLOGY,
                        "双标识失败病例", SpecialtyExecutionCaseCreateRequestWire.PriorityValue.ROUTINE,
                        null, invalid));
        assertThatThrownBy(() -> transition(author(), draft, "MARK_READY"))
                .isInstanceOf(ExecutionWorklistException.class)
                .satisfies(error -> assertThat(((ExecutionWorklistException) error).code())
                        .isEqualTo("PATIENT_TWO_IDENTIFIER_CHECK_FAILED"));
    }

    @Test
    void deviceMonitoringFailsClosedForUnverifiedBindingAndClockDrift() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of(
                "patient_identifier_one", "ZY202608310001",
                "patient_identifier_two", "WB-ICU-0001",
                "patient_verification_method", "住院号+腕带扫码",
                "device_id", "ICU-MON-01",
                "device_type", "床旁监护仪",
                "binding_verified", false,
                "clock_offset_seconds", 2,
                "alarm_policy", "ICU-ADULT-v3",
                "monitoring_parameters", "HR/SpO2/NIBP，1秒采样"));
        SpecialtyExecutionCaseWire draft = cases.create(author(), "device-create-" + UUID.randomUUID(),
                new SpecialtyExecutionCaseCreateRequestWire(
                        ORGANIZATION, FACILITY, PATIENT, ENCOUNTER,
                        SpecialtyExecutionCaseCreateRequestWire.DomainValue.DEVICE_MONITORING,
                        "ICU床旁监护绑定", SpecialtyExecutionCaseCreateRequestWire.PriorityValue.URGENT,
                        null, payload));
        assertThatThrownBy(() -> transition(author(), draft, "MARK_READY"))
                .isInstanceOf(ExecutionWorklistException.class)
                .satisfies(error -> assertThat(((ExecutionWorklistException) error).code())
                        .isEqualTo("DEVICE_PATIENT_BINDING_NOT_VERIFIED"));

        payload.put("binding_verified", true);
        payload.put("clock_offset_seconds", 45);
        SpecialtyExecutionCaseWire drifted = cases.create(author(), "device-create-" + UUID.randomUUID(),
                new SpecialtyExecutionCaseCreateRequestWire(
                        ORGANIZATION, FACILITY, PATIENT, ENCOUNTER,
                        SpecialtyExecutionCaseCreateRequestWire.DomainValue.DEVICE_MONITORING,
                        "ICU监护时钟偏移", SpecialtyExecutionCaseCreateRequestWire.PriorityValue.URGENT,
                        null, payload));
        assertThatThrownBy(() -> transition(author(), drifted, "MARK_READY"))
                .isInstanceOf(ExecutionWorklistException.class)
                .satisfies(error -> assertThat(((ExecutionWorklistException) error).code())
                        .isEqualTo("DEVICE_CLOCK_NOT_SYNCHRONIZED"));
    }

    @Test
    void therapyCompletionRecordsTheAuthoritativeOrderExecutionEvent() {
        UUID taskId = seedActiveTreatmentTask();
        SpecialtyExecutionCaseWire draft = cases.create(author(), "therapy-create-" + UUID.randomUUID(),
                new SpecialtyExecutionCaseCreateRequestWire(
                        ORGANIZATION, FACILITY, PATIENT, ENCOUNTER,
                        SpecialtyExecutionCaseCreateRequestWire.DomainValue.THERAPY,
                        "术后呼吸康复治疗", SpecialtyExecutionCaseCreateRequestWire.PriorityValue.ROUTINE,
                        null, readyTherapyPayload(taskId)));

        SpecialtyExecutionCaseWire ready = transition(author(), draft, "MARK_READY");
        SpecialtyExecutionCaseWire started = transition(author(), ready, "START");
        SpecialtyExecutionCaseWire review = transition(author(), started, "REQUEST_REVIEW");
        SpecialtyExecutionCaseWire completed = transition(author(), review, "COMPLETE");

        assertThat(completed.status()).isEqualTo(SpecialtyExecutionCaseWire.StatusValue.COMPLETED);
        assertThat(jdbc.sql("""
                select task_state from order_execution_task
                where tenant_id = :tenant and execution_task_id = :task
                """).param("tenant", TENANT).param("task", taskId).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("""
                select count(*) from order_execution_event
                where tenant_id = :tenant and execution_task_id = :task and event_type = 'COMPLETED'
                """).param("tenant", TENANT).param("task", taskId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void anesthesiaFailsClosedWhenFastingOrConsentIsNotConfirmed() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of(
                "patient_identifier_one", "ZY202608310002",
                "patient_identifier_two", "WB-OR-0002",
                "patient_verification_method", "住院号+腕带扫码",
                "surgical_procedure_id", "SS202608310002",
                "asa_class", "ASA II",
                "anesthesia_method", "全身麻醉",
                "fasting_confirmed", false,
                "consent_confirmed", true));
        SpecialtyExecutionCaseWire draft = cases.create(author(), "anesthesia-create-" + UUID.randomUUID(),
                new SpecialtyExecutionCaseCreateRequestWire(
                        ORGANIZATION, FACILITY, PATIENT, ENCOUNTER,
                        SpecialtyExecutionCaseCreateRequestWire.DomainValue.ANESTHESIA,
                        "术前麻醉核查", SpecialtyExecutionCaseCreateRequestWire.PriorityValue.ROUTINE,
                        null, payload));

        assertThatThrownBy(() -> transition(author(), draft, "MARK_READY"))
                .isInstanceOf(ExecutionWorklistException.class)
                .satisfies(error -> assertThat(((ExecutionWorklistException) error).code())
                        .isEqualTo("ANESTHESIA_PREOPERATIVE_VERIFICATION_FAILED"));
    }

    private SpecialtyExecutionCaseWire transition(ClinicalIdentity identity, SpecialtyExecutionCaseWire item, String action) {
        return cases.transition(identity, item.specialtyExecutionCaseId(), "path-transition-" + UUID.randomUUID(),
                new SpecialtyExecutionCaseTransitionRequestWire(
                        ORGANIZATION, FACILITY, PATIENT, ENCOUNTER,
                        SpecialtyExecutionCaseTransitionRequestWire.ActionValue.valueOf(action),
                        item.rowVersion(), "测试状态迁移并保留业务证据"));
    }

    private Map<String, Object> readyPathologyPayload() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of(
                "patient_identifier_one", "MZ202608310001",
                "patient_identifier_two", "1978-04-16",
                "patient_verification_method", "门诊号加出生日期",
                "accession_number", "BL202608310001",
                "specimen_type", "手术切除标本",
                "specimen_site", "左肺上叶",
                "fixative", "10%中性福尔马林固定12小时"));
        payload.put("lab_specimen_id", seedReceivedTissueSpecimen().toString());
        return payload;
    }

    private Map<String, Object> readyTherapyPayload(UUID taskId) {
        return new java.util.LinkedHashMap<>(Map.of(
                "patient_identifier_one", "MZ202608310001",
                "patient_identifier_two", "1978-04-16",
                "patient_verification_method", "门诊号加出生日期",
                "therapy_code", "REHAB-PT-001",
                "order_execution_task_id", taskId.toString(),
                "course_number", 1,
                "session_number", 1,
                "verification_method", "医嘱条码和治疗卡双核对"));
    }

    private UUID seedActiveTreatmentTask() {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String code = "REHAB-PT-" + taskId.toString().substring(0, 8);
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at)
                values (:tenant, :order, :patient, :encounter, :facility, 'TEMPORARY', 'ACTIVE',
                  '术后呼吸康复治疗', :author, :author, now())
                """).param("tenant", TENANT).param("order", orderId).param("patient", PATIENT)
                .param("encounter", ENCOUNTER).param("facility", FACILITY).param("author", USER).update();
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, item_state)
                values (:tenant, :item, :order, 'TREATMENT', :code, '呼吸康复训练', 1, '次', 'ACTIVE')
                """).param("tenant", TENANT).param("item", itemId).param("order", orderId).param("code", code).update();
        jdbc.sql("""
                insert into order_execution_task(
                  tenant_id, execution_task_id, order_id, order_item_id, patient_id, encounter_id,
                  task_state, requested_quantity, performed_quantity, quantity_unit)
                values (:tenant, :task, :order, :item, :patient, :encounter,
                  'PENDING', 1, 0, '次')
                """).param("tenant", TENANT).param("task", taskId).param("order", orderId).param("item", itemId)
                .param("patient", PATIENT).param("encounter", ENCOUNTER).update();
        return taskId;
    }

    private UUID seedReceivedTissueSpecimen() {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID specimenId = UUID.randomUUID();
        String code = "PATH-TISSUE-" + specimenId.toString().substring(0, 8);
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope,
                  status, clinical_indication, author_user_id)
                values (:tenant, :order, :patient, :encounter, :facility, 'TEMPORARY',
                  'DRAFT', '病理组织标本留存', :author)
                """).param("tenant", TENANT).param("order", orderId).param("patient", PATIENT)
                .param("encounter", ENCOUNTER).param("facility", FACILITY).param("author", USER).update();
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, item_state)
                values (:tenant, :item, :order, 'LAB', :code, '组织病理标本', 1, '份', 'DRAFT')
                """).param("tenant", TENANT).param("item", itemId).param("order", orderId).param("code", code).update();
        jdbc.sql("""
                insert into lab_specimen(
                  tenant_id, specimen_id, order_id, order_item_id, patient_id, encounter_id,
                  facility_id, specimen_type, collection_status, collected_at, collected_by,
                  received_at, received_by)
                values (:tenant, :specimen, :order, :item, :patient, :encounter,
                  :facility, 'TISSUE', 'RECEIVED', now(), :actor, now(), :actor)
                """).param("tenant", TENANT).param("specimen", specimenId).param("order", orderId)
                .param("item", itemId).param("patient", PATIENT).param("encounter", ENCOUNTER)
                .param("facility", FACILITY).param("actor", USER).update();
        return specimenId;
    }
}
