package org.haoemr.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortDeactivateRequestWire;
import org.haoemr.contracts.ResearchCohortDefineRequestWire;
import org.haoemr.contracts.ResearchCohortWire;
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
final class ResearchCohortApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ResearchCohortService cohorts;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ResearchCohortWire define(String cohortCode) {
        return cohorts.define(identity(), "cohort-" + UUID.randomUUID(),
                new ResearchCohortDefineRequestWire(organization, facility, cohortCode,
                        "高血压研究队列", "age_gte=18;age_lte=75;diagnosis_code=I10.0", "age_lte=17"));
    }

    @Test
    void givenCohort_whenDefiningAndListing_thenActiveCohortRecorded() {
        String cohortCode = "COHORT-" + UUID.randomUUID().toString().substring(0, 8);
        ResearchCohortWire defined = define(cohortCode);
        assertThat(defined.status()).isEqualTo(ResearchCohortWire.StatusValue.ACTIVE);
        assertThat(defined.cohortCode()).isEqualTo(cohortCode);

        List<ResearchCohortWire> listed = cohorts.listCohorts(identity(), "ACTIVE");
        assertThat(listed).extracting(ResearchCohortWire::researchCohortId).contains(defined.researchCohortId());
    }

    @Test
    void givenActiveCohort_whenDeactivating_thenInactive() {
        String cohortCode = "COHORT-" + UUID.randomUUID().toString().substring(0, 8);
        ResearchCohortWire defined = define(cohortCode);
        ResearchCohortWire deactivated = cohorts.deactivate(identity(), "deact-" + UUID.randomUUID(),
                defined.researchCohortId(), new ResearchCohortDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(ResearchCohortWire.StatusValue.INACTIVE);
    }

    @Test
    void givenCohortIdentity_whenTampered_thenDatabaseRejectsMutation() {
        String cohortCode = "COHORT-" + UUID.randomUUID().toString().substring(0, 8);
        ResearchCohortWire defined = define(cohortCode);
        assertThatThrownBy(() -> jdbc.sql("""
                update research_cohort set inclusion_criteria = '篡改'
                where tenant_id = cast(:tenant as uuid) and research_cohort_id = :cohort
                """).param("tenant", TENANT).param("cohort", defined.researchCohortId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
