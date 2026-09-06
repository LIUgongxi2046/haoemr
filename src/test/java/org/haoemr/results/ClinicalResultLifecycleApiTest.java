package org.haoemr.results;

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
final class ClinicalResultLifecycleApiTest {
    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String activeRoleHeaders = ROLE;

    @Test
    void givenACompletedLabExecution_whenCriticalReportIsHandledAndCorrected_thenReceiptIsNotDispositionAndHistoryRemains()
            throws Exception {
        Context context = seedCompletedLabExecution(true);
        Lease lease = issueLease(context);

        HttpResponse<String> created = send("POST", "/api/v1/results", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "execution_task_id":"%s","source_system":"SYNTHETIC-LIS","source_report_key":"%s",
                 "report_type":"LAB","conclusion":"血钾危急值，建议立即复核并处置",
                 "reported_at":"2026-08-14T11:00:00Z",
                 "observations":[{"item_code":"K","item_name":"血钾","value_type":"NUMERIC",
                   "numeric_value":6.8,"unit":"mmol/L","reference_low":3.5,"reference_high":5.5,
                   "abnormal_flag":"CRITICAL_HIGH"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                context.executionTaskId(), context.sourceKey()), lease, context, UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode initial = objectMapper.readTree(created.body());
        String resultId = initial.path("result_id").stringValue();
        String criticalId = initial.path("critical_values").get(0).path("critical_value_id").stringValue();
        assertThat(initial.path("version_no").longValue()).isEqualTo(1);
        assertThat(initial.path("critical_values").get(0).path("state").stringValue()).isEqualTo("OPEN");
        assertThat(jdbc.sql("""
                select task_type || ':' || state || ':' || business_state from clinical_task
                where tenant_id = cast(:tenant as uuid) and source_type = 'CRITICAL_VALUE'
                  and source_id = cast(:critical_id as uuid)
                """).param("tenant", TENANT).param("critical_id", criticalId)
                .query(String.class).single()).isEqualTo("CRITICAL_VALUE_RECEIPT:PENDING:OPEN");
        assertThat(criticalWorkflowState(criticalId))
                .isEqualTo("ACTIVE:critical-result-loop-verification");

        HttpResponse<String> duplicate = send("POST", "/api/v1/results", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "execution_task_id":"%s","source_system":"SYNTHETIC-LIS","source_report_key":"%s",
                 "report_type":"LAB","conclusion":"重复消息","reported_at":"2026-08-14T11:00:01Z",
                 "observations":[{"item_code":"K","item_name":"血钾","value_type":"NUMERIC",
                   "numeric_value":6.8,"unit":"mmol/L","abnormal_flag":"CRITICAL_HIGH"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                context.executionTaskId(), context.sourceKey()), lease, context, UUID.randomUUID().toString());
        assertThat(duplicate.statusCode()).isEqualTo(409);

        HttpResponse<String> acknowledged = send(
                "POST", "/api/v1/critical-values/" + criticalId + "/acknowledge", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":1,"notification_method":"PHONE_READ_BACK",
                 "recipient_confirmed":true}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(acknowledged.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(acknowledged.body()).path("state").stringValue())
                .isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.sql("""
                select string_agg(task_type || ':' || state || ':' || business_state, '|'
                  order by task_type) from clinical_task
                where tenant_id = cast(:tenant as uuid) and source_type = 'CRITICAL_VALUE'
                  and source_id = cast(:critical_id as uuid)
                """).param("tenant", TENANT).param("critical_id", criticalId)
                .query(String.class).single()).isEqualTo(
                        "CRITICAL_VALUE_DISPOSITION:PENDING:ACKNOWLEDGED|CRITICAL_VALUE_RECEIPT:COMPLETED:ACKNOWLEDGED");
        assertThat(criticalWorkflowState(criticalId))
                .isEqualTo("ACTIVE:critical-result-loop-acknowledge");

        HttpResponse<String> beforeDisposition = send(
                "GET", "/api/v1/results?encounter_id=" + context.encounterId(), null, lease, context, null);
        assertThat(beforeDisposition.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(beforeDisposition.body()).get(0).path("critical_values")
                .get(0).path("state").stringValue()).isEqualTo("ACKNOWLEDGED");

        HttpResponse<String> disposed = send(
                "POST", "/api/v1/critical-values/" + criticalId + "/dispositions", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":2,"assessment":"排除标本溶血后按高钾血症处置",
                 "action_taken":"停用补钾并安排即刻复测","outcome":"患者已接受处置",
                 "retest_required":true}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(disposed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(disposed.body()).path("state").stringValue()).isEqualTo("DISPOSED");
        assertThat(jdbc.sql("""
                select count(*) from clinical_task
                where tenant_id = cast(:tenant as uuid) and source_type = 'CRITICAL_VALUE'
                  and source_id = cast(:critical_id as uuid) and state = 'COMPLETED'
                """).param("tenant", TENANT).param("critical_id", criticalId)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(criticalWorkflowState(criticalId))
                .isEqualTo("ACTIVE:critical-result-loop-action");

        HttpResponse<String> corrected = send("POST", "/api/v1/results/" + resultId + "/corrections", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":1,"correction_reason":"首次标本溶血，使用复测结果",
                 "conclusion":"复测血钾正常","reported_at":"2026-08-14T11:30:00Z",
                 "observations":[{"item_code":"K","item_name":"血钾","value_type":"NUMERIC",
                   "numeric_value":4.2,"unit":"mmol/L","reference_low":3.5,"reference_high":5.5,
                   "abnormal_flag":"NORMAL"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(corrected.statusCode()).isEqualTo(200);
        JsonNode current = objectMapper.readTree(corrected.body());
        assertThat(current.path("version_no").longValue()).isEqualTo(2);
        assertThat(current.path("report_status").stringValue()).isEqualTo("CORRECTED");
        assertThat(current.path("observations").get(0).path("numeric_value").doubleValue()).isEqualTo(4.2);
        assertThat(current.path("critical_values").get(0).path("state").stringValue()).isEqualTo("DISPOSED");

        assertThat(jdbc.sql("""
                select string_agg(version.version_no || ':' || observation.numeric_value, '|' order by version.version_no)
                from clinical_result_version version
                join clinical_result_observation observation on observation.tenant_id = version.tenant_id
                  and observation.result_version_id = version.result_version_id
                where version.tenant_id = cast(:tenant as uuid) and version.result_id = cast(:result_id as uuid)
                """).param("tenant", TENANT).param("result_id", resultId).query(String.class).single())
                .isEqualTo("1:6.800000|2:4.200000");
    }

    @Test
    void givenClinicianWithoutLaboratoryRole_whenSigningLabResult_thenRequestIsDenied() throws Exception {
        Context context = seedCompletedLabExecution(false);
        Lease lease = issueLease(context);

        HttpResponse<String> denied = send("POST", "/api/v1/results", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "execution_task_id":"%s","source_system":"SYNTHETIC-LIS","source_report_key":"%s",
                 "report_type":"LAB","conclusion":"越权签发应被拒绝","reported_at":"2026-08-14T11:00:00Z",
                 "observations":[{"item_code":"K","item_name":"血钾","value_type":"NUMERIC",
                   "numeric_value":4.2,"unit":"mmol/L","reference_low":3.5,"reference_high":5.5,
                   "abnormal_flag":"NORMAL"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                context.executionTaskId(), context.sourceKey()), lease, context, UUID.randomUUID().toString());

        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(denied.body()).path("error").path("code").stringValue())
                .isEqualTo("LAB_RESULT_AUTHOR_ROLE_REQUIRED");
    }

    private Context seedCompletedLabExecution(boolean grantLaboratoryRole) {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成结果患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1975, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-RESULT', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                insert into clinical_order(tenant_id, order_id, patient_id, encounter_id, facility_id,
                  order_scope, status, clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                values (cast(:tenant as uuid), :order_id, :patient, :encounter, cast(:facility as uuid),
                  'TEMPORARY', 'COMPLETED', '复查电解质', cast(:user_id as uuid),
                  cast(:user_id as uuid), now(), 'RULESET-CORE-1')
                """).param("tenant", TENANT).param("order_id", orderId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY).param("user_id", USER).update();
        jdbc.sql("""
                insert into clinical_order_item(tenant_id, order_item_id, order_id, item_type, catalog_code,
                  display_name, requested_quantity, quantity_unit, item_state)
                values (cast(:tenant as uuid), :item_id, :order_id, 'LAB', 'LAB-K', '血钾', 1, '次', 'COMPLETED')
                """).param("tenant", TENANT).param("item_id", itemId).param("order_id", orderId).update();
        jdbc.sql("""
                insert into order_execution_task(tenant_id, execution_task_id, order_id, order_item_id,
                  patient_id, encounter_id, task_state, requested_quantity, performed_quantity, quantity_unit)
                values (cast(:tenant as uuid), :task_id, :order_id, :item_id, :patient, :encounter,
                  'COMPLETED', 1, 1, '次')
                """).param("tenant", TENANT).param("task_id", taskId).param("order_id", orderId)
                .param("item_id", itemId).param("patient", patientId).param("encounter", encounterId).update();
        if (grantLaboratoryRole) grantLaboratoryRole();
        return new Context(patientId, encounterId, taskId, UUID.randomUUID().toString());
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
                """).param("laboratory_role", laboratoryRole).param("tenant", TENANT).param("role", ROLE).update();
        activeRoleHeaders = ROLE + "," + laboratoryRole;
    }

    private String criticalWorkflowState(String criticalValueId) {
        return jdbc.sql("""
                select state || ':' || current_node
                from configuration_runtime_execution
                where tenant_id = cast(:tenant as uuid) and config_type = 'WORKFLOW'
                  and config_key = 'runtime-workflow-critical-result-loop-v1'
                  and subject_type = 'CRITICAL_VALUE' and subject_id = cast(:critical_id as uuid)
                order by created_at desc, execution_id desc limit 1
                """).param("tenant", TENANT).param("critical_id", criticalValueId)
                .query(String.class).single();
    }

    private Lease issueLease(Context context) throws Exception {
        HttpRequest request = baseRequest("/api/v1/context-leases").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organization_id\":\"" + ORGANIZATION
                        + "\",\"facility_id\":\"" + FACILITY + "\",\"patient_id\":\"" + context.patientId()
                        + "\",\"encounter_id\":\"" + context.encounterId()
                        + "\",\"purpose_code\":\"RESULT_WORKFLOW\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return new Lease(body.path("lease_id").stringValue(), body.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> send(
            String method, String path, String body, Lease lease, Context context, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request = baseRequest(path)
                .header("X-Context-Lease-Id", lease.id()).header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION).header("X-Facility-Context", FACILITY)
                .header("X-Patient-Context", context.patientId().toString())
                .header("X-Encounter-Context", context.encounterId().toString());
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT).header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", activeRoleHeaders);
    }

    @AfterEach
    void removeTemporaryResultRoles() {
        jdbc.sql("""
                delete from role_assignment where tenant_id=cast(:tenant as uuid)
                  and user_id=cast(:user as uuid) and role_code='LAB_TECHNICIAN'
                """).param("tenant", TENANT).param("user", USER).update();
        activeRoleHeaders = ROLE;
    }

    private record Context(UUID patientId, UUID encounterId, UUID executionTaskId, String sourceKey) {}
    private record Lease(String id, String watermark) {}
}
