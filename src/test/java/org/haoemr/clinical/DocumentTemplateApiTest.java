package org.haoemr.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
final class DocumentTemplateApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ADMIN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa09");
    private static final UUID CLINICIAN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID REVIEWER = UUID.fromString("018f0000-0000-7000-8000-00000000aa06");
    private static final UUID PATIENT = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID ENCOUNTER = UUID.fromString("018f0000-0000-7000-8000-000000000101");

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final UUID reviewerRole = UUID.randomUUID();
    private final List<UUID> documents = new ArrayList<>();
    private final List<String> idempotencyKeys = new ArrayList<>();
    private UUID templateId;

    @BeforeEach
    void seedIndependentReviewer() {
        jdbc.sql("""
                insert into role_assignment(tenant_id,role_assignment_id,user_id,organization_id,
                  facility_id,role_code,valid_from,status)
                values (:tenant,:role,:user,:organization,:facility,'CLINICAL_ADMIN',now()-interval '1 day','ACTIVE')
                """).param("tenant",TENANT).param("role",reviewerRole).param("user",REVIEWER)
                .param("organization",ORGANIZATION).param("facility",FACILITY).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("alter table document_quality_run disable trigger document_quality_run_immutable").update();
        try {
            for (UUID document : documents) {
                jdbc.sql("delete from outbox_event where tenant_id=:tenant and (aggregate_id=:document or payload->>'document_id'=:document_text)")
                        .param("tenant",TENANT).param("document",document).param("document_text",document.toString()).update();
                jdbc.sql("delete from quality_finding where tenant_id=:tenant and document_id=:document")
                        .param("tenant",TENANT).param("document",document).update();
                jdbc.sql("delete from document_quality_run where tenant_id=:tenant and document_id=:document")
                        .param("tenant",TENANT).param("document",document).update();
                jdbc.sql("update clinical_document set current_version_id=null where tenant_id=:tenant and document_id=:document")
                        .param("tenant",TENANT).param("document",document).update();
                jdbc.sql("delete from clinical_document_version where tenant_id=:tenant and document_id=:document")
                        .param("tenant",TENANT).param("document",document).update();
                jdbc.sql("delete from clinical_document where tenant_id=:tenant and document_id=:document")
                        .param("tenant",TENANT).param("document",document).update();
            }
        } finally {
            jdbc.sql("alter table document_quality_run enable trigger document_quality_run_immutable").update();
        }
        if (templateId != null) {
            jdbc.sql("alter table clinical_document_template_version disable trigger clinical_document_template_version_immutable").update();
            try {
                jdbc.sql("delete from clinical_document_template_version where tenant_id=:tenant and template_id=:template")
                        .param("tenant",TENANT).param("template",templateId).update();
            } finally {
                jdbc.sql("alter table clinical_document_template_version enable trigger clinical_document_template_version_immutable").update();
            }
            jdbc.sql("delete from clinical_document_template where tenant_id=:tenant and template_id=:template")
                    .param("tenant",TENANT).param("template",templateId).update();
            jdbc.sql("delete from audit_event where tenant_id=:tenant and resource_id=:template")
                    .param("tenant",TENANT).param("template",templateId).update();
            jdbc.sql("delete from outbox_event where tenant_id=:tenant and aggregate_id=:template")
                    .param("tenant",TENANT).param("template",templateId).update();
        }
        for (String key : idempotencyKeys) jdbc.sql("delete from idempotency_record where tenant_id=:tenant and idempotency_key=:key")
                .param("tenant",TENANT).param("key",key).update();
        jdbc.sql("delete from role_assignment where tenant_id=:tenant and role_assignment_id=:role")
                .param("tenant",TENANT).param("role",reviewerRole).update();
    }

    @Test
    void givenVersionedTemplate_whenIndependentlyPublished_thenNewDocumentsAdvanceAndHistoryKeepsOriginalSemantics()
            throws Exception {
        String type = "TEST.TEMPLATE." + UUID.randomUUID();
        HttpResponse<String> created = adminPost("/api/v1/admin/document-templates", USER, ADMIN_ROLE, """
                {"template_code":"%s","display_name":"集成测试病历模板","document_type_code":"%s",
                 "section_schema":{"type":"object","properties":{"chief_complaint":{"type":"string"}},"additionalProperties":false},
                 "required_fields":["chief_complaint"],"display_rules":{"layout":"single-column"}}
                """.formatted("TPL-" + UUID.randomUUID(), type));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode v1Draft = objectMapper.readTree(created.body());
        templateId = UUID.fromString(v1Draft.path("template_id").stringValue());
        UUID v1 = UUID.fromString(v1Draft.path("template_version_id").stringValue());
        assertThat(v1Draft.path("version_status").stringValue()).isEqualTo("DRAFT");

        HttpResponse<String> selfPublish = adminPost("/api/v1/admin/document-templates/" + templateId
                + "/versions/" + v1 + "/publish", USER, ADMIN_ROLE,
                "{\"expected_version_row_version\":1,\"effective_from\":\"2026-08-20T00:00:00Z\"}");
        assertThat(selfPublish.statusCode()).isEqualTo(409);

        HttpResponse<String> published1 = adminPost("/api/v1/admin/document-templates/" + templateId
                + "/versions/" + v1 + "/publish", REVIEWER, reviewerRole,
                "{\"expected_version_row_version\":1,\"effective_from\":\"2026-08-20T00:00:00Z\"}");
        assertThat(published1.statusCode()).as(published1.body()).isEqualTo(200);
        JsonNode firstDocument = createDocument(type);
        documents.add(UUID.fromString(firstDocument.path("document_id").stringValue()));
        assertThat(firstDocument.path("template_version_id").stringValue()).isEqualTo(v1.toString());
        assertThat(firstDocument.path("template_version_no").intValue()).isEqualTo(1);

        HttpResponse<String> v2Response = adminPost("/api/v1/admin/document-templates/" + templateId
                + "/versions", USER, ADMIN_ROLE, """
                {"expected_template_row_version":1,
                 "section_schema":{"type":"object","properties":{"chief_complaint":{"type":"string"},"present_illness":{"type":"string"}},"additionalProperties":false},
                 "required_fields":["chief_complaint","present_illness"],"display_rules":{"layout":"two-sections"}}
                """);
        assertThat(v2Response.statusCode()).as(v2Response.body()).isEqualTo(201);
        JsonNode v2Draft = objectMapper.readTree(v2Response.body());
        UUID v2 = UUID.fromString(v2Draft.path("template_version_id").stringValue());
        HttpResponse<String> published2 = adminPost("/api/v1/admin/document-templates/" + templateId
                + "/versions/" + v2 + "/publish", REVIEWER, reviewerRole,
                "{\"expected_version_row_version\":1,\"effective_from\":\"2026-08-20T01:00:00Z\"}");
        assertThat(published2.statusCode()).as(published2.body()).isEqualTo(200);

        JsonNode secondDocument = createDocument(type);
        documents.add(UUID.fromString(secondDocument.path("document_id").stringValue()));
        assertThat(secondDocument.path("template_version_id").stringValue()).isEqualTo(v2.toString());
        JsonNode historical = currentDocument(documents.getFirst());
        assertThat(historical.path("template_version_id").stringValue()).isEqualTo(v1.toString());
        assertThat(jdbc.sql("select status from clinical_document_template_version where tenant_id=:tenant and template_version_id=:version")
                .param("tenant",TENANT).param("version",v1).query(String.class).single()).isEqualTo("RETIRED");

        JsonNode incompleteDocument = createDocument(type, false);
        UUID incompleteDocumentId = UUID.fromString(incompleteDocument.path("document_id").stringValue());
        documents.add(incompleteDocumentId);
        HttpResponse<String> quality = runQualityCheck(incompleteDocumentId,
                UUID.fromString(incompleteDocument.path("document_version_id").stringValue()));
        assertThat(quality.statusCode()).as(quality.body()).isEqualTo(200);
        assertThat(quality.body()).contains("DOC-PRESENT-ILLNESS-REQUIRED", "BLOCKING");
        assertThat(jdbc.sql("""
                select rule_version from document_quality_run
                where tenant_id=:tenant and document_id=:document order by executed_at desc limit 1
                """).param("tenant",TENANT).param("document",incompleteDocumentId)
                .query(String.class).single()).contains("+tpl-2-" + v2.toString().replace("-", "").substring(0, 12));

        HttpResponse<String> deactivated = adminPost("/api/v1/admin/document-templates/" + templateId
                + "/deactivate", USER, ADMIN_ROLE,
                "{\"expected_template_row_version\":2,\"reason\":\"测试停用后禁止新建病历\"}");
        assertThat(deactivated.statusCode()).as(deactivated.body()).isEqualTo(200);
        assertThat(createDocumentResponse(type).statusCode()).isEqualTo(409);
    }

    @Test
    void givenPublishedFormConfiguration_whenTemplateDraftCreated_thenRequiredFieldsAndVersionAreBound() throws Exception {
        String type = "TEST.FORM.BINDING." + UUID.randomUUID();
        HttpResponse<String> created = adminPost("/api/v1/admin/document-templates", USER, ADMIN_ROLE, """
                {"template_code":"%s","display_name":"表单绑定测试模板","document_type_code":"%s",
                 "section_schema":{"type":"object","properties":{
                   "patient_name":{"type":"string"},"encounter_no":{"type":"string"},
                   "department":{"type":"string"},"recorded_at":{"type":"string"},
                   "chief_complaint":{"type":"string"},"present_illness":{"type":"string"},
                   "allergies":{"type":"string"},"primary_diagnosis":{"type":"string"},
                   "treatment_plan":{"type":"string"},"followup_plan":{"type":"string"},
                   "author_signature":{"type":"string"},"reviewer_signature":{"type":"string"}},
                   "additionalProperties":false},
                 "required_fields":["chief_complaint"],
                 "display_rules":{"layout":"single-column","form_config_key":"form-outpatient-record-v1"}}
                """.formatted("TPL-FORM-" + UUID.randomUUID(), type));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode draft = objectMapper.readTree(created.body());
        templateId = UUID.fromString(draft.path("template_id").stringValue());
        assertThat(draft.path("required_fields").toString())
                .contains("chief_complaint", "present_illness", "allergies", "primary_diagnosis", "treatment_plan");
        assertThat(draft.path("display_rules").path("form_config_key").stringValue())
                .isEqualTo("form-outpatient-record-v1");
        assertThat(draft.path("display_rules").path("form_config_id").stringValue()).isNotBlank();
        assertThat(draft.path("display_rules").path("form_config_row_version").longValue()).isGreaterThan(0);
    }

    private JsonNode createDocument(String type) throws Exception {
        return createDocument(type, true);
    }
    private JsonNode createDocument(String type, boolean includePresentIllness) throws Exception {
        HttpResponse<String> response = createDocumentResponse(type, includePresentIllness);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return objectMapper.readTree(response.body());
    }
    private HttpResponse<String> createDocumentResponse(String type) throws Exception {
        return createDocumentResponse(type, true);
    }
    private HttpResponse<String> createDocumentResponse(String type, boolean includePresentIllness) throws Exception {
        Lease lease = issueLease();
        String sections = includePresentIllness
                ? "{\"chief_complaint\":\"模板版本测试\",\"present_illness\":\"升级后字段\"}"
                : "{\"chief_complaint\":\"模板必填质控测试\"}";
        return http.send(base("/api/v1/documents",USER,CLINICIAN_ROLE)
                .header("X-Context-Lease-Id",lease.id()).header("X-Authorization-Watermark",lease.watermark())
                .header("X-Organization-Context",ORGANIZATION.toString()).header("X-Facility-Context",FACILITY.toString())
                .header("X-Patient-Context",PATIENT.toString()).header("X-Encounter-Context",ENCOUNTER.toString())
                .header("Idempotency-Key",key()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                         "document_type_code":"%s","sections":%s}
                        """.formatted(ORGANIZATION,FACILITY,PATIENT,ENCOUNTER,type,sections))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> runQualityCheck(UUID document, UUID version) throws Exception {
        Lease lease=issueLease();
        return http.send(base("/api/v1/documents/"+document+"/quality-checks",USER,CLINICIAN_ROLE)
                .header("X-Context-Lease-Id",lease.id()).header("X-Authorization-Watermark",lease.watermark())
                .header("X-Organization-Context",ORGANIZATION.toString()).header("X-Facility-Context",FACILITY.toString())
                .header("X-Patient-Context",PATIENT.toString()).header("X-Encounter-Context",ENCOUNTER.toString())
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("""
                        {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                         "document_version_id":"%s"}
                        """.formatted(ORGANIZATION,FACILITY,PATIENT,ENCOUNTER,version))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode currentDocument(UUID document) throws Exception {
        Lease lease=issueLease();
        HttpResponse<String> response=http.send(base("/api/v1/documents/"+document,USER,CLINICIAN_ROLE)
                .header("X-Context-Lease-Id",lease.id()).header("X-Authorization-Watermark",lease.watermark())
                .header("X-Organization-Context",ORGANIZATION.toString()).header("X-Facility-Context",FACILITY.toString())
                .header("X-Patient-Context",PATIENT.toString()).header("X-Encounter-Context",ENCOUNTER.toString())
                .GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return objectMapper.readTree(response.body());
    }
    private Lease issueLease() throws Exception {
        HttpResponse<String> response=http.send(base("/api/v1/context-leases",USER,CLINICIAN_ROLE)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("""
                        {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","purpose_code":"DOCUMENT_TEMPLATE_TEST"}
                        """.formatted(ORGANIZATION,FACILITY,PATIENT,ENCOUNTER))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201); JsonNode body=objectMapper.readTree(response.body());
        return new Lease(body.path("lease_id").stringValue(),body.path("authorization_watermark").stringValue());
    }
    private HttpResponse<String> adminPost(String path,UUID user,UUID role,String body)throws Exception{
        return http.send(base(path,user,role).header("Idempotency-Key",key()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder base(String path,UUID user,UUID role){return HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
            .header("Authorization","Bearer dev-synthetic-token").header("X-HaoEMR-Tenant-Id",TENANT.toString())
            .header("X-HaoEMR-User-Id",user.toString()).header("X-HaoEMR-Role-Assignment-Ids",role.toString());}
    private String key(){String key="r01-template-"+UUID.randomUUID();idempotencyKeys.add(key);return key;}
    private record Lease(String id,String watermark){}
}
