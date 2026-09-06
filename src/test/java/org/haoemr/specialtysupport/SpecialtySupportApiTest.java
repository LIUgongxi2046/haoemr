package org.haoemr.specialtysupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

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
final class SpecialtySupportApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void givenAnActivePackAndEvidence_whenAssessingADepartment_thenVersionAuditAndExpiryGuardAreEnforced()
            throws Exception {
        UUID departmentId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        seedDepartmentAndPack(departmentId, packId);
        Lease lease = issueOrganizationLease();
        String path = "/api/v1/specialty-support/" + FACILITY + "/" + departmentId + "/OBGYN";
        String evidenceHash = "a".repeat(64);

        MvcResult created = send("PUT", path, """
                {"organization_id":"%s","support_level":"BASIC_CLOSED_LOOP",
                 "pack_release_id":"%s","evidence_bundle_hash":"%s",
                 "missing_safety_gates":[],"expires_at":"2027-08-14T00:00:00Z",
                 "expected_row_version":0}
                """.formatted(ORGANIZATION, packId, evidenceHash), lease, UUID.randomUUID().toString());

        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        assertThat(created.getResponse().getHeader("etag")).isEqualTo("\"1\"");
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        String assessmentId = body.path("department_support_assessment_id").stringValue();
        assertThat(body.path("support_level").stringValue()).isEqualTo("BASIC_CLOSED_LOOP");
        assertThat(body.path("missing_safety_gates").size()).isZero();
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = cast(:tenant as uuid)
                  and resource_id = cast(:assessment as uuid) and action_code = 'SPECIALTY_SUPPORT_ASSESSED'
                """).param("tenant", TENANT).param("assessment", assessmentId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event where tenant_id = cast(:tenant as uuid)
                  and aggregate_id = cast(:assessment as uuid) and event_type = 'DepartmentSupportAssessed'
                """).param("tenant", TENANT).param("assessment", assessmentId).query(Long.class).single()).isEqualTo(1);

        MvcResult staleUpdate = send("PUT", path, """
                {"organization_id":"%s","support_level":"PACK_PENDING",
                 "pack_release_id":"%s","evidence_bundle_hash":null,
                 "missing_safety_gates":["DEVICE_INTERFACE"],"expires_at":null,
                 "expected_row_version":0}
                """.formatted(ORGANIZATION, packId), lease, UUID.randomUUID().toString());
        assertThat(staleUpdate.getResponse().getStatus()).isEqualTo(409);
        assertThat(staleUpdate.getResponse().getContentAsString()).contains("SUPPORT_VERSION_CONFLICT");

        jdbc.sql("""
                update department_support_assessment
                set assessed_at = now() - interval '2 days', expires_at = now() - interval '1 day'
                where tenant_id = cast(:tenant as uuid) and department_support_assessment_id = cast(:assessment as uuid)
                """).param("tenant", TENANT).param("assessment", assessmentId).update();
        MvcResult expired = send("GET", path, null, lease, null);
        assertThat(expired.getResponse().getStatus()).isEqualTo(200);
        assertThat(expired.getResponse().getContentAsString()).contains("PACK_PENDING", "EVIDENCE_EXPIRED");

        MvcResult deleted = send("DELETE", path + "?expected_row_version=1", null,
                lease, UUID.randomUUID().toString());
        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("""
                select count(*) from department_support_assessment
                where tenant_id = cast(:tenant as uuid)
                  and department_support_assessment_id = cast(:assessment as uuid)
                """).param("tenant", TENANT).param("assessment", assessmentId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = cast(:tenant as uuid)
                  and resource_id = cast(:assessment as uuid) and action_code = 'SPECIALTY_SUPPORT_REMOVED'
                """).param("tenant", TENANT).param("assessment", assessmentId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event where tenant_id = cast(:tenant as uuid)
                  and aggregate_id = cast(:assessment as uuid) and event_type = 'DepartmentSupportRemoved'
                """).param("tenant", TENANT).param("assessment", assessmentId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenMissingEvidence_whenClaimingPositiveSupport_thenTheDeclarationIsRejected() throws Exception {
        UUID departmentId = UUID.randomUUID();
        UUID packReleaseId = UUID.randomUUID();
        seedDepartmentAndPack(departmentId, packReleaseId);
        Lease lease = issueOrganizationLease();
        MvcResult response = send("PUT",
                "/api/v1/specialty-support/" + FACILITY + "/" + departmentId + "/MENTAL", """
                        {"organization_id":"%s","support_level":"BASIC_CLOSED_LOOP",
                         "pack_release_id":"%s","evidence_bundle_hash":null,
                         "missing_safety_gates":["RESTRICTED_DATA_REVIEW"],
                         "expires_at":"2027-08-14T00:00:00Z","expected_row_version":0}
                        """.formatted(ORGANIZATION, packReleaseId), lease, UUID.randomUUID().toString());

        assertThat(response.getResponse().getStatus()).isEqualTo(409);
        assertThat(response.getResponse().getContentAsString()).contains("SAFETY_GATE_MISSING");
        assertThat(jdbc.sql("""
                select count(*) from department_support_assessment
                where tenant_id = cast(:tenant as uuid) and department_id = cast(:department as uuid)
                """).param("tenant", TENANT).param("department", departmentId).query(Long.class).single()).isZero();
    }

    @Test
    void givenAModeledSpecialty_whenItsCapabilityCompositionIsNotPublished_thenPositiveSupportIsRejected()
            throws Exception {
        UUID supportedDepartment = UUID.randomUUID();
        UUID blockedDepartment = UUID.randomUUID();
        UUID cardiologySpecialtyPack = UUID.fromString("018f0000-0000-7000-8000-00000000c402");
        seedDepartmentAndPack(supportedDepartment, UUID.randomUUID());
        seedDepartmentAndPack(blockedDepartment, UUID.randomUUID());
        Lease lease = issueOrganizationLease();
        String evidenceHash = "c".repeat(64);

        MvcResult available = send("PUT",
                "/api/v1/specialty-support/" + FACILITY + "/" + supportedDepartment + "/CARDIOLOGY", """
                        {"organization_id":"%s","support_level":"BASIC_CLOSED_LOOP",
                         "pack_release_id":"%s","evidence_bundle_hash":"%s",
                         "missing_safety_gates":[],"expires_at":"2027-08-14T00:00:00Z",
                         "expected_row_version":0}
                        """.formatted(ORGANIZATION, cardiologySpecialtyPack, evidenceHash), lease,
                UUID.randomUUID().toString());
        assertThat(available.getResponse().getStatus()).isEqualTo(200);
        String assessment = objectMapper.readTree(available.getResponse().getContentAsString())
                .path("department_support_assessment_id").stringValue();
        assertThat(jdbc.sql("""
                select details->>'capability_module' from audit_event
                where tenant_id = cast(:tenant as uuid) and resource_id = cast(:assessment as uuid)
                  and action_code = 'SPECIALTY_SUPPORT_ASSESSED'
                """).param("tenant", TENANT).param("assessment", assessment).query(String.class).single())
                .isEqualTo("SPECIALTY_CARDIOLOGY");

        jdbc.sql("""
                update config_item set status = 'ARCHIVED', published_at = null
                where tenant_id = cast(:tenant as uuid)
                  and config_type = 'CAPABILITY_PACK_COMPOSITION'
                  and config_key = 'composition-syn-cardiology'
                """).param("tenant", TENANT).update();
        MvcResult unavailable = send("PUT",
                "/api/v1/specialty-support/" + FACILITY + "/" + blockedDepartment + "/CARDIOLOGY", """
                        {"organization_id":"%s","support_level":"BASIC_CLOSED_LOOP",
                         "pack_release_id":"%s","evidence_bundle_hash":"%s",
                         "missing_safety_gates":[],"expires_at":"2027-08-14T00:00:00Z",
                         "expected_row_version":0}
                        """.formatted(ORGANIZATION, cardiologySpecialtyPack, evidenceHash), lease,
                UUID.randomUUID().toString());
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(409);
        assertThat(unavailable.getResponse().getContentAsString()).contains("CAPABILITY_MODULE_NOT_ACTIVE");
    }

    private void seedDepartmentAndPack(UUID departmentId, UUID packId) {
        jdbc.sql("""
                insert into clinical_department(
                  tenant_id, facility_id, department_id, department_code, display_name, status)
                values (cast(:tenant as uuid), cast(:facility as uuid), :department,
                  :code, '合成专科', 'ACTIVE')
                """).param("tenant", TENANT).param("facility", FACILITY).param("department", departmentId)
                .param("code", "SYN-" + departmentId.toString().substring(0, 8)).update();
        jdbc.sql("""
                insert into specialty_pack_release(
                  tenant_id, specialty_pack_release_id, pack_code, semantic_version,
                  content_hash, manifest, lifecycle_status, compatibility_range, created_by)
                values (cast(:tenant as uuid), :pack, :code, '1.0.0', :hash,
                  '{"synthetic":true}', 'ACTIVE', '{"core":">=0.1"}', cast(:user as uuid))
                """).param("tenant", TENANT).param("pack", packId)
                .param("code", "PACK-" + packId.toString().substring(0, 8)).param("hash", "b".repeat(64))
                .param("user", USER).update();
    }

    private Lease issueOrganizationLease() throws Exception {
        MockHttpServletRequestBuilder request = baseRequest("POST", "/api/v1/context-leases")
                .contentType("application/json")
                .content("""
                        {"organization_id":"%s","facility_id":"%s","purpose_code":"CONFIGURATION_REVIEW"}
                        """.formatted(ORGANIZATION, FACILITY));
        MvcResult response = mockMvc.perform(request).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(response.getResponse().getContentAsString());
        return new Lease(json.path("lease_id").stringValue(), json.path("authorization_watermark").stringValue());
    }

    private MvcResult send(
            String method, String path, String body, Lease lease, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = baseRequest(method, path)
                .header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION)
                .header("X-Facility-Context", FACILITY);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType("application/json").content(body);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MockHttpServletRequestBuilder baseRequest(String method, String path) {
        return request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE);
    }

    private record Lease(String id, String watermark) {}
}
