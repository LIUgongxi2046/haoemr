package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRegistryDeactivateRequestWire;
import org.haoemr.contracts.AgentRegistryRegisterRequestWire;
import org.haoemr.contracts.AgentRegistryVersionRequestWire;
import org.haoemr.contracts.AgentRegistryWire;
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
final class AgentRegistryApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private AgentRegistryService agents;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private AgentRegistryWire register(String agentCode) {
        return agents.register(identity(), "agent-" + UUID.randomUUID(),
                new AgentRegistryRegisterRequestWire(organization, facility, agentCode,
                        "临床摘要助手", "v1"));
    }

    @Test
    void givenAgent_whenRegisteringAndListing_thenActiveAgentRecorded() {
        String agentCode = "AGENT-" + UUID.randomUUID().toString().substring(0, 8);
        AgentRegistryWire registered = register(agentCode);
        assertThat(registered.status()).isEqualTo(AgentRegistryWire.StatusValue.ACTIVE);
        assertThat(registered.agentCode()).isEqualTo(agentCode);

        List<AgentRegistryWire> listed = agents.listAgents(identity(), "ACTIVE");
        assertThat(listed).extracting(AgentRegistryWire::agentRegistryId).contains(registered.agentRegistryId());
    }

    @Test
    void givenActiveAgent_whenDeactivating_thenInactive() {
        String agentCode = "AGENT-" + UUID.randomUUID().toString().substring(0, 8);
        AgentRegistryWire registered = register(agentCode);
        AgentRegistryWire deactivated = agents.deactivate(identity(), "deact-" + UUID.randomUUID(),
                registered.agentRegistryId(), new AgentRegistryDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(AgentRegistryWire.StatusValue.INACTIVE);
    }

    @Test
    void givenActiveAgent_whenPublishingVersion_thenOldVersionIsSafelyReplaced() {
        AgentRegistryWire registered = register("AGENT-" + UUID.randomUUID().toString().substring(0, 8));
        AgentRegistryWire published = agents.publishVersion(identity(), "version-" + UUID.randomUUID(),
                registered.agentRegistryId(), new AgentRegistryVersionRequestWire(
                        organization, facility, "临床摘要医助", "v2"));
        assertThat(published.agentCode()).isEqualTo(registered.agentCode());
        assertThat(published.agentVersion()).isEqualTo("v2");
        assertThat(agents.listAgents(identity(), "ACTIVE")).extracting(AgentRegistryWire::agentRegistryId)
                .contains(published.agentRegistryId()).doesNotContain(registered.agentRegistryId());
    }

    @Test
    void givenAgentIdentity_whenTampered_thenDatabaseRejectsMutation() {
        String agentCode = "AGENT-" + UUID.randomUUID().toString().substring(0, 8);
        AgentRegistryWire registered = register(agentCode);
        assertThatThrownBy(() -> jdbc.sql("""
                update agent_registry set agent_code = 'TAMPERED'
                where tenant_id = cast(:tenant as uuid) and agent_registry_id = :registry
                """).param("tenant", TENANT).param("registry", registered.agentRegistryId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
