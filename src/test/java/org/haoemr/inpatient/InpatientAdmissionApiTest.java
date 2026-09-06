package org.haoemr.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class InpatientAdmissionApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String DEPARTMENT = "018f0000-0000-7000-8000-00000000aa08";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void givenAnAuthorizedWardAndFreeBed_whenAdmitting_thenOverviewTasksAuditAndWorklistAreConsistent()
            throws Exception {
        Ward ward = seedWardAndBed();
        Encounter context = seedInpatientEncounter("合成住院患者甲");
        Lease encounterLease = issueLease(context.patientId(), context.encounterId());
        Lease organizationLease = issueLease(null, null);
        Instant admittedAt = Instant.now().minusSeconds(60 * 60L);

        HttpResponse<String> availableBoard = send(
                "GET", "/api/v1/inpatient/bed-board?ward_id=" + ward.wardId(),
                null, organizationLease, null, null, null);
        assertThat(availableBoard.statusCode()).isEqualTo(200);
        assertThat(availableBoard.body()).contains(
                ward.bedId().toString(), "\"occupancy_status\":\"AVAILABLE\"");

        HttpResponse<String> response = admit(ward, context, encounterLease, admittedAt, UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        String admissionId = body.path("admission").path("admission_id").stringValue();
        assertThat(body.path("admission").path("status").stringValue()).isEqualTo("ADMITTED");
        assertThat(body.path("bed_label").stringValue()).isEqualTo("01");
        assertThat(body.path("document_tasks").size()).isEqualTo(3);
        assertThat(body.path("data_watermark").stringValue()).hasSize(64);
        assertThat(jdbc.sql("""
                select count(*) from bed_occupancy where tenant_id = cast(:tenant as uuid)
                  and admission_id = cast(:admission as uuid) and ended_at is null
                """).param("tenant", TENANT).param("admission", admissionId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = cast(:tenant as uuid)
                  and resource_id = cast(:admission as uuid) and action_code = 'INPATIENT_ADMITTED'
                """).param("tenant", TENANT).param("admission", admissionId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event where tenant_id = cast(:tenant as uuid)
                  and aggregate_id = cast(:admission as uuid) and event_type = 'InpatientAdmitted'
                """).param("tenant", TENANT).param("admission", admissionId).query(Long.class).single()).isEqualTo(1);

        HttpResponse<String> worklist = send("GET", "/api/v1/inpatient/worklist?ward_id=" + ward.wardId(),
                null, organizationLease, null, null, null);
        assertThat(worklist.statusCode()).isEqualTo(200);
        assertThat(worklist.body()).contains(admissionId, "合成住院患者甲", "pending_task_count");
        HttpResponse<String> occupiedBoard = send(
                "GET", "/api/v1/inpatient/bed-board?ward_id=" + ward.wardId(),
                null, organizationLease, null, null, null);
        assertThat(occupiedBoard.statusCode()).isEqualTo(200);
        assertThat(occupiedBoard.body()).contains(
                admissionId, "合成住院患者甲", "\"occupancy_status\":\"OCCUPIED\"");

        HttpResponse<String> rules = send(
                "GET", "/api/v1/inpatient/document-rules", null,
                organizationLease, null, null, null);
        assertThat(rules.statusCode()).isEqualTo(200);
        JsonNode ruleCatalog = objectMapper.readTree(rules.body());
        assertThat(ruleCatalog.size()).isEqualTo(16);
        assertThat(rules.body()).contains(
                "入院记录", "日常病程记录", "主任医师查房记录", "死亡记录", "四级审签文书");
        assertThat(jdbc.sql("""
                select count(*) from inpatient_document_task
                where tenant_id = cast(:tenant as uuid) and admission_id = cast(:admission as uuid)
                  and occurrence_key = 'ADMISSION' and rule_version = 1
                """).param("tenant", TENANT).param("admission", admissionId)
                .query(Long.class).single()).isEqualTo(3);

        String dailyOccurrence = "DAILY-" + UUID.randomUUID();
        HttpResponse<String> dailyTask = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/document-tasks", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "rule_code":"IP-DAILY-COURSE","event_occurred_at":"%s",
                 "occurrence_key":"%s"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                Instant.now(), dailyOccurrence), encounterLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(dailyTask.statusCode()).isEqualTo(201);
        assertThat(dailyTask.body()).contains("WS445.5.DAILY_COURSE_RECORD", "PENDING");
        HttpResponse<String> duplicateDailyTask = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/document-tasks", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "rule_code":"IP-DAILY-COURSE","event_occurred_at":"%s",
                 "occurrence_key":"%s"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                Instant.now(), dailyOccurrence), encounterLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(duplicateDailyTask.statusCode()).isEqualTo(409);
        assertThat(duplicateDailyTask.body()).contains("DOCUMENT_TASK_OCCURRENCE_CONFLICT");

        String rescueSourceKey = "RESCUE-" + UUID.randomUUID();
        HttpResponse<String> rescueEvent = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/clinical-events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"RESCUE_COMPLETED","occurred_at":"%s","summary":"抢救结束，生命体征趋稳",
                 "source_system":"HAOEMR-CLINICAL","source_event_key":"%s"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                Instant.now(), rescueSourceKey), encounterLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(rescueEvent.statusCode()).isEqualTo(201);
        JsonNode rescueBody = objectMapper.readTree(rescueEvent.body());
        String rescueEventId = rescueBody.path("clinical_event_id").stringValue();
        String rescueTaskId = rescueBody.path("document_task_id").stringValue();
        assertThat(rescueBody.path("event_type").stringValue()).isEqualTo("RESCUE_COMPLETED");
        assertThat(jdbc.sql("""
                select count(*) from inpatient_document_task
                where tenant_id = cast(:tenant as uuid) and task_id = cast(:task as uuid)
                  and document_type_code = 'WS445.5.RESCUE_RECORD'
                  and source_event_id = cast(:event as uuid)
                """).param("tenant", TENANT).param("task", rescueTaskId).param("event", rescueEventId)
                .query(Long.class).single()).isEqualTo(1);
        HttpResponse<String> duplicateRescueEvent = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/clinical-events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"RESCUE_COMPLETED","occurred_at":"%s","summary":"重复来源事件",
                 "source_system":"HAOEMR-CLINICAL","source_event_key":"%s"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                Instant.now(), rescueSourceKey), encounterLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(duplicateRescueEvent.statusCode()).isEqualTo(409);
        assertThat(duplicateRescueEvent.body()).contains("CLINICAL_EVENT_SOURCE_CONFLICT");

        HttpResponse<String> overview = send("GET", "/api/v1/inpatient/admissions/" + admissionId + "/overview",
                null, encounterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(overview.statusCode()).isEqualTo(200);
        assertThat(overview.body()).contains(admissionId, "WS445.5.FIRST_COURSE_RECORD");

        JsonNode task = null;
        for (JsonNode candidate : body.path("document_tasks")) {
            if ("WS445.5.FIRST_COURSE_RECORD".equals(
                    candidate.path("document_type_code").stringValue())) {
                task = candidate;
                break;
            }
        }
        assertThat(task).as("admission should create the first-course document task").isNotNull();
        String taskId = task.path("task_id").stringValue();
        jdbc.sql("""
                update inpatient_document_task set required_signature_level = 'MEDICAL_RECORDS'
                where tenant_id = cast(:tenant as uuid) and task_id = cast(:task as uuid)
                """).param("tenant", TENANT).param("task", taskId).update();
        HttpResponse<String> started = send(
                "POST", "/api/v1/inpatient/document-tasks/" + taskId + "/documents", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","admission_id":"%s","expected_task_row_version":1,
                 "sections":{"case_features":"反复心悸，入院后完善检查",
                   "provisional_diagnosis":"心律失常待查","diagnostic_basis":"症状与心电图提示",
                   "differential_diagnosis":"排除结构性心脏病","risk_assessment":"当前生命体征平稳",
                   "assessment":"心律失常待查","treatment_plan":"住院诊疗计划",
                   "communication":"已向患者说明诊疗计划"}}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), admissionId),
                encounterLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(started.statusCode()).isEqualTo(201);
        JsonNode document = objectMapper.readTree(started.body());
        String documentId = document.path("document_id").stringValue();
        String documentVersionId = document.path("document_version_id").stringValue();
        assertThat(document.path("document_type_code").stringValue())
                .isEqualTo(task.path("document_type_code").stringValue());

        HttpResponse<String> afterStart = send(
                "GET", "/api/v1/inpatient/admissions/" + admissionId + "/overview",
                null, encounterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(afterStart.statusCode()).isEqualTo(200);
        assertThat(afterStart.body()).contains("IN_PROGRESS", "working_document_id", documentId);

        HttpResponse<String> quality = send(
                "POST", "/api/v1/documents/" + documentId + "/quality-checks", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                encounterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(quality.statusCode()).isEqualTo(200);
        assertThat(quality.body()).isEqualTo("[]");

        HttpResponse<String> skippedAuthor = send(
                "POST", "/api/v1/documents/" + documentId + "/signatures", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s","expected_row_version":1,
                 "signature_role":"ATTENDING"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                encounterLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(skippedAuthor.statusCode()).isEqualTo(409);
        assertThat(skippedAuthor.body()).contains("SIGNATURE_SEQUENCE_INVALID");

        HttpResponse<String> authorSigned = send(
                "POST", "/api/v1/documents/" + documentId + "/signatures", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s","expected_row_version":1,
                 "signature_role":"AUTHOR"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                encounterLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(authorSigned.statusCode()).isEqualTo(201);

        HttpResponse<String> afterAuthor = send(
                "GET", "/api/v1/inpatient/admissions/" + admissionId + "/overview",
                null, encounterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(afterAuthor.statusCode()).isEqualTo(200);
        assertThat(afterAuthor.body()).contains("IN_PROGRESS", "\"current_signature_level\":\"AUTHOR\"",
                "\"next_signature_level\":\"ATTENDING\"", "\"review_status\":\"IN_REVIEW\"");
        assertThat(jdbc.sql("""
                select current_signature_level || ':' || review_status
                from document_signature_policy
                where tenant_id = cast(:tenant as uuid) and document_id = cast(:document as uuid)
                  and document_version_id = cast(:version as uuid)
                """).param("tenant", TENANT).param("document", documentId).param("version", documentVersionId)
                .query(String.class).single()).isEqualTo("AUTHOR:IN_REVIEW");

        Actor attending = seedReviewer("ATTENDING_PHYSICIAN");
        Lease attendingLease = issueLease(context.patientId(), context.encounterId(), attending);
        HttpResponse<String> rejected = sendAs(
                "POST", "/api/v1/documents/" + documentId + "/review-rejections", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s","expected_row_version":2,
                 "rejection_level":"ATTENDING","reason":"补充鉴别诊断依据后重新提交"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                attendingLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString(), attending);
        assertThat(rejected.statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("""
                select review_status from document_signature_policy
                where tenant_id = cast(:tenant as uuid) and document_id = cast(:document as uuid)
                  and document_version_id = cast(:version as uuid)
                """).param("tenant", TENANT).param("document", documentId).param("version", documentVersionId)
                .query(String.class).single()).isEqualTo("REJECTED");
        assertThat(jdbc.sql("""
                select count(*) from signature_evidence
                where tenant_id = cast(:tenant as uuid) and document_version_id = cast(:version as uuid)
                  and signature_status = 'REVOKED'
                """).param("tenant", TENANT).param("version", documentVersionId)
                .query(Long.class).single()).isEqualTo(1);

        HttpResponse<String> rewritten = send(
                "PUT", "/api/v1/documents/" + documentId + "/draft", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","expected_row_version":3,
                 "sections":{"case_features":"反复心悸，入院后完善检查",
                   "provisional_diagnosis":"心律失常待查","diagnostic_basis":"症状与心电图提示",
                   "differential_diagnosis":"已补充结构性心脏病鉴别依据",
                   "risk_assessment":"当前生命体征平稳","assessment":"心悸待查，已补充鉴别诊断依据",
                   "treatment_plan":"住院诊疗计划","communication":"已向患者说明诊疗计划"}}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                encounterLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(rewritten.statusCode()).isEqualTo(200);
        documentVersionId = objectMapper.readTree(rewritten.body()).path("document_version_id").stringValue();
        HttpResponse<String> rewrittenQuality = send(
                "POST", "/api/v1/documents/" + documentId + "/quality-checks", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                encounterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(rewrittenQuality.statusCode()).isEqualTo(200);

        HttpResponse<String> rewrittenAuthorSigned = send(
                "POST", "/api/v1/documents/" + documentId + "/signatures", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s","expected_row_version":4,
                 "signature_role":"AUTHOR"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                encounterLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(rewrittenAuthorSigned.statusCode()).isEqualTo(201);

        HttpResponse<String> signed = sendAs(
                "POST", "/api/v1/documents/" + documentId + "/signatures", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s","expected_row_version":5,
                 "signature_role":"ATTENDING"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                attendingLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString(), attending);
        assertThat(signed.statusCode()).isEqualTo(201);

        HttpResponse<String> afterAttending = send(
                "GET", "/api/v1/inpatient/admissions/" + admissionId + "/overview",
                null, encounterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(afterAttending.statusCode()).isEqualTo(200);
        assertThat(afterAttending.body()).contains("\"current_signature_level\":\"ATTENDING\"",
                "\"next_signature_level\":\"CHIEF\"", "\"review_status\":\"IN_REVIEW\"");

        Actor chief = seedReviewer("CHIEF_PHYSICIAN");
        Lease chiefLease = issueLease(context.patientId(), context.encounterId(), chief);
        HttpResponse<String> chiefSigned = sendAs(
                "POST", "/api/v1/documents/" + documentId + "/signatures", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s","expected_row_version":6,
                 "signature_role":"CHIEF"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                chiefLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString(), chief);
        assertThat(chiefSigned.statusCode()).isEqualTo(201);

        HttpResponse<String> afterChief = send(
                "GET", "/api/v1/inpatient/admissions/" + admissionId + "/overview",
                null, encounterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(afterChief.statusCode()).isEqualTo(200);
        assertThat(afterChief.body()).contains("\"current_signature_level\":\"CHIEF\"",
                "\"next_signature_level\":\"MEDICAL_RECORDS\"", "\"review_status\":\"IN_REVIEW\"");

        Actor medicalRecords = seedReviewer("MEDICAL_RECORDS");
        Lease medicalRecordsLease = issueLease(context.patientId(), context.encounterId(), medicalRecords);
        HttpResponse<String> medicalRecordsSigned = sendAs(
                "POST", "/api/v1/documents/" + documentId + "/signatures", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","document_version_id":"%s","expected_row_version":7,
                 "signature_role":"MEDICAL_RECORDS"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), documentVersionId),
                medicalRecordsLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString(), medicalRecords);
        assertThat(medicalRecordsSigned.statusCode()).isEqualTo(201);
        assertThat(jdbc.sql("""
                select count(distinct signer_user_id) from signature_evidence
                where tenant_id = cast(:tenant as uuid) and document_version_id = cast(:version as uuid)
                  and signature_status <> 'REVOKED'
                """).param("tenant", TENANT).param("version", documentVersionId)
                .query(Long.class).single()).isEqualTo(4);

        HttpResponse<String> afterSign = send(
                "GET", "/api/v1/inpatient/admissions/" + admissionId + "/overview",
                null, encounterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(afterSign.statusCode()).isEqualTo(200);
        JsonNode completedTasks = objectMapper.readTree(afterSign.body()).path("document_tasks");
        JsonNode completed = null;
        for (JsonNode candidate : completedTasks) {
            if (candidate.path("task_id").stringValue().equals(taskId)) completed = candidate;
        }
        assertThat(completed).isNotNull();
        assertThat(completed.path("task_state").stringValue()).isEqualTo("COMPLETED");
        assertThat(completed.path("completed_document_id").stringValue()).isEqualTo(documentId);
        assertThat(completed.path("review_status").stringValue()).isEqualTo("COMPLETED");
        assertThat(completed.path("next_signature_level").isNull()).isTrue();
        assertThat(jdbc.sql("""
                select count(*) from outbox_event where tenant_id = cast(:tenant as uuid)
                  and aggregate_id = cast(:task as uuid)
                  and event_type = 'InpatientDocumentTaskCompleted'
                """).param("tenant", TENANT).param("task", taskId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void consultationRequiresIndependentAcceptanceSignedOpinionAndRequesterCompletion() throws Exception {
        Ward ward = seedWardAndBed();
        Encounter context = seedInpatientEncounter("合成住院会诊患者");
        Lease requesterLease = issueLease(context.patientId(), context.encounterId());
        HttpResponse<String> admissionResponse = admit(
                ward, context, requesterLease, Instant.now().minusSeconds(60), UUID.randomUUID().toString());
        assertThat(admissionResponse.statusCode()).isEqualTo(201);
        String admissionId = objectMapper.readTree(admissionResponse.body())
                .path("admission").path("admission_id").stringValue();

        String createBody = """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "requested_department":"心血管内科","urgency":"URGENT",
                 "reason":"反复胸痛伴心电图异常",
                 "clinical_question":"请评估是否需要急诊冠脉介入治疗",
                 "due_at":"%s"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                Instant.now().plusSeconds(3600));
        String createKey = UUID.randomUUID().toString();
        HttpResponse<String> created = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/consultations",
                createBody, requesterLease, context.patientId().toString(), context.encounterId().toString(), createKey);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode requested = objectMapper.readTree(created.body());
        String consultationId = requested.path("consultation_id").stringValue();
        assertThat(requested.path("status").stringValue()).isEqualTo("REQUESTED");
        assertThat(requested.path("row_version").longValue()).isEqualTo(1);
        assertThat(requested.path("data_watermark").stringValue()).hasSize(64);

        HttpResponse<String> duplicateCreate = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/consultations",
                createBody, requesterLease, context.patientId().toString(), context.encounterId().toString(), createKey);
        assertThat(duplicateCreate.statusCode()).isEqualTo(409);
        assertThat(duplicateCreate.body()).contains("IDEMPOTENCY_REPLAY");

        String actionV1 = actionBody(context, 1);
        HttpResponse<String> selfAccept = send(
                "POST", "/api/v1/inpatient/consultations/" + consultationId + "/accept",
                actionV1, requesterLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(selfAccept.statusCode()).isEqualTo(409);
        assertThat(selfAccept.body()).contains("CONSULTATION_SELF_ACCEPT_FORBIDDEN");

        Actor consultant = seedReviewer("CONSULTING_PHYSICIAN");
        jdbc.sql("""
                insert into ward_role_scope(tenant_id,ward_id,role_assignment_id)
                values (cast(:tenant as uuid),:ward,cast(:role as uuid))
                """).param("tenant", TENANT).param("ward", ward.wardId())
                .param("role", consultant.roleAssignmentId()).update();
        Lease consultantLease = issueLease(context.patientId(), context.encounterId(), consultant);
        HttpResponse<String> accepted = sendAs(
                "POST", "/api/v1/inpatient/consultations/" + consultationId + "/accept",
                actionV1, consultantLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString(), consultant);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains("\"status\":\"ACCEPTED\"", "\"row_version\":2");

        HttpResponse<String> staleAccept = sendAs(
                "POST", "/api/v1/inpatient/consultations/" + consultationId + "/accept",
                actionV1, consultantLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString(), consultant);
        assertThat(staleAccept.statusCode()).isEqualTo(409);
        assertThat(staleAccept.body()).contains("CONSULTATION_VERSION_CONFLICT");

        HttpResponse<String> signed = sendAs(
                "POST", "/api/v1/inpatient/consultations/" + consultationId + "/opinions", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":2,"opinion":"考虑急性冠脉综合征高危",
                 "recommendation":"建议完善肌钙蛋白动态检测并启动急诊冠脉造影准备"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                consultantLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString(), consultant);
        assertThat(signed.statusCode()).isEqualTo(200);
        assertThat(signed.body()).contains("\"status\":\"OPINION_SIGNED\"", "\"row_version\":3");

        HttpResponse<String> completed = send(
                "POST", "/api/v1/inpatient/consultations/" + consultationId + "/complete",
                actionBody(context, 3), requesterLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(completed.body()).contains("\"status\":\"COMPLETED\"", "\"row_version\":4");

        assertThat(jdbc.sql("""
                select count(*) from clinical_task where tenant_id=cast(:tenant as uuid)
                  and source_type='CONSULTATION' and source_id=cast(:consultation as uuid)
                  and task_type='CONSULTATION_RESPONSE' and state='COMPLETED'
                  and task_rule_config_id is not null and rule_snapshot is not null
                """).param("tenant", TENANT).param("consultation", consultationId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select string_agg(event_type, '>' order by occurred_at, task_event_id)
                from clinical_task_event where tenant_id=cast(:tenant as uuid) and task_id=(
                  select task_id from clinical_task where tenant_id=cast(:tenant as uuid)
                    and source_type='CONSULTATION' and source_id=cast(:consultation as uuid))
                """).param("tenant", TENANT).param("consultation", consultationId)
                .query(String.class).single()).isEqualTo("CREATED>SOURCE_COMPLETED");

        HttpResponse<String> listed = send(
                "GET", "/api/v1/inpatient/admissions/" + admissionId + "/consultations",
                null, requesterLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(consultationId, "心血管内科", "COMPLETED");
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id=cast(:tenant as uuid)
                  and resource_id=cast(:consultation as uuid)
                  and resource_type='INPATIENT_CONSULTATION'
                """).param("tenant", TENANT).param("consultation", consultationId)
                .query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event where tenant_id=cast(:tenant as uuid)
                  and aggregate_id=cast(:consultation as uuid)
                  and aggregate_type='INPATIENT_CONSULTATION'
                """).param("tenant", TENANT).param("consultation", consultationId)
                .query(Long.class).single()).isEqualTo(4);
        assertThatThrownBy(() -> jdbc.sql("""
                update inpatient_consultation set accepted_at=now(), row_version=row_version+1
                where tenant_id=cast(:tenant as uuid) and consultation_id=cast(:consultation as uuid)
                """).param("tenant", TENANT).param("consultation", consultationId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pathwayBindsPublishedVersionAndRequiresSourceEvidenceOrIndependentlyApprovedVariance()
            throws Exception {
        Ward ward = seedWardAndBed();
        Encounter context = seedInpatientEncounter("合成临床路径患者");
        Lease authorLease = issueLease(context.patientId(), context.encounterId());
        HttpResponse<String> admissionResponse = admit(
                ward, context, authorLease, Instant.now().minusSeconds(60), UUID.randomUUID().toString());
        assertThat(admissionResponse.statusCode()).isEqualTo(201);
        String admissionId = objectMapper.readTree(admissionResponse.body())
                .path("admission").path("admission_id").stringValue();

        HttpResponse<String> workspace = send(
                "GET", "/api/v1/inpatient/admissions/" + admissionId + "/pathway-workspace",
                null, authorLease, context.patientId().toString(), context.encounterId().toString(), null);
        assertThat(workspace.statusCode()).isEqualTo(200);
        JsonNode catalog = objectMapper.readTree(workspace.body()).path("catalog");
        assertThat(catalog.size()).isGreaterThan(0);
        JsonNode selectedPathway = null;
        for (JsonNode candidate : catalog) {
            if ("HF-INPATIENT".equals(candidate.path("pathway_code").stringValue())) {
                selectedPathway = candidate;
                break;
            }
        }
        assertThat(selectedPathway).isNotNull();
        String pathwayVersionId = selectedPathway.path("pathway_version_id").stringValue();
        int pathwayVersionNo = selectedPathway.path("version_no").intValue();

        HttpResponse<String> enrolledResponse = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/pathways", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "pathway_version_id":"%s","admission_basis":"临床诊断为心力衰竭，已核对入径标准与患者意愿"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), pathwayVersionId),
                authorLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(enrolledResponse.statusCode()).isEqualTo(201);
        JsonNode instance = objectMapper.readTree(enrolledResponse.body());
        String instanceId = instance.path("pathway_instance_id").stringValue();
        assertThat(instance.path("version_no").intValue()).isEqualTo(pathwayVersionNo);
        assertThat(instance.path("current_stage_code").stringValue()).isEqualTo("ADMISSION_ASSESSMENT");
        assertThat(instance.path("row_version").longValue()).isEqualTo(1);
        assertThat(instance.path("data_watermark").stringValue()).hasSize(64);
        assertThat(jdbc.sql("""
                select count(*) from clinical_order_item item
                join clinical_order orders on orders.tenant_id=item.tenant_id and orders.order_id=item.order_id
                where item.tenant_id=cast(:tenant as uuid) and orders.encounter_id=:encounter
                  and item.catalog_code='LAB.TROPONIN.I'
                """).param("tenant", TENANT).param("encounter", context.encounterId())
                .query(Long.class).single()).isZero();

        UUID sourceDocumentId = UUID.randomUUID();
        UUID sourceDocumentVersionId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_document(
                  tenant_id,document_id,patient_id,encounter_id,document_type_code,status,
                  template_version_id,created_by)
                values (cast(:tenant as uuid),:document,:patient,:encounter,
                  'WS445.5.ADMISSION_RECORD','SIGNED',
                  (select version.template_version_id from clinical_document_template template
                   join clinical_document_template_version version on version.tenant_id=template.tenant_id
                     and version.template_id=template.template_id
                   where template.tenant_id=cast(:tenant as uuid)
                     and template.document_type_code='WS445.5.ADMISSION_RECORD'
                     and version.status='PUBLISHED' order by version.version_no desc limit 1),
                  cast(:user as uuid))
                """).param("tenant", TENANT).param("document", sourceDocumentId)
                .param("patient", context.patientId()).param("encounter", context.encounterId())
                .param("user", USER).update();
        jdbc.sql("""
                insert into clinical_document_version(
                  tenant_id,document_id,document_version_id,version_no,status,sections,
                  content_hash,author_user_id,signed_at)
                values (cast(:tenant as uuid),:document,:version,1,'SIGNED','{}'::jsonb,
                  repeat('a',64),cast(:user as uuid),now())
                """).param("tenant", TENANT).param("document", sourceDocumentId)
                .param("version", sourceDocumentVersionId).param("user", USER).update();
        jdbc.sql("""
                update clinical_document set current_version_id=:version
                where tenant_id=cast(:tenant as uuid) and document_id=:document
                """).param("version", sourceDocumentVersionId).param("tenant", TENANT)
                .param("document", sourceDocumentId).update();
        jdbc.sql("""
                update inpatient_document_task set task_state='COMPLETED',
                  completed_document_id=:document, working_document_id=:document, updated_at=now()
                where tenant_id=cast(:tenant as uuid) and admission_id=cast(:admission as uuid)
                  and document_type_code='WS445.5.ADMISSION_RECORD'
                """).param("document", sourceDocumentId).param("tenant", TENANT)
                .param("admission", admissionId).update();
        HttpResponse<String> refreshed = send(
                "POST", "/api/v1/inpatient/pathways/" + instanceId + "/refresh",
                actionBody(context, 1), authorLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(refreshed.statusCode()).isEqualTo(200);
        instance = objectMapper.readTree(refreshed.body());
        assertThat(instance.path("row_version").longValue()).isEqualTo(2);
        assertThat(findPathwayTask(instance, "ADMISSION_RECORD").path("state").stringValue())
                .isEqualTo("COMPLETED");
        assertThat(findPathwayTask(instance, "ADMISSION_RECORD").path("source_resource_id").stringValue())
                .isNotBlank();

        HttpResponse<String> blockedAdvance = send(
                "POST", "/api/v1/inpatient/pathways/" + instanceId + "/advance",
                actionBody(context, 2), authorLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(blockedAdvance.statusCode()).isEqualTo(409);
        assertThat(blockedAdvance.body()).contains("PATHWAY_REQUIRED_TASKS_OPEN");

        HttpResponse<String> blockedDischarge = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/discharges", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_admission_row_version":1,"discharge_diagnosis":"心力衰竭好转",
                 "disposition_code":"HOME",
                 "outstanding_task_waiver_reason":"即使文书申请豁免，活动路径仍必须先闭环"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                authorLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(blockedDischarge.statusCode()).isEqualTo(409);
        assertThat(blockedDischarge.body()).contains("DISCHARGE_PATHWAY_ACTIVE");

        Actor reviewer = seedReviewer("ATTENDING_PHYSICIAN");
        jdbc.sql("""
                insert into ward_role_scope(tenant_id,ward_id,role_assignment_id)
                values (cast(:tenant as uuid),:ward,cast(:role as uuid))
                """).param("tenant", TENANT).param("ward", ward.wardId())
                .param("role", reviewer.roleAssignmentId()).update();
        Lease reviewerLease = issueLease(context.patientId(), context.encounterId(), reviewer);

        long version = 2;
        for (String taskCode : new String[]{"FIRST_COURSE"}) {
            instance = requestAndApproveTaskWaiver(
                    instance, instanceId, taskCode, version, context, authorLease, reviewerLease, reviewer);
            version += 2;
        }
        HttpResponse<String> firstAdvance = send(
                "POST", "/api/v1/inpatient/pathways/" + instanceId + "/advance",
                actionBody(context, version), authorLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(firstAdvance.statusCode()).isEqualTo(200);
        instance = objectMapper.readTree(firstAdvance.body());
        version++;
        assertThat(instance.path("current_stage_code").stringValue()).isEqualTo("DIAGNOSIS_TREATMENT");

        instance = requestAndApproveTaskWaiver(
                instance, instanceId, "TROPONIN_RESULT", version, context, authorLease, reviewerLease, reviewer);
        version += 2;
        HttpResponse<String> secondAdvance = send(
                "POST", "/api/v1/inpatient/pathways/" + instanceId + "/advance",
                actionBody(context, version), authorLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(secondAdvance.statusCode()).isEqualTo(200);
        instance = objectMapper.readTree(secondAdvance.body());
        version++;
        assertThat(instance.path("current_stage_code").stringValue()).isEqualTo("DISCHARGE_PREPARATION");

        instance = requestAndApproveTaskWaiver(
                instance, instanceId, "DISCHARGE_RECORD", version, context, authorLease, reviewerLease, reviewer);
        version += 2;
        HttpResponse<String> completed = send(
                "POST", "/api/v1/inpatient/pathways/" + instanceId + "/complete",
                actionBody(context, version), authorLease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(completed.statusCode()).isEqualTo(200);
        JsonNode completedBody = objectMapper.readTree(completed.body());
        assertThat(completedBody.path("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(completedBody.path("completion_percent").intValue()).isEqualTo(100);
        assertThat(completedBody.path("pathway_version_id").stringValue()).isEqualTo(pathwayVersionId);
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id=cast(:tenant as uuid)
                  and resource_id=cast(:instance as uuid) and resource_type='INPATIENT_PATHWAY'
                """).param("tenant", TENANT).param("instance", instanceId)
                .query(Long.class).single()).isEqualTo(11);
        assertThatThrownBy(() -> jdbc.sql("""
                update inpatient_pathway_instance set pathway_version_id=gen_random_uuid(),
                  row_version=row_version+1 where tenant_id=cast(:tenant as uuid)
                  and pathway_instance_id=cast(:instance as uuid)
                """).param("tenant", TENANT).param("instance", instanceId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private JsonNode requestAndApproveTaskWaiver(
            JsonNode instance, String instanceId, String taskCode, long version, Encounter context,
            Lease authorLease, Lease reviewerLease, Actor reviewer) throws Exception {
        String taskId = findPathwayTask(instance, taskCode).path("pathway_task_id").stringValue();
        HttpResponse<String> requested = send(
                "POST", "/api/v1/inpatient/pathways/" + instanceId + "/variances", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d,"variance_type":"RESOURCE_UNAVAILABLE",
                 "reason":"合成回归验收：当前业务来源不可用，申请审核豁免",
                 "disposition":"WAIVE_TASK","affected_task_id":"%s"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), version, taskId),
                authorLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(requested.statusCode()).isEqualTo(201);
        JsonNode requestedBody = objectMapper.readTree(requested.body());
        String varianceId = requestedBody.path("variances").get(0).path("variance_id").stringValue();

        HttpResponse<String> selfReview = send(
                "POST", "/api/v1/inpatient/pathways/" + instanceId + "/variances/" + varianceId + "/review", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d,"decision":"APPROVE","review_note":"申请人不得自审"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), version + 1),
                authorLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(selfReview.statusCode()).isEqualTo(409);
        assertThat(selfReview.body()).contains("PATHWAY_VARIANCE_SELF_REVIEW_FORBIDDEN");

        HttpResponse<String> approved = sendAs(
                "POST", "/api/v1/inpatient/pathways/" + instanceId + "/variances/" + varianceId + "/review", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d,"decision":"APPROVE","review_note":"已核对临床原因及替代处置，同意本次例外"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), version + 1),
                reviewerLease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString(), reviewer);
        assertThat(approved.statusCode()).isEqualTo(200);
        JsonNode approvedBody = objectMapper.readTree(approved.body());
        assertThat(findPathwayTask(approvedBody, taskCode).path("state").stringValue()).isEqualTo("WAIVED");
        return approvedBody;
    }

    private static JsonNode findPathwayTask(JsonNode instance, String taskCode) {
        for (JsonNode stage : instance.path("stages")) {
            for (JsonNode task : stage.path("tasks")) {
                if (taskCode.equals(task.path("task_code").stringValue())) return task;
            }
        }
        throw new AssertionError("Missing pathway task " + taskCode);
    }

    @Test
    void givenAnOccupiedBedOrLostWardScope_whenAdmittingOrListing_thenTheRequestFailsClosed() throws Exception {
        Ward ward = seedWardAndBed();
        Encounter first = seedInpatientEncounter("合成住院患者乙");
        Lease firstLease = issueLease(first.patientId(), first.encounterId());
        HttpResponse<String> admitted = admit(
                ward, first, firstLease, Instant.now().minusSeconds(3 * 24 * 60 * 60L), UUID.randomUUID().toString());
        assertThat(admitted.statusCode()).isEqualTo(201);
        assertThat(admitted.body()).contains("OVERDUE");

        Encounter second = seedInpatientEncounter("合成住院患者丙");
        Lease secondLease = issueLease(second.patientId(), second.encounterId());
        HttpResponse<String> conflict = admit(
                ward, second, secondLease, Instant.now(), UUID.randomUUID().toString());
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("BED_OCCUPIED");
        assertThat(jdbc.sql("""
                select count(*) from inpatient_admission
                where tenant_id = cast(:tenant as uuid) and encounter_id = :encounter
                """).param("tenant", TENANT).param("encounter", second.encounterId()).query(Long.class).single()).isZero();

        jdbc.sql("""
                delete from ward_role_scope where tenant_id = cast(:tenant as uuid)
                  and ward_id = :ward and role_assignment_id = cast(:role as uuid)
                """).param("tenant", TENANT).param("ward", ward.wardId()).param("role", ROLE).update();
        Lease organizationLease = issueLease(null, null);
        HttpResponse<String> denied = send("GET", "/api/v1/inpatient/worklist?ward_id=" + ward.wardId(),
                null, organizationLease, null, null, null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("WARD_SCOPE_DENIED");
    }

    @Test
    void givenTwoAuthorizedWards_whenTransferring_thenOccupancyMovesAtomicallyAndOccupiedTargetRollsBack()
            throws Exception {
        Ward source = seedWardAndBed();
        Ward target = seedWardAndBed();
        Encounter context = seedInpatientEncounter("合成转科患者甲");
        Lease lease = issueLease(context.patientId(), context.encounterId());
        HttpResponse<String> admitted = admit(
                source, context, lease, Instant.now().minusSeconds(3600), UUID.randomUUID().toString());
        assertThat(admitted.statusCode()).isEqualTo(201);
        String admissionId = objectMapper.readTree(admitted.body()).path("admission").path("admission_id").stringValue();

        HttpResponse<String> transferred = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/transfers", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "target_ward_id":"%s","target_bed_id":"%s","expected_admission_row_version":1,
                 "reason":"专科诊疗需要"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                target.wardId(), target.bedId()), lease, context.patientId().toString(),
                context.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(transferred.statusCode()).isEqualTo(200);
        JsonNode transferredBody = objectMapper.readTree(transferred.body());
        assertThat(transferredBody.path("admission").path("ward_id").stringValue())
                .isEqualTo(target.wardId().toString());
        assertThat(transferredBody.path("admission").path("bed_id").stringValue())
                .isEqualTo(target.bedId().toString());
        assertThat(transferredBody.path("admission").path("row_version").longValue()).isEqualTo(2);
        assertThat(jdbc.sql("""
                select count(*) from bed_occupancy where tenant_id = cast(:tenant as uuid)
                  and admission_id = cast(:admission as uuid) and ended_at is not null
                  and end_reason = 'TRANSFER'
                """).param("tenant", TENANT).param("admission", admissionId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from inpatient_transfer where tenant_id = cast(:tenant as uuid)
                  and admission_id = cast(:admission as uuid) and status = 'COMPLETED'
                """).param("tenant", TENANT).param("admission", admissionId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from inpatient_document_task
                where tenant_id = cast(:tenant as uuid) and admission_id = cast(:admission as uuid)
                  and document_type_code = 'WS445.5.TRANSFER_RECORD'
                  and source_event_id is not null and occurrence_key like 'TRANSFER:%'
                """).param("tenant", TENANT).param("admission", admissionId)
                .query(Long.class).single()).isEqualTo(1);

        Ward otherSource = seedWardAndBed();
        Encounter other = seedInpatientEncounter("合成转科患者乙");
        Lease otherLease = issueLease(other.patientId(), other.encounterId());
        HttpResponse<String> otherAdmitted = admit(
                otherSource, other, otherLease, Instant.now(), UUID.randomUUID().toString());
        String otherAdmissionId = objectMapper.readTree(otherAdmitted.body())
                .path("admission").path("admission_id").stringValue();
        HttpResponse<String> conflict = send(
                "POST", "/api/v1/inpatient/admissions/" + otherAdmissionId + "/transfers", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "target_ward_id":"%s","target_bed_id":"%s","expected_admission_row_version":1,
                 "reason":"测试已占用目标床"}
                """.formatted(ORGANIZATION, FACILITY, other.patientId(), other.encounterId(),
                target.wardId(), target.bedId()), otherLease, other.patientId().toString(),
                other.encounterId().toString(), UUID.randomUUID().toString());
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("BED_OCCUPIED");
        assertThat(jdbc.sql("""
                select current_bed_id from inpatient_admission
                where tenant_id = cast(:tenant as uuid) and admission_id = cast(:admission as uuid)
                """).param("tenant", TENANT).param("admission", otherAdmissionId)
                .query(UUID.class).single()).isEqualTo(otherSource.bedId());
    }

    @Test
    void givenOutstandingDocuments_whenDischarging_thenItAlwaysBlocksAndLegacyWaiverCannotBypass()
            throws Exception {
        Ward ward = seedWardAndBed();
        Encounter context = seedInpatientEncounter("合成出院患者");
        Lease lease = issueLease(context.patientId(), context.encounterId());
        HttpResponse<String> admitted = admit(
                ward, context, lease, Instant.now().minusSeconds(86400), UUID.randomUUID().toString());
        String admissionId = objectMapper.readTree(admitted.body()).path("admission").path("admission_id").stringValue();

        HttpResponse<String> blocked = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/discharges", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_admission_row_version":1,"discharge_diagnosis":"心悸待查",
                 "disposition_code":"HOME"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(blocked.body()).contains("DISCHARGE_TASKS_OPEN");
        assertThat(jdbc.sql("""
                select count(*) from bed_occupancy where tenant_id = cast(:tenant as uuid)
                  and admission_id = cast(:admission as uuid) and ended_at is null
                """).param("tenant", TENANT).param("admission", admissionId)
                .query(Long.class).single()).isEqualTo(1);

        HttpResponse<String> legacyWaiverAttempt = send(
                "POST", "/api/v1/inpatient/admissions/" + admissionId + "/discharges", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_admission_row_version":1,"discharge_diagnosis":"心悸待查",
                 "disposition_code":"HOME",
                 "outstanding_task_waiver_reason":"合成回归测试：出院文书由后续演示流程补齐"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context.patientId().toString(), context.encounterId().toString(),
                UUID.randomUUID().toString());
        assertThat(legacyWaiverAttempt.statusCode()).isEqualTo(409);
        assertThat(legacyWaiverAttempt.body()).contains("DISCHARGE_TASKS_OPEN");
        assertThat(jdbc.sql("""
                select count(*) from bed_occupancy where tenant_id = cast(:tenant as uuid)
                  and admission_id = cast(:admission as uuid) and ended_at is null
                """).param("tenant", TENANT).param("admission", admissionId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select status from encounter where tenant_id = cast(:tenant as uuid) and encounter_id = :encounter
                """).param("tenant", TENANT).param("encounter", context.encounterId())
                .query(String.class).single()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.sql("""
                select count(*) from inpatient_discharge where tenant_id = cast(:tenant as uuid)
                  and admission_id = cast(:admission as uuid)
                """).param("tenant", TENANT).param("admission", admissionId)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                select count(*) from inpatient_document_task where tenant_id = cast(:tenant as uuid)
                  and admission_id = cast(:admission as uuid)
                  and task_state in ('PENDING', 'IN_PROGRESS', 'OVERDUE')
                """).param("tenant", TENANT).param("admission", admissionId)
                .query(Long.class).single()).isGreaterThan(0);
    }

    private Ward seedWardAndBed() {
        UUID wardId = UUID.randomUUID();
        UUID bedId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_ward(
                  tenant_id, facility_id, department_id, ward_id, ward_code, display_name, status)
                values (cast(:tenant as uuid), cast(:facility as uuid), cast(:department as uuid),
                  :ward, :code, '合成心内科病区', 'ACTIVE')
                """).param("tenant", TENANT).param("facility", FACILITY)
                .param("department", DEPARTMENT).param("ward", wardId)
                .param("code", "WARD-" + wardId.toString().substring(0, 8)).update();
        jdbc.sql("""
                insert into clinical_bed(tenant_id, bed_id, ward_id, bed_label, status)
                values (cast(:tenant as uuid), :bed, :ward, '01', 'ACTIVE')
                """).param("tenant", TENANT).param("bed", bedId).param("ward", wardId).update();
        jdbc.sql("""
                insert into ward_role_scope(tenant_id, ward_id, role_assignment_id)
                values (cast(:tenant as uuid), :ward, cast(:role as uuid))
                """).param("tenant", TENANT).param("ward", wardId).param("role", ROLE).update();
        return new Ward(wardId, bedId);
    }

    private Encounter seedInpatientEncounter(String displayName) {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, :name, 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId).param("name", displayName)
                .param("birth", LocalDate.of(1980, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(
                  tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-INPATIENT', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Encounter(patientId, encounterId);
    }

    private Actor seedReviewer(String roleCode) {
        UUID userId = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        jdbc.sql("""
                insert into app_user(tenant_id, user_id, external_subject, display_name, status)
                values (cast(:tenant as uuid), :user, :subject, '合成上级审签用户', 'ACTIVE')
                """).param("tenant", TENANT).param("user", userId)
                .param("subject", "synthetic-reviewer-" + userId).update();
        jdbc.sql("""
                insert into role_assignment(
                  tenant_id, role_assignment_id, user_id, organization_id, facility_id,
                  role_code, valid_from, status)
                values (cast(:tenant as uuid), :role, :user, cast(:organization as uuid),
                  cast(:facility as uuid), :role_code, now(), 'ACTIVE')
                """).param("tenant", TENANT).param("role", roleAssignmentId).param("user", userId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("role_code", roleCode).update();
        return new Actor(userId.toString(), roleAssignmentId.toString());
    }

    private HttpResponse<String> admit(
            Ward ward, Encounter context, Lease lease, Instant admittedAt, String idempotencyKey) throws Exception {
        return send("POST", "/api/v1/inpatient/admissions", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","ward_id":"%s","bed_id":"%s",
                 "attending_user_id":"%s","admitted_at":"%s","department_id":"%s",
                 "admission_source":"OUTPATIENT","admission_type":"ELECTIVE","condition_level":"GENERAL",
                 "admitting_diagnosis_code":"I50.9","admitting_diagnosis_text":"心力衰竭待诊",
                 "payment_method_code":"URBMI","identity_verification_method":"RESIDENT_ID",
                 "contact_name":"张敏","contact_relationship":"配偶","contact_phone":"13800138000"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                ward.wardId(), ward.bedId(), USER, admittedAt, DEPARTMENT), lease,
                context.patientId().toString(), context.encounterId().toString(), idempotencyKey);
    }

    private String actionBody(Encounter context, long expectedRowVersion) {
        return """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), expectedRowVersion);
    }

    private Lease issueLease(UUID patientId, UUID encounterId) throws Exception {
        return issueLease(patientId, encounterId, new Actor(USER, ROLE));
    }

    private Lease issueLease(UUID patientId, UUID encounterId, Actor actor) throws Exception {
        String patient = patientId == null ? "" : ",\"patient_id\":\"" + patientId + "\"";
        String encounter = encounterId == null ? "" : ",\"encounter_id\":\"" + encounterId + "\"";
        HttpRequest request = baseRequestAs("/api/v1/context-leases", actor)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organization_id\":\"" + ORGANIZATION
                        + "\",\"facility_id\":\"" + FACILITY + "\"" + patient + encounter
                        + ",\"purpose_code\":\"INPATIENT_WORKFLOW\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return new Lease(body.path("lease_id").stringValue(), body.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> send(
            String method, String path, String body, Lease lease,
            String patientId, String encounterId, String idempotencyKey) throws Exception {
        return sendAs(method, path, body, lease, patientId, encounterId, idempotencyKey, new Actor(USER, ROLE));
    }

    private HttpResponse<String> sendAs(
            String method, String path, String body, Lease lease,
            String patientId, String encounterId, String idempotencyKey, Actor actor) throws Exception {
        HttpRequest.Builder request = baseRequestAs(path, actor)
                .header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION)
                .header("X-Facility-Context", FACILITY);
        if (patientId != null) request.header("X-Patient-Context", patientId);
        if (encounterId != null) request.header("X-Encounter-Context", encounterId);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder baseRequest(String path) {
        return baseRequestAs(path, new Actor(USER, ROLE));
    }

    private HttpRequest.Builder baseRequestAs(String path, Actor actor) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", actor.userId())
                .header("X-HaoEMR-Role-Assignment-Ids", actor.roleAssignmentId());
    }

    private record Ward(UUID wardId, UUID bedId) {}
    private record Encounter(UUID patientId, UUID encounterId) {}
    private record Lease(String id, String watermark) {}
    private record Actor(String userId, String roleAssignmentId) {}
}
