package org.haoemr.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ExecutionRoleSessionApiTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void executionStaffUseIndependentAccountsAndServerAssignedRoles() throws Exception {
        Map<String, String> expected = Map.of(
                "jiahui.xu", "REGISTERED_NURSE",
                "qinghua.deng", "PHARMACIST",
                "shuhan.jiang", "PHARMACIST",
                "ruifeng.cao", "LAB_TECHNICIAN",
                "chenxi.peng", "IMAGING_TECHNICIAN",
                "meiqi.zeng", "PATHOLOGY_TECHNICIAN");
        for (Map.Entry<String, String> account : expected.entrySet()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session/login"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"username\":\"" + account.getKey() + "\",\"password\":\"HaoEMR-dev!\"}"))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(account.getKey()).isEqualTo(200);
            JsonNode body = mapper.readTree(response.body());
            assertThat(body.path("user").path("role_codes").toString()).contains(account.getValue());
            assertThat(body.path("user").path("role_assignment_ids").size()).isEqualTo(1);
        }
    }
}
