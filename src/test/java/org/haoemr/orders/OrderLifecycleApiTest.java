package org.haoemr.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class OrderLifecycleApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String activeRoleHeaders = ROLE;

    @Test
    void givenDraftOrder_whenEditing_thenVersionedContentChangesBeforeSigning() throws Exception {
        Encounter context = seedEncounter();
        Lease lease = issueLease(context);
        HttpResponse<String> created = send("POST", "/api/v1/orders", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "order_scope":"TEMPORARY","clinical_indication":"初始检查计划",
                 "items":[{"item_type":"LAB","catalog_code":"LAB-CBC","display_name":"血常规",
                   "requested_quantity":1,"quantity_unit":"次","instructions":"初始说明"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        JsonNode draft = objectMapper.readTree(created.body());

        HttpResponse<String> edited = sendWithIfMatch("PATCH", "/api/v1/orders/" + draft.path("order_id").stringValue(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "order_scope":"TEMPORARY","clinical_indication":"复核后调整检查计划",
                 "items":[{"item_type":"LAB","catalog_code":"LAB-CRP","display_name":"C反应蛋白",
                   "requested_quantity":1,"quantity_unit":"次","instructions":"当日完成"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString(), draft.path("row_version").longValue());

        assertThat(edited.statusCode()).isEqualTo(200);
        JsonNode updated = objectMapper.readTree(edited.body());
        assertThat(updated.path("row_version").longValue()).isEqualTo(2);
        assertThat(updated.path("clinical_indication").stringValue()).isEqualTo("复核后调整检查计划");
        assertThat(updated.path("items").get(0).path("catalog_code").stringValue()).isEqualTo("LAB-CRP");
    }

    @Test
    void givenOneEncounter_whenSigningAndPartiallyExecuting_thenEveryFactTracesToTheOrderAndWrongPatientIsDenied()
            throws Exception {
        Encounter context = seedEncounter();
        grantLaboratoryRole();
        Lease lease = issueLease(context);

        HttpResponse<String> created = send("POST", "/api/v1/orders", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "order_scope":"TEMPORARY","clinical_indication":"排查贫血",
                 "items":[{"item_type":"LAB","catalog_code":"LAB-CBC","display_name":"血常规",
                   "requested_quantity":2,"quantity_unit":"次","instructions":"急诊，空腹否"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode draft = objectMapper.readTree(created.body());
        String orderId = draft.path("order_id").stringValue();
        assertThat(draft.path("status").stringValue()).isEqualTo("DRAFT");
        assertThat(draft.path("items").size()).isEqualTo(1);
        HttpResponse<String> listed = send(
                "GET", "/api/v1/orders?encounter_id=" + context.encounterId(), null, lease, context, null);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(listed.body()).size()).isEqualTo(1);

        HttpResponse<String> signed = send("POST", "/api/v1/orders/" + orderId + "/sign", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":1,"rule_watermark":"RULESET-MEDICATION-6"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(signed.statusCode()).isEqualTo(200);
        JsonNode active = objectMapper.readTree(signed.body());
        assertThat(active.path("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(active.path("execution_tasks").size()).isEqualTo(1);
        String executionTaskId = active.path("execution_tasks").get(0).path("execution_task_id").stringValue();

        Encounter wrong = seedEncounter();
        Lease wrongLease = issueLease(wrong);
        HttpResponse<String> denied = send("GET", "/api/v1/orders/" + orderId, null, wrongLease, wrong, null);
        assertThat(denied.statusCode()).isEqualTo(403);

        HttpResponse<String> partial = send("POST", "/api/v1/executions/" + executionTaskId + "/events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"PARTIAL","expected_task_row_version":1,"performed_quantity":1,
                 "quantity_unit":"次","note":"已完成第一次采集"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(partial.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(partial.body()).path("task_state").stringValue()).isEqualTo("PARTIAL");

        HttpResponse<String> completed = send("POST", "/api/v1/executions/" + executionTaskId + "/events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"COMPLETED","expected_task_row_version":2,"performed_quantity":1,
                 "quantity_unit":"次","note":"第二次采集完成"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(completed.body()).path("task_state").stringValue()).isEqualTo("COMPLETED");

        HttpResponse<String> finalOrder = send("GET", "/api/v1/orders/" + orderId, null, lease, context, null);
        assertThat(finalOrder.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(finalOrder.body()).path("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("""
                select count(*) from order_execution_event event
                join clinical_order_item item on item.tenant_id = event.tenant_id
                  and item.order_item_id = event.order_item_id
                where event.tenant_id = cast(:tenant as uuid) and item.order_id = cast(:order_id as uuid)
                """).param("tenant", TENANT).param("order_id", orderId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void givenAnUnexecutedActiveOrder_whenCancelled_thenPendingWorkAndItemsAreCancelledWithImmutableReason()
            throws Exception {
        Encounter context = seedEncounter();
        Lease lease = issueLease(context);
        JsonNode active = createAndSign(context, lease, 1, "IMG-CT-" + UUID.randomUUID());
        String orderId = active.path("order_id").stringValue();

        HttpResponse<String> cancelled = send("POST", "/api/v1/orders/" + orderId + "/cancel", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":2,"reason":"患者撤回检查同意"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());

        assertThat(cancelled.statusCode()).isEqualTo(200);
        JsonNode finalOrder = objectMapper.readTree(cancelled.body());
        assertThat(finalOrder.path("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(finalOrder.path("items").get(0).path("item_state").stringValue()).isEqualTo("CANCELLED");
        assertThat(finalOrder.path("execution_tasks").get(0).path("task_state").stringValue())
                .isEqualTo("CANCELLED");
        assertThat(jdbc.sql("""
                select task.state || '|' || task.business_state from clinical_task task
                join order_execution_task execution on execution.tenant_id = task.tenant_id
                  and execution.execution_task_id = task.source_id
                where task.tenant_id = cast(:tenant as uuid)
                  and execution.order_id = cast(:order_id as uuid)
                """).param("tenant", TENANT).param("order_id", orderId)
                .query(String.class).single()).isEqualTo("WITHDRAWN|CANCELLED");
        assertThat(jdbc.sql("""
                select action_type || '|' || resulting_status || '|' || reason
                from order_control_event where tenant_id = cast(:tenant as uuid)
                  and order_id = cast(:order_id as uuid)
                """).param("tenant", TENANT).param("order_id", orderId)
                .query(String.class).single()).isEqualTo("CANCELLED|CANCELLED|患者撤回检查同意");
    }

    @Test
    void givenAnInFlightExecution_whenStopped_thenOrderWaitsForTerminalFactAndEndsStopped()
            throws Exception {
        Encounter context = seedEncounter();
        grantLaboratoryRole();
        Lease lease = issueLease(context);
        JsonNode active = createAndSign(context, lease, 2, "LAB-STOP-" + UUID.randomUUID());
        String orderId = active.path("order_id").stringValue();
        String taskId = active.path("execution_tasks").get(0).path("execution_task_id").stringValue();

        HttpResponse<String> partial = send("POST", "/api/v1/executions/" + taskId + "/events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"PARTIAL","expected_task_row_version":1,"performed_quantity":1,
                 "quantity_unit":"次","note":"第一次已执行"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(partial.statusCode()).isEqualTo(200);

        HttpResponse<String> stopping = send("POST", "/api/v1/orders/" + orderId + "/stop", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":3,"reason":"病情变化，停止后续医嘱"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(stopping.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(stopping.body()).path("status").stringValue()).isEqualTo("STOPPING");

        HttpResponse<String> completed = send("POST", "/api/v1/executions/" + taskId + "/events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"COMPLETED","expected_task_row_version":2,"performed_quantity":1,
                 "quantity_unit":"次","note":"收口已在途执行"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(completed.statusCode()).isEqualTo(200);

        HttpResponse<String> finalOrder = send("GET", "/api/v1/orders/" + orderId, null, lease, context, null);
        assertThat(finalOrder.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(finalOrder.body()).path("status").stringValue()).isEqualTo("STOPPED");
        assertThat(jdbc.sql("""
                select resulting_status from order_control_event
                where tenant_id = cast(:tenant as uuid) and order_id = cast(:order_id as uuid)
                """).param("tenant", TENANT).param("order_id", orderId)
                .query(String.class).single()).isEqualTo("STOPPING");
    }

    private JsonNode createAndSign(
            Encounter context, Lease lease, int quantity, String catalogCode) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/orders", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "order_scope":"TEMPORARY","clinical_indication":"状态机验证",
                 "items":[{"item_type":"LAB","catalog_code":"%s","display_name":"合成验证项",
                   "requested_quantity":%d,"quantity_unit":"次","instructions":"按医嘱执行"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), catalogCode, quantity),
                lease, context, UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        String orderId = objectMapper.readTree(created.body()).path("order_id").stringValue();
        HttpResponse<String> signed = send("POST", "/api/v1/orders/" + orderId + "/sign", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":1,"rule_watermark":"RULESET-MEDICATION-6"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(signed.statusCode()).isEqualTo(200);
        return objectMapper.readTree(signed.body());
    }

    @Test
    void givenClinicianOnly_whenRecordingLabExecution_thenRequestIsDenied() throws Exception {
        Encounter context = seedEncounter();
        Lease lease = issueLease(context);
        JsonNode active = createAndSign(context, lease, 1, "LAB-RBAC-" + UUID.randomUUID());
        String taskId = active.path("execution_tasks").get(0).path("execution_task_id").stringValue();

        HttpResponse<String> denied = send("POST", "/api/v1/executions/" + taskId + "/events", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "event_type":"COMPLETED","expected_task_row_version":1,"performed_quantity":1,
                 "quantity_unit":"次","note":"越权执行尝试"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());

        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(denied.body()).path("error").path("code").stringValue())
                .isEqualTo("ORDER_EXECUTION_ROLE_REQUIRED");
    }

    private void grantLaboratoryRole() {
        UUID laboratoryRole = UUID.randomUUID();
        jdbc.sql("""
                insert into role_assignment(
                  tenant_id, role_assignment_id, user_id, person_id, organization_id,
                  facility_id, role_code, valid_from, status)
                select tenant_id, :laboratory_role, user_id, person_id, organization_id,
                  facility_id, 'LAB_TECHNICIAN', now() - interval '1 day', 'ACTIVE'
                from role_assignment where tenant_id=cast(:tenant as uuid)
                  and role_assignment_id=cast(:role as uuid)
                """).param("laboratory_role", laboratoryRole).param("tenant", TENANT)
                .param("role", ROLE).update();
        activeRoleHeaders = ROLE + "," + laboratoryRole;
    }

    private Encounter seedEncounter() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成医嘱患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(
                  tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-ORDER', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Encounter(patientId, encounterId);
    }

    private Lease issueLease(Encounter context) throws Exception {
        HttpRequest request = baseRequest("/api/v1/context-leases")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organization_id\":\"" + ORGANIZATION
                        + "\",\"facility_id\":\"" + FACILITY + "\",\"patient_id\":\"" + context.patientId()
                        + "\",\"encounter_id\":\"" + context.encounterId()
                        + "\",\"purpose_code\":\"ORDER_WORKFLOW\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return new Lease(body.path("lease_id").stringValue(), body.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> send(
            String method, String path, String body, Lease lease, Encounter context, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request = baseRequest(path)
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

    private HttpResponse<String> sendWithIfMatch(
            String method, String path, String body, Lease lease, Encounter context,
            String idempotencyKey, long expectedVersion) throws Exception {
        HttpRequest.Builder request = baseRequest(path)
                .header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION)
                .header("X-Facility-Context", FACILITY)
                .header("X-Patient-Context", context.patientId().toString())
                .header("X-Encounter-Context", context.encounterId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("If-Match", "\"" + expectedVersion + "\"")
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", activeRoleHeaders);
    }

    @AfterEach
    void removeTemporaryExecutionRoles() {
        jdbc.sql("""
                delete from role_assignment where tenant_id=cast(:tenant as uuid)
                  and user_id=cast(:user as uuid) and role_code='LAB_TECHNICIAN'
                """).param("tenant", TENANT).param("user", USER).update();
        activeRoleHeaders = ROLE;
    }

    private record Encounter(UUID patientId, UUID encounterId) {}
    private record Lease(String id, String watermark) {}
}
