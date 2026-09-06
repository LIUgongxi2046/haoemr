package org.haoemr.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortMemberComputeRequestWire;
import org.haoemr.contracts.ResearchCohortMemberWire;
import org.haoemr.contracts.ResearchCohortRecomputeRequestWire;
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
final class ResearchCohortMemberApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ResearchCohortMemberService members;

    @Autowired
    private ResearchCohortRecomputeService recomputes;

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
                  tenant_id, research_cohort_id, cohort_code, cohort_name, inclusion_criteria, exclusion_criteria, status)
                values (cast(:tenant as uuid), :cohort, :code, '队列', 'age_gte=18', 'age_lte=17', :status)
                """).param("tenant", TENANT).param("cohort", cohortId)
                .param("code", "COH-" + UUID.randomUUID().toString().substring(0, 8)).param("status", status).update();
        return cohortId;
    }

    private UUID seedPatient(String status) {
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成队列成员患者', 'M', :birth, :status)
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1970, 1, 1)).param("status", status).update();
        return patientId;
    }

    private ResearchCohortMemberWire compute(UUID cohortId, UUID patientId) {
        return members.compute(identity(), "member-" + UUID.randomUUID(),
                new ResearchCohortMemberComputeRequestWire(organization, facility, cohortId, patientId, Instant.now()));
    }

    @Test
    void givenActiveCohortAndPatient_whenComputing_thenMember() {
        UUID cohortId = seedCohort("ACTIVE");
        UUID patientId = seedPatient("ACTIVE");
        ResearchCohortMemberWire member = compute(cohortId, patientId);
        assertThat(member.patientId()).isEqualTo(patientId);
        assertThat(member.computedBy()).isEqualTo(UUID.fromString(USER));

        List<ResearchCohortMemberWire> listed = members.list(identity(), cohortId);
        assertThat(listed).extracting(ResearchCohortMemberWire::cohortMemberId)
                .contains(member.cohortMemberId());
    }

    @Test
    void givenDuplicateMember_whenComputing_thenRejected() {
        UUID cohortId = seedCohort("ACTIVE");
        UUID patientId = seedPatient("ACTIVE");
        compute(cohortId, patientId);
        assertThatThrownBy(() -> compute(cohortId, patientId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenInactiveCohort_whenComputing_thenRejected() {
        UUID cohortId = seedCohort("INACTIVE");
        UUID patientId = seedPatient("ACTIVE");
        assertThatThrownBy(() -> compute(cohortId, patientId))
                .isInstanceOf(ResearchCohortMemberException.class)
                .satisfies(e -> assertThat(((ResearchCohortMemberException) e).code())
                        .isEqualTo("RESEARCH_COHORT_INACTIVE"));
    }

    @Test
    void givenInactivePatient_whenComputing_thenRejected() {
        UUID cohortId = seedCohort("ACTIVE");
        UUID patientId = seedPatient("DECEASED");
        assertThatThrownBy(() -> compute(cohortId, patientId))
                .isInstanceOf(ResearchCohortMemberException.class)
                .satisfies(e -> assertThat(((ResearchCohortMemberException) e).code())
                        .isEqualTo("PATIENT_INACTIVE"));
    }

    @Test
    void givenPatientOutsideVersionedCriteria_whenComputing_thenRejected() {
        UUID cohortId = seedCohort("ACTIVE");
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成未成年患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.now().minusYears(12)).update();
        assertThatThrownBy(() -> compute(cohortId, patientId))
                .isInstanceOf(ResearchCohortMemberException.class)
                .satisfies(e -> assertThat(((ResearchCohortMemberException) e).code())
                        .isEqualTo("RESEARCH_COHORT_PATIENT_NOT_ELIGIBLE"));
    }

    @Test
    void givenMember_whenTampered_thenDatabaseRejectsMutation() {
        UUID cohortId = seedCohort("ACTIVE");
        UUID patientId = seedPatient("ACTIVE");
        ResearchCohortMemberWire member = compute(cohortId, patientId);
        assertThatThrownBy(() -> jdbc.sql("""
                update research_cohort_member set patient_id = cast(:other as uuid)
                where tenant_id = cast(:tenant as uuid) and cohort_member_id = :member
                """).param("tenant", TENANT).param("member", member.cohortMemberId())
                .param("other", UUID.randomUUID()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenActiveCohort_whenRecomputingAll_thenEligiblePatientsAndSnapshotAreMaterialized() {
        UUID cohortId = seedCohort("ACTIVE");
        UUID eligible = seedPatient("ACTIVE");
        UUID ineligible = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成未成年患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", ineligible)
                .param("birth", LocalDate.now().minusYears(12)).update();

        var result = recomputes.recompute(identity(), "recompute-" + UUID.randomUUID(), cohortId,
                new ResearchCohortRecomputeRequestWire(organization, facility, Instant.now()));

        assertThat(result.evaluatedCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.newMemberCount()).isGreaterThanOrEqualTo(1);
        assertThat(members.list(identity(), cohortId)).extracting(ResearchCohortMemberWire::patientId)
                .contains(eligible).doesNotContain(ineligible);
        assertThat(jdbc.sql("select count(*) from research_cohort_snapshot where tenant_id = :tenant and research_cohort_snapshot_id = :snapshot")
                .param("tenant", tenant).param("snapshot", result.snapshotId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select member_snapshot_materialized from research_cohort_snapshot where tenant_id = :tenant and research_cohort_snapshot_id = :snapshot")
                .param("tenant", tenant).param("snapshot", result.snapshotId()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select count(*) from research_cohort_snapshot_member where tenant_id = :tenant and research_cohort_snapshot_id = :snapshot")
                .param("tenant", tenant).param("snapshot", result.snapshotId()).query(Integer.class).single())
                .isEqualTo(result.memberCount());
    }

    @Test
    void givenMaterializedCohort_whenRecomputingAgain_thenNoDuplicateMembersAndAnotherSnapshot() {
        UUID cohortId = seedCohort("ACTIVE");
        seedPatient("ACTIVE");
        var first = recomputes.recompute(identity(), "recompute-first-" + UUID.randomUUID(), cohortId,
                new ResearchCohortRecomputeRequestWire(organization, facility, Instant.now()));

        var second = recomputes.recompute(identity(), "recompute-second-" + UUID.randomUUID(), cohortId,
                new ResearchCohortRecomputeRequestWire(organization, facility, Instant.now().plusSeconds(1)));

        assertThat(second.newMemberCount()).isZero();
        assertThat(second.memberCount()).isEqualTo(first.memberCount());
        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(jdbc.sql("""
                select count(*) from research_cohort_snapshot
                where tenant_id = :tenant and research_cohort_id = :cohort
                """).param("tenant", tenant).param("cohort", cohortId).query(Integer.class).single()).isEqualTo(2);
    }
}
