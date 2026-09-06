package org.haoemr.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "haoemr.outbox.enabled=false")
@ActiveProfiles("dev-synthetic")
final class OutboxOperationsApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void integrationOperatorCanReadFailClosedHospitalDeliveryState() throws Exception {
        Lease lease = issueIntegrationLease();
        HttpResponse<String> response = http.send(scoped("/api/v1/outbox/reconciliation", lease)
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("external_delivery_configured").booleanValue()).isFalse();
        assertThat(body.path("scheduled_dispatch_enabled").booleanValue()).isFalse();
        assertThat(body.path("pending").longValue()).isGreaterThanOrEqualTo(0);
        assertThat(body.path("in_flight").longValue()).isGreaterThanOrEqualTo(0);
        assertThat(body.path("published").longValue()).isGreaterThanOrEqualTo(0);
        assertThat(body.path("external_delivery_accepted").longValue()).isGreaterThanOrEqualTo(0);
        assertThat(body.path("closed_without_external_evidence").longValue()).isGreaterThanOrEqualTo(0);
        assertThat(body.path("external_delivery_accepted").longValue()
                + body.path("closed_without_external_evidence").longValue())
                .isEqualTo(body.path("published").longValue());
        assertThat(body.path("dead_letter").longValue()).isGreaterThanOrEqualTo(0);
        assertThat(body.path("oldest_pending_seconds").longValue()).isGreaterThanOrEqualTo(0);
    }

    private Lease issueIntegrationLease() throws Exception {
        String body = """
                {"organization_id":"%s","facility_id":"%s","purpose_code":"INTEGRATION_OPERATIONS"}
                """.formatted(ORGANIZATION, FACILITY);
        HttpResponse<String> response = http.send(base("/api/v1/context-leases")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(response.body());
        return new Lease(json.path("lease_id").stringValue(), json.path("authorization_watermark").stringValue());
    }

    private HttpRequest.Builder scoped(String path, Lease lease) {
        return base(path).header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION).header("X-Facility-Context", FACILITY);
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT).header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE);
    }

    private record Lease(String id, String watermark) {
    }
}
