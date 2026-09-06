package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentDependencyDeclareRequestWire;
import org.haoemr.contracts.AgentDependencyWire;
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
final class AgentDependencyApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private AgentDependencyService dependencies;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedAgent() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into agent_registry(tenant_id, agent_registry_id, agent_code, agent_name, agent_version, status)
                values (cast(:tenant as uuid), :id, :code, '文档助手', '1.0.0', 'ACTIVE')
                """).param("tenant", TENANT).param("id", id)
                .param("code", "AG-" + UUID.randomUUID().toString().substring(0, 8)).update();
        return id;
    }

    private String seedSkill(String status) {
        String code = "SK-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                insert into skill_registry(tenant_id, skill_registry_id, skill_code, skill_name, skill_version, status)
                values (cast(:tenant as uuid), :id, :code, '技能-摘要', '1.0.0', :status)
                """).param("tenant", TENANT).param("id", UUID.randomUUID())
                .param("code", code).param("status", status).update();
        return code;
    }

    private String seedTool(String status) {
        String code = "TO-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                insert into tool_registry(tenant_id, tool_registry_id, tool_code, tool_name, tool_version, tool_type, status)
                values (cast(:tenant as uuid), :id, :code, '工具-检索', '1.0.0', 'API', :status)
                """).param("tenant", TENANT).param("id", UUID.randomUUID())
                .param("code", code).param("status", status).update();
        return code;
    }

    private AgentDependencyWire declare(UUID agentId, AgentDependencyDeclareRequestWire.DependencyTypeValue type, String code) {
        return dependencies.declare(identity(), "dep-" + UUID.randomUUID(),
                new AgentDependencyDeclareRequestWire(organization, facility, agentId, type, code));
    }

    @Test
    void givenActiveSkillDependency_whenDeclaring_thenResolved() {
        UUID agentId = seedAgent();
        String skillCode = seedSkill("ACTIVE");
        AgentDependencyWire declared = declare(agentId, AgentDependencyDeclareRequestWire.DependencyTypeValue.SKILL, skillCode);
        assertThat(declared.dependencyType()).isEqualTo(AgentDependencyWire.DependencyTypeValue.SKILL);
        assertThat(declared.resolved()).isTrue();

        List<AgentDependencyWire> listed = dependencies.listDependencies(identity(), agentId);
        assertThat(listed).extracting(AgentDependencyWire::agentDependencyId).contains(declared.agentDependencyId());
    }

    @Test
    void givenActiveToolDependency_whenDeclaring_thenResolved() {
        UUID agentId = seedAgent();
        String toolCode = seedTool("ACTIVE");
        AgentDependencyWire declared = declare(agentId, AgentDependencyDeclareRequestWire.DependencyTypeValue.TOOL, toolCode);
        assertThat(declared.dependencyType()).isEqualTo(AgentDependencyWire.DependencyTypeValue.TOOL);
        assertThat(declared.resolved()).isTrue();
    }

    @Test
    void givenMissingSkill_whenDeclaring_thenRejected() {
        UUID agentId = seedAgent();
        assertThatThrownBy(() -> declare(agentId, AgentDependencyDeclareRequestWire.DependencyTypeValue.SKILL, "SK-MISSING"))
                .isInstanceOf(AgentDependencyException.class)
                .satisfies(e -> assertThat(((AgentDependencyException) e).code())
                        .isEqualTo("AGENT_DEPENDENCY_UNRESOLVABLE"));
    }

    @Test
    void givenInactiveSkill_whenDeclaring_thenRejected() {
        UUID agentId = seedAgent();
        String inactiveSkill = seedSkill("INACTIVE");
        assertThatThrownBy(() -> declare(agentId, AgentDependencyDeclareRequestWire.DependencyTypeValue.SKILL, inactiveSkill))
                .isInstanceOf(AgentDependencyException.class)
                .satisfies(e -> assertThat(((AgentDependencyException) e).code())
                        .isEqualTo("AGENT_DEPENDENCY_UNRESOLVABLE"));
    }

    @Test
    void givenDuplicateDependency_whenDeclaring_thenRejected() {
        UUID agentId = seedAgent();
        String skillCode = seedSkill("ACTIVE");
        declare(agentId, AgentDependencyDeclareRequestWire.DependencyTypeValue.SKILL, skillCode);
        assertThatThrownBy(() -> declare(agentId, AgentDependencyDeclareRequestWire.DependencyTypeValue.SKILL, skillCode))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenDeactivatedSkill_whenListing_thenResolvedFalse() {
        UUID agentId = seedAgent();
        String skillCode = seedSkill("ACTIVE");
        AgentDependencyWire declared = declare(agentId, AgentDependencyDeclareRequestWire.DependencyTypeValue.SKILL, skillCode);
        jdbc.sql("""
                update skill_registry set status = 'INACTIVE'
                where tenant_id = cast(:tenant as uuid) and skill_code = :code
                """).param("tenant", TENANT).param("code", skillCode).update();

        List<AgentDependencyWire> listed = dependencies.listDependencies(identity(), agentId);
        AgentDependencyWire found = listed.stream()
                .filter(d -> d.agentDependencyId().equals(declared.agentDependencyId())).findFirst().orElseThrow();
        assertThat(found.resolved()).isFalse();
    }
}
