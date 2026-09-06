package org.haoemr.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.HistoricalMigrationCheckpointRecordRequestWire;
import org.haoemr.contracts.HistoricalMigrationCheckpointWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class HistoricalMigrationCheckpointApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private HistoricalMigrationCheckpointService checkpoints;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedBatch(String status) {
        UUID batchId = UUID.randomUUID();
        jdbc.sql("""
                insert into historical_migration_batch(
                  tenant_id, batch_id, source_system, batch_status, record_count, mismatch_count,
                  started_at, completed_at, created_by)
                values (cast(:tenant as uuid), :batch, :source, :status, 100, 0, now(), cast(:completed_at as timestamptz), cast(:creator as uuid))
                """).param("tenant", TENANT).param("batch", batchId)
                .param("source", "SRC-" + UUID.randomUUID().toString().substring(0, 8)).param("status", status)
                .param("completed_at", "TRIAL".equals(status) ? null : Instant.now().atOffset(ZoneOffset.UTC))
                .param("creator", USER).update();
        return batchId;
    }

    private HistoricalMigrationCheckpointWire record(UUID batchId, long processedRecords, String sourceKey) {
        return checkpoints.record(identity(), "ckpt-" + UUID.randomUUID(),
                new HistoricalMigrationCheckpointRecordRequestWire(organization, facility, batchId,
                        processedRecords, sourceKey, Instant.now()));
    }

    @Test
    void givenTrialBatch_whenRecordingCheckpoint_thenRecorded() {
        UUID batchId = seedBatch("TRIAL");
        HistoricalMigrationCheckpointWire recorded = record(batchId, 42, "SRC-KEY-42");
        assertThat(recorded.processedRecords()).isEqualTo(42);
        assertThat(recorded.checkpointedBy()).isEqualTo(UUID.fromString(USER));
    }

    @Test
    void givenProgressingCheckpoints_whenRecording_thenLatestReflectsResumePoint() {
        UUID batchId = seedBatch("TRIAL");
        record(batchId, 40, "SRC-KEY-40");
        record(batchId, 80, "SRC-KEY-80");
        HistoricalMigrationCheckpointWire latest = checkpoints.latest(identity(), batchId);
        assertThat(latest.processedRecords()).isEqualTo(80);
        assertThat(latest.lastSourceKey()).isEqualTo("SRC-KEY-80");
    }

    @Test
    void givenRegression_whenRecording_thenRejected() {
        UUID batchId = seedBatch("TRIAL");
        record(batchId, 50, "SRC-KEY-50");
        assertThatThrownBy(() -> record(batchId, 30, "SRC-KEY-30"))
                .isInstanceOf(HistoricalMigrationCheckpointException.class)
                .satisfies(e -> assertThat(((HistoricalMigrationCheckpointException) e).code())
                        .isEqualTo("CHECKPOINT_REGRESSION"));
    }

    @Test
    void givenSwitchedBatch_whenRecording_thenRejected() {
        UUID batchId = seedBatch("SWITCHED");
        assertThatThrownBy(() -> record(batchId, 10, null))
                .isInstanceOf(HistoricalMigrationCheckpointException.class)
                .satisfies(e -> assertThat(((HistoricalMigrationCheckpointException) e).code())
                        .isEqualTo("BATCH_NOT_RESUMABLE"));
    }

    @Test
    void givenNoCheckpoint_whenFetchingLatest_thenNotFound() {
        UUID batchId = seedBatch("TRIAL");
        assertThatThrownBy(() -> checkpoints.latest(identity(), batchId))
                .isInstanceOf(HistoricalMigrationCheckpointException.class)
                .satisfies(e -> assertThat(((HistoricalMigrationCheckpointException) e).code())
                        .isEqualTo("CHECKPOINT_NOT_FOUND"));
    }

    @Test
    void givenCheckpoint_whenTampered_thenDatabaseRejectsMutation() {
        UUID batchId = seedBatch("TRIAL");
        HistoricalMigrationCheckpointWire recorded = record(batchId, 10, "SRC-KEY-10");
        assertThatThrownBy(() -> jdbc.sql("""
                update historical_migration_checkpoint set processed_records = 999
                where tenant_id = cast(:tenant as uuid) and checkpoint_id = :checkpoint
                """).param("tenant", TENANT).param("checkpoint", recorded.checkpointId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
