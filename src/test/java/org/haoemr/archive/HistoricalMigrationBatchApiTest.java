package org.haoemr.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.HistoricalMigrationBatchArchiveRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchObservationStartRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchReconcileRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchRollbackRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchStartRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchSwitchRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class HistoricalMigrationBatchApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private HistoricalMigrationBatchService batches;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private HistoricalMigrationBatchWire start(String source) {
        return batches.start(identity(), "start-" + UUID.randomUUID(),
                new HistoricalMigrationBatchStartRequestWire(organization, facility, source, null, 1, Instant.now()));
    }

    private HistoricalMigrationBatchWire start(String source, UUID sourceImportId) {
        return batches.start(identity(), "start-import-" + UUID.randomUUID(),
                new HistoricalMigrationBatchStartRequestWire(organization, facility, source, sourceImportId, 1, Instant.now()));
    }

    private PreparedSource prepareSource(boolean resolved) {
        UUID sourceId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID mappingId = UUID.randomUUID();
        String sourceCode = "HIS-" + UUID.randomUUID().toString().substring(0, 8);
        UUID patientId = jdbc.sql("""
                select patient_id from patient where tenant_id = :tenant and status = 'ACTIVE'
                order by patient_id limit 1
                """).param("tenant", tenant).query(UUID.class).single();
        jdbc.sql("""
                insert into source_system_inventory(
                  tenant_id, source_system_id, source_code, display_name, system_type,
                  connection_status, registered_by, registered_at)
                values (:tenant, :source, :code, :name, 'EMR', 'ACTIVE', :actor, now())
                """).param("tenant", tenant).param("source", sourceId).param("code", sourceCode)
                .param("name", "历史HIS测试源").param("actor", UUID.fromString(USER)).update();
        jdbc.sql("""
                insert into source_field_mapping(
                  tenant_id, mapping_id, source_system_id, source_field, target_entity,
                  target_field, status, registered_by, registered_at)
                values (:tenant, :mapping, :source, 'PATIENT_NO', 'Patient',
                  'identifier', 'ACTIVE', :actor, now())
                """).param("tenant", tenant).param("mapping", mappingId).param("source", sourceId)
                .param("actor", UUID.fromString(USER)).update();
        jdbc.sql("""
                insert into source_patient_match_candidate(
                  tenant_id, candidate_id, source_system_id, source_patient_identifier,
                  display_name, sex_code, birth_date, matched_patient_id, match_score,
                  review_status, resolved_by, resolved_at)
                values (:tenant, :candidate, :source, :identifier, '张三', 'M', :birth,
                  :patient, 1.0, :status, :resolver, :resolved_at)
                """).param("tenant", tenant).param("candidate", candidateId).param("source", sourceId)
                .param("identifier", "PAT-" + candidateId).param("birth", LocalDate.of(1980, 1, 1))
                .param("patient", resolved ? patientId : null).param("status", resolved ? "RESOLVED" : "PENDING")
                .param("resolver", resolved ? UUID.fromString(USER) : null)
                .param("resolved_at", resolved ? java.time.OffsetDateTime.now() : null).update();
        return new PreparedSource(sourceId, sourceCode, candidateId);
    }

    private HistoricalMigrationBatchWire reconcile(UUID batchId, int mismatchCount, long expectedRowVersion) {
        return batches.reconcile(identity(), "reconcile-" + UUID.randomUUID(), batchId,
                new HistoricalMigrationBatchReconcileRequestWire(organization, facility, mismatchCount, expectedRowVersion));
    }

    private HistoricalMigrationBatchWire switchBatch(UUID batchId, long expectedRowVersion) {
        return batches.switchBatch(identity(), "switch-" + UUID.randomUUID(), batchId,
                new HistoricalMigrationBatchSwitchRequestWire(organization, facility, expectedRowVersion));
    }

    private HistoricalMigrationBatchWire rollback(UUID batchId, long expectedRowVersion) {
        return batches.rollback(identity(), "rollback-" + UUID.randomUUID(), batchId,
                new HistoricalMigrationBatchRollbackRequestWire(organization, facility, expectedRowVersion));
    }

    private HistoricalMigrationBatchWire startObservation(UUID batchId, long expectedRowVersion) {
        return batches.startObservation(identity(), "observation-" + UUID.randomUUID(), batchId,
                new HistoricalMigrationBatchObservationStartRequestWire(organization, facility, expectedRowVersion));
    }

    private HistoricalMigrationBatchWire archive(UUID batchId, long expectedRowVersion, String summary) {
        return batches.archive(identity(), "archive-" + UUID.randomUUID(), batchId,
                new HistoricalMigrationBatchArchiveRequestWire(organization, facility, summary, expectedRowVersion));
    }

    @Test
    void givenBatch_whenStarting_thenTrial() {
        HistoricalMigrationBatchWire batch = start(prepareSource(true).sourceCode());
        assertThat(batch.batchStatus()).isEqualTo(HistoricalMigrationBatchWire.BatchStatusValue.TRIAL);
        assertThat(batch.mismatchCount()).isZero();
    }

    @Test
    void givenTrialBatch_whenReconciling_thenReconciled() {
        HistoricalMigrationBatchWire batch = start(prepareSource(true).sourceCode());
        HistoricalMigrationBatchWire reconciled = reconcile(batch.batchId(), 0, 1L);
        assertThat(reconciled.batchStatus()).isEqualTo(HistoricalMigrationBatchWire.BatchStatusValue.RECONCILED);
        assertThat(reconciled.completedAt()).isNotNull();
    }

    @Test
    void givenReconciledBatch_whenSwitching_thenSwitched() {
        HistoricalMigrationBatchWire batch = start(prepareSource(true).sourceCode());
        HistoricalMigrationBatchWire reconciled = reconcile(batch.batchId(), 0, 1L);
        HistoricalMigrationBatchWire switched = switchBatch(batch.batchId(), reconciled.rowVersion());
        assertThat(switched.batchStatus()).isEqualTo(HistoricalMigrationBatchWire.BatchStatusValue.SWITCHED);
    }

    @Test
    void givenSwitchedBatch_whenObservedAndArchived_thenObservationEvidenceIsImmutable() {
        HistoricalMigrationBatchWire batch = start(prepareSource(true).sourceCode());
        HistoricalMigrationBatchWire reconciled = reconcile(batch.batchId(), 0, batch.rowVersion());
        HistoricalMigrationBatchWire switched = switchBatch(batch.batchId(), reconciled.rowVersion());

        HistoricalMigrationBatchWire observing = startObservation(batch.batchId(), switched.rowVersion());
        HistoricalMigrationBatchWire archived = archive(batch.batchId(), observing.rowVersion(),
                "观察期内完成迁移结果核验，未发现需回退的问题。");

        assertThat(observing.batchStatus()).isEqualTo(HistoricalMigrationBatchWire.BatchStatusValue.OBSERVING);
        assertThat(observing.observationStartedAt()).isNotNull();
        assertThat(archived.batchStatus()).isEqualTo(HistoricalMigrationBatchWire.BatchStatusValue.ARCHIVED);
        assertThat(archived.observationCompletedAt()).isNotNull();
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(archived.observationSummary()).isEqualTo("观察期内完成迁移结果核验，未发现需回退的问题。");
        assertThatThrownBy(() -> archive(batch.batchId(), archived.rowVersion(), "重复归档不应覆盖原观察结论。"))
                .isInstanceOf(HistoricalMigrationBatchException.class)
                .satisfies(e -> assertThat(((HistoricalMigrationBatchException) e).code())
                        .isEqualTo("HISTORICAL_MIGRATION_STATE_INVALID"));
    }

    @Test
    void givenReconciledWithMismatch_whenSwitching_thenRejected() {
        HistoricalMigrationBatchWire batch = start(prepareSource(false).sourceCode());
        HistoricalMigrationBatchWire reconciled = reconcile(batch.batchId(), 0, 1L);
        assertThat(reconciled.mismatchCount()).isEqualTo(1);
        assertThatThrownBy(() -> switchBatch(batch.batchId(), reconciled.rowVersion()))
                .isInstanceOf(HistoricalMigrationBatchException.class)
                .satisfies(e -> assertThat(((HistoricalMigrationBatchException) e).code())
                        .isEqualTo("HISTORICAL_MIGRATION_MISMATCH"));
    }

    @Test
    void givenReconciledBatch_whenRollingBack_thenRolledBack() {
        HistoricalMigrationBatchWire batch = start(prepareSource(false).sourceCode());
        HistoricalMigrationBatchWire reconciled = reconcile(batch.batchId(), 0, 1L);
        HistoricalMigrationBatchWire rolledBack = rollback(batch.batchId(), reconciled.rowVersion());
        assertThat(rolledBack.batchStatus()).isEqualTo(HistoricalMigrationBatchWire.BatchStatusValue.ROLLED_BACK);
    }

    @Test
    void givenStaleVersion_whenReconciling_thenRejected() {
        HistoricalMigrationBatchWire batch = start(prepareSource(true).sourceCode());
        assertThatThrownBy(() -> reconcile(batch.batchId(), 0, 99L))
                .isInstanceOf(HistoricalMigrationBatchException.class)
                .satisfies(e -> assertThat(((HistoricalMigrationBatchException) e).code())
                        .isEqualTo("HISTORICAL_MIGRATION_VERSION_CONFLICT"));
    }

    @Test
    void givenClientCountDifferentFromStagedRows_whenStarting_thenRejected() {
        String source = prepareSource(true).sourceCode();
        assertThatThrownBy(() -> batches.start(identity(), "start-" + UUID.randomUUID(),
                new HistoricalMigrationBatchStartRequestWire(organization, facility, source, null, 99, Instant.now())))
                .isInstanceOf(HistoricalMigrationBatchException.class)
                .satisfies(e -> assertThat(((HistoricalMigrationBatchException) e).code())
                        .isEqualTo("HISTORICAL_MIGRATION_RECORD_COUNT_MISMATCH"));
    }

    @Test
    void givenControlledImport_whenNewCandidateArrives_thenBatchReconcilesFixedInputOnly() {
        PreparedSource source = prepareSource(true);
        UUID importId = UUID.randomUUID();
        jdbc.sql("""
                insert into historical_migration_source_import(
                  tenant_id, source_import_id, source_system_id, file_name, content_hash, row_count, imported_by)
                values (:tenant, :import, :source, 'fixed.csv', :hash, 1, :actor)
                """).param("tenant", tenant).param("import", importId).param("source", source.sourceId())
                .param("hash", "a".repeat(64)).param("actor", UUID.fromString(USER)).update();
        jdbc.sql("""
                update source_patient_match_candidate set source_import_id = :import
                where tenant_id = :tenant and candidate_id = :candidate
                """).param("tenant", tenant).param("import", importId).param("candidate", source.candidateId()).update();
        UUID laterCandidate = UUID.randomUUID();
        jdbc.sql("""
                insert into source_patient_match_candidate(
                  tenant_id, candidate_id, source_system_id, source_patient_identifier, display_name,
                  sex_code, birth_date, match_score, review_status)
                values (:tenant, :candidate, :source, :identifier, '后来新增', 'F', :birth, 0, 'PENDING')
                """).param("tenant", tenant).param("candidate", laterCandidate).param("source", source.sourceId())
                .param("identifier", "PAT-LATER-" + laterCandidate).param("birth", LocalDate.of(1991, 1, 1)).update();

        HistoricalMigrationBatchWire batch = start(source.sourceCode(), importId);
        HistoricalMigrationBatchWire reconciled = reconcile(batch.batchId(), 0, batch.rowVersion());

        assertThat(batch.sourceImportId()).isEqualTo(importId);
        assertThat(reconciled.batchStatus()).isEqualTo(HistoricalMigrationBatchWire.BatchStatusValue.RECONCILED);
        assertThat(reconciled.mismatchCount()).isZero();
    }

    private record PreparedSource(UUID sourceId, String sourceCode, UUID candidateId) {}
}
