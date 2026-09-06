package org.haoemr.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ContextLeaseApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String PATIENT = "018f0000-0000-7000-8000-000000000001";
    private static final String ENCOUNTER = "018f0000-0000-7000-8000-000000000101";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void givenAnActiveScopedRole_whenIssuingALease_thenLeaseAuditAndOutboxCommitTogether() throws Exception {
        HttpResponse<String> response = send(PATIENT, ENCOUNTER);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(response.headers().firstValue("x-frame-options")).contains("DENY");
        var body = objectMapper.readTree(response.body());
        UUID leaseId = UUID.fromString(body.path("lease_id").stringValue());
        assertThat(body.path("authorization_watermark").stringValue()).hasSize(64);
        assertThat(body.path("model_residency_policy").stringValue()).isEqualTo("ON_PREM_ONLY");
        assertThat(jdbc.sql("select count(*) from context_lease where tenant_id = :tenant and lease_id = :lease")
                .param("tenant", UUID.fromString(TENANT)).param("lease", leaseId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_event where tenant_id = :tenant and resource_id = :lease and action_code = 'CONTEXT_LEASE_ISSUED'")
                .param("tenant", UUID.fromString(TENANT)).param("lease", leaseId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox_event where tenant_id = :tenant and aggregate_id = :lease and event_type = 'ContextLeaseIssued'")
                .param("tenant", UUID.fromString(TENANT)).param("lease", leaseId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenAnArrivedSyntheticPatient_whenSwitchingContext_thenTheLeaseIsIssued() throws Exception {
        String[] arrivedContext = jdbc.sql("""
                select patient_id::text, encounter_id::text
                from encounter
                where tenant_id = :tenant and source_system = 'SYNTHETIC-50' and status = 'ARRIVED'
                order by source_key
                limit 1
                """)
                .param("tenant", UUID.fromString(TENANT))
                .query((resultSet, rowNumber) -> new String[] {
                        resultSet.getString(1), resultSet.getString(2)
                })
                .single();

        HttpResponse<String> response = send(arrivedContext[0], arrivedContext[1]);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).doesNotContain("CONTEXT_NOT_PERMITTED");
        assertThat(objectMapper.readTree(response.body()).path("patient_id").stringValue())
                .isEqualTo(arrivedContext[0]);
    }

    @Test
    void givenAnArrivedSyntheticPatient_whenLoadingTheWorkspace_thenAllClinicalFactsRemainReadable()
            throws Exception {
        String[] arrivedContext = jdbc.sql("""
                select patient_id::text, encounter_id::text
                from encounter
                where tenant_id = :tenant and source_system = 'SYNTHETIC-50' and status = 'ARRIVED'
                order by source_key
                limit 1
                """)
                .param("tenant", UUID.fromString(TENANT))
                .query((resultSet, rowNumber) -> new String[] {
                        resultSet.getString(1), resultSet.getString(2)
                })
                .single();
        var lease = objectMapper.readTree(send(arrivedContext[0], arrivedContext[1]).body());

        for (String resource : java.util.List.of("diagnoses", "orders", "results")) {
            HttpResponse<String> response = getClinicalFacts(
                    resource, arrivedContext[0], arrivedContext[1],
                    lease.path("lease_id").stringValue(),
                    lease.path("authorization_watermark").stringValue());

            assertThat(response.statusCode()).as(resource).isEqualTo(200);
            assertThat(objectMapper.readTree(response.body()).size()).as(resource).isEqualTo(1);
        }
    }

    @Test
    void givenAPatientOutsideTheLeaseContext_whenIssuingALease_thenItIsDeniedWithoutSideEffects() throws Exception {
        long before = jdbc.sql("select count(*) from context_lease where tenant_id = :tenant")
                .param("tenant", UUID.fromString(TENANT)).query(Long.class).single();

        HttpResponse<String> response = send(UUID.randomUUID().toString(), null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("CONTEXT_NOT_PERMITTED");
        assertThat(response.body()).doesNotContain("patient");
        assertThat(jdbc.sql("select count(*) from context_lease where tenant_id = :tenant")
                .param("tenant", UUID.fromString(TENANT)).query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void givenAnOversizedApiBody_whenReceived_thenItIsRejectedBeforeClinicalProcessing() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/context-leases"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1_048_577)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("REQUEST_BODY_TOO_LARGE");
    }

    private HttpResponse<String> send(String patientId, String encounterId) throws Exception {
        String encounterProperty = encounterId == null ? "" : ",\"encounter_id\":\"" + encounterId + "\"";
        String body = """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s"%s,"purpose_code":"DOCUMENT_DRAFT"}
                """.formatted(ORGANIZATION, FACILITY, patientId, encounterProperty);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/context-leases"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getClinicalFacts(
            String resource, String patientId, String encounterId, String leaseId, String watermark)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/" + resource
                        + "?encounter_id=" + encounterId))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE)
                .header("X-Context-Lease-Id", leaseId)
                .header("X-Authorization-Watermark", watermark)
                .header("X-Organization-Context", ORGANIZATION)
                .header("X-Facility-Context", FACILITY)
                .header("X-Patient-Context", patientId)
                .header("X-Encounter-Context", encounterId)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
