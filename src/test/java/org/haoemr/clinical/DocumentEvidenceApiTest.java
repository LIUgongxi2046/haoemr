package org.haoemr.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DocumentEvidenceApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID PATIENT = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID ENCOUNTER = UUID.fromString("018f0000-0000-7000-8000-000000000101");

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ClinicalObjectStorage storage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> keys = new ArrayList<>();
    private final List<String> storageKeys = new ArrayList<>();
    private UUID documentId;
    private UUID orderId;
    private UUID lifecycleOrderId;

    @AfterEach
    void cleanup() {
        storageKeys.forEach(storage::deleteBestEffort);
        if (documentId != null) {
            jdbc.sql("alter table clinical_document_attachment disable trigger clinical_document_attachment_immutable").update();
            jdbc.sql("alter table clinical_document_source_reference disable trigger clinical_document_source_reference_immutable").update();
            jdbc.sql("alter table clinical_document_evidence_lifecycle_event disable trigger clinical_document_evidence_lifecycle_immutable").update();
            jdbc.sql("alter table document_quality_run disable trigger document_quality_run_immutable").update();
            try {
                jdbc.sql("delete from outbox_event where tenant_id=:tenant and payload->>'document_id'=:document")
                        .param("tenant", TENANT).param("document", documentId.toString()).update();
                jdbc.sql("delete from audit_event where tenant_id=:tenant and resource_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
                jdbc.sql("delete from clinical_document_evidence_lifecycle_event where tenant_id=:tenant and document_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
                jdbc.sql("delete from clinical_document_source_reference where tenant_id=:tenant and document_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
                jdbc.sql("delete from clinical_document_attachment where tenant_id=:tenant and document_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
                jdbc.sql("delete from quality_finding where tenant_id=:tenant and document_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
                jdbc.sql("delete from document_quality_run where tenant_id=:tenant and document_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
                jdbc.sql("update clinical_document set current_version_id=null where tenant_id=:tenant and document_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
                jdbc.sql("delete from clinical_document_version where tenant_id=:tenant and document_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
                jdbc.sql("delete from clinical_document where tenant_id=:tenant and document_id=:document")
                        .param("tenant", TENANT).param("document", documentId).update();
            } finally {
                jdbc.sql("alter table document_quality_run enable trigger document_quality_run_immutable").update();
                jdbc.sql("alter table clinical_document_source_reference enable trigger clinical_document_source_reference_immutable").update();
                jdbc.sql("alter table clinical_document_attachment enable trigger clinical_document_attachment_immutable").update();
                jdbc.sql("alter table clinical_document_evidence_lifecycle_event enable trigger clinical_document_evidence_lifecycle_immutable").update();
            }
        }
        if (orderId != null) jdbc.sql("delete from clinical_order where tenant_id=:tenant and order_id=:order")
                .param("tenant", TENANT).param("order", orderId).update();
        if (lifecycleOrderId != null) jdbc.sql("delete from clinical_order where tenant_id=:tenant and order_id=:order")
                .param("tenant", TENANT).param("order", lifecycleOrderId).update();
        for (String key : keys) jdbc.sql("delete from idempotency_record where tenant_id=:tenant and idempotency_key=:key")
                .param("tenant", TENANT).param("key", key).update();
    }

    @Test
    void givenImmutableEvidence_whenCorrectedReplacedRevokedAndVoided_thenLifecycleIsAppendOnly()
            throws Exception {
        Lease lease = issueLease();
        HttpResponse<String> created = post("/api/v1/documents", lease, key(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_type_code":"WS445.2.OUTPATIENT_RECORD",
                 "sections":{"chief_complaint":"证据生命周期测试","present_illness":"现病史完整",
                   "assessment":"临床评估完整","treatment_plan":"治疗计划完整"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode document = objectMapper.readTree(created.body());
        documentId = UUID.fromString(document.path("document_id").stringValue());
        UUID versionId = UUID.fromString(document.path("document_version_id").stringValue());

        byte[] originalBytes = "original evidence".getBytes(StandardCharsets.UTF_8);
        JsonNode original = objectMapper.readTree(post("/api/v1/documents/" + documentId + "/attachments",
                lease, key(), attachmentBody(versionId, "original.txt", "text/plain", originalBytes,
                        sha256(originalBytes))).body());
        UUID originalId = UUID.fromString(original.path("attachment_id").stringValue());
        storageKeys.add(storageKey(originalId));

        byte[] replacementBytes = "replacement evidence".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> replacementResponse = post("/api/v1/documents/" + documentId + "/attachments",
                lease, key(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","original_filename":"replacement.txt","media_type":"text/plain",
                 "content_base64":"%s","expected_sha256":"%s","target_field_path":"sections.present_illness",
                 "replaces_attachment_id":"%s","replacement_reason":"原附件内容需要以新文件依法替换"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, versionId,
                        Base64.getEncoder().encodeToString(replacementBytes), sha256(replacementBytes), originalId));
        assertThat(replacementResponse.statusCode()).as(replacementResponse.body()).isEqualTo(201);
        JsonNode replacement = objectMapper.readTree(replacementResponse.body());
        UUID replacementId = UUID.fromString(replacement.path("attachment_id").stringValue());
        storageKeys.add(storageKey(replacementId));

        JsonNode afterReplacement = objectMapper.readTree(get(
                "/api/v1/documents/" + documentId + "/sources?document_version_id=" + versionId, lease).body());
        JsonNode originalState = findById(afterReplacement.path("attachments"), "attachment_id", originalId);
        assertThat(originalState.path("evidence_state").stringValue()).isEqualTo("SUPERSEDED");
        assertThat(originalState.path("superseded_by_attachment_id").stringValue())
                .isEqualTo(replacementId.toString());

        HttpResponse<String> voided = post("/api/v1/documents/" + documentId + "/attachments/"
                + replacementId + "/voids", lease, key(), lifecycleBody(versionId, "复核后确认替换附件不应继续使用"));
        assertThat(voided.statusCode()).as(voided.body()).isEqualTo(200);
        assertThat(voided.body()).contains("\"evidence_state\":\"VOID\"");

        lifecycleOrderId = UUID.randomUUID();
        insertOrder(lifecycleOrderId, "来源引用生命周期测试");
        JsonNode reference = objectMapper.readTree(post("/api/v1/documents/" + documentId
                + "/source-references", lease, key(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","source_type":"ORDER","source_resource_id":"%s",
                 "target_field_path":"sections.treatment_plan","excerpt":"原始引用摘要"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, versionId, lifecycleOrderId)).body());
        UUID referenceId = UUID.fromString(reference.path("source_reference_id").stringValue());

        HttpResponse<String> corrected = post("/api/v1/documents/" + documentId + "/source-references/"
                + referenceId + "/corrections", lease, key(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","target_field_path":"sections.assessment",
                 "excerpt":"更正后的摘要","reason":"原引用目标字段选择错误，需要更正"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, versionId));
        assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(200);
        assertThat(corrected.body()).contains("\"evidence_state\":\"CORRECTED\"",
                "\"target_field_path\":\"sections.assessment\"");

        HttpResponse<String> revoked = post("/api/v1/documents/" + documentId + "/source-references/"
                + referenceId + "/revocations", lease, key(), lifecycleBody(versionId,
                        "进一步复核后确认该来源引用不适用于本病历"));
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(revoked.body()).contains("\"evidence_state\":\"REVOKED\"");

        assertThatThrownBy(() -> jdbc.sql("""
                update clinical_document_evidence_lifecycle_event set reason='tampered'
                where tenant_id=:tenant and document_id=:document
                """).param("tenant", TENANT).param("document", documentId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("document evidence lifecycle events are immutable");
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id=:tenant and resource_id=:document
                  and action_code in ('DOCUMENT_ATTACHMENT_VOIDED','DOCUMENT_SOURCE_REFERENCE_CORRECTED',
                    'DOCUMENT_SOURCE_REFERENCE_REVOKED')
                """).param("tenant", TENANT).param("document", documentId).query(Long.class).single())
                .isEqualTo(3);
    }

    @Test
    void givenImmutableDocumentSources_whenAReferencedFactChanges_thenSourceIsStaleAndSigningRequiresRecheck()
            throws Exception {
        Lease lease = issueLease();
        HttpResponse<String> created = post("/api/v1/documents", lease, key(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_type_code":"WS445.2.OUTPATIENT_RECORD",
                 "sections":{"chief_complaint":"附件与来源测试","present_illness":"现病史完整",
                   "assessment":"临床评估完整","treatment_plan":"治疗计划完整"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode document = objectMapper.readTree(created.body());
        documentId = UUID.fromString(document.path("document_id").stringValue());
        UUID versionId = UUID.fromString(document.path("document_version_id").stringValue());

        byte[] attachmentBytes = "source evidence".getBytes(StandardCharsets.UTF_8);
        String attachmentHash = sha256(attachmentBytes);
        HttpResponse<String> uploaded = post("/api/v1/documents/" + documentId + "/attachments", lease, key(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","original_filename":"external-note.txt","media_type":"text/plain",
                 "content_base64":"%s","expected_sha256":"%s","target_field_path":"sections.present_illness"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, versionId,
                        Base64.getEncoder().encodeToString(attachmentBytes), attachmentHash));
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(201);
        JsonNode attachment = objectMapper.readTree(uploaded.body());
        UUID attachmentId = UUID.fromString(attachment.path("attachment_id").stringValue());
        assertThat(attachment.path("content_hash").stringValue()).isEqualTo(attachmentHash);
        assertThat(attachment.path("storage_status").stringValue()).isEqualTo("AVAILABLE");
        assertThat(attachment.path("malware_scan_status").stringValue()).isEqualTo("PASSED");
        storageKeys.add(jdbc.sql("select storage_key from clinical_document_attachment where tenant_id=:tenant and attachment_id=:attachment")
                .param("tenant", TENANT).param("attachment", attachmentId).query(String.class).single());

        assertThat(post("/api/v1/documents/" + documentId + "/attachments", lease, key(), attachmentBody(
                versionId, "declared.pdf", "application/pdf", attachmentBytes, attachmentHash)).statusCode()).isEqualTo(422);
        assertThat(post("/api/v1/documents/" + documentId + "/attachments", lease, key(), attachmentBody(
                versionId, "wrong-hash.txt", "text/plain", attachmentBytes, "0".repeat(64))).statusCode()).isEqualTo(409);
        byte[] eicar = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII);
        assertThat(post("/api/v1/documents/" + documentId + "/attachments", lease, key(), attachmentBody(
                versionId, "eicar.txt", "text/plain", eicar, sha256(eicar))).statusCode()).isEqualTo(422);
        assertThatThrownBy(() -> jdbc.sql("delete from clinical_document_attachment where tenant_id=:tenant and attachment_id=:attachment")
                .param("tenant", TENANT).param("attachment", attachmentId).update()).isInstanceOf(DataAccessException.class);

        orderId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_order(tenant_id,order_id,patient_id,encounter_id,facility_id,order_scope,
                  status,clinical_indication,author_user_id)
                values (:tenant,:order,:patient,:encounter,:facility,'TEMPORARY','DRAFT','来源版本变化测试',:author)
                """).param("tenant", TENANT).param("order", orderId).param("patient", PATIENT)
                .param("encounter", ENCOUNTER).param("facility", FACILITY).param("author", USER).update();
        HttpResponse<String> referenced = post("/api/v1/documents/" + documentId + "/source-references", lease, key(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","source_type":"ORDER","source_resource_id":"%s",
                 "target_field_path":"sections.treatment_plan","excerpt":"复查相关医嘱"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, versionId, orderId));
        assertThat(referenced.statusCode()).as(referenced.body()).isEqualTo(201);
        assertThat(referenced.body()).contains("row-1", "CURRENT");

        HttpResponse<String> quality = post("/api/v1/documents/" + documentId + "/quality-checks", lease, null, """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, versionId));
        assertThat(quality.statusCode()).as(quality.body()).isEqualTo(200);
        String qualityWatermark = jdbc.sql("""
                select source_watermark from document_quality_run where tenant_id=:tenant and document_id=:document
                order by executed_at desc, quality_run_id desc limit 1
                """).param("tenant", TENANT).param("document", documentId).query(String.class).single();
        assertThat(qualityWatermark).hasSize(64).isNotEqualTo("0".repeat(64));

        jdbc.sql("update clinical_order set row_version=row_version+1 where tenant_id=:tenant and order_id=:order")
                .param("tenant", TENANT).param("order", orderId).update();
        HttpResponse<String> sources = get("/api/v1/documents/" + documentId + "/sources?document_version_id=" + versionId, lease);
        assertThat(sources.statusCode()).as(sources.body()).isEqualTo(200);
        assertThat(sources.body()).contains("STALE", "row-1", "row-2");
        HttpResponse<String> repeatedSources = get(
                "/api/v1/documents/" + documentId + "/sources?document_version_id=" + versionId, lease);
        assertThat(repeatedSources.statusCode()).as(repeatedSources.body()).isEqualTo(200);
        assertThat(jdbc.sql("""
                select count(distinct aggregate_version) from outbox_event
                where tenant_id=:tenant and aggregate_type='CLINICAL_DOCUMENT_EVIDENCE'
                  and aggregate_id=:version and event_type='DocumentSourcesViewed'
                """).param("tenant", TENANT).param("version", versionId).query(Long.class).single())
                .isGreaterThanOrEqualTo(2);

        HttpResponse<String> sign = post("/api/v1/documents/" + documentId + "/signatures", lease, key(), """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","expected_row_version":1,"signature_role":"ATTENDING"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, versionId));
        assertThat(sign.statusCode()).as(sign.body()).isEqualTo(409);
        assertThat(sign.body()).contains("QUALITY_SOURCE_CHECK_REQUIRED");
    }

    private void insertOrder(UUID id, String indication) {
        jdbc.sql("""
                insert into clinical_order(tenant_id,order_id,patient_id,encounter_id,facility_id,order_scope,
                  status,clinical_indication,author_user_id)
                values (:tenant,:order,:patient,:encounter,:facility,'TEMPORARY','DRAFT',:indication,:author)
                """).param("tenant", TENANT).param("order", id).param("patient", PATIENT)
                .param("encounter", ENCOUNTER).param("facility", FACILITY).param("indication", indication)
                .param("author", USER).update();
    }

    private String storageKey(UUID attachmentId) {
        return jdbc.sql("select storage_key from clinical_document_attachment where tenant_id=:tenant and attachment_id=:attachment")
                .param("tenant", TENANT).param("attachment", attachmentId).query(String.class).single();
    }

    private JsonNode findById(JsonNode values, String field, UUID id) {
        for (JsonNode value : values) if (id.toString().equals(value.path(field).stringValue())) return value;
        throw new AssertionError("Missing " + field + " " + id);
    }

    private String lifecycleBody(UUID versionId, String reason) {
        return """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","reason":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, versionId, reason);
    }

    private String attachmentBody(UUID version, String filename, String mediaType, byte[] bytes, String hash) {
        return """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","original_filename":"%s","media_type":"%s",
                 "content_base64":"%s","expected_sha256":"%s","target_field_path":"sections.present_illness"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER, version, filename, mediaType,
                        Base64.getEncoder().encodeToString(bytes), hash);
    }

    private Lease issueLease() throws Exception {
        HttpResponse<String> response = http.send(base("/api/v1/context-leases")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""
                        {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                         "purpose_code":"DOCUMENT_SOURCE_TEST"}
                        """.formatted(ORGANIZATION, FACILITY, PATIENT, ENCOUNTER))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return new Lease(body.path("lease_id").stringValue(), body.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> post(String path, Lease lease, String key, String body) throws Exception {
        HttpRequest.Builder request = contextual(base(path), lease).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, Lease lease) throws Exception {
        return http.send(contextual(base(path), lease).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder contextual(HttpRequest.Builder request, Lease lease) {
        return request.header("X-Context-Lease-Id", lease.id()).header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION.toString()).header("X-Facility-Context", FACILITY.toString())
                .header("X-Patient-Context", PATIENT.toString()).header("X-Encounter-Context", ENCOUNTER.toString());
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer dev-synthetic-token").header("X-HaoEMR-Tenant-Id", TENANT.toString())
                .header("X-HaoEMR-User-Id", USER.toString()).header("X-HaoEMR-Role-Assignment-Ids", ROLE.toString());
    }

    private String key() { String key = "r01-source-" + UUID.randomUUID(); keys.add(key); return key; }
    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private record Lease(String id, String watermark) { }
}
