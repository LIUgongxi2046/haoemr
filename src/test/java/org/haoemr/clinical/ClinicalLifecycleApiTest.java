package org.haoemr.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PatientSummaryWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.out-of-order=true")
@ActiveProfiles("dev-synthetic")
final class ClinicalLifecycleApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String PATIENT = "018f0000-0000-7000-8000-000000000001";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ClinicalLifecycleService clinical;

    @Autowired
    private RecordCenterService records;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void givenPossibleDuplicateRegistration_whenPendingIdentityIsChosen_thenReplayIsIdempotentAndCandidateIsRetained() {
        UUID tenant = UUID.fromString(TENANT);
        UUID user = UUID.fromString(USER);
        ClinicalIdentity identity = new ClinicalIdentity(tenant, user, List.of(UUID.fromString(ROLE)));
        String blockedKey = "patient-duplicate-blocked-" + UUID.randomUUID();
        assertThatThrownBy(() -> clinical.createPatient(identity, blockedKey, "张慧敏", "F",
                java.time.LocalDate.parse("1978-04-16"), "HAOEMR-TEST", "MRN",
                "blocked-" + UUID.randomUUID(), "ACTIVE", List.of()))
                .isInstanceOf(ClinicalCommandException.class)
                .hasMessageContaining("Possible duplicate");
        assertThat(jdbc.sql("""
                select count(*) from idempotency_record where tenant_id = :tenant
                  and command_scope = 'PATIENT_CREATE' and idempotency_key = :key
                """).param("tenant", tenant).param("key", blockedKey).query(Long.class).single()).isZero();

        String key = "patient-pending-" + UUID.randomUUID();
        String identifier = "pending-" + UUID.randomUUID();
        PatientSummaryWire created = clinical.createPatient(identity, key, "张慧敏", "F",
                java.time.LocalDate.parse("1978-04-16"), "HAOEMR-TEST", "MRN", identifier,
                "PENDING_VERIFICATION", List.of());
        try {
            PatientSummaryWire replay = clinical.createPatient(identity, key, "张慧敏", "F",
                    java.time.LocalDate.parse("1978-04-16"), "HAOEMR-TEST", "MRN", identifier,
                    "PENDING_VERIFICATION", List.of());
            assertThat(replay.patientId()).isEqualTo(created.patientId());
            assertThat(jdbc.sql("select status from patient where tenant_id = :tenant and patient_id = :patient")
                    .param("tenant", tenant).param("patient", created.patientId())
                    .query(String.class).single()).isEqualTo("PENDING_VERIFICATION");
            assertThat(jdbc.sql("""
                    select count(*) from patient_match_candidate where tenant_id = :tenant and status = 'OPEN'
                      and (patient_a_id = :patient or patient_b_id = :patient)
                    """).param("tenant", tenant).param("patient", created.patientId())
                    .query(Long.class).single()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.sql("""
                    select count(*) from patient_demographic_version
                    where tenant_id = :tenant and patient_id = :patient and version_no = 1
                    """).param("tenant", tenant).param("patient", created.patientId())
                    .query(Long.class).single()).isEqualTo(1);
        } finally {
            jdbc.sql("delete from patient_match_candidate where tenant_id = :tenant and (patient_a_id = :patient or patient_b_id = :patient)")
                    .param("tenant", tenant).param("patient", created.patientId()).update();
            jdbc.sql("delete from patient_identifier where tenant_id = :tenant and patient_id = :patient")
                    .param("tenant", tenant).param("patient", created.patientId()).update();
            jdbc.sql("alter table patient_demographic_version disable trigger patient_demographic_version_immutable").update();
            try {
                jdbc.sql("delete from patient_demographic_version where tenant_id = :tenant and patient_id = :patient")
                        .param("tenant", tenant).param("patient", created.patientId()).update();
            } finally {
                jdbc.sql("alter table patient_demographic_version enable trigger patient_demographic_version_immutable").update();
            }
            jdbc.sql("delete from audit_event where tenant_id = :tenant and resource_id = :patient")
                    .param("tenant", tenant).param("patient", created.patientId()).update();
            jdbc.sql("delete from outbox_event where tenant_id = :tenant and aggregate_id = :patient")
                    .param("tenant", tenant).param("patient", created.patientId()).update();
            jdbc.sql("delete from patient where tenant_id = :tenant and patient_id = :patient")
                    .param("tenant", tenant).param("patient", created.patientId()).update();
            jdbc.sql("delete from idempotency_record where tenant_id = :tenant and command_scope = 'PATIENT_CREATE' and idempotency_key = :key")
                    .param("tenant", tenant).param("key", key).update();
        }
    }

    @Test
    void givenScopedLeases_whenCompletingTheOutpatientDraftFlow_thenVersionsAreImmutableAndConflictsAreExplicit()
            throws Exception {
        Lease organizationLease = issueLease(null, null);
        HttpResponse<String> search = send("POST", "/api/v1/patients/search", """
                {"organization_id":"%s","facility_id":"%s","purpose_code":"PATIENT_SEARCH","query":"张慧敏","limit":10}
                """.formatted(ORGANIZATION, FACILITY), organizationLease, null, null, null);
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(search.body()).contains(PATIENT);
        HttpResponse<String> injectionSearch = send("POST", "/api/v1/patients/search", """
                {"organization_id":"%s","facility_id":"%s","purpose_code":"PATIENT_SEARCH","query":"%%' OR 1=1; DROP TABLE patient; --","limit":10}
                """.formatted(ORGANIZATION, FACILITY), organizationLease, null, null, null);
        assertThat(injectionSearch.statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from patient where tenant_id = :tenant")
                .param("tenant", UUID.fromString(TENANT)).query(Long.class).single()).isGreaterThanOrEqualTo(2);

        Lease patientLease = issueLease(PATIENT, null);
        String encounterKey = UUID.randomUUID().toString();
        HttpResponse<String> encounterResponse = send("POST", "/api/v1/encounters", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_type":"OUTPATIENT","started_at":"2026-08-14T06:00:00Z","source_system":"SYNTHETIC-TEST","source_key":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterKey), patientLease, PATIENT, null, encounterKey);
        assertThat(encounterResponse.statusCode()).isEqualTo(201);
        String encounterId = objectMapper.readTree(encounterResponse.body()).path("encounter_id").stringValue();

        Lease encounterLease = issueLease(PATIENT, encounterId);
        String documentKey = UUID.randomUUID().toString();
        HttpResponse<String> createResponse = send("POST", "/api/v1/documents", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","document_type_code":"WS445.2.OUTPATIENT_RECORD","sections":{"chief_complaint":"合成主诉"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId, documentKey);
        assertThat(createResponse.statusCode()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(createResponse.body());
        String documentId = created.path("document_id").stringValue();
        String firstVersionId = created.path("document_version_id").stringValue();

        HttpResponse<String> replay = send("POST", "/api/v1/documents", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","document_type_code":"WS445.2.OUTPATIENT_RECORD","sections":{"chief_complaint":"合成主诉"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId, documentKey);
        assertThat(replay.statusCode()).isEqualTo(409);
        assertThat(replay.body()).contains("IDEMPOTENCY_REPLAY");

        HttpResponse<String> saveResponse = send("PUT", "/api/v1/documents/" + documentId + "/draft", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","expected_row_version":1,"sections":{"chief_complaint":"合成主诉","present_illness":"合成现病史"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId, UUID.randomUUID().toString());
        assertThat(saveResponse.statusCode()).isEqualTo(200);
        assertThat(saveResponse.headers().firstValue("etag")).contains("\"2\"");
        JsonNode saved = objectMapper.readTree(saveResponse.body());
        String secondVersionId = saved.path("document_version_id").stringValue();

        HttpResponse<String> encounterDocuments = send(
                "GET", "/api/v1/encounters/" + encounterId + "/documents",
                null, encounterLease, PATIENT, encounterId, null);
        assertThat(encounterDocuments.statusCode()).isEqualTo(200);
        JsonNode documentList = objectMapper.readTree(encounterDocuments.body());
        assertThat(documentList.isArray()).isTrue();
        assertThat(documentList).hasSize(1);
        assertThat(documentList.get(0).path("document_id").stringValue()).isEqualTo(documentId);
        assertThat(documentList.get(0).path("document_version_id").stringValue()).isEqualTo(secondVersionId);

        HttpResponse<String> versionHistory = send(
                "GET", "/api/v1/documents/" + documentId + "/versions",
                null, encounterLease, PATIENT, encounterId, null);
        assertThat(versionHistory.statusCode()).isEqualTo(200);
        JsonNode versions = objectMapper.readTree(versionHistory.body());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("version_no").intValue()).isEqualTo(2);
        assertThat(versions.get(0).path("document_version_id").stringValue()).isEqualTo(secondVersionId);
        assertThat(versions.get(1).path("version_no").intValue()).isEqualTo(1);
        assertThat(versions.get(1).path("document_version_id").stringValue()).isEqualTo(firstVersionId);

        HttpResponse<String> otherPatientVersions = send(
                "GET", "/api/v1/documents/018f0000-0000-7000-8000-000000001002/versions",
                null, encounterLease, PATIENT, encounterId, null);
        assertThat(otherPatientVersions.statusCode()).isEqualTo(403);
        assertThat(otherPatientVersions.body()).doesNotContain("000000000002", "INPATIENT_GENERAL");
        HttpResponse<String> otherPatientGovernance = send(
                "GET", "/api/v1/documents/018f0000-0000-7000-8000-000000001002/governance"
                        + "?document_version_id=018f0000-0000-7000-8000-000000001002",
                null, encounterLease, PATIENT, encounterId, null);
        assertThat(otherPatientGovernance.statusCode()).isEqualTo(403);
        assertThat(otherPatientGovernance.body()).doesNotContain("000000000002", "WS445.12.ADMISSION_NOTE");

        HttpResponse<String> staleSave = send("PUT", "/api/v1/documents/" + documentId + "/draft", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","expected_row_version":1,"sections":{"chief_complaint":"覆盖尝试"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId, UUID.randomUUID().toString());
        assertThat(staleSave.statusCode()).isEqualTo(409);
        assertThat(staleSave.body()).contains("VERSION_CONFLICT", "OPEN_DIFF");

        HttpResponse<String> diff = send("GET", "/api/v1/documents/" + documentId + "/diff?from_version_id="
                + firstVersionId + "&to_version_id=" + secondVersionId, null, encounterLease, PATIENT, encounterId, null);
        assertThat(diff.statusCode()).isEqualTo(200);
        assertThat(diff.body()).contains("present_illness");
        assertThat(jdbc.sql("select count(*) from clinical_document_version where tenant_id = :tenant and document_id = :document")
                .param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(documentId))
                .query(Long.class).single()).isEqualTo(2);

        HttpResponse<String> mismatchedPatient = send("PUT", "/api/v1/documents/" + documentId + "/draft", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","expected_row_version":2,"sections":{"chief_complaint":"越界覆盖"}}
                """.formatted(ORGANIZATION, FACILITY, UUID.randomUUID(), encounterId), encounterLease,
                UUID.randomUUID().toString(), encounterId, UUID.randomUUID().toString());
        assertThat(mismatchedPatient.statusCode()).isEqualTo(403);
        assertThat(jdbc.sql("select count(*) from clinical_document_version where tenant_id = :tenant and document_id = :document")
                .param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(documentId))
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void givenDraftDocument_whenVoided_thenItLeavesTheEditableFlowAndKeepsImmutableEvidence()
            throws Exception {
        Lease patientLease = issueLease(PATIENT, null);
        String encounterKey = "document-void-" + UUID.randomUUID();
        HttpResponse<String> encounterResponse = send("POST", "/api/v1/encounters", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_type":"OUTPATIENT","started_at":"2026-08-30T01:00:00Z","source_system":"SYNTHETIC-DOCUMENT-VOID","source_key":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterKey), patientLease, PATIENT, null,
                UUID.randomUUID().toString());
        assertThat(encounterResponse.statusCode()).isEqualTo(201);
        String encounterId = objectMapper.readTree(encounterResponse.body()).path("encounter_id").stringValue();
        Lease encounterLease = issueLease(PATIENT, encounterId);

        HttpResponse<String> createResponse = send("POST", "/api/v1/documents", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","document_type_code":"WS445.2.OUTPATIENT_RECORD","sections":{"chief_complaint":"待作废草稿"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId,
                UUID.randomUUID().toString());
        assertThat(createResponse.statusCode()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(createResponse.body());
        String documentId = created.path("document_id").stringValue();
        String firstVersionId = created.path("document_version_id").stringValue();

        HttpResponse<String> voidResponse = send("POST", "/api/v1/documents/" + documentId + "/voids", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","expected_row_version":1,"reason":"重复建立的草稿"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId,
                UUID.randomUUID().toString());
        assertThat(voidResponse.statusCode()).isEqualTo(200);
        JsonNode voided = objectMapper.readTree(voidResponse.body());
        assertThat(voided.path("status").stringValue()).isEqualTo("VOID");
        assertThat(voided.path("version_no").intValue()).isEqualTo(2);
        assertThat(voided.path("row_version").longValue()).isEqualTo(2L);
        assertThat(voided.path("document_version_id").stringValue()).isNotEqualTo(firstVersionId);

        HttpResponse<String> staleEdit = send("PUT", "/api/v1/documents/" + documentId + "/draft", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","expected_row_version":2,"sections":{"chief_complaint":"作废后覆盖"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId,
                UUID.randomUUID().toString());
        assertThat(staleEdit.statusCode()).isEqualTo(409);
        assertThat(staleEdit.body()).contains("INVALID_DOCUMENT_STATE");
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = :tenant
                  and resource_id = :document and action_code = 'DOCUMENT_VOIDED'
                """).param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(documentId))
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event where tenant_id = :tenant
                  and aggregate_id = :document and event_type = 'DocumentVoided'
                """).param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(documentId))
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenDeterministicQualityRules_whenSigning_thenBlockingFindingsStopAndSignedContentIsImmutable()
            throws Exception {
        String chainHeadBefore = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", UUID.fromString(TENANT)).query(String.class).optional().orElse(null);
        OffsetDateTime chainStart = OffsetDateTime.now(ZoneOffset.UTC);
        Lease patientLease = issueLease(PATIENT, null);
        String encounterKey = UUID.randomUUID().toString();
        HttpResponse<String> encounterResponse = send("POST", "/api/v1/encounters", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_type":"OUTPATIENT","started_at":"2026-08-14T07:00:00Z","source_system":"SYNTHETIC-QC","source_key":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterKey), patientLease, PATIENT, null, UUID.randomUUID().toString());
        String encounterId = objectMapper.readTree(encounterResponse.body()).path("encounter_id").stringValue();
        Lease encounterLease = issueLease(PATIENT, encounterId);

        HttpResponse<String> createResponse = send("POST", "/api/v1/documents", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","document_type_code":"WS445.2.OUTPATIENT_RECORD","sections":{"chief_complaint":"合成主诉"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId, UUID.randomUUID().toString());
        JsonNode created = objectMapper.readTree(createResponse.body());
        String documentId = created.path("document_id").stringValue();
        String firstVersion = created.path("document_version_id").stringValue();

        HttpResponse<String> blockedFindings = qualityCheck(encounterLease, encounterId, documentId, firstVersion);
        assertThat(blockedFindings.statusCode()).isEqualTo(200);
        assertThat(blockedFindings.body()).contains("BLOCKING", "DOC-PRESENT-ILLNESS-REQUIRED", "WARNING");
        HttpResponse<String> governanceWithFindings = governance(
                encounterLease, encounterId, documentId, firstVersion);
        assertThat(governanceWithFindings.statusCode()).isEqualTo(200);
        JsonNode blockedSnapshot = objectMapper.readTree(governanceWithFindings.body());
        assertThat(blockedSnapshot.path("document_status").stringValue()).isEqualTo("DRAFT");
        assertThat(blockedSnapshot.path("quality_run").toString())
                .contains("BLOCKED", "haoemr-core-2", firstVersion);
        // The published template may add stricter blocking fields on top of the core rules.
        // Assert the stable lower bound and the named core finding instead of coupling this
        // lifecycle test to a specific template revision.
        assertThat(blockedSnapshot.path("quality_run").path("blocking_count").intValue())
                .isGreaterThanOrEqualTo(1);
        assertThat(blockedSnapshot.path("quality_run").path("warning_count").intValue())
                .isGreaterThanOrEqualTo(1);
        assertThat(blockedSnapshot.path("quality_findings").size()).isGreaterThanOrEqualTo(3);
        assertThat(blockedSnapshot.path("quality_findings").toString())
                .contains("haoemr-core-2", "DOC-PRESENT-ILLNESS-REQUIRED", "BLOCKING");
        assertThat(blockedSnapshot.path("signatures")).isEmpty();
        assertThat(blockedSnapshot.path("review_decisions")).isEmpty();
        assertThat(blockedSnapshot.path("data_watermark").stringValue()).hasSize(64);

        HttpResponse<String> blockedSign = sign(encounterLease, encounterId, documentId, firstVersion, 1);
        assertThat(blockedSign.statusCode()).isEqualTo(409);
        assertThat(blockedSign.body()).contains("SIGNING_RULE_BLOCKED");
        assertThat(jdbc.sql("select count(*) from signature_evidence where tenant_id = :tenant and document_id = :document")
                .param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(documentId))
                .query(Long.class).single()).isZero();

        HttpResponse<String> completeSave = send("PUT", "/api/v1/documents/" + documentId + "/draft", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","expected_row_version":1,"sections":{"chief_complaint":"合成主诉","present_illness":"合成现病史","assessment":"合成评估","treatment_plan":"合成处置"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId, UUID.randomUUID().toString());
        JsonNode completed = objectMapper.readTree(completeSave.body());
        String completedVersion = completed.path("document_version_id").stringValue();
        String contentHash = completed.path("content_hash").stringValue();
        HttpResponse<String> signWithoutQualityRun = sign(
                encounterLease, encounterId, documentId, completedVersion, 2);
        assertThat(signWithoutQualityRun.statusCode()).isEqualTo(409);
        assertThat(signWithoutQualityRun.body()).contains("QUALITY_CHECK_REQUIRED");
        assertThat(jdbc.sql("select count(*) from signature_evidence where tenant_id = :tenant and document_id = :document")
                .param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(documentId))
                .query(Long.class).single()).isZero();
        HttpResponse<String> noFindings = qualityCheck(encounterLease, encounterId, documentId, completedVersion);
        assertThat(noFindings.statusCode()).isEqualTo(200);
        assertThat(noFindings.body()).isEqualTo("[]");

        HttpResponse<String> signed = sign(encounterLease, encounterId, documentId, completedVersion, 2);
        assertThat(signed.statusCode()).isEqualTo(201);
        assertThat(signed.body()).contains("VALID", contentHash);
        assertThat(jdbc.sql("select status from clinical_document_version where tenant_id = :tenant and document_version_id = :version")
                .param("tenant", UUID.fromString(TENANT)).param("version", UUID.fromString(completedVersion))
                .query(String.class).single()).isEqualTo("SIGNED");
        HttpResponse<String> signedGovernance = governance(
                encounterLease, encounterId, documentId, completedVersion);
        assertThat(signedGovernance.statusCode()).isEqualTo(200);
        JsonNode signedSnapshot = objectMapper.readTree(signedGovernance.body());
        String originalSignatureId = objectMapper.readTree(signed.body()).path("signature_id").stringValue();
        assertThat(signedSnapshot.path("document_status").stringValue()).isEqualTo("SIGNED");
        assertThat(signedSnapshot.path("quality_run").toString()).contains("PASSED", contentHash);
        assertThat(signedSnapshot.path("quality_run").path("finding_count").intValue()).isZero();
        assertThat(signedSnapshot.path("quality_findings")).isEmpty();
        assertThat(signedSnapshot.path("signatures")).hasSize(1);
        assertThat(signedSnapshot.path("signatures").get(0).toString())
                .contains("VALID", "ATTENDING", "林伟 / William Lin", contentHash, "SYNTHETIC-CA://");

        HttpResponse<String> verification = send("POST",
                "/api/v1/documents/" + documentId + "/signature-verifications", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","document_version_id":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId, completedVersion),
                encounterLease, PATIENT, encounterId, UUID.randomUUID().toString());
        assertThat(verification.statusCode()).isEqualTo(201);
        assertThat(verification.body()).contains("\"outcome\":\"VALID\"", "SYNTHETIC_CA");
        assertThat(jdbc.sql("""
                select count(*) from document_signature_verification_run
                where tenant_id = :tenant and document_id = :document
                  and document_version_id = :version and outcome = 'VALID'
                """).param("tenant", UUID.fromString(TENANT)).param("document", UUID.fromString(documentId))
                .param("version", UUID.fromString(completedVersion)).query(Long.class).single()).isEqualTo(1L);

        assertThatThrownBy(() -> jdbc.sql("""
                update clinical_document_version set sections = '{"chief_complaint":"tampered"}'
                where tenant_id = :tenant and document_version_id = :version
                """)
                .param("tenant", UUID.fromString(TENANT)).param("version", UUID.fromString(completedVersion)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("select content_hash from clinical_document_version where tenant_id = :tenant and document_version_id = :version")
                .param("tenant", UUID.fromString(TENANT)).param("version", UUID.fromString(completedVersion))
                .query(String.class).single()).isEqualTo(contentHash);
        String qualityRunId = signedSnapshot.path("quality_run").path("quality_run_id").stringValue();
        assertThatThrownBy(() -> jdbc.sql("""
                update document_quality_run set outcome = 'WARNING'
                where tenant_id = :tenant and quality_run_id = :quality_run
                """).param("tenant", UUID.fromString(TENANT)).param("quality_run", UUID.fromString(qualityRunId)).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("document quality runs are immutable");

        HttpResponse<String> correctionCreated = send("POST", "/api/v1/documents/" + documentId + "/corrections", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "source_document_version_id":"%s","expected_row_version":3,"correction_type":"CORRECTION",
                 "reason":"患者补充了准确的发作时间，需要更正现病史。",
                 "sections":{"chief_complaint":"合成主诉","present_illness":"更正后的合成现病史",
                   "assessment":"合成评估","treatment_plan":"合成处置"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId, completedVersion),
                encounterLease, PATIENT, encounterId, UUID.randomUUID().toString());
        assertThat(correctionCreated.statusCode()).isEqualTo(201);
        JsonNode correction = objectMapper.readTree(correctionCreated.body());
        String correctionId = correction.path("correction_id").stringValue();
        String correctionVersion = correction.path("correction_document_version_id").stringValue();
        assertThat(correction.toString()).contains("CORRECTION", "DRAFT", completedVersion, correctionVersion);
        assertThat(jdbc.sql("select status from clinical_document_version where tenant_id = :tenant and document_version_id = :version")
                .param("tenant", UUID.fromString(TENANT)).param("version", UUID.fromString(completedVersion))
                .query(String.class).single()).isEqualTo("SIGNED");
        assertThat(qualityCheck(encounterLease, encounterId, documentId, correctionVersion).statusCode()).isEqualTo(200);
        HttpResponse<String> correctionSigned = sign(encounterLease, encounterId, documentId, correctionVersion, 4);
        assertThat(correctionSigned.statusCode()).isEqualTo(201);

        HttpResponse<String> correctionList = send("GET", "/api/v1/documents/" + documentId + "/corrections",
                null, encounterLease, PATIENT, encounterId, null);
        assertThat(correctionList.statusCode()).isEqualTo(200);
        JsonNode signedCorrection = objectMapper.readTree(correctionList.body()).get(0);
        assertThat(signedCorrection.path("correction_id").stringValue()).isEqualTo(correctionId);
        assertThat(signedCorrection.toString()).contains("SIGNED", "EXTERNAL_SHARED_RECORD", "PENDING");
        String propagationId = signedCorrection.path("propagations").get(0).path("propagation_id").stringValue();

        HttpResponse<String> propagationRetry = send("POST", "/api/v1/documents/" + documentId
                + "/correction-propagations/" + propagationId + "/retry", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","expected_row_version":1}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), encounterLease, PATIENT, encounterId,
                UUID.randomUUID().toString());
        assertThat(propagationRetry.statusCode()).isEqualTo(200);
        assertThat(propagationRetry.body()).contains("SUCCEEDED", "\"attempt_count\":1");
        assertThat(jdbc.sql("""
                select count(*) from document_correction_event
                where tenant_id = :tenant and correction_id = :correction
                  and event_type = 'PROPAGATION_SUCCEEDED'
                  and details ->> 'provider_code' = 'SYNTHETIC_HIE'
                  and details ->> 'receipt_ref' like 'SYNTHETIC-HIE://%'
                """).param("tenant", UUID.fromString(TENANT)).param("correction", UUID.fromString(correctionId))
                .query(Long.class).single()).isEqualTo(1L);

        HttpResponse<String> revoked = send("POST", "/api/v1/documents/" + documentId + "/signature-revocations", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "signature_id":"%s","expected_document_row_version":5,
                 "reason":"原签名证书被机构确认失效，保留撤销证据。"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId, originalSignatureId),
                encounterLease, PATIENT, encounterId, UUID.randomUUID().toString());
        assertThat(revoked.statusCode()).isEqualTo(201);
        JsonNode revocation = objectMapper.readTree(revoked.body());
        assertThat(revocation.toString()).contains(originalSignatureId, completedVersion, "原签名证书被机构确认失效");
        assertThat(jdbc.sql("select signature_status from signature_evidence where tenant_id = :tenant and signature_id = :signature")
                .param("tenant", UUID.fromString(TENANT)).param("signature", UUID.fromString(originalSignatureId))
                .query(String.class).single()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> jdbc.sql("""
                update document_signature_revocation set revocation_reason = 'tampered'
                where tenant_id = :tenant and revocation_id = :revocation
                """).param("tenant", UUID.fromString(TENANT))
                .param("revocation", UUID.fromString(revocation.path("revocation_id").stringValue())).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("document legal evidence is immutable");

        var auditChain = jdbc.sql("""
                select previous_hash, event_hash from audit_event
                where tenant_id = :tenant and occurred_at >= :chain_start
                order by occurred_at, audit_event_id
                """).param("tenant", UUID.fromString(TENANT)).param("chain_start", chainStart)
                .query((rs, row) -> new String[] {rs.getString("previous_hash"), rs.getString("event_hash")}).list();
        assertThat(auditChain).isNotEmpty();
        assertThat(auditChain.getFirst()[0]).isEqualTo(chainHeadBefore);
        for (int index = 1; index < auditChain.size(); index++) {
            assertThat(auditChain.get(index)[0]).isEqualTo(auditChain.get(index - 1)[1]);
        }
    }

    @Test
    void qualitySnapshotsRemainImmutableAndPostSaveRetriesAreDeduplicated() throws Exception {
        Lease patientLease = issueLease(PATIENT, null);
        String key = UUID.randomUUID().toString();
        var encounter = send("POST", "/api/v1/encounters", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_type":"OUTPATIENT",
                 "started_at":"2026-09-06T06:00:00Z","source_system":"SYNTHETIC-QC-TEST","source_key":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, key), patientLease, PATIENT, null, key);
        assertThat(encounter.statusCode()).isEqualTo(201);
        String encounterId = objectMapper.readTree(encounter.body()).path("encounter_id").stringValue();
        Lease lease = issueLease(PATIENT, encounterId);
        var created = send("POST", "/api/v1/documents", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_type_code":"WS445.2.OUTPATIENT_RECORD","sections":{
                 "chief_complaint":"同一段合成文字用于重复检查","present_illness":"同一段合成文字用于重复检查",
                 "past_history":"同一段合成文字用于重复检查","assessment":"独立合成评估","treatment_plan":"独立合成计划"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), lease, PATIENT, encounterId, UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode document = objectMapper.readTree(created.body());
        String documentId = document.path("document_id").stringValue();
        String versionId = document.path("document_version_id").stringValue();
        String body = """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "document_version_id":"%s","trigger_stage":"POST_SAVE"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId, versionId);
        var checked = send("POST", "/api/v1/documents/" + documentId + "/quality-checks", body, lease, PATIENT, encounterId, null);
        assertThat(checked.statusCode()).isEqualTo(200);
        // Three identical sections implicate two targets, never count the third target twice.
        assertThat(objectMapper.readTree(checked.body()).size()).isEqualTo(2);
        var replay = send("POST", "/api/v1/documents/" + documentId + "/quality-checks", body, lease, PATIENT, encounterId, null);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(checked.body());
        JsonNode snapshot = objectMapper.readTree(governance(lease, encounterId, documentId, versionId).body());
        assertThat(snapshot.path("quality_run_history").size()).isEqualTo(1);
        JsonNode run = snapshot.path("quality_run");
        assertThat(run.path("trigger_stage").stringValue()).isEqualTo("POST_SAVE");
        assertThat(run.path("finding_snapshots")).isEqualTo(objectMapper.readTree(checked.body()));
        assertThat(run.path("blocking_count").intValue()).isEqualTo(2);
        UUID runId = UUID.fromString(run.path("quality_run_id").stringValue());
        assertThatThrownBy(() -> jdbc.sql("""
                insert into document_quality_run(tenant_id,quality_run_id,document_id,document_version_id,rule_version,
                  outcome,finding_count,blocking_count,warning_count,content_hash,source_watermark,executed_by,trigger_stage,finding_snapshots)
                select tenant_id,gen_random_uuid(),document_id,document_version_id,rule_version,
                  outcome,finding_count,blocking_count,warning_count,content_hash,source_watermark,executed_by,trigger_stage,
                  jsonb_set(finding_snapshots,'{0,document_version_id}',to_jsonb(gen_random_uuid()::text))
                from document_quality_run where tenant_id=:tenant and quality_run_id=:run
                """).param("tenant", UUID.fromString(TENANT)).param("run", runId).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("snapshot scope or evidence mismatch");
        assertThatThrownBy(() -> jdbc.sql("update document_quality_run set finding_snapshots='[]' where tenant_id=:tenant and quality_run_id=:run")
                .param("tenant", UUID.fromString(TENANT)).param("run", runId).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(qualityCheck(lease, encounterId, documentId, versionId).statusCode()).isEqualTo(200);
        var history = objectMapper.readTree(governance(lease, encounterId, documentId, versionId).body()).path("quality_run_history");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(1)).isEqualTo(run);
        assertThat(history.get(0).path("trigger_stage").stringValue()).isEqualTo("MANUAL");
        assertThat(jdbc.sql("select count(*) from outbox_event where tenant_id=:tenant and aggregate_id=:run and event_type='DocumentQualityChecked'")
                .param("tenant", UUID.fromString(TENANT)).param("run", runId).query(Long.class).single()).isEqualTo(1);
        // Stage metadata is not an escape hatch around existing sign gates.
        assertThat(sign(lease, encounterId, documentId, versionId, 1).statusCode()).isEqualTo(409);
        var forbidden = send("POST", "/api/v1/documents/" + documentId + "/quality-checks",
                body.replace(PATIENT, "018f0000-0000-7000-8000-000000000002"), lease, PATIENT, encounterId, null);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        var summary = send("GET", "/api/v1/encounters/" + encounterId + "/record-quality", null, lease, PATIENT, encounterId, null);
        assertThat(summary.statusCode()).isEqualTo(200);
        JsonNode summaryJson = objectMapper.readTree(summary.body());
        assertThat(summaryJson.path("total_documents").intValue()).isEqualTo(1);
        assertThat(summaryJson.path("has_more").booleanValue()).isFalse();
        assertThat(summaryJson.path("documents").get(0).path("quality_state").stringValue()).isEqualTo("BLOCKED");
        assertThat(summary.body()).doesNotContain("sections", "同一段合成文字");
        assertThat(send("GET", "/api/v1/encounters/" + UUID.randomUUID() + "/record-quality", null, lease, PATIENT, encounterId, null).statusCode()).isEqualTo(403);

        // Legacy evidence with obsolete source watermark must never appear passed/current.
        jdbc.sql("""
                insert into document_quality_run(tenant_id,quality_run_id,document_id,document_version_id,rule_version,
                  outcome,finding_count,blocking_count,warning_count,content_hash,source_watermark,executed_by,executed_at)
                select tenant_id,gen_random_uuid(),document_id,document_version_id,rule_version,
                  outcome,finding_count,blocking_count,warning_count,content_hash,repeat('0',64),executed_by,now()+interval '1 second'
                from document_quality_run where tenant_id=:tenant and quality_run_id=:run
                """).param("tenant", UUID.fromString(TENANT)).param("run", runId).update();
        var staleSummary = send("GET", "/api/v1/encounters/" + encounterId + "/record-quality", null, lease, PATIENT, encounterId, null);
        assertThat(objectMapper.readTree(staleSummary.body()).path("documents").get(0).path("quality_state").stringValue()).isEqualTo("STALE");

        var saved = send("PUT", "/api/v1/documents/" + documentId + "/draft", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":1,"sections":{"chief_complaint":"新的合成主诉","present_illness":"新的合成现病史",
                 "assessment":"独立合成评估","treatment_plan":"独立合成计划"}}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId), lease, PATIENT, encounterId, UUID.randomUUID().toString());
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        var notRun = send("GET", "/api/v1/encounters/" + encounterId + "/record-quality", null, lease, PATIENT, encounterId, null);
        assertThat(objectMapper.readTree(notRun.body()).path("documents").get(0).path("quality_state").stringValue()).isEqualTo("NOT_RUN");
        var worklist = records.worklist(new ClinicalIdentity(UUID.fromString(TENANT), UUID.fromString(USER), List.of(UUID.fromString(ROLE))),
                UUID.fromString(ORGANIZATION), UUID.fromString(FACILITY), null, documentId);
        assertThat(worklist).singleElement().satisfies(item -> {
            assertThat(item.openFindingCount()).isZero();
            assertThat(item.hasBlockingFinding()).isFalse();
        });
    }

    private HttpResponse<String> qualityCheck(Lease lease, String encounterId, String documentId, String versionId)
            throws Exception {
        return send("POST", "/api/v1/documents/" + documentId + "/quality-checks", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","document_version_id":"%s"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId, versionId),
                lease, PATIENT, encounterId, null);
    }

    private HttpResponse<String> governance(Lease lease, String encounterId, String documentId, String versionId)
            throws Exception {
        return send("GET", "/api/v1/documents/" + documentId
                + "/governance?document_version_id=" + versionId,
                null, lease, PATIENT, encounterId, null);
    }

    private HttpResponse<String> sign(Lease lease, String encounterId, String documentId, String versionId, long rowVersion)
            throws Exception {
        return send("POST", "/api/v1/documents/" + documentId + "/signatures", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s","document_version_id":"%s","expected_row_version":%d,"signature_role":"ATTENDING"}
                """.formatted(ORGANIZATION, FACILITY, PATIENT, encounterId, versionId, rowVersion),
                lease, PATIENT, encounterId, UUID.randomUUID().toString());
    }

    private Lease issueLease(String patientId, String encounterId) throws Exception {
        String patient = patientId == null ? "" : ",\"patient_id\":\"" + patientId + "\"";
        String encounter = encounterId == null ? "" : ",\"encounter_id\":\"" + encounterId + "\"";
        String body = "{\"organization_id\":\"" + ORGANIZATION + "\",\"facility_id\":\"" + FACILITY
                + "\"" + patient + encounter + ",\"purpose_code\":\"CLINICAL_WORKFLOW\"}";
        HttpRequest request = baseRequest("/api/v1/context-leases")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(response.body());
        return new Lease(json.path("lease_id").stringValue(), json.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> send(
            String method,
            String path,
            String body,
            Lease lease,
            String patientId,
            String encounterId,
            String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = baseRequest(path)
                .header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION)
                .header("X-Facility-Context", FACILITY);
        if (patientId != null) builder.header("X-Patient-Context", patientId);
        if (encounterId != null) builder.header("X-Encounter-Context", encounterId);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE);
    }

    private record Lease(String id, String watermark) {}
}
