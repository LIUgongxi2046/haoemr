package org.haoemr.organization;

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
final class WorkforceAdministrationApiTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID ADMIN_USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ADMIN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000c202");
    private static final UUID WARD = UUID.fromString("018f0000-0000-7000-8000-00000000bb01");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> usedKeys = new ArrayList<>();
    private UUID person;
    private UUID user;
    private UUID role;
    private UUID credential;

    @BeforeEach
    void seedAdministratorRole() {
        jdbc.sql("""
                insert into role_assignment(
                  tenant_id, role_assignment_id, user_id, organization_id, facility_id,
                  role_code, valid_from, status)
                values (:tenant, :role, :user, :organization, :facility,
                  'SYSTEM_ADMIN', now() - interval '1 minute', 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do update
                  set status = 'ACTIVE', valid_from = excluded.valid_from, valid_until = null
                """).param("tenant", TENANT).param("role", ADMIN_ROLE).param("user", ADMIN_USER)
                .param("organization", ORGANIZATION).param("facility", FACILITY).update();
    }

    @AfterEach
    void cleanup() {
        if (person != null) {
            jdbc.sql("delete from audit_event where tenant_id = :tenant and resource_id = :person and resource_type = 'WORKFORCE_PERSON'")
                    .param("tenant", TENANT).param("person", person).update();
            jdbc.sql("delete from outbox_event where tenant_id = :tenant and aggregate_id = :person and aggregate_type = 'WORKFORCE_PERSON'")
                    .param("tenant", TENANT).param("person", person).update();
        }
        for (String key : usedKeys) {
            jdbc.sql("delete from idempotency_record where tenant_id = :tenant and idempotency_key = :key and command_scope like 'WORKFORCE_%'")
                    .param("tenant", TENANT).param("key", key).update();
        }
        if (credential != null) {
            jdbc.sql("delete from practitioner_credential where tenant_id = :tenant and credential_id = :credential")
                    .param("tenant", TENANT).param("credential", credential).update();
        }
        if (role != null) {
            jdbc.sql("delete from role_assignment where tenant_id = :tenant and role_assignment_id = :role")
                    .param("tenant", TENANT).param("role", role).update();
        }
        if (person != null) {
            jdbc.sql("delete from workforce_person_name_history where tenant_id = :tenant and person_id = :person")
                    .param("tenant", TENANT).param("person", person).update();
        }
        if (user != null) {
            jdbc.sql("delete from audit_event where tenant_id = :tenant and actor_user_id = :user")
                    .param("tenant", TENANT).param("user", user).update();
            jdbc.sql("delete from context_lease where tenant_id = :tenant and user_id = :user")
                    .param("tenant", TENANT).param("user", user).update();
            jdbc.sql("delete from app_user where tenant_id = :tenant and user_id = :user")
                    .param("tenant", TENANT).param("user", user).update();
        }
        if (person != null) {
            jdbc.sql("delete from workforce_person where tenant_id = :tenant and person_id = :person")
                    .param("tenant", TENANT).param("person", person).update();
        }
        jdbc.sql("delete from role_assignment where tenant_id = :tenant and role_assignment_id = :role")
                .param("tenant", TENANT).param("role", ADMIN_ROLE).update();
    }

    @Test
    void givenAnAdministrator_whenOnboardingAndRevokingWorkforce_thenAccountPersonRoleAndScopeStaySeparate()
            throws Exception {
        person = UUID.randomUUID();
        user = UUID.randomUUID();
        role = UUID.randomUUID();
        credential = UUID.randomUUID();
        UUID department = jdbc.sql("select department_id from clinical_ward where tenant_id = :tenant and ward_id = :ward")
                .param("tenant", TENANT).param("ward", WARD).query(UUID.class).single();
        Instant validFrom = Instant.now().minusSeconds(60);
        String onboarding = """
                {"person_id":"%s","person_code":"EMP-%s","display_name":"C01 新入职医生",
                 "user_id":"%s","external_subject":"c01-workforce-%s",
                 "role_assignment_id":"%s","role_code":"CLINICIAN","position_code":"ATTENDING_PHYSICIAN",
                 "organization_id":"%s","facility_id":"%s","department_id":"%s","ward_id":"%s",
                 "valid_from":"%s","credential_id":"%s","credential_type":"PHYSICIAN_LICENSE",
                 "registration_number":"C01-LICENSE-%s","issuing_authority":"C01 测试卫生主管部门",
                 "practice_scope":{"specialty":"CARDIOLOGY"}}
                """.formatted(person, person.toString().substring(0, 8), user, user, role,
                ORGANIZATION, FACILITY, department, WARD, validFrom, credential,
                credential.toString().substring(0, 8));

        HttpResponse<String> onboarded = post("/api/v1/admin/workforce", onboarding);
        assertThat(onboarded.statusCode()).isEqualTo(201);
        JsonNode onboardedBody = objectMapper.readTree(onboarded.body());
        assertThat(onboardedBody.path("person_id").stringValue()).isEqualTo(person.toString());
        assertThat(onboardedBody.path("user_id").stringValue()).isEqualTo(user.toString());
        assertThat(onboardedBody.path("role_assignment_id").stringValue()).isEqualTo(role.toString());
        assertThat(onboardedBody.path("active_credential_count").asLong()).isEqualTo(1);

        HttpResponse<String> list = get("/api/v1/admin/workforce", ADMIN_USER, ADMIN_ROLE);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(person.toString(), user.toString(), role.toString(), "ATTENDING_PHYSICIAN");

        HttpResponse<String> leaseBeforeRevocation = issueLease(user, role);
        assertThat(leaseBeforeRevocation.statusCode()).isEqualTo(201);

        HttpResponse<String> disabled = post("/api/v1/admin/workforce/accounts/" + user + "/deactivate", """
                {"expected_row_version":1,"reason":"C01 离岗撤权测试"}
                """);
        assertThat(disabled.statusCode()).withFailMessage(disabled.body()).isEqualTo(200);
        assertThat(objectMapper.readTree(disabled.body()).path("account_status").stringValue()).isEqualTo("DISABLED");
        assertThat(issueLease(user, role).statusCode()).isEqualTo(403);

        HttpResponse<String> ended = post("/api/v1/admin/workforce/role-assignments/" + role + "/end", """
                {"expected_row_version":1,"effective_until":"%s","reason":"C01 任期结束测试"}
                """.formatted(Instant.now()));
        assertThat(ended.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(ended.body()).path("role_status").stringValue()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("select status from workforce_assignment where tenant_id = :tenant and source_role_assignment_id = :role")
                .param("tenant", TENANT).param("role", role).query(String.class).single()).isEqualTo("ENDED");
    }

    private HttpResponse<String> issueLease(UUID actor, UUID actorRole) throws Exception {
        String body = """
                {"organization_id":"%s","facility_id":"%s","purpose_code":"WORKFORCE_REVOCATION_TEST"}
                """.formatted(ORGANIZATION, FACILITY);
        return http.send(base("/api/v1/context-leases", actor, actorRole)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        String key = "c01-workforce-" + UUID.randomUUID();
        usedKeys.add(key);
        return http.send(base(path, ADMIN_USER, ADMIN_ROLE)
                        .header("Content-Type", "application/json").header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, UUID actor, UUID actorRole) throws Exception {
        return http.send(base(path, actor, actorRole).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder base(String path, UUID actor, UUID actorRole) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT.toString())
                .header("X-HaoEMR-User-Id", actor.toString())
                .header("X-HaoEMR-Role-Assignment-Ids", actorRole.toString());
    }
}
