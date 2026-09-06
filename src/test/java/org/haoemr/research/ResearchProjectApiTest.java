package org.haoemr.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.ResearchProjectCreateRequestWire;
import org.haoemr.contracts.ResearchProjectDeactivateRequestWire;
import org.haoemr.contracts.ResearchProjectWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class ResearchProjectApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ResearchProjectService projects;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenSeededProjects_whenListingActive_thenDomainProjectsReturned() {
        List<ResearchProjectWire> listed = projects.listProjects(identity(), "ACTIVE");
        assertThat(listed).extracting(ResearchProjectWire::projectCode)
                .contains("res-2026-014", "res-2026-021");
    }

    @Test
    void givenNewProject_whenCreating_thenActiveRecorded() {
        String code = "PRJ-" + UUID.randomUUID().toString().substring(0, 8);
        ResearchProjectWire created = projects.create(identity(), "create-" + UUID.randomUUID(),
                new ResearchProjectCreateRequestWire(organization, facility, code, "测试观察性研究",
                        ResearchProjectCreateRequestWire.ProjectTypeValue.OBSERVATIONAL, "周教授", null, "IRB-2026-000",
                        "测试用途", List.of("门诊病历", "检验"), 5, null));
        assertThat(created.status()).isEqualTo(ResearchProjectWire.StatusValue.ACTIVE);
        assertThat(created.dataScope()).containsExactly("门诊病历", "检验");
    }

    @Test
    void givenActiveProject_whenDeactivating_thenInactive() {
        String code = "PRJ-" + UUID.randomUUID().toString().substring(0, 8);
        ResearchProjectWire created = projects.create(identity(), "create-" + UUID.randomUUID(),
                new ResearchProjectCreateRequestWire(organization, facility, code, "测试回顾性研究",
                        ResearchProjectCreateRequestWire.ProjectTypeValue.RETROSPECTIVE, "刘主任", null, null,
                        "测试用途", List.of("住院病历"), 3, null));
        ResearchProjectWire deactivated = projects.deactivate(identity(), "deact-" + UUID.randomUUID(),
                created.projectId(), new ResearchProjectDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(ResearchProjectWire.StatusValue.INACTIVE);
    }
}
