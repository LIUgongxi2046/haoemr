package org.haoemr.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortSnapshotRequestWire;
import org.haoemr.contracts.ResearchCohortSnapshotWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class ResearchCohortSnapshotApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ResearchCohortSnapshotService snapshots;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedCohort(String status) {
        UUID cohortId = UUID.randomUUID();
        jdbc.sql("""
                insert into research_cohort(
                  tenant_id, research_cohort_id, cohort_code, cohort_name,
                  inclusion_criteria, exclusion_criteria, status)
                values (cast(:tenant as uuid), :cohort, :code, '糖尿病队列',
                  '诊断为 2 型糖尿病', '年龄小于 18 岁', :status)
                """).param("tenant", TENANT).param("cohort", cohortId)
                .param("code", "CH-" + UUID.randomUUID().toString().substring(0, 8))
                .param("status", status).update();
        return cohortId;
    }

    private ResearchCohortSnapshotWire record(UUID cohortId, int memberCount) {
        return snapshots.record(identity(), "snap-" + UUID.randomUUID(),
                new ResearchCohortSnapshotRequestWire(organization, facility, cohortId, memberCount, Instant.now()));
    }

    @Test
    void givenActiveCohort_whenRecording_thenSnapshotWithCriteriaHash() {
        UUID cohortId = seedCohort("ACTIVE");
        ResearchCohortSnapshotWire snapshot = record(cohortId, 0);
        assertThat(snapshot.memberCount()).isZero();
        assertThat(snapshot.criteriaHash()).hasSize(64);
        assertThat(snapshot.computedBy()).isEqualTo(UUID.fromString(USER));
        assertThat(snapshot.memberSnapshotMaterialized()).isTrue();

        List<ResearchCohortSnapshotWire> listed = snapshots.listSnapshots(identity(), cohortId);
        assertThat(listed).extracting(ResearchCohortSnapshotWire::researchCohortSnapshotId)
                .contains(snapshot.researchCohortSnapshotId());
    }

    @Test
    void givenInactiveCohort_whenRecording_thenRejected() {
        UUID cohortId = seedCohort("INACTIVE");
        assertThatThrownBy(() -> record(cohortId, 5))
                .isInstanceOf(ResearchCohortSnapshotException.class)
                .satisfies(e -> assertThat(((ResearchCohortSnapshotException) e).code())
                        .isEqualTo("RESEARCH_COHORT_INACTIVE"));
    }

    @Test
    void givenClientCountDifferentFromServerMembership_whenRecording_thenRejected() {
        UUID cohortId = seedCohort("ACTIVE");
        assertThatThrownBy(() -> record(cohortId, 99))
                .isInstanceOf(ResearchCohortSnapshotException.class)
                .satisfies(e -> assertThat(((ResearchCohortSnapshotException) e).code())
                        .isEqualTo("RESEARCH_COHORT_SNAPSHOT_COUNT_MISMATCH"));
    }

    @Test
    void givenNegativeMemberCount_whenBypassingService_thenDatabaseRejects() {
        UUID cohortId = seedCohort("ACTIVE");
        assertThatThrownBy(() -> jdbc.sql("""
                insert into research_cohort_snapshot(
                  tenant_id, research_cohort_snapshot_id, research_cohort_id, member_count,
                  criteria_hash, computed_at, computed_by)
                values (cast(:tenant as uuid), :snapshot, :cohort, -1,
                  '0000000000000000000000000000000000000000000000000000000000000000',
                  now(), cast(:user as uuid))
                """).param("tenant", TENANT).param("snapshot", UUID.randomUUID())
                .param("cohort", cohortId).param("user", USER).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenSnapshot_whenTampered_thenDatabaseRejectsMutation() {
        UUID cohortId = seedCohort("ACTIVE");
        ResearchCohortSnapshotWire snapshot = record(cohortId, 0);
        assertThatThrownBy(() -> jdbc.sql("""
                update research_cohort_snapshot set member_count = 999
                where tenant_id = cast(:tenant as uuid) and research_cohort_snapshot_id = :snapshot
                """).param("tenant", TENANT).param("snapshot", snapshot.researchCohortSnapshotId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
