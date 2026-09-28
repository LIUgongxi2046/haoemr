package org.haoemr.security;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DevelopmentSessionApiTest {

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void loginCurrentAndLogoutAreBackedByRevocableDatabaseSession() throws Exception {
        HttpResponse<String> invalid = send("/api/v1/session/login", "POST",
                "{\"username\":\"linwei\",\"password\":\"wrong-password\"}", null);
        assertThat(invalid.statusCode()).isEqualTo(401);

        HttpResponse<String> login = send("/api/v1/session/login", "POST",
                "{\"username\":\"linwei\",\"password\":\"HaoEMR-dev!\"}", null);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode payload = mapper.readTree(login.body());
        String token = payload.path("bearer_token").stringValue();
        assertThat(token).isNotBlank();
        assertThat(payload.path("user").path("display_name").stringValue()).isEqualTo("林伟 / William Lin");
        assertThat(payload.path("user").path("authorized_ward_ids").toString()).contains("018f0000-0000-7000-8000-00000000bb01");
        assertThat(jdbc.sql("select count(*) from user_session where revoked_at is null and expires_at > now()")
                .query(Long.class).single()).isPositive();

        HttpResponse<String> current = send("/api/v1/session/current", "GET", null, token);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(current.body()).contains("江城大学附属医院", "今日 08:00–17:00");

        assertThat(send("/api/v1/session/logout", "POST", null, token).statusCode()).isEqualTo(204);
        assertThat(send("/api/v1/session/current", "GET", null, token).statusCode()).isEqualTo(401);
        assertThat(jdbc.sql("select count(*) from user_session where revoked_at is not null")
                .query(Long.class).single()).isPositive();
    }

    @Test void laboratorySessionDoesNotAdvertiseAnUnauthorizedWard() throws Exception {
        HttpResponse<String> login = send("/api/v1/session/login", "POST",
                "{\"username\":\"ruifeng.cao\",\"password\":\"HaoEMR-dev!\"}", null);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode payload = mapper.readTree(login.body());
        assertThat(payload.path("user").path("authorized_ward_ids").isArray()).isTrue();
        assertThat(payload.path("user").path("authorized_ward_ids").isEmpty()).isTrue();
        String token = payload.path("bearer_token").stringValue();
        assertThat(send("/api/v1/session/logout", "POST", null, token).statusCode()).isEqualTo(204);
    }

    @Test
    void failedLoginAttemptsCommitAndLockTheDevelopmentAccount() throws Exception {
        resetCredential();
        try {
            for (int attempt = 1; attempt < 5; attempt++) {
                HttpResponse<String> invalid = send("/api/v1/session/login", "POST",
                        "{\"username\":\"linwei\",\"password\":\"wrong-password\"}", null);
                assertThat(invalid.statusCode()).isEqualTo(401);
            }
            assertThat(failedAttempts()).isEqualTo(4);

            HttpResponse<String> locked = send("/api/v1/session/login", "POST",
                    "{\"username\":\"linwei\",\"password\":\"wrong-password\"}", null);
            assertThat(locked.statusCode()).isEqualTo(423);
            assertThat(failedAttempts()).isEqualTo(5);
            assertThat(jdbc.sql("""
                    select count(*) from dev_user_credential
                    where lower(username) = 'linwei' and locked_until > now()
                    """).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("""
                    select count(*) from audit_event audit
                    join dev_user_credential credential on credential.tenant_id = audit.tenant_id
                      and credential.user_id = audit.actor_user_id
                    where lower(credential.username) = 'linwei' and audit.action_code = 'LOGIN_FAILED'
                    """).query(Long.class).single()).isGreaterThanOrEqualTo(5);
        } finally {
            resetCredential();
        }
    }

    private int failedAttempts() {
        return jdbc.sql("select failed_attempts from dev_user_credential where lower(username) = 'linwei'")
                .query(Integer.class).single();
    }

    private void resetCredential() {
        jdbc.sql("""
                update dev_user_credential set failed_attempts = 0, locked_until = null, updated_at = now()
                where lower(username) = 'linwei'
                """).update();
    }

    private HttpResponse<String> send(String path, String method, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
