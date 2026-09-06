package org.haoemr.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
final class PatientIdentityWorkflowApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID CLINICIAN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID ADMIN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa09");
    private static final UUID REVIEWER = UUID.fromString("018f0000-0000-7000-8000-00000000aa06");
    private static final UUID REVIEWER_ADMIN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000c299");

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final UUID sourcePatient = UUID.randomUUID();
    private final UUID targetPatient = UUID.randomUUID();
    private final UUID encounter = UUID.randomUUID();
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void seed() {
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :organization, :facility,
                  'HEALTH_INFORMATION_MANAGER', now() - interval '1 minute', 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do update
                  set status = 'ACTIVE', valid_until = null, role_code = 'HEALTH_INFORMATION_MANAGER'
                """).param("tenant", TENANT).param("role", REVIEWER_ADMIN_ROLE).param("user", REVIEWER)
                .param("organization", ORGANIZATION).param("facility", FACILITY).update();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (:tenant, :source, '王敏', 'F', date '1988-05-12', 'ACTIVE'),
                  (:tenant, :target, '王 敏', 'F', date '1988-05-12', 'ACTIVE')
                """).param("tenant", TENANT).param("source", sourcePatient).param("target", targetPatient).update();
        jdbc.sql("""
                insert into patient_demographic_version(tenant_id, patient_id, demographic_version_id,
                  version_no, display_name, sex_code, birth_date, patient_status, change_type,
                  change_reason, changed_by)
                values (:tenant, :source, :source_version, 1, '王敏', 'F', date '1988-05-12',
                    'ACTIVE', 'INITIAL_IMPORT', 'MPI integration test source', :user),
                  (:tenant, :target, :target_version, 1, '王 敏', 'F', date '1988-05-12',
                    'ACTIVE', 'INITIAL_IMPORT', 'MPI integration test target', :user)
                """).param("tenant", TENANT).param("source", sourcePatient).param("target", targetPatient)
                .param("source_version", UUID.randomUUID()).param("target_version", UUID.randomUUID())
                .param("user", USER).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, ended_at, source_system, source_key)
                values (:tenant, :encounter, :patient, :organization, :facility,
                  'OUTPATIENT', 'FINISHED', now() - interval '1 day', now() - interval '1 day' + interval '30 minutes',
                  'MPI-TEST', :source_key)
                """).param("tenant", TENANT).param("encounter", encounter).param("patient", sourcePatient)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", encounter.toString()).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from audit_event where tenant_id = :tenant and resource_id in (:source, :target)")
                .param("tenant", TENANT).param("source", sourcePatient).param("target", targetPatient).update();
        jdbc.sql("""
                delete from audit_event where tenant_id = :tenant and resource_id in (
                  select merge_case_id from patient_merge_case where tenant_id = :tenant
                    and source_patient_id = :source)
                """).param("tenant", TENANT).param("source", sourcePatient).update();
        jdbc.sql("""
                delete from audit_event where tenant_id = :tenant and resource_id in (
                  select candidate_id from patient_match_candidate where tenant_id = :tenant
                    and (patient_a_id = :source or patient_b_id = :source))
                """).param("tenant", TENANT).param("source", sourcePatient).update();
        jdbc.sql("delete from outbox_event where tenant_id = :tenant and aggregate_id in (:source, :target)")
                .param("tenant", TENANT).param("source", sourcePatient).param("target", targetPatient).update();
        jdbc.sql("""
                delete from outbox_event where tenant_id = :tenant and aggregate_id in (
                  select merge_case_id from patient_merge_case where tenant_id = :tenant
                    and source_patient_id = :source)
                """).param("tenant", TENANT).param("source", sourcePatient).update();
        jdbc.sql("delete from patient_merge_case where tenant_id = :tenant and source_patient_id = :source")
                .param("tenant", TENANT).param("source", sourcePatient).update();
        jdbc.sql("""
                delete from patient_match_candidate where tenant_id = :tenant
                  and (patient_a_id in (:source, :target) or patient_b_id in (:source, :target))
                """).param("tenant", TENANT).param("source", sourcePatient).param("target", targetPatient).update();
        jdbc.sql("alter table encounter_state_event disable trigger encounter_state_event_immutable").update();
        try {
            jdbc.sql("delete from encounter_state_event where tenant_id = :tenant and encounter_id = :encounter")
                    .param("tenant", TENANT).param("encounter", encounter).update();
        } finally {
            jdbc.sql("alter table encounter_state_event enable trigger encounter_state_event_immutable").update();
        }
        jdbc.sql("delete from encounter where tenant_id = :tenant and encounter_id = :encounter")
                .param("tenant", TENANT).param("encounter", encounter).update();
        jdbc.sql("alter table patient_demographic_version disable trigger patient_demographic_version_immutable").update();
        try {
            jdbc.sql("delete from patient_demographic_version where tenant_id = :tenant and patient_id in (:source, :target)")
                    .param("tenant", TENANT).param("source", sourcePatient).param("target", targetPatient).update();
        } finally {
            jdbc.sql("alter table patient_demographic_version enable trigger patient_demographic_version_immutable").update();
        }
        jdbc.sql("delete from patient where tenant_id = :tenant and patient_id in (:source, :target)")
                .param("tenant", TENANT).param("source", sourcePatient).param("target", targetPatient).update();
        for (String key : keys) {
            jdbc.sql("delete from idempotency_record where tenant_id = :tenant and idempotency_key = :key")
                    .param("tenant", TENANT).param("key", key).update();
        }
        jdbc.sql("delete from workforce_assignment where tenant_id = :tenant and source_role_assignment_id = :role")
                .param("tenant", TENANT).param("role", REVIEWER_ADMIN_ROLE).update();
        jdbc.sql("delete from role_assignment where tenant_id = :tenant and role_assignment_id = :role")
                .param("tenant", TENANT).param("role", REVIEWER_ADMIN_ROLE).update();
    }

    @Test
    void givenPossibleDuplicate_whenTwoPeopleMergeAndReverse_thenAllClinicalLinksRemainTraceable()
            throws Exception {
        assertThat(get("/api/v1/patient-match-candidates", USER, CLINICIAN_ROLE).statusCode()).isEqualTo(403);

        HttpResponse<String> detected = post("/api/v1/patient-match-candidates", USER, ADMIN_ROLE, """
                {"patient_a_id":"%s","patient_b_id":"%s"}
                """.formatted(sourcePatient, targetPatient));
        assertThat(detected.statusCode()).as(detected.body()).isEqualTo(201);
        JsonNode candidate = objectMapper.readTree(detected.body());
        UUID candidateId = UUID.fromString(candidate.path("candidate_id").stringValue());
        assertThat(candidate.path("match_score").doubleValue()).isEqualTo(0.8);
        assertThat(candidate.path("match_signals").toString())
                .contains("same_birth_date", "same_normalized_name", "algorithm_version");

        HttpResponse<String> requested = post("/api/v1/patient-merge-cases", USER, ADMIN_ROLE, """
                {"candidate_id":"%s","source_patient_id":"%s","target_patient_id":"%s",
                 "reason":"确认同一患者重复建档，保留目标档案作为主索引",
                 "conflict_resolution":{"display_name":"TARGET","identifiers":"RETAIN_ALL",
                   "clinical_links":"PRESERVE_SOURCE_REFERENCES"}}
                """.formatted(candidateId, sourcePatient, targetPatient));
        assertThat(requested.statusCode()).isEqualTo(201);
        JsonNode merge = objectMapper.readTree(requested.body());
        UUID caseId = UUID.fromString(merge.path("merge_case_id").stringValue());
        assertThat(merge.path("status").stringValue()).isEqualTo("PENDING_SECOND_REVIEW");

        HttpResponse<String> selfApproval = post("/api/v1/patient-merge-cases/" + caseId + "/approve",
                USER, ADMIN_ROLE, "{\"expected_row_version\":1,\"confirm_no_clinical_data_loss\":true}");
        assertThat(selfApproval.statusCode()).isEqualTo(409);
        assertThat(selfApproval.body()).contains("independent approval");

        HttpResponse<String> approved = post("/api/v1/patient-merge-cases/" + caseId + "/approve",
                REVIEWER, REVIEWER_ADMIN_ROLE,
                "{\"expected_row_version\":1,\"confirm_no_clinical_data_loss\":true}");
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.body()).contains("MERGED", REVIEWER.toString());
        assertThat(patientStatus(sourcePatient)).isEqualTo("MERGED");
        assertThat(jdbc.sql("select merged_into_patient_id from patient where tenant_id = :tenant and patient_id = :patient")
                .param("tenant", TENANT).param("patient", sourcePatient).query(UUID.class).single())
                .isEqualTo(targetPatient);
        assertThat(encounterPatient()).isEqualTo(sourcePatient);

        HttpResponse<String> reversalRequested = post(
                "/api/v1/patient-merge-cases/" + caseId + "/reversal-requests",
                REVIEWER, REVIEWER_ADMIN_ROLE,
                "{\"expected_row_version\":2,\"reason\":\"复核发现两个档案应保持独立，需要撤销主索引映射\"}");
        assertThat(reversalRequested.statusCode()).isEqualTo(200);
        assertThat(reversalRequested.body()).contains("REVERSAL_PENDING");

        HttpResponse<String> reversalSelfApproval = post(
                "/api/v1/patient-merge-cases/" + caseId + "/reversal-approve",
                REVIEWER, REVIEWER_ADMIN_ROLE,
                "{\"expected_row_version\":3,\"confirm_links_remain_traceable\":true}");
        assertThat(reversalSelfApproval.statusCode()).isEqualTo(409);

        HttpResponse<String> reversed = post(
                "/api/v1/patient-merge-cases/" + caseId + "/reversal-approve", USER, ADMIN_ROLE,
                "{\"expected_row_version\":3,\"confirm_links_remain_traceable\":true}");
        assertThat(reversed.statusCode()).isEqualTo(200);
        assertThat(reversed.body()).contains("REVERSED");
        assertThat(patientStatus(sourcePatient)).isEqualTo("ACTIVE");
        assertThat(encounterPatient()).isEqualTo(sourcePatient);

        HttpResponse<String> corrected = post("/api/v1/patients/" + sourcePatient + "/identity-corrections",
                USER, ADMIN_ROLE, """
                {"expected_row_version":3,"display_name":"王敏（已核验）","sex_code":"F",
                 "birth_date":"1988-05-12","status":"ACTIVE","reason":"证件原件复核后修正姓名显示"}
                """);
        assertThat(corrected.statusCode()).isEqualTo(201);
        assertThat(corrected.body()).contains("IDENTITY_CORRECTION", "王敏（已核验）");
        HttpResponse<String> history = get(
                "/api/v1/patients/" + sourcePatient + "/demographic-versions", USER, ADMIN_ROLE);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(history.body())).hasSize(2);
        assertThatThrownBy(() -> jdbc.sql("""
                update patient_demographic_version set change_reason = 'tampered'
                where tenant_id = :tenant and patient_id = :patient and version_no = 1
                """).param("tenant", TENANT).param("patient", sourcePatient).update())
                .isInstanceOf(DataAccessException.class);
    }

    private String patientStatus(UUID patient) {
        return jdbc.sql("select status from patient where tenant_id = :tenant and patient_id = :patient")
                .param("tenant", TENANT).param("patient", patient).query(String.class).single();
    }

    private UUID encounterPatient() {
        return jdbc.sql("select patient_id from encounter where tenant_id = :tenant and encounter_id = :encounter")
                .param("tenant", TENANT).param("encounter", encounter).query(UUID.class).single();
    }

    private String key() { String value = "c01-mpi-" + UUID.randomUUID(); keys.add(value); return value; }
    private HttpResponse<String> get(String path, UUID user, UUID role) throws Exception {
        return http.send(base(path, user, role).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path, UUID user, UUID role, String body) throws Exception {
        return http.send(base(path, user, role).header("Content-Type", "application/json")
                .header("Idempotency-Key", key()).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder base(String path, UUID user, UUID role) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT.toString())
                .header("X-HaoEMR-User-Id", user.toString())
                .header("X-HaoEMR-Role-Assignment-Ids", role.toString());
    }
}
