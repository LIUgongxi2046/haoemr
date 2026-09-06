package org.haoemr.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ClinicalTaskApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";
    private static final String COLLABORATOR_ROLE = "018f0000-0000-7000-8000-00000000aa07";
    private static final String LAB_TECHNICIAN = "d1281807-abb4-3065-90e2-861ec72869fe";
    private static final String LAB_TECHNICIAN_ROLE = "eff92d2b-de27-377a-9d5e-12c8371d048b";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void givenAnOrderExecutionTask_whenViewedClaimedAndExecuted_thenOnlyTheSourceCanCompleteIt()
            throws Exception {
        Context context = seedContext();
        Lease lease = issueLease(context);
        JsonNode order = createAndSignLabOrder(context, lease);
        String executionTaskId = order.path("execution_tasks").get(0).path("execution_task_id").stringValue();

        HttpResponse<String> listed = send(
                "GET", "/api/v1/clinical-tasks?encounter_id=" + context.encounterId(), null,
                lease, context, null);
        assertThat(listed.statusCode()).isEqualTo(200);
        JsonNode tasks = objectMapper.readTree(listed.body());
        assertThat(tasks.size()).isEqualTo(1);
        JsonNode pending = tasks.get(0);
        String taskId = pending.path("task_id").stringValue();
        assertThat(pending.path("state").stringValue()).isEqualTo("PENDING");
        assertThat(pending.path("source_type").stringValue()).isEqualTo("ORDER_EXECUTION");
        assertThat(pending.path("source_id").stringValue()).isEqualTo(executionTaskId);
        assertThat(pending.path("source_route").stringValue()).isEqualTo("#/opd-orders");

        HttpResponse<String> viewed = taskCommand(context, lease, taskId, "views", 1);
        assertThat(viewed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(viewed.body()).path("state").stringValue()).isEqualTo("VIEWED");

        HttpResponse<String> claimed = taskCommand(context, lease, taskId, "claims", 2);
        assertThat(claimed.statusCode()).isEqualTo(200);
        JsonNode claimedTask = objectMapper.readTree(claimed.body());
        assertThat(claimedTask.path("state").stringValue()).isEqualTo("CLAIMED");
        assertThat(claimedTask.path("claimed_by").stringValue()).isEqualTo(USER);

        HttpResponse<String> staleClaim = taskCommand(context, lease, taskId, "claims", 2);
        assertThat(staleClaim.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(staleClaim.body()).path("error").path("code").stringValue())
                .isEqualTo("CLINICAL_TASK_VERSION_CONFLICT");

        HttpResponse<String> forbiddenCompletion = taskCommand(context, lease, taskId, "complete", 3);
        assertThat(forbiddenCompletion.statusCode()).isEqualTo(404);

        Lease labTechnicianLease = issueLeaseAs(context, LAB_TECHNICIAN, LAB_TECHNICIAN_ROLE);
        HttpResponse<String> execution = sendAs("POST", "/api/v1/executions/" + executionTaskId + "/events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"COMPLETED","expected_task_row_version":1,"performed_quantity":1,
                 "quantity_unit":"次","note":"来源业务完成"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                labTechnicianLease, context, UUID.randomUUID().toString(), LAB_TECHNICIAN, LAB_TECHNICIAN_ROLE);
        assertThat(execution.statusCode()).isEqualTo(200);

        JsonNode completed = objectMapper.readTree(send(
                "GET", "/api/v1/clinical-tasks?encounter_id=" + context.encounterId(), null,
                lease, context, null).body()).get(0);
        assertThat(completed.path("state").stringValue()).isEqualTo("COMPLETED");
        assertThat(completed.path("business_state").stringValue()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("""
                select string_agg(event_type, '>' order by occurred_at, task_event_id)
                from clinical_task_event where tenant_id = cast(:tenant as uuid)
                  and task_id = cast(:task_id as uuid)
                """).param("tenant", TENANT).param("task_id", taskId)
                .query(String.class).single()).isEqualTo("CREATED>VIEWED>CLAIMED>SOURCE_COMPLETED");
        assertThatThrownBy(() -> jdbc.sql("""
                update clinical_task_event set reason = 'tamper'
                where tenant_id = cast(:tenant as uuid) and task_id = cast(:task_id as uuid)
                """).param("tenant", TENANT).param("task_id", taskId).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenOverdueTask_whenExpiring_thenTransitionsToExpiredWithEvidenceAndIsIdempotent() throws Exception {
        Context context = seedContext();
        Lease lease = issueLease(context);
        UUID taskId = seedOverdueTask(context);

        HttpResponse<String> expired = send("POST", "/api/v1/clinical-tasks/expirations", null,
                lease, context, null);
        assertThat(expired.statusCode()).isEqualTo(200);
        JsonNode result = objectMapper.readTree(expired.body());
        assertThat(result.path("expired_count").intValue()).isEqualTo(1);
        assertThat(result.path("encounter_id").stringValue()).isEqualTo(context.encounterId().toString());

        assertThat(jdbc.sql("""
                select state from clinical_task where tenant_id = cast(:tenant as uuid) and task_id = :task
                """).param("tenant", TENANT).param("task", taskId).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("""
                select count(*) from clinical_task_event where tenant_id = cast(:tenant as uuid) and task_id = :task
                  and event_type = 'EXPIRED' and previous_state = 'PENDING'
                """).param("tenant", TENANT).param("task", taskId).query(Long.class).single()).isEqualTo(1);

        HttpResponse<String> again = send("POST", "/api/v1/clinical-tasks/expirations", null,
                lease, context, null);
        assertThat(objectMapper.readTree(again.body()).path("expired_count").intValue()).isZero();
        HttpResponse<String> activeList = send(
                "GET", "/api/v1/clinical-tasks?encounter_id=" + context.encounterId(), null,
                lease, context, null);
        assertThat(activeList.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(activeList.body())).isEmpty();
        HttpResponse<String> historyList = send(
                "GET", "/api/v1/clinical-tasks?encounter_id=" + context.encounterId() + "&include_expired=true", null,
                lease, context, null);
        assertThat(historyList.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(historyList.body()).get(0).path("state").stringValue()).isEqualTo("EXPIRED");
    }

    @Test
    void givenAClaimedTask_whenDelegatedTransferredAndEscalated_thenResponsibilityChainIsComplete()
            throws Exception {
        seedCollaborator();
        Context context = seedContext();
        Lease ownerLease = issueLease(context);
        JsonNode collaborators = objectMapper.readTree(send(
                "GET", "/api/v1/clinical-tasks/collaborators", null,
                ownerLease, context, null).body());
        JsonNode eligibleCollaborator = null;
        for (JsonNode item : collaborators) {
            if (COLLABORATOR.equals(item.path("user_id").stringValue())) eligibleCollaborator = item;
        }
        assertThat(eligibleCollaborator).isNotNull();
        assertThat(eligibleCollaborator.path("active_credential_count").intValue()).isGreaterThanOrEqualTo(1);
        JsonNode order = createAndSignLabOrder(context, ownerLease);
        String executionTaskId = order.path("execution_tasks").get(0).path("execution_task_id").stringValue();
        JsonNode pending = objectMapper.readTree(send(
                "GET", "/api/v1/clinical-tasks?encounter_id=" + context.encounterId(), null,
                ownerLease, context, null).body()).get(0);
        String taskId = pending.path("task_id").stringValue();

        JsonNode claimed = objectMapper.readTree(taskCommand(context, ownerLease, taskId, "claims", 1).body());
        assertThat(claimed.path("claimed_by").stringValue()).isEqualTo(USER);

        HttpResponse<String> delegated = collaborationCommand(
                context, ownerLease, USER, ROLE, taskId, "delegations", 2, COLLABORATOR,
                "夜班同事继续处理", OffsetDateTime.now().plusHours(8).toString());
        assertThat(delegated.statusCode()).isEqualTo(200);
        JsonNode assigned = objectMapper.readTree(delegated.body());
        assertThat(assigned.path("state").stringValue()).isEqualTo("ASSIGNED");
        assertThat(assigned.path("assigned_user_id").stringValue()).isEqualTo(COLLABORATOR);
        assertThat(assigned.path("claimed_by").isNull()).isTrue();
        assertThat(assigned.path("business_state").stringValue()).isEqualTo("PENDING");

        HttpResponse<String> wrongView = taskCommand(context, ownerLease, taskId, "views", 3);
        assertThat(wrongView.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(wrongView.body()).path("error").path("code").stringValue())
                .isEqualTo("CLINICAL_TASK_ASSIGNEE_REQUIRED");
        HttpResponse<String> wrongClaim = taskCommand(context, ownerLease, taskId, "claims", 3);
        assertThat(wrongClaim.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(wrongClaim.body()).path("error").path("code").stringValue())
                .isEqualTo("CLINICAL_TASK_ASSIGNEE_REQUIRED");

        Lease collaboratorLease = issueLeaseAs(context, COLLABORATOR, COLLABORATOR_ROLE);
        JsonNode collaboratorClaim = objectMapper.readTree(sendAs(
                "POST", "/api/v1/clinical-tasks/" + taskId + "/claims", commandBody(context, 3),
                collaboratorLease, context, UUID.randomUUID().toString(), COLLABORATOR, COLLABORATOR_ROLE).body());
        assertThat(collaboratorClaim.path("state").stringValue()).isEqualTo("CLAIMED");
        assertThat(collaboratorClaim.path("claimed_by").stringValue()).isEqualTo(COLLABORATOR);

        JsonNode transferred = objectMapper.readTree(collaborationCommand(
                context, collaboratorLease, COLLABORATOR, COLLABORATOR_ROLE, taskId, "transfers", 4,
                USER, "返回日班责任医生", null).body());
        assertThat(transferred.path("state").stringValue()).isEqualTo("CLAIMED");
        assertThat(transferred.path("claimed_by").stringValue()).isEqualTo(USER);
        assertThat(transferred.path("assigned_user_id").stringValue()).isEqualTo(USER);

        JsonNode escalated = objectMapper.readTree(collaborationCommand(
                context, ownerLease, USER, ROLE, taskId, "escalations", 5, COLLABORATOR,
                "临近时限升级至上级", null).body());
        assertThat(escalated.path("state").stringValue()).isEqualTo("ESCALATED");
        assertThat(escalated.path("assigned_user_id").stringValue()).isEqualTo(COLLABORATOR);
        assertThat(escalated.path("claimed_by").stringValue()).isEqualTo(USER);
        assertThat(escalated.path("business_state").stringValue()).isEqualTo("PENDING");

        JsonNode escalationClaim = objectMapper.readTree(sendAs(
                "POST", "/api/v1/clinical-tasks/" + taskId + "/claims", commandBody(context, 6),
                collaboratorLease, context, UUID.randomUUID().toString(), COLLABORATOR, COLLABORATOR_ROLE).body());
        assertThat(escalationClaim.path("claimed_by").stringValue()).isEqualTo(COLLABORATOR);

        Lease labTechnicianLease = issueLeaseAs(context, LAB_TECHNICIAN, LAB_TECHNICIAN_ROLE);
        HttpResponse<String> execution = sendAs(
                "POST", "/api/v1/executions/" + executionTaskId + "/events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"COMPLETED","expected_task_row_version":1,"performed_quantity":1,
                 "quantity_unit":"次","note":"协作后仍由来源业务完成"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                labTechnicianLease, context, UUID.randomUUID().toString(), LAB_TECHNICIAN, LAB_TECHNICIAN_ROLE);
        assertThat(execution.statusCode()).isEqualTo(200);

        assertThat(jdbc.sql("""
                select string_agg(event_type, '>' order by occurred_at, task_event_id)
                from clinical_task_event where tenant_id = cast(:tenant as uuid)
                  and task_id = cast(:task_id as uuid)
                """).param("tenant", TENANT).param("task_id", taskId)
                .query(String.class).single())
                .isEqualTo("CREATED>CLAIMED>DELEGATED>CLAIMED>TRANSFERRED>ESCALATED>CLAIMED>SOURCE_COMPLETED");
        assertThat(jdbc.sql("""
                select count(*) from clinical_task_delegation
                where tenant_id = cast(:tenant as uuid) and task_id = cast(:task_id as uuid)
                """).param("tenant", TENANT).param("task_id", taskId).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("""
                update clinical_task_delegation set reason = 'tamper'
                where tenant_id = cast(:tenant as uuid) and task_id = cast(:task_id as uuid)
                """).param("tenant", TENANT).param("task_id", taskId).update())
                .isInstanceOf(DataAccessException.class);

        HttpResponse<String> detailResponse = sendAs(
                "GET", "/api/v1/clinical-tasks/" + taskId, null,
                collaboratorLease, context, null, COLLABORATOR, COLLABORATOR_ROLE);
        assertThat(detailResponse.statusCode()).withFailMessage(detailResponse.body()).isEqualTo(200);
        JsonNode detail = objectMapper.readTree(detailResponse.body());
        assertThat(detail.path("task").path("state").stringValue()).isEqualTo("COMPLETED");
        assertThat(detail.path("events").size()).isEqualTo(8);
        assertThat(detail.path("delegations").size()).isEqualTo(1);
        assertThat(detail.path("rule_snapshot").isObject()).isTrue();
    }

    private JsonNode createAndSignLabOrder(Context context, Lease lease) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/orders", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "order_scope":"TEMPORARY","clinical_indication":"统一任务验证",
                 "items":[{"item_type":"LAB","catalog_code":"LAB-TASK-%s","display_name":"任务验证检验",
                   "requested_quantity":1,"quantity_unit":"次","instructions":"按医嘱执行"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), UUID.randomUUID()),
                lease, context, UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode draft = objectMapper.readTree(created.body());
        HttpResponse<String> signed = send("POST", "/api/v1/orders/" + draft.path("order_id").stringValue() + "/sign", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":1,"rule_watermark":"RULESET-MEDICATION-6"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(signed.statusCode()).isEqualTo(200);
        return objectMapper.readTree(signed.body());
    }

    private HttpResponse<String> taskCommand(
            Context context, Lease lease, String taskId, String action, long version) throws Exception {
        return send("POST", "/api/v1/clinical-tasks/" + taskId + "/" + action, """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), version),
                lease, context, UUID.randomUUID().toString());
    }

    private HttpResponse<String> collaborationCommand(
            Context context, Lease lease, String actorUser, String actorRole, String taskId,
            String action, long version, String targetUser, String reason, String expiresAt) throws Exception {
        String expiry = expiresAt == null ? "null" : "\"" + expiresAt + "\"";
        return sendAs("POST", "/api/v1/clinical-tasks/" + taskId + "/" + action, """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d,"target_user_id":"%s","reason":"%s","valid_until":%s}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                version, targetUser, reason, expiry), lease, context, UUID.randomUUID().toString(),
                actorUser, actorRole);
    }

    private String commandBody(Context context, long version) {
        return """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), version);
    }

    private void seedCollaborator() {
        jdbc.sql("""
                insert into app_user(tenant_id, user_id, external_subject, display_name, status)
                values (cast(:tenant as uuid), cast(:user as uuid), 'synthetic-collaborator', '合成协作医生', 'ACTIVE')
                on conflict (tenant_id, user_id) do nothing
                """).param("tenant", TENANT).param("user", COLLABORATOR).update();
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (cast(:tenant as uuid), cast(:role as uuid), cast(:user as uuid),
                  cast(:organization as uuid), cast(:facility as uuid), 'CLINICIAN', now(), 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do nothing
                """).param("tenant", TENANT).param("role", COLLABORATOR_ROLE)
                .param("user", COLLABORATOR).param("organization", ORGANIZATION)
                .param("facility", FACILITY).update();
        jdbc.sql("""
                update workforce_assignment
                set department_id = cast('018f0000-0000-7000-8000-00000000aa08' as uuid),
                    status = 'ACTIVE', valid_until = null
                where tenant_id = cast(:tenant as uuid)
                  and source_role_assignment_id = cast(:role as uuid)
                """).param("tenant", TENANT).param("role", COLLABORATOR_ROLE).update();
        jdbc.sql("""
                insert into practitioner_credential(
                  tenant_id, credential_id, person_id, credential_type, registration_number,
                  issuing_authority, practice_scope, status, valid_from, valid_until)
                values (cast(:tenant as uuid), cast('018f0000-0000-7000-8000-00000000be02' as uuid),
                  cast(:user as uuid), 'PHYSICIAN_LICENSE', '110420000002',
                  '江城市卫生健康委员会', '{"primary_department":"CARDIOLOGY"}'::jsonb,
                  'ACTIVE', now() - interval '1 year', now() + interval '5 years')
                on conflict (tenant_id, credential_id) do update
                set status = 'ACTIVE', valid_from = excluded.valid_from, valid_until = excluded.valid_until
                """).param("tenant", TENANT).param("user", COLLABORATOR).update();
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成任务患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-TASK', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private UUID seedOverdueTask(Context context) {
        UUID taskId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level, state, business_state, due_at, source_route)
                values (cast(:tenant as uuid), :task, :patient, :encounter, cast(:facility as uuid),
                  'DOCUMENT', :source, 'SIGN_DOCUMENT', '超期待签文书', 'HIGH', 'PENDING', 'OPEN',
                  now() - interval '2 hours', '#/record')
                """).param("tenant", TENANT).param("task", taskId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("source", sourceId)
                .param("facility", FACILITY).update();
        return taskId;
    }

    private Lease issueLease(Context context) throws Exception {
        return issueLeaseAs(context, USER, ROLE);
    }

    private Lease issueLeaseAs(Context context, String actorUser, String actorRole) throws Exception {
        HttpRequest request = baseRequest("/api/v1/context-leases", actorUser, actorRole)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organization_id\":\"" + ORGANIZATION
                        + "\",\"facility_id\":\"" + FACILITY + "\",\"patient_id\":\"" + context.patientId()
                        + "\",\"encounter_id\":\"" + context.encounterId()
                        + "\",\"purpose_code\":\"CLINICAL_TASK_WORKFLOW\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return new Lease(body.path("lease_id").stringValue(), body.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> send(
            String method, String path, String body, Lease lease, Context context, String idempotencyKey)
            throws Exception {
        return sendAs(method, path, body, lease, context, idempotencyKey, USER, ROLE);
    }

    private HttpResponse<String> sendAs(
            String method, String path, String body, Lease lease, Context context, String idempotencyKey,
            String actorUser, String actorRole) throws Exception {
        HttpRequest.Builder request = baseRequest(path, actorUser, actorRole)
                .header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION)
                .header("X-Facility-Context", FACILITY)
                .header("X-Patient-Context", context.patientId().toString())
                .header("X-Encounter-Context", context.encounterId().toString());
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder baseRequest(String path) {
        return baseRequest(path, USER, ROLE);
    }

    private HttpRequest.Builder baseRequest(String path, String actorUser, String actorRole) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", actorUser)
                .header("X-HaoEMR-Role-Assignment-Ids", actorRole);
    }

    private record Context(UUID patientId, UUID encounterId) {}
    private record Lease(String id, String watermark) {}
}
