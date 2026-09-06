package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "haoemr.agent-run.synthetic-test-model-enabled=true")
@ActiveProfiles("dev-synthetic")
final class AgentRunApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String PATIENT = "018f0000-0000-7000-8000-000000000001";
    private static final String ENCOUNTER = "018f0000-0000-7000-8000-000000000101";
    private static final String DOCUMENT = "018f0000-0000-7000-8000-000000001001";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void givenAnExplicitIsolatedTestModel_whenRunningTheSyntheticFixture_thenOnlyASourcedProposalIsCreated()
            throws Exception {
        enableSyntheticTestPolicy();
        UUID versionId = jdbc.sql("""
                select current_version_id from clinical_document where tenant_id = :tenant and document_id = :document
                """).param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(DOCUMENT))
                .query(UUID.class).single();
        String sectionsBefore = jdbc.sql("""
                select sections::text from clinical_document_version
                where tenant_id = :tenant and document_version_id = :version
                """).param("tenant", UUID.fromString(TENANT)).param("version", versionId).query(String.class).single();
        Lease lease = issueLease();

        HttpResponse<String> response = run(lease, versionId, UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode snapshot = objectMapper.readTree(response.body());
        assertThat(snapshot.path("state").stringValue()).isEqualTo("READY_FOR_REVIEW");
        assertThat(snapshot.path("proposals")).hasSize(1);
        JsonNode proposal = snapshot.path("proposals").get(0);
        assertThat(proposal.path("status").stringValue()).isEqualTo("PENDING_REVIEW");
        assertThat(proposal.path("references").get(0).path("source_id").stringValue()).isEqualTo(versionId.toString());
        assertThat(proposal.path("references").get(0).path("authorization_watermark").stringValue())
                .isEqualTo(lease.watermark());
        UUID runId = UUID.fromString(snapshot.path("run_id").stringValue());
        UUID proposalId = UUID.fromString(proposal.path("proposal_id").stringValue());
        assertThat(jdbc.sql("select count(*) from ai_tool_invocation where tenant_id = :tenant and run_id = :run and outcome = 'ALLOWED'")
                .param("tenant", UUID.fromString(TENANT)).param("run", runId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select sections::text from clinical_document_version where tenant_id = :tenant and document_version_id = :version")
                .param("tenant", UUID.fromString(TENANT)).param("version", versionId).query(String.class).single())
                .isEqualTo(sectionsBefore);

        HttpResponse<String> events = get(lease, "/api/v1/ai/runs/" + runId + "/events", null);
        assertThat(events.statusCode()).isEqualTo(200);
        assertThat(events.body()).contains("event: RunCreated", "event: ContextRetrievalStarted",
                "event: GenerationStarted", "event: ProposalReady");
        HttpResponse<String> resumed = get(lease, "/api/v1/ai/runs/" + runId + "/events", "2");
        assertThat(resumed.body()).doesNotContain("id: 1\n", "id: 2\n");

        HttpResponse<String> wrongContext = decide(lease, proposalId, UUID.randomUUID().toString(), 1, "ACCEPTED");
        assertThat(wrongContext.statusCode()).isEqualTo(403);
        assertThat(jdbc.sql("select status from ai_proposal where tenant_id = :tenant and proposal_id = :proposal")
                .param("tenant", UUID.fromString(TENANT)).param("proposal", proposalId)
                .query(String.class).single()).isEqualTo("PENDING_REVIEW");

        HttpResponse<String> accepted = decide(lease, proposalId, PATIENT, 1, "ACCEPTED");
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains("ACCEPTED");
        assertThat(jdbc.sql("select sections::text from clinical_document_version where tenant_id = :tenant and document_version_id = :version")
                .param("tenant", UUID.fromString(TENANT)).param("version", versionId).query(String.class).single())
                .isEqualTo(sectionsBefore);
    }

    @Test
    void givenAnUnavailableConfiguredProvider_whenRunningAi_thenItDegradesWithoutChangingTheClinicalMainline()
            throws Exception {
        enableSyntheticTestPolicy();
        UUID versionId = jdbc.sql("select current_version_id from clinical_document where tenant_id = :tenant and document_id = :document")
                .param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(DOCUMENT))
                .query(UUID.class).single();
        Lease lease = issueLease();
        jdbc.sql("update ai_use_case_policy set provider_code = 'UNAVAILABLE' where tenant_id = :tenant and use_case_code = 'DOCUMENT_DRAFT_ASSIST'")
                .param("tenant", UUID.fromString(TENANT)).update();
        try {
            HttpResponse<String> response = run(lease, versionId, UUID.randomUUID().toString());
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(response.body()).contains("DEGRADED", "\"proposals\":[]");
        } finally {
            disableSyntheticTestPolicy();
        }
    }

    @Test
    void givenTheDefaultDevelopmentPolicy_whenCreatingALegacyDocumentRun_thenItFailsClosedBeforeCreatingARun()
            throws Exception {
        disableSyntheticTestPolicy();
        UUID versionId = jdbc.sql("select current_version_id from clinical_document where tenant_id = :tenant and document_id = :document")
                .param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(DOCUMENT))
                .query(UUID.class).single();
        Lease lease = issueLease();
        long before = jdbc.sql("select count(*) from ai_run where tenant_id = :tenant")
                .param("tenant", UUID.fromString(TENANT)).query(Long.class).single();

        HttpResponse<String> response = run(lease, versionId, UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("AI_USE_CASE_DISABLED");
        assertThat(jdbc.sql("select count(*) from ai_run where tenant_id = :tenant")
                .param("tenant", UUID.fromString(TENANT)).query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void givenAnUnauthenticatedSseRequest_whenReadingRunEvents_thenItFailsClosedWithJson401()
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/runs/"
                        + UUID.randomUUID() + "/events"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/json"));
        assertThat(response.body()).contains("AUTHENTICATION_REQUIRED").doesNotContain("stackTrace");
    }

    private Lease issueLease() throws Exception {
        String body = """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","purpose_code":"DOCUMENT_DRAFT_ASSIST"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER);
        HttpResponse<String> response = http.send(base("/api/v1/context-leases")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(response.body());
        return new Lease(json.path("lease_id").stringValue(), json.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> run(Lease lease, UUID versionId, String idempotencyKey) throws Exception {
        String body = """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","context_lease_id":"%s","use_case_code":"DOCUMENT_DRAFT_ASSIST","document_id":"%s","document_version_id":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, lease.id(), DOCUMENT, versionId);
        return http.send(scoped("/api/v1/ai/runs", lease, PATIENT)
                .header("Content-Type", "application/json").header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> decide(Lease lease, UUID proposalId, String patientId, long rowVersion, String decision)
            throws Exception {
        String body = """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","expected_row_version":%d,"decision":"%s","reason":"医生人工审阅"}
                """.formatted(ORGANIZATION, FACILITY, patientId, ENCOUNTER, rowVersion, decision);
        HttpRequest.Builder request = scoped("/api/v1/ai/proposals/" + proposalId + "/decisions", lease, PATIENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Lease lease, String path, String lastEventId) throws Exception {
        HttpRequest.Builder request = scoped(path, lease, PATIENT).GET();
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder scoped(String path, Lease lease, String patientId) {
        return base(path)
                .header("X-Context-Lease-Id", lease.id()).header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION).header("X-Facility-Context", FACILITY)
                .header("X-Patient-Context", patientId).header("X-Encounter-Context", ENCOUNTER);
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT).header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE);
    }

    @AfterEach
    void restoreDisabledDevelopmentPolicy() {
        disableSyntheticTestPolicy();
    }

    private void enableSyntheticTestPolicy() {
        jdbc.sql("""
                update ai_use_case_policy set enabled = true, provider_code = 'DETERMINISTIC_FAKE',
                  model_code = 'haoemr-ci-fake-v1'
                where tenant_id = :tenant and use_case_code = 'DOCUMENT_DRAFT_ASSIST'
                """).param("tenant", UUID.fromString(TENANT)).update();
    }

    private void disableSyntheticTestPolicy() {
        jdbc.sql("""
                update ai_use_case_policy set enabled = false, provider_code = 'DETERMINISTIC_FAKE',
                  model_code = 'haoemr-ci-fake-v1'
                where tenant_id = :tenant and use_case_code = 'DOCUMENT_DRAFT_ASSIST'
                """).param("tenant", UUID.fromString(TENANT)).update();
    }

    private record Lease(String id, String watermark) {}
}
