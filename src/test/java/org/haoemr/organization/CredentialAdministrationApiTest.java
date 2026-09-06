package org.haoemr.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("dev-synthetic")
@Transactional
final class CredentialAdministrationApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ADMIN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000c203");

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :organization, :facility,
                  'SYSTEM_ADMIN', now() - interval '1 minute', 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do update set status = 'ACTIVE', valid_until = null
                """).param("tenant", TENANT).param("role", ADMIN_ROLE).param("user", USER)
                .param("organization", ORGANIZATION).param("facility", FACILITY).update();
    }

    @Test
    void everyCredentialWriteButtonPersistsVersionsEvidenceAndImmediateRevocation() throws Exception {
        UUID personId = jdbc.sql("""
                select person_id from workforce_person where tenant_id = :tenant and status = 'ACTIVE'
                order by person_id limit 1
                """).param("tenant", TENANT).query(UUID.class).single();
        String registration = "E2E-" + UUID.randomUUID();
        Instant from = Instant.now().minusSeconds(60);
        Instant until = Instant.now().plusSeconds(86400 * 365L);

        MvcResult created = send("POST", "/api/v1/admin/credentials", """
                {"person_id":"%s","credential_type":"PHYSICIAN_LICENSE",
                 "registration_number":"%s","issuing_authority":"端到端测试卫健委",
                 "practice_scope":{"schema_version":2,"specialty_code":"CARDIOLOGY","authorization_basis":"MEDICAL_AFFAIRS_TEST_APPROVAL","prescription_authority":"ORDINARY","antimicrobial_level":"RESTRICTED","controlled_drug_authorized":false,"max_surgery_level":2,"procedure_codes":["PROC-ECHO"],"temporary_authorization":false},"valid_from":"%s",
                 "valid_until":"%s","expected_row_version":0}
                """.formatted(personId, registration, from, until));
        assertThat(created.getResponse().getStatus()).withFailMessage(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID credentialId = UUID.fromString(createdBody.path("credential_id").stringValue());
        assertThat(createdBody.path("row_version").asLong()).isEqualTo(1);
        assertThat(jdbc.sql("select status from practitioner_credential where tenant_id = :tenant and credential_id = :credential")
                .param("tenant", TENANT).param("credential", credentialId).query(String.class).single()).isEqualTo("ACTIVE");

        MvcResult updated = send("PUT", "/api/v1/admin/credentials/" + credentialId, """
                {"person_id":"%s","credential_type":"PHYSICIAN_LICENSE",
                 "registration_number":"%s","issuing_authority":"端到端测试卫健委",
                 "practice_scope":{"schema_version":2,"specialty_code":"CARDIOLOGY","authorization_basis":"MEDICAL_AFFAIRS_TEST_APPROVAL","prescription_authority":"ORDINARY","antimicrobial_level":"SPECIAL","controlled_drug_authorized":true,"max_surgery_level":3,"procedure_codes":["PROC-ECHO","PROC-ABLATION"],"temporary_authorization":false},"valid_from":"%s",
                 "valid_until":"%s","expected_row_version":1}
                """.formatted(personId, registration, from, until));
        assertThat(updated.getResponse().getStatus()).withFailMessage(updated.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(objectMapper.readTree(updated.getResponse().getContentAsString()).path("row_version").asLong()).isEqualTo(2);
        assertThat(jdbc.sql("select practice_scope ->> 'max_surgery_level' from practitioner_credential where tenant_id = :tenant and credential_id = :credential")
                .param("tenant", TENANT).param("credential", credentialId).query(String.class).single()).isEqualTo("3");

        MvcResult allowed = send("POST", "/api/v1/admin/credentials/" + credentialId + "/simulations", """
                {"action":"SURGERY","patient_relationship":true,"surgery_level":3,"procedure_code":null}
                """);
        assertThat(allowed.getResponse().getStatus()).withFailMessage(allowed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(objectMapper.readTree(allowed.getResponse().getContentAsString()).path("decision").stringValue()).isEqualTo("ALLOW");

        MvcResult denied = send("POST", "/api/v1/admin/credentials/" + credentialId + "/simulations", """
                {"action":"SURGERY","patient_relationship":true,"surgery_level":4,"procedure_code":null}
                """);
        assertThat(denied.getResponse().getStatus()).withFailMessage(denied.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(objectMapper.readTree(denied.getResponse().getContentAsString()).path("decision").stringValue()).isEqualTo("DENY");

        MvcResult revoked = send("POST", "/api/v1/admin/credentials/" + credentialId + "/revoke", """
                {"expected_row_version":2,"reason":"端到端回归验证撤销立即生效"}
                """);
        assertThat(revoked.getResponse().getStatus()).withFailMessage(revoked.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode revokedBody = objectMapper.readTree(revoked.getResponse().getContentAsString());
        assertThat(revokedBody.path("status").stringValue()).isEqualTo("REVOKED");
        assertThat(revokedBody.path("row_version").asLong()).isEqualTo(3);
        assertThat(jdbc.sql("""
                select count(*) from practitioner_credential where tenant_id = :tenant
                  and credential_id = :credential and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", TENANT).param("credential", credentialId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from audit_event where tenant_id = :tenant and resource_id = :credential and resource_type = 'PRACTITIONER_CREDENTIAL'")
                .param("tenant", TENANT).param("credential", credentialId).query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("select count(*) from outbox_event where tenant_id = :tenant and aggregate_id = :credential and aggregate_type = 'PRACTITIONER_CREDENTIAL'")
                .param("tenant", TENANT).param("credential", credentialId).query(Long.class).single()).isEqualTo(3);
    }

    private MvcResult send(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", ADMIN_ROLE)
                .header("Idempotency-Key", "credential-e2e-" + UUID.randomUUID())
                .contentType("application/json").content(body);
        return mockMvc.perform(request).andReturn();
    }
}
