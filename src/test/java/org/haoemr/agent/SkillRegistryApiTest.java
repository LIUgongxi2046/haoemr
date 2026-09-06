package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SkillRegistryDeactivateRequestWire;
import org.haoemr.contracts.SkillRegistryRegisterRequestWire;
import org.haoemr.contracts.SkillRegistryVersionRequestWire;
import org.haoemr.contracts.SkillRegistryWire;
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
final class SkillRegistryApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private SkillRegistryService skills;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private SkillRegistryWire register(String skillCode) {
        return skills.register(identity(), "skill-" + UUID.randomUUID(),
                new SkillRegistryRegisterRequestWire(organization, facility, skillCode,
                        "临床摘要技能", "v1"));
    }

    @Test
    void givenSkill_whenRegisteringAndListing_thenActiveSkillRecorded() {
        String skillCode = "SKILL-" + UUID.randomUUID().toString().substring(0, 8);
        SkillRegistryWire registered = register(skillCode);
        assertThat(registered.status()).isEqualTo(SkillRegistryWire.StatusValue.ACTIVE);
        assertThat(registered.skillCode()).isEqualTo(skillCode);

        List<SkillRegistryWire> listed = skills.listSkills(identity(), "ACTIVE");
        assertThat(listed).extracting(SkillRegistryWire::skillRegistryId).contains(registered.skillRegistryId());
    }

    @Test
    void givenActiveSkill_whenDeactivating_thenInactive() {
        String skillCode = "SKILL-" + UUID.randomUUID().toString().substring(0, 8);
        SkillRegistryWire registered = register(skillCode);
        SkillRegistryWire deactivated = skills.deactivate(identity(), "deact-" + UUID.randomUUID(),
                registered.skillRegistryId(), new SkillRegistryDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(SkillRegistryWire.StatusValue.INACTIVE);
    }

    @Test
    void givenActiveSkill_whenPublishingVersion_thenOnlyNewVersionRemainsActive() {
        SkillRegistryWire registered = register("SKILL-" + UUID.randomUUID().toString().substring(0, 8));
        SkillRegistryWire published = skills.publishVersion(identity(), "version-" + UUID.randomUUID(),
                registered.skillRegistryId(), new SkillRegistryVersionRequestWire(
                        organization, facility, "临床摘要能力", "v2"));
        assertThat(published.skillCode()).isEqualTo(registered.skillCode());
        assertThat(published.skillVersion()).isEqualTo("v2");
        assertThat(skills.listSkills(identity(), "ACTIVE")).extracting(SkillRegistryWire::skillRegistryId)
                .contains(published.skillRegistryId()).doesNotContain(registered.skillRegistryId());
    }

    @Test
    void givenSkillIdentity_whenTampered_thenDatabaseRejectsMutation() {
        String skillCode = "SKILL-" + UUID.randomUUID().toString().substring(0, 8);
        SkillRegistryWire registered = register(skillCode);
        assertThatThrownBy(() -> jdbc.sql("""
                update skill_registry set skill_code = 'TAMPERED'
                where tenant_id = cast(:tenant as uuid) and skill_registry_id = :registry
                """).param("tenant", TENANT).param("registry", registered.skillRegistryId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
