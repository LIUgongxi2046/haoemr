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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class OrganizationAdministrationApiTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID CLINICIAN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID ADMIN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000c201");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<UUID> createdUnits = new ArrayList<>();
    private final List<String> usedKeys = new ArrayList<>();

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
                """).param("tenant", TENANT).param("role", ADMIN_ROLE).param("user", USER)
                .param("organization", ORGANIZATION).param("facility", FACILITY).update();
    }

    @AfterEach
    void cleanup() {
        for (UUID unit : createdUnits) {
            jdbc.sql("delete from audit_event where tenant_id = :tenant and resource_id = :resource and resource_type = 'ORGANIZATION_UNIT'")
                    .param("tenant", TENANT).param("resource", unit).update();
            jdbc.sql("delete from outbox_event where tenant_id = :tenant and aggregate_id = :resource and aggregate_type = 'ORGANIZATION_UNIT'")
                    .param("tenant", TENANT).param("resource", unit).update();
        }
        for (String key : usedKeys) {
            jdbc.sql("delete from idempotency_record where tenant_id = :tenant and idempotency_key = :key and command_scope like 'ORGANIZATION_UNIT_%'")
                    .param("tenant", TENANT).param("key", key).update();
        }
        if (createdUnits.size() >= 3) {
            jdbc.sql("delete from clinical_bed where tenant_id = :tenant and bed_id = :id")
                    .param("tenant", TENANT).param("id", createdUnits.get(2)).update();
            jdbc.sql("delete from clinical_ward where tenant_id = :tenant and ward_id = :id")
                    .param("tenant", TENANT).param("id", createdUnits.get(1)).update();
            jdbc.sql("delete from clinical_department where tenant_id = :tenant and facility_id = :facility and department_id = :id")
                    .param("tenant", TENANT).param("facility", FACILITY).param("id", createdUnits.get(0)).update();
        }
        jdbc.sql("delete from role_assignment where tenant_id = :tenant and role_assignment_id = :role")
                .param("tenant", TENANT).param("role", ADMIN_ROLE).update();
    }

    @Test
    void givenAClinicianWithoutAdminRole_whenListingOrganizationUnits_thenAccessIsDenied() throws Exception {
        HttpResponse<String> response = get(CLINICIAN_ROLE);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("ADMIN_SCOPE_DENIED");
    }

    @Test
    void givenAnAdministrator_whenCreatingAndEndingAHierarchy_thenChildrenAuditAndVersionsAreEnforced()
            throws Exception {
        UUID department = UUID.randomUUID();
        UUID ward = UUID.randomUUID();
        UUID bed = UUID.randomUUID();
        createdUnits.addAll(List.of(department, ward, bed));
        Instant effectiveFrom = Instant.now().minusSeconds(60);

        HttpResponse<String> departmentResponse = create("""
                {"unit_type":"DEPARTMENT","unit_id":"%s","facility_id":"%s",
                 "unit_code":"C01-DEPT-%s","display_name":"C01 测试科室",
                 "subtype":"DEPARTMENT","effective_from":"%s"}
                """.formatted(department, FACILITY, department.toString().substring(0, 8), effectiveFrom));
        assertThat(departmentResponse.statusCode()).isEqualTo(201);

        HttpResponse<String> wardResponse = create("""
                {"unit_type":"WARD","unit_id":"%s","facility_id":"%s","department_id":"%s",
                 "unit_code":"C01-WARD-%s","display_name":"C01 测试病区","effective_from":"%s"}
                """.formatted(ward, FACILITY, department, ward.toString().substring(0, 8), effectiveFrom));
        assertThat(wardResponse.statusCode()).isEqualTo(201);

        HttpResponse<String> bedResponse = create("""
                {"unit_type":"BED","unit_id":"%s","parent_unit_id":"%s",
                 "unit_code":"C01-01","display_name":"C01-01","effective_from":"%s"}
                """.formatted(bed, ward, effectiveFrom));
        assertThat(bedResponse.statusCode()).isEqualTo(201);

        HttpResponse<String> list = get(ADMIN_ROLE);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(department.toString(), ward.toString(), bed.toString());

        HttpResponse<String> wardBlocked = deactivate("WARD", ward, 1);
        assertThat(wardBlocked.statusCode()).isEqualTo(409);
        assertThat(wardBlocked.body()).contains("ORGANIZATION_UNIT_HAS_ACTIVE_CHILDREN");

        HttpResponse<String> bedEnded = deactivate("BED", bed, 1);
        assertThat(bedEnded.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(bedEnded.body()).path("status").stringValue()).isEqualTo("INACTIVE");

        HttpResponse<String> wardEnded = deactivate("WARD", ward, 1);
        assertThat(wardEnded.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(wardEnded.body()).path("row_version").asLong()).isEqualTo(2);

        for (UUID resource : List.of(department, ward, bed)) {
            assertThat(jdbc.sql("select count(*) from audit_event where tenant_id = :tenant and resource_id = :resource and resource_type = 'ORGANIZATION_UNIT'")
                    .param("tenant", TENANT).param("resource", resource).query(Long.class).single()).isGreaterThan(0);
            assertThat(jdbc.sql("select count(*) from outbox_event where tenant_id = :tenant and aggregate_id = :resource and aggregate_type = 'ORGANIZATION_UNIT'")
                    .param("tenant", TENANT).param("resource", resource).query(Long.class).single()).isGreaterThan(0);
        }
    }

    private HttpResponse<String> get(UUID role) throws Exception {
        return http.send(base("/api/v1/admin/organization-units", role).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> create(String body) throws Exception {
        String key = "c01-org-create-" + UUID.randomUUID();
        usedKeys.add(key);
        return http.send(base("/api/v1/admin/organization-units", ADMIN_ROLE)
                        .header("Content-Type", "application/json").header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deactivate(String type, UUID id, long version) throws Exception {
        String key = "c01-org-deactivate-" + UUID.randomUUID();
        usedKeys.add(key);
        String body = """
                {"expected_row_version":%d,"effective_until":"%s","reason":"C01 受控停用测试"}
                """.formatted(version, Instant.now());
        return http.send(base("/api/v1/admin/organization-units/" + type + "/" + id + "/deactivate", ADMIN_ROLE)
                        .header("Content-Type", "application/json").header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder base(String path, UUID role) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT.toString())
                .header("X-HaoEMR-User-Id", USER.toString())
                .header("X-HaoEMR-Role-Assignment-Ids", role.toString());
    }
}
