package org.haoemr.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class AuthorizationAdministrationApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID CLINICIAN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID ADMIN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa09");
    private static final UUID REVIEWER = UUID.fromString("018f0000-0000-7000-8000-00000000aa06");
    private static final UUID REVIEWER_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000c203");
    // Keep this policy-isolation fixture separate from the canonical UI patient, which may
    // legitimately hold active dev-synthetic emergency-access records after browser seeding.
    private static final UUID PATIENT = UUID.fromString("018f0000-0000-7000-8000-000000000005");
    private static final UUID ENCOUNTER = UUID.fromString("018f0000-0000-7000-8000-000000000105");
    private static final UUID DEPARTMENT = UUID.fromString("018f0000-0000-7000-8000-00000000aa08");
    private static final UUID WARD = UUID.fromString("018f0000-0000-7000-8000-00000000bb01");

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EmergencyAccessExpirySweeper expirySweeper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<UUID> policies = new ArrayList<>();
    private final List<UUID> grants = new ArrayList<>();
    private final List<UUID> relationships = new ArrayList<>();
    private final List<UUID> leases = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void seedIndependentReviewer() {
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :organization, :facility,
                  'SECURITY_ADMIN', now() - interval '1 minute', 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do update set status = 'ACTIVE', valid_until = null
                """).param("tenant", TENANT).param("role", REVIEWER_ROLE).param("user", REVIEWER)
                .param("organization", ORGANIZATION).param("facility", FACILITY).update();
    }

    @AfterEach
    void cleanup() {
        for (UUID id : leases) {
            jdbc.sql("delete from audit_event where tenant_id = :tenant and resource_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
            jdbc.sql("delete from outbox_event where tenant_id = :tenant and aggregate_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
            jdbc.sql("delete from context_lease where tenant_id = :tenant and lease_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
        }
        for (UUID id : grants) {
            jdbc.sql("delete from audit_event where tenant_id = :tenant and resource_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
            jdbc.sql("delete from outbox_event where tenant_id = :tenant and aggregate_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
            jdbc.sql("delete from emergency_access_grant where tenant_id = :tenant and emergency_access_grant_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
        }
        for (UUID id : policies) {
            jdbc.sql("delete from audit_event where tenant_id = :tenant and resource_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
            jdbc.sql("delete from outbox_event where tenant_id = :tenant and aggregate_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
            jdbc.sql("delete from authorization_policy where tenant_id = :tenant and policy_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
        }
        for (UUID id : relationships) {
            jdbc.sql("delete from patient_care_relationship where tenant_id = :tenant and patient_relationship_id = :id")
                    .param("tenant", TENANT).param("id", id).update();
        }
        jdbc.sql("""
                delete from audit_event where tenant_id = :tenant and resource_type = 'CONFIG_RUNTIME_EXECUTION'
                  and resource_id in (select execution_id from configuration_runtime_execution
                    where tenant_id = :tenant and config_type = 'SCOPE'
                      and config_key = 'runtime-scope-clinical-v1' and executed_by = :user
                      and subject_type = 'CLINICAL_CONTEXT' and subject_id = :encounter)
                """).param("tenant", TENANT).param("user", USER).param("encounter", ENCOUNTER).update();
        jdbc.sql("""
                delete from idempotency_record where tenant_id = :tenant
                  and command_scope = 'CONFIG_RUNTIME_SCOPE_AUTHORIZE'
                  and response_ref->>'resource_id' in (select execution_id::text from configuration_runtime_execution
                    where tenant_id = :tenant and config_type = 'SCOPE'
                      and config_key = 'runtime-scope-clinical-v1' and executed_by = :user
                      and subject_type = 'CLINICAL_CONTEXT' and subject_id = :encounter)
                """).param("tenant", TENANT).param("user", USER).param("encounter", ENCOUNTER).update();
        jdbc.sql("""
                delete from configuration_runtime_execution
                where tenant_id = :tenant and config_type = 'SCOPE'
                  and config_key = 'runtime-scope-clinical-v1' and executed_by = :user
                  and subject_type = 'CLINICAL_CONTEXT' and subject_id = :encounter
                """).param("tenant", TENANT).param("user", USER).param("encounter", ENCOUNTER).update();
        for (String key : keys) {
            jdbc.sql("delete from idempotency_record where tenant_id = :tenant and idempotency_key = :key")
                    .param("tenant", TENANT).param("key", key).update();
        }
        jdbc.sql("delete from audit_event where tenant_id = :tenant and action_code = 'AUTHORIZATION_SIMULATED' and actor_user_id in (:user, :reviewer)")
                .param("tenant", TENANT).param("user", USER).param("reviewer", REVIEWER).update();
        jdbc.sql("delete from outbox_event where tenant_id = :tenant and event_type = 'AUTHORIZATION_SIMULATED' and aggregate_id = :user")
                .param("tenant", TENANT).param("user", USER).update();
        jdbc.sql("delete from workforce_assignment where tenant_id = :tenant and source_role_assignment_id = :role")
                .param("tenant", TENANT).param("role", REVIEWER_ROLE).update();
        jdbc.sql("delete from role_assignment where tenant_id = :tenant and role_assignment_id = :role")
                .param("tenant", TENANT).param("role", REVIEWER_ROLE).update();
        jdbc.sql("update workforce_assignment set status = 'ACTIVE', valid_until = null where tenant_id = :tenant and source_role_assignment_id = :role")
                .param("tenant", TENANT).param("role", CLINICIAN_ROLE).update();
    }

    @Test
    void givenPublishedRelationshipPolicy_whenIssuingLease_thenRelationshipOrReviewedEmergencyGrantIsRequired()
            throws Exception {
        HttpResponse<String> deniedList = get("/api/v1/admin/access-policies", USER, CLINICIAN_ROLE);
        assertThat(deniedList.statusCode()).isEqualTo(403);

        UUID policyId = UUID.randomUUID(); policies.add(policyId);
        HttpResponse<String> draft = post("/api/v1/admin/access-policies", USER, ADMIN_ROLE, key(), """
                {"policy_id":"%s","policy_code":"C01-LEASE-%s","policy_name":"临床上下文限时授权测试策略","version_no":1,"effect":"ALLOW",
                 "subject_role_code":"CLINICIAN","resource_type":"CLINICAL_CONTEXT","action_code":"LEASE_ISSUE",
                 "organization_id":"%s","facility_id":"%s","patient_relationship_required":true,
                 "relationship_types":["CARE_TEAM"],"resource_statuses":["ACTIVE"],
                 "purpose_codes":["DOCUMENT_DRAFT"],"emergency_override_allowed":true,
                 "priority":500,"valid_from":"%s"}
                """.formatted(policyId, policyId.toString().substring(0, 8), ORGANIZATION, FACILITY,
                Instant.now().minusSeconds(30)));
        assertThat(draft.statusCode()).isEqualTo(201);
        assertThat(draft.body()).contains("\"policy_name\":\"临床上下文限时授权测试策略\"");

        HttpResponse<String> selfPublish = post("/api/v1/admin/access-policies/" + policyId + "/publish",
                USER, ADMIN_ROLE, key(), "{\"expected_row_version\":1}");
        assertThat(selfPublish.statusCode()).isEqualTo(409);

        HttpResponse<String> published = post("/api/v1/admin/access-policies/" + policyId + "/publish",
                REVIEWER, REVIEWER_ROLE, key(), "{\"expected_row_version\":1}");
        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(published.body()).contains("PUBLISHED");

        HttpResponse<String> deniedLease = lease();
        assertThat(deniedLease.statusCode()).isEqualTo(403);
        assertThat(deniedLease.body()).contains("AUTHORIZATION_POLICY_DENIED");

        UUID relationship = UUID.randomUUID(); relationships.add(relationship);
        jdbc.sql("""
                insert into patient_care_relationship(tenant_id, patient_relationship_id, patient_id,
                  user_id, role_assignment_id, encounter_id, relationship_type, status, valid_from, created_by)
                values (:tenant, :id, :patient, :user, :role, :encounter, 'CARE_TEAM', 'ACTIVE', now(), :user)
                """).param("tenant", TENANT).param("id", relationship).param("patient", PATIENT)
                .param("user", USER).param("role", CLINICIAN_ROLE).param("encounter", ENCOUNTER).update();
        HttpResponse<String> relationshipLeaseResponse = lease();
        assertThat(relationshipLeaseResponse.statusCode()).isEqualTo(201);
        Lease relationshipLease = leaseFrom(relationshipLeaseResponse);
        UUID scopeExecution = jdbc.sql("""
                select execution_id from configuration_runtime_execution
                where tenant_id = :tenant and config_type = 'SCOPE'
                  and config_key = 'runtime-scope-clinical-v1' and executed_by = :user
                  and subject_type = 'CLINICAL_CONTEXT' and subject_id = :encounter
                order by created_at desc, execution_id desc limit 1
                """).param("tenant", TENANT).param("user", USER).param("encounter", ENCOUNTER)
                .query(UUID.class).single();
        assertThat(jdbc.sql("""
                select state || ':' || (output_payload->>'authorized')
                from configuration_runtime_execution
                where tenant_id = :tenant and execution_id = :execution
                """).param("tenant", TENANT).param("execution", scopeExecution)
                .query(String.class).single()).isEqualTo("PASSED:true");
        assertThat(listDiagnoses(relationshipLease).statusCode()).isEqualTo(200);

        String originalScopePayload = jdbc.sql("""
                select payload::text from config_item
                where tenant_id = :tenant and config_type = 'SCOPE'
                  and config_key = 'runtime-scope-clinical-v1'
                """).param("tenant", TENANT).query(String.class).single();
        try {
            jdbc.sql("""
                    update config_item set payload = jsonb_set(payload, '{permissions}',
                      (payload->'permissions') || jsonb_build_array(jsonb_build_object(
                        'role_code', 'CLINICIAN', 'resource_code', 'CLINICAL_CONTEXT',
                        'action_code', 'LEASE_ISSUE', 'scope_code', 'DOCUMENT_DRAFT', 'effect', 'DENY')), true),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and config_type = 'SCOPE'
                      and config_key = 'runtime-scope-clinical-v1'
                    """).param("tenant", TENANT).update();
            HttpResponse<String> deniedByConfiguredScope = lease();
            assertThat(deniedByConfiguredScope.statusCode()).isEqualTo(403);
            assertThat(deniedByConfiguredScope.body()).contains("CONFIG_SCOPE_DENIED");
        } finally {
            jdbc.sql("""
                    update config_item set payload = cast(:payload as jsonb), row_version = row_version + 1,
                      updated_at = now()
                    where tenant_id = :tenant and config_type = 'SCOPE'
                      and config_key = 'runtime-scope-clinical-v1'
                    """).param("tenant", TENANT).param("payload", originalScopePayload).update();
        }

        jdbc.sql("update patient_care_relationship set status = 'ENDED', valid_until = now(), row_version = 2 where tenant_id = :tenant and patient_relationship_id = :id")
                .param("tenant", TENANT).param("id", relationship).update();
        HttpResponse<String> staleLeaseUse = listDiagnoses(relationshipLease);
        assertThat(staleLeaseUse.statusCode()).isEqualTo(403);
        assertThat(staleLeaseUse.body()).contains("AUTHORIZATION_POLICY_DENIED");
        HttpResponse<String> missingReauthentication = postWithoutReauthentication(
                "/api/v1/emergency-access-grants", USER, CLINICIAN_ROLE, key(), """
                {"role_assignment_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "resource_types":["CLINICAL_CONTEXT"],"action_codes":["LEASE_ISSUE"],
                 "reason":"患者危急，必须立即查看必要病历并开始处置",
                 "duration_minutes":15,"risk_acknowledged":true}
                """.formatted(CLINICIAN_ROLE, PATIENT, ENCOUNTER));
        assertThat(missingReauthentication.statusCode()).isEqualTo(403);
        assertThat(missingReauthentication.body()).contains("RECENT_REAUTHENTICATION_REQUIRED");
        HttpResponse<String> emergency = post("/api/v1/emergency-access-grants", USER, CLINICIAN_ROLE, key(), """
                {"role_assignment_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "resource_types":["CLINICAL_CONTEXT"],"action_codes":["LEASE_ISSUE"],
                 "reason":"患者危急，必须立即查看必要病历并开始处置",
                 "duration_minutes":15,"risk_acknowledged":true}
                """.formatted(CLINICIAN_ROLE, PATIENT, ENCOUNTER));
        assertThat(emergency.statusCode()).isEqualTo(201);
        JsonNode grant = objectMapper.readTree(emergency.body());
        UUID grantId = UUID.fromString(grant.path("emergency_access_grant_id").stringValue());
        grants.add(grantId);
        assertThat(get("/api/v1/emergency-access-grants", USER, CLINICIAN_ROLE).body()).contains(grantId.toString());
        assertThat(get("/api/v1/admin/emergency-access-grants", REVIEWER, REVIEWER_ROLE).body()).contains(grantId.toString());
        HttpResponse<String> emergencyLeaseResponse = lease();
        assertThat(emergencyLeaseResponse.statusCode()).isEqualTo(201);
        Lease emergencyLease = leaseFrom(emergencyLeaseResponse);
        assertThat(listDiagnoses(emergencyLease).statusCode()).isEqualTo(200);

        HttpResponse<String> reviewed = post("/api/v1/admin/emergency-access-grants/" + grantId + "/reviews",
                REVIEWER, REVIEWER_ROLE, key(), """
                {"expected_row_version":1,"outcome":"APPROPRIATE","note":"事后复核确认范围与时限必要"}
                """);
        assertThat(reviewed.statusCode()).isEqualTo(200);
        assertThat(reviewed.body()).contains("REVIEWED", "APPROPRIATE");
        assertThat(listDiagnoses(emergencyLease).statusCode()).isEqualTo(403);
        assertThat(lease().statusCode()).isEqualTo(403);

        UUID denyPolicyId = UUID.randomUUID(); policies.add(denyPolicyId);
        HttpResponse<String> denyDraft = post("/api/v1/admin/access-policies", USER, ADMIN_ROLE, key(), """
                {"policy_id":"%s","policy_code":"C01-DENY-%s","policy_name":"临床上下文明确拒绝测试策略","version_no":1,"effect":"DENY",
                 "subject_role_code":"CLINICIAN","resource_type":"CLINICAL_CONTEXT","action_code":"LEASE_ISSUE",
                 "organization_id":"%s","facility_id":"%s","patient_relationship_required":false,
                 "relationship_types":[],"resource_statuses":["ACTIVE"],"purpose_codes":["DOCUMENT_DRAFT"],
                 "emergency_override_allowed":false,"priority":1000,"valid_from":"%s"}
                """.formatted(denyPolicyId, denyPolicyId.toString().substring(0, 8), ORGANIZATION, FACILITY,
                Instant.now().minusSeconds(30)));
        assertThat(denyDraft.statusCode()).isEqualTo(201);
        assertThat(post("/api/v1/admin/access-policies/" + denyPolicyId + "/publish",
                REVIEWER, REVIEWER_ROLE, key(), "{\"expected_row_version\":1}").statusCode()).isEqualTo(200);

        HttpResponse<String> simulation = post("/api/v1/admin/access-simulations", REVIEWER, REVIEWER_ROLE, null, """
                {"target_user_id":"%s","target_role_assignment_ids":["%s"],
                 "resource_type":"CLINICAL_CONTEXT","action_code":"LEASE_ISSUE",
                 "organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "purpose_code":"DOCUMENT_DRAFT","resource_status":"ACTIVE"}
                """.formatted(USER, CLINICIAN_ROLE, ORGANIZATION, FACILITY, PATIENT, ENCOUNTER));
        assertThat(simulation.statusCode()).isEqualTo(200);
        assertThat(simulation.body()).contains("EXPLICIT_DENY", "\"allowed\":false");

        UUID scopedPolicyId = UUID.randomUUID(); policies.add(scopedPolicyId);
        HttpResponse<String> scopedDraft = post("/api/v1/admin/access-policies", USER, ADMIN_ROLE, key(), """
                {"policy_id":"%s","policy_code":"C01-SCOPE-%s","policy_name":"指定科室病历查看测试策略","version_no":1,"effect":"ALLOW",
                 "subject_role_code":"CLINICIAN","resource_type":"DOCUMENT","action_code":"READ",
                 "organization_id":"%s","facility_id":"%s","department_id":"%s","ward_id":"%s",
                 "patient_relationship_required":false,"relationship_types":[],"resource_statuses":["SIGNED"],
                 "purpose_codes":["CARE_DELIVERY"],"emergency_override_allowed":false,
                 "priority":700,"valid_from":"%s"}
                """.formatted(scopedPolicyId, scopedPolicyId.toString().substring(0, 8), ORGANIZATION, FACILITY,
                DEPARTMENT, WARD, Instant.now().minusSeconds(30)));
        assertThat(scopedDraft.statusCode()).isEqualTo(201);
        assertThat(post("/api/v1/admin/access-policies/" + scopedPolicyId + "/publish",
                REVIEWER, REVIEWER_ROLE, key(), "{\"expected_row_version\":1}").statusCode()).isEqualTo(200);

        String scopedSimulation = """
                {"target_user_id":"%s","target_role_assignment_ids":["%s"],
                 "resource_type":"DOCUMENT","action_code":"READ","organization_id":"%s","facility_id":"%s",
                 "department_id":"%s","ward_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "purpose_code":"CARE_DELIVERY","resource_status":"SIGNED"}
                """.formatted(USER, CLINICIAN_ROLE, ORGANIZATION, FACILITY, DEPARTMENT, WARD, PATIENT, ENCOUNTER);
        HttpResponse<String> scopedAllowed = post(
                "/api/v1/admin/access-simulations", REVIEWER, REVIEWER_ROLE, null, scopedSimulation);
        assertThat(scopedAllowed.body()).contains("POLICY_ALLOW", "\"allowed\":true");

        jdbc.sql("update workforce_assignment set status = 'SUSPENDED' where tenant_id = :tenant and source_role_assignment_id = :role")
                .param("tenant", TENANT).param("role", CLINICIAN_ROLE).update();
        HttpResponse<String> offDuty = post(
                "/api/v1/admin/access-simulations", REVIEWER, REVIEWER_ROLE, null, scopedSimulation);
        assertThat(offDuty.body()).contains("NO_PUBLISHED_POLICY", "\"allowed\":false");

        UUID expiredGrantId = UUID.randomUUID(); grants.add(expiredGrantId);
        jdbc.sql("""
                insert into emergency_access_grant(tenant_id, emergency_access_grant_id, user_id,
                  role_assignment_id, patient_id, encounter_id, resource_types, action_codes,
                  reason, status, requested_at, expires_at)
                values (:tenant, :grant, :user, :role, :patient, :encounter,
                  cast('{CLINICAL_CONTEXT}' as text[]), cast('{LEASE_ISSUE}' as text[]),
                  '患者危急测试结束后的限时授权到期取证', 'ACTIVE',
                  now() - interval '2 minutes', now() - interval '1 minute')
                """).param("tenant", TENANT).param("grant", expiredGrantId).param("user", USER)
                .param("role", CLINICIAN_ROLE).param("patient", PATIENT).param("encounter", ENCOUNTER).update();
        assertThat(expirySweeper.sweepExpired()).isEqualTo(1);
        assertThat(jdbc.sql("select status from emergency_access_grant where tenant_id = :tenant and emergency_access_grant_id = :grant")
                .param("tenant", TENANT).param("grant", expiredGrantId).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("select count(*) from audit_event where tenant_id = :tenant and resource_id = :grant and action_code = 'EMERGENCY_ACCESS_EXPIRED'")
                .param("tenant", TENANT).param("grant", expiredGrantId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox_event where tenant_id = :tenant and aggregate_id = :grant and event_type = 'EMERGENCY_ACCESS_EXPIRED'")
                .param("tenant", TENANT).param("grant", expiredGrantId).query(Long.class).single()).isEqualTo(1);
    }

    private HttpResponse<String> lease() throws Exception {
        return post("/api/v1/context-leases", USER, CLINICIAN_ROLE, null, """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                 "encounter_id":"%s","purpose_code":"DOCUMENT_DRAFT"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER));
    }

    private Lease leaseFrom(HttpResponse<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.body());
        UUID leaseId = UUID.fromString(body.path("lease_id").stringValue());
        leases.add(leaseId);
        return new Lease(leaseId.toString(), body.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> listDiagnoses(Lease lease) throws Exception {
        return http.send(base("/api/v1/diagnoses?encounter_id=" + ENCOUNTER, USER, CLINICIAN_ROLE)
                .header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION.toString())
                .header("X-Facility-Context", FACILITY.toString())
                .header("X-Patient-Context", PATIENT.toString())
                .header("X-Encounter-Context", ENCOUNTER.toString())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String key() { String value = "c01-auth-" + UUID.randomUUID(); keys.add(value); return value; }
    private HttpResponse<String> get(String path, UUID user, UUID role) throws Exception {
        return http.send(base(path, user, role).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path, UUID user, UUID role, String key, String body) throws Exception {
        HttpRequest.Builder builder = base(path, user, role).header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        if ("/api/v1/emergency-access-grants".equals(path)) {
            builder.header("X-HaoEMR-Synthetic-Reauthenticated-At", Instant.now().toString());
        }
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postWithoutReauthentication(
            String path, UUID user, UUID role, String key, String body) throws Exception {
        HttpRequest.Builder builder = base(path, user, role).header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder base(String path, UUID user, UUID role) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT.toString())
                .header("X-HaoEMR-User-Id", user.toString())
                .header("X-HaoEMR-Role-Assignment-Ids", role.toString());
    }

    private record Lease(String id, String watermark) {}
}
