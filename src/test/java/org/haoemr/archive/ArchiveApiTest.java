package org.haoemr.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
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
final class ArchiveApiTest {
    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String PATIENT = "018f0000-0000-7000-8000-000000000001";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void givenFinishedSignedQualityPassedRecord_whenArchived_thenSeparationSealAndReadableExportAreEnforced()
            throws Exception {
        Actor archiver = createActor("MEDICAL_RECORDS");
        Actor sealer = createActor("CLINICAL_ADMIN");
        Actor outsider = createActor("ATTENDING_PHYSICIAN");
        Fixture fixture = createEligibleFixture(archiver.userId());

        Lease archiverLease = issueLease(archiver, fixture.encounterId());
        HttpResponse<String> readiness = send(archiver, archiverLease, "GET",
                "/api/v1/archive/readiness?encounter_id=" + fixture.encounterId(), null, null);
        assertThat(readiness.statusCode()).isEqualTo(200);
        JsonNode ready = objectMapper.readTree(readiness.body());
        assertThat(ready.path("ready").booleanValue()).isTrue();
        assertThat(ready.path("blockers")).isEmpty();
        assertThat(ready.path("document_count").intValue()).isEqualTo(1);

        Lease outsiderLease = issueLease(outsider, fixture.encounterId());
        HttpResponse<String> forbidden = send(outsider, outsiderLease, "POST", "/api/v1/archive/cases",
                createBody(fixture.encounterId()), UUID.randomUUID().toString());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.body()).contains("ARCHIVE_ROLE_REQUIRED");

        HttpResponse<String> createdResponse = send(archiver, archiverLease, "POST", "/api/v1/archive/cases",
                createBody(fixture.encounterId()), UUID.randomUUID().toString());
        assertThat(createdResponse.statusCode()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(createdResponse.body());
        String archiveId = created.path("archive_case_id").stringValue();
        assertThat(created.path("status").stringValue()).isEqualTo("ARCHIVED");
        assertThat(created.path("items")).hasSize(1);
        assertThat(created.path("items").get(0).path("content_hash").stringValue())
                .isEqualTo(fixture.contentHash());
        assertThat(created.path("manifest_hash").stringValue()).hasSize(64);

        HttpResponse<String> sameActorSeal = send(archiver, archiverLease, "POST",
                "/api/v1/archive/cases/" + archiveId + "/seals",
                transitionBody(fixture.encounterId(), 1, "归档复核完成"), UUID.randomUUID().toString());
        assertThat(sameActorSeal.statusCode()).isEqualTo(403);
        assertThat(sameActorSeal.body()).contains("ARCHIVE_SEPARATION_REQUIRED");

        Lease sealerLease = issueLease(sealer, fixture.encounterId());
        HttpResponse<String> sealedResponse = send(sealer, sealerLease, "POST",
                "/api/v1/archive/cases/" + archiveId + "/seals",
                transitionBody(fixture.encounterId(), 1, "独立封存复核完成"), UUID.randomUUID().toString());
        assertThat(sealedResponse.statusCode()).isEqualTo(200);
        JsonNode sealed = objectMapper.readTree(sealedResponse.body());
        assertThat(sealed.path("status").stringValue()).isEqualTo("SEALED");
        assertThat(sealed.path("row_version").longValue()).isEqualTo(2);
        assertThat(sealed.path("sealed_by").stringValue()).isEqualTo(sealer.userId().toString());

        HttpResponse<String> exportedResponse = send(sealer, sealerLease, "POST",
                "/api/v1/archive/cases/" + archiveId + "/export-packages",
                exportBody(fixture.encounterId()), UUID.randomUUID().toString());
        assertThat(exportedResponse.statusCode()).isEqualTo(201);
        JsonNode exported = objectMapper.readTree(exportedResponse.body());
        String exportId = exported.path("export_package_id").stringValue();
        String expectedHash = exported.path("content_hash").stringValue();
        assertThat(exported.path("status").stringValue()).isEqualTo("READY");
        assertThat(exported.path("byte_count").longValue()).isPositive();

        HttpResponse<String> download = send(sealer, sealerLease, "GET",
                "/api/v1/archive/export-packages/" + exportId + "/content", null, null);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("X-Content-SHA256")).contains(expectedHash);
        assertThat(sha256(download.body())).isEqualTo(expectedHash);
        JsonNode packageJson = objectMapper.readTree(download.body());
        assertThat(packageJson.path("schema").stringValue()).isEqualTo("haoemr.archive.export.v1");
        assertThat(packageJson.path("generator").stringValue()).contains("haoemr");
        assertThat(packageJson.path("documents")).hasSize(1);
        assertThat(packageJson.path("documents").get(0).path("sections").path("chief_complaint").stringValue())
                .isEqualTo("归档测试主诉");
        assertThat(packageJson.path("documents").get(0).path("quality_evidence").path("outcome").stringValue())
                .isEqualTo("PASSED");
        assertThat(packageJson.path("documents").get(0).path("signature_evidence").get(0)
                .path("signature_status").stringValue()).isEqualTo("VALID");

        assertThatThrownBy(() -> jdbc.sql("""
                update archive_case_item set item_order = 2
                where tenant_id = :tenant and archive_case_id = :archive
                """).param("tenant", UUID.fromString(TENANT)).param("archive", UUID.fromString(archiveId)).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                delete from archive_export_package where tenant_id = :tenant and export_package_id = :export
                """).param("tenant", UUID.fromString(TENANT)).param("export", UUID.fromString(exportId)).update())
                .isInstanceOf(DataAccessException.class);

        HttpResponse<String> unsealedResponse = send(sealer, sealerLease, "POST",
                "/api/v1/archive/cases/" + archiveId + "/unseals",
                transitionBody(fixture.encounterId(), 2, "患者授权复印调阅"), UUID.randomUUID().toString());
        assertThat(unsealedResponse.statusCode()).isEqualTo(200);
        JsonNode unsealed = objectMapper.readTree(unsealedResponse.body());
        assertThat(unsealed.path("status").stringValue()).isEqualTo("UNSEALED");
        assertThat(unsealed.path("events").toString()).contains("ARCHIVED", "SEALED", "EXPORT_CREATED", "UNSEALED");
        HttpResponse<String> unavailableAfterUnseal = send(sealer, sealerLease, "GET",
                "/api/v1/archive/export-packages/" + exportId + "/content", null, null);
        assertThat(unavailableAfterUnseal.statusCode()).isEqualTo(403);
        assertThat(unavailableAfterUnseal.body()).contains("ARCHIVE_EXPORT_NOT_AVAILABLE");
    }

    @Test
    void givenArrivedEncounter_whenReadinessRequested_thenItReturnsTheLifecycleStatusAndBlocker() throws Exception {
        Actor reader = createActor("MEDICAL_RECORDS");
        Fixture fixture = createEligibleFixture(reader.userId(), "ARRIVED");

        Lease lease = issueLease(reader, fixture.encounterId());
        HttpResponse<String> readiness = send(reader, lease, "GET",
                "/api/v1/archive/readiness?encounter_id=" + fixture.encounterId(), null, null);

        assertThat(readiness.statusCode()).isEqualTo(200);
        JsonNode response = objectMapper.readTree(readiness.body());
        assertThat(response.path("encounter_status").stringValue()).isEqualTo("ARRIVED");
        assertThat(response.path("ready").booleanValue()).isFalse();
        assertThat(response.path("blockers").toString()).contains("ENCOUNTER_NOT_FINISHED");
    }

    private Actor createActor(String roleCode) {
        UUID user = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        jdbc.sql("""
                insert into app_user(tenant_id, user_id, external_subject, display_name, status)
                values (:tenant, :user, :subject, :name, 'ACTIVE')
                """).param("tenant", UUID.fromString(TENANT)).param("user", user)
                .param("subject", "archive-test-" + user).param("name", "归档测试-" + roleCode).update();
        jdbc.sql("""
                insert into role_assignment(
                  tenant_id, role_assignment_id, user_id, organization_id, facility_id,
                  role_code, valid_from, status)
                values (:tenant, :role, :user, :organization, :facility, :role_code,
                  now() - interval '1 day', 'ACTIVE')
                """).param("tenant", UUID.fromString(TENANT)).param("role", role).param("user", user)
                .param("organization", UUID.fromString(ORGANIZATION)).param("facility", UUID.fromString(FACILITY))
                .param("role_code", roleCode).update();
        return new Actor(user, role);
    }

    private Fixture createEligibleFixture(UUID actor) {
        return createEligibleFixture(actor, "FINISHED");
    }

    private Fixture createEligibleFixture(UUID actor, String encounterStatus) {
        UUID encounter = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        String sections = "{\"chief_complaint\":\"归档测试主诉\",\"present_illness\":\"归档测试现病史\",\"assessment\":\"归档测试评估\",\"treatment_plan\":\"归档测试计划\"}";
        String contentHash = sha256(sections);
        jdbc.sql("""
                insert into encounter(
                  tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, ended_at, source_system, source_key)
                values (:tenant, :encounter, :patient, :organization, :facility,
                  'OUTPATIENT', :status, now() - interval '2 hours',
                  case when :status = 'FINISHED' then now() - interval '1 hour' else null end,
                  'ARCHIVE-TEST', :source_key)
                """).param("tenant", UUID.fromString(TENANT)).param("encounter", encounter)
                .param("patient", UUID.fromString(PATIENT)).param("organization", UUID.fromString(ORGANIZATION))
                .param("facility", UUID.fromString(FACILITY)).param("status", encounterStatus)
                .param("source_key", encounter.toString()).update();
        jdbc.sql("""
                insert into clinical_document(
                  tenant_id, document_id, patient_id, encounter_id, document_type_code,
                  template_version_id, status, created_by)
                values (:tenant, :document, :patient, :encounter,
                  'WS445.2.OUTPATIENT_RECORD',
                  (select version.template_version_id from clinical_document_template template
                    join clinical_document_template_version version
                      on version.tenant_id = template.tenant_id and version.template_id = template.template_id
                    where template.tenant_id = :tenant
                      and template.document_type_code = 'WS445.2.OUTPATIENT_RECORD'
                      and version.status = 'PUBLISHED' order by version.version_no desc limit 1),
                  'SIGNED', :actor)
                """).param("tenant", UUID.fromString(TENANT)).param("document", document)
                .param("patient", UUID.fromString(PATIENT)).param("encounter", encounter).param("actor", actor).update();
        jdbc.sql("""
                insert into clinical_document_version(
                  tenant_id, document_id, document_version_id, version_no, status,
                  sections, content_hash, author_user_id, signed_at)
                values (:tenant, :document, :version, 1, 'SIGNED', cast(:sections as jsonb),
                  :content_hash, :actor, now() - interval '70 minutes')
                """).param("tenant", UUID.fromString(TENANT)).param("document", document).param("version", version)
                .param("sections", sections).param("content_hash", contentHash).param("actor", actor).update();
        jdbc.sql("""
                update clinical_document set current_version_id = :version
                where tenant_id = :tenant and document_id = :document
                """).param("version", version).param("tenant", UUID.fromString(TENANT)).param("document", document).update();
        jdbc.sql("""
                insert into document_quality_run(
                  tenant_id, quality_run_id, document_id, document_version_id, rule_version,
                  outcome, finding_count, blocking_count, warning_count, content_hash, source_watermark, executed_by)
                values (:tenant, :run, :document, :version, 'archive-test-1',
                  'PASSED', 0, 0, 0, :content_hash,
                  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', :actor)
                """).param("tenant", UUID.fromString(TENANT)).param("run", UUID.randomUUID())
                .param("document", document).param("version", version).param("content_hash", contentHash)
                .param("actor", actor).update();
        jdbc.sql("""
                insert into signature_evidence(
                  tenant_id, signature_id, document_id, document_version_id, signer_user_id,
                  signature_role, signature_status, content_hash, credential_ref, signed_at)
                values (:tenant, :signature, :document, :version, :actor,
                  'ATTENDING', 'VALID', :content_hash, 'ca:test:archive', now() - interval '70 minutes')
                """).param("tenant", UUID.fromString(TENANT)).param("signature", UUID.randomUUID())
                .param("document", document).param("version", version).param("actor", actor)
                .param("content_hash", contentHash).update();
        return new Fixture(encounter, document, version, contentHash);
    }

    private Lease issueLease(Actor actor, UUID encounterId) throws Exception {
        String body = "{\"organization_id\":\"" + ORGANIZATION + "\",\"facility_id\":\"" + FACILITY
                + "\",\"patient_id\":\"" + PATIENT + "\",\"encounter_id\":\"" + encounterId
                + "\",\"purpose_code\":\"ARCHIVE_WORKFLOW\"}";
        HttpResponse<String> response = http.send(baseRequest(actor, "/api/v1/context-leases")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(response.body());
        return new Lease(json.path("lease_id").stringValue(), json.path("authorization_watermark").stringValue(), encounterId);
    }

    private HttpResponse<String> send(
            Actor actor, Lease lease, String method, String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = baseRequest(actor, path)
                .header("X-Context-Lease-Id", lease.id()).header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION).header("X-Facility-Context", FACILITY)
                .header("X-Patient-Context", PATIENT).header("X-Encounter-Context", lease.encounterId().toString());
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder baseRequest(Actor actor, String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", actor.userId().toString())
                .header("X-HaoEMR-Role-Assignment-Ids", actor.roleId().toString());
    }

    private static String createBody(UUID encounterId) {
        return "{\"organization_id\":\"" + ORGANIZATION + "\",\"facility_id\":\"" + FACILITY
                + "\",\"patient_id\":\"" + PATIENT + "\",\"encounter_id\":\"" + encounterId + "\"}";
    }

    private static String transitionBody(UUID encounterId, long version, String reason) {
        return "{\"organization_id\":\"" + ORGANIZATION + "\",\"facility_id\":\"" + FACILITY
                + "\",\"patient_id\":\"" + PATIENT + "\",\"encounter_id\":\"" + encounterId
                + "\",\"expected_row_version\":" + version + ",\"reason\":\"" + reason + "\"}";
    }

    private static String exportBody(UUID encounterId) {
        return "{\"organization_id\":\"" + ORGANIZATION + "\",\"facility_id\":\"" + FACILITY
                + "\",\"patient_id\":\"" + PATIENT + "\",\"encounter_id\":\"" + encounterId
                + "\",\"purpose\":\"病案复印测试\",\"output_format\":\"JSON\"}";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Actor(UUID userId, UUID roleId) {}
    private record Lease(String id, String watermark, UUID encounterId) {}
    private record Fixture(UUID encounterId, UUID documentId, UUID versionId, String contentHash) {}
}
