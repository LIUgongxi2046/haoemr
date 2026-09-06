package org.haoemr.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SourcePatientMatchCandidateRecordRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateCsvImportRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateResolveRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class SourcePatientMatchCandidateApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private SourcePatientMatchCandidateService candidates;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedSource(String status) {
        UUID sourceId = UUID.randomUUID();
        jdbc.sql("""
                insert into source_system_inventory(
                  tenant_id, source_system_id, source_code, display_name, system_type,
                  connection_status, registered_by, registered_at)
                values (cast(:tenant as uuid), :source, :code, '源系统', 'EMR', :status,
                  cast(:actor as uuid), now())
                """).param("tenant", TENANT).param("source", sourceId)
                .param("code", "SRC-" + UUID.randomUUID().toString().substring(0, 8)).param("status", status)
                .param("actor", USER).update();
        return sourceId;
    }

    private UUID seedPatient(String name, String status) {
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, :name, 'F', :birth, :status)
                """).param("tenant", TENANT).param("patient", patientId).param("name", name)
                .param("birth", LocalDate.of(1985, 6, 6)).param("status", status).update();
        return patientId;
    }

    private SourcePatientMatchCandidateWire record(UUID sourceId, String identifier, String name) {
        return candidates.record(identity(), "cand-" + UUID.randomUUID(),
                new SourcePatientMatchCandidateRecordRequestWire(organization, facility, sourceId,
                        identifier, name, "F", LocalDate.of(1985, 6, 6)));
    }

    @Test
    void givenActiveSourceAndMatchingPatient_whenRecording_thenMatchedScoreOne() {
        UUID sourceId = seedSource("ACTIVE");
        String name = "患者-" + UUID.randomUUID().toString().substring(0, 6);
        UUID patientId = seedPatient(name, "ACTIVE");
        SourcePatientMatchCandidateWire candidate = record(sourceId, "SRC-ID-" + UUID.randomUUID(), name);
        assertThat(candidate.matchScore()).isEqualTo(1.0);
        assertThat(candidate.matchedPatientId()).isEqualTo(patientId);
    }

    @Test
    void givenActiveSourceAndNoMatch_whenRecording_thenScoreZero() {
        UUID sourceId = seedSource("ACTIVE");
        SourcePatientMatchCandidateWire candidate = record(sourceId, "SRC-ID-" + UUID.randomUUID(),
                "无匹配患者-" + UUID.randomUUID().toString().substring(0, 6));
        assertThat(candidate.matchScore()).isEqualTo(0.0);
        assertThat(candidate.matchedPatientId()).isNull();
    }

    @Test
    void givenPendingCandidate_whenResolving_thenResolved() {
        UUID sourceId = seedSource("ACTIVE");
        SourcePatientMatchCandidateWire candidate = record(sourceId, "SRC-ID-" + UUID.randomUUID(),
                "无匹配患者-" + UUID.randomUUID().toString().substring(0, 6));
        UUID patientId = seedPatient("复核患者-" + UUID.randomUUID().toString().substring(0, 6), "ACTIVE");
        SourcePatientMatchCandidateWire resolved = candidates.resolve(identity(), "res-" + UUID.randomUUID(),
                candidate.candidateId(),
                new SourcePatientMatchCandidateResolveRequestWire(organization, facility, candidate.rowVersion(), patientId));
        assertThat(resolved.reviewStatus()).isEqualTo(SourcePatientMatchCandidateWire.ReviewStatusValue.RESOLVED);
        assertThat(resolved.matchedPatientId()).isEqualTo(patientId);
    }

    @Test
    void givenPendingCandidate_whenResolvingToInactivePatient_thenRejected() {
        UUID sourceId = seedSource("ACTIVE");
        SourcePatientMatchCandidateWire candidate = record(sourceId, "SRC-ID-" + UUID.randomUUID(),
                "无匹配患者-" + UUID.randomUUID().toString().substring(0, 6));
        UUID deceased = seedPatient("已故患者-" + UUID.randomUUID().toString().substring(0, 6), "DECEASED");
        assertThatThrownBy(() -> candidates.resolve(identity(), "res-" + UUID.randomUUID(),
                candidate.candidateId(),
                new SourcePatientMatchCandidateResolveRequestWire(organization, facility, candidate.rowVersion(), deceased)))
                .isInstanceOf(SourcePatientMatchCandidateException.class)
                .satisfies(e -> assertThat(((SourcePatientMatchCandidateException) e).code())
                        .isEqualTo("PATIENT_INACTIVE"));
    }

    @Test
    void givenInactiveSource_whenRecording_thenRejected() {
        UUID sourceId = seedSource("CONFIGURED");
        assertThatThrownBy(() -> record(sourceId, "SRC-ID-" + UUID.randomUUID(), "患者"))
                .isInstanceOf(SourcePatientMatchCandidateException.class)
                .satisfies(e -> assertThat(((SourcePatientMatchCandidateException) e).code())
                        .isEqualTo("SOURCE_SYSTEM_NOT_ACTIVE"));
    }

    @Test
    void givenActiveSource_whenImportingCsv_thenRecordsImmutableImportAndStagesCandidates() {
        UUID sourceId = seedSource("ACTIVE");
        String csv = "source_patient_identifier,display_name,sex_code,birth_date\n"
                + "HIS-1001,迁移患者一,F,1985-06-06\n"
                + "HIS-1002,迁移患者二,M,1979-02-03\n";
        var imported = candidates.importCsv(identity(), "import-" + UUID.randomUUID(),
                new SourcePatientMatchCandidateCsvImportRequestWire(
                        organization, facility, sourceId, "his-patients.csv", csv));

        assertThat(imported.rowCount()).isEqualTo(2);
        assertThat(candidates.listImports(identity(), sourceId)).extracting(item -> item.sourceImportId())
                .contains(imported.sourceImportId());
        assertThat(jdbc.sql("""
                select count(*) from source_patient_match_candidate
                where tenant_id = :tenant and source_import_id = :import
                """).param("tenant", tenant).param("import", imported.sourceImportId())
                .query(Integer.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.sql("""
                update historical_migration_source_import set file_name = 'replaced.csv'
                where tenant_id = :tenant and source_import_id = :import
                """).param("tenant", tenant).param("import", imported.sourceImportId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenDuplicateSourceIdentifierAcrossImports_whenImportingCsv_thenRejectedBeforeStaging() {
        UUID sourceId = seedSource("ACTIVE");
        record(sourceId, "HIS-1001", "存量患者");
        String csv = "source_patient_identifier,display_name,sex_code,birth_date\n"
                + "HIS-1001,迁移患者一,F,1985-06-06\n";

        assertThatThrownBy(() -> candidates.importCsv(identity(), "import-" + UUID.randomUUID(),
                new SourcePatientMatchCandidateCsvImportRequestWire(
                        organization, facility, sourceId, "duplicate.csv", csv)))
                .isInstanceOf(SourcePatientMatchCandidateException.class)
                .satisfies(e -> assertThat(((SourcePatientMatchCandidateException) e).code())
                        .isEqualTo("PATIENT_MATCH_SOURCE_IDENTIFIER_EXISTS"));
    }

    @Test
    void givenCandidate_whenTampered_thenDatabaseRejectsMutation() {
        UUID sourceId = seedSource("ACTIVE");
        SourcePatientMatchCandidateWire candidate = record(sourceId, "SRC-ID-" + UUID.randomUUID(),
                "无匹配患者-" + UUID.randomUUID().toString().substring(0, 6));
        assertThatThrownBy(() -> jdbc.sql("""
                update source_patient_match_candidate set match_score = 1.0
                where tenant_id = cast(:tenant as uuid) and candidate_id = :candidate
                """).param("tenant", TENANT).param("candidate", candidate.candidateId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
