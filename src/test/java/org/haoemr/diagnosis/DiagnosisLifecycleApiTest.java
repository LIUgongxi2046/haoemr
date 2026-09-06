package org.haoemr.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
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
final class DiagnosisLifecycleApiTest {
    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void givenVersionedTerminology_whenDiagnosisIsConfirmedCorrectedAndStopped_thenHistoryNeverChanges()
            throws Exception {
        Encounter context = seedEncounter();
        Lease lease = issueLease(context);

        HttpResponse<String> terminology = send(
                "GET", "/api/v1/diagnosis-terminology?query=E11.9&limit=10", null, lease, context, null);
        assertThat(terminology.statusCode()).isEqualTo(200);
        JsonNode terminologyRows = objectMapper.readTree(terminology.body());
        assertThat(terminologyRows.size()).isGreaterThanOrEqualTo(1);
        assertThat(terminologyRows.get(0).path("code").stringValue()).isEqualTo("E11.9");
        assertThat(terminologyRows.get(0).path("terminology_release").stringValue()).isEqualTo("2026B");
        assertThat(terminologyRows.toString()).doesNotContain("SYNTHETIC");

        HttpResponse<String> created = send("POST", "/api/v1/diagnoses", createBody(
                context, "PRIMARY", "PROVISIONAL", "2026A", "I10.0", "原发性高血压 2 级"),
                lease, context, UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode provisional = objectMapper.readTree(created.body());
        String diagnosisId = provisional.path("diagnosis_id").stringValue();
        assertThat(provisional.path("status").stringValue()).isEqualTo("PROVISIONAL");
        assertThat(provisional.path("code_display_snapshot").stringValue()).isEqualTo("原发性高血压");

        HttpResponse<String> duplicatePrimary = send("POST", "/api/v1/diagnoses", createBody(
                context, "PRIMARY", "PROVISIONAL", "2026A", "I10.9", "高血压待查"),
                lease, context, UUID.randomUUID().toString());
        assertThat(duplicatePrimary.statusCode()).isEqualTo(409);

        HttpResponse<String> confirmed = send("POST", "/api/v1/diagnoses/" + diagnosisId + "/confirm", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":1}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(confirmed.body()).path("status").stringValue()).isEqualTo("CONFIRMED");

        HttpResponse<String> corrected = send("POST", "/api/v1/diagnoses/" + diagnosisId + "/correct", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":2,"terminology_system":"ICD-10-CN","terminology_release":"2026B",
                 "code":"I10.0","diagnosis_text":"原发性高血压 2 级（高危）",
                 "diagnosis_role":"PRIMARY","certainty":"CONFIRMED","effective_at":"2026-08-14T10:30:00Z",
                 "evidence_summary":"诊室血压与家庭监测一致","plan_summary":"分层管理并复评",
                 "correction_reason":"补充危险分层"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(corrected.statusCode()).isEqualTo(200);
        JsonNode correctedBody = objectMapper.readTree(corrected.body());
        assertThat(correctedBody.path("version_no").longValue()).isEqualTo(3);
        assertThat(correctedBody.path("terminology_release").stringValue()).isEqualTo("2026B");
        assertThat(correctedBody.path("code_display_snapshot").stringValue()).isEqualTo("原发性高血压（更新术语）");

        HttpResponse<String> stopped = send("POST", "/api/v1/diagnoses/" + diagnosisId + "/stop", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":3,"reason":"后续证据支持替代诊断"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId()),
                lease, context, UUID.randomUUID().toString());
        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(stopped.body()).path("status").stringValue()).isEqualTo("STOPPED");

        HttpResponse<String> replacement = send("POST", "/api/v1/diagnoses", createBody(
                context, "PRIMARY", "CONFIRMED", "2026B", "I10.9", "高血压病"),
                lease, context, UUID.randomUUID().toString());
        assertThat(replacement.statusCode()).isEqualTo(201);

        HttpResponse<String> listed = send(
                "GET", "/api/v1/diagnoses?encounter_id=" + context.encounterId(), null, lease, context, null);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(listed.body()).size()).isEqualTo(2);
        assertThat(jdbc.sql("""
                select string_agg(version_no || ':' || terminology_release || ':' || code_display_snapshot, '|' order by version_no)
                from clinical_diagnosis_version where tenant_id = cast(:tenant as uuid)
                  and diagnosis_id = cast(:diagnosis_id as uuid)
                """).param("tenant", TENANT).param("diagnosis_id", diagnosisId)
                .query(String.class).single()).isEqualTo(
                        "1:2026A:原发性高血压|2:2026A:原发性高血压|3:2026B:原发性高血压（更新术语）");
    }

    private String createBody(
            Encounter context, String role, String certainty, String release, String code, String diagnosisText) {
        return """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "terminology_system":"ICD-10-CN","terminology_release":"%s","code":"%s",
                 "diagnosis_text":"%s","diagnosis_role":"%s","certainty":"%s",
                 "effective_at":"%s","evidence_summary":"诊断证据",
                 "plan_summary":"诊疗计划"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(),
                release, code, diagnosisText, role, certainty,
                "2026A".equals(release) ? "2026-02-14T10:00:00Z" : "2026-08-14T10:00:00Z");
    }

    private Encounter seedEncounter() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成诊断患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1970, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-DIAGNOSIS', :source_key)
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
                        + "\",\"purpose_code\":\"DIAGNOSIS_WORKFLOW\"}"))
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
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE);
    }

    private record Encounter(UUID patientId, UUID encounterId) {}
    private record Lease(String id, String watermark) {}
}
