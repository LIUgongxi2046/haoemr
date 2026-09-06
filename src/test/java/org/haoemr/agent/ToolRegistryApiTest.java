package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ToolRegistryDeactivateRequestWire;
import org.haoemr.contracts.ToolRegistryRegisterRequestWire;
import org.haoemr.contracts.ToolRegistryVersionRequestWire;
import org.haoemr.contracts.ToolRegistryWire;
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
final class ToolRegistryApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ToolRegistryService tools;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ToolRegistryWire register(String toolCode) {
        return tools.register(identity(), "tool-" + UUID.randomUUID(),
                new ToolRegistryRegisterRequestWire(organization, facility, toolCode,
                        "病历检索工具", "v1", ToolRegistryRegisterRequestWire.ToolTypeValue.DATABASE_QUERY));
    }

    @Test
    void givenTool_whenRegisteringAndListing_thenActiveToolRecorded() {
        String toolCode = "TOOL-" + UUID.randomUUID().toString().substring(0, 8);
        ToolRegistryWire registered = register(toolCode);
        assertThat(registered.status()).isEqualTo(ToolRegistryWire.StatusValue.ACTIVE);
        assertThat(registered.toolType()).isEqualTo(ToolRegistryWire.ToolTypeValue.DATABASE_QUERY);

        List<ToolRegistryWire> listed = tools.listTools(identity(), "ACTIVE");
        assertThat(listed).extracting(ToolRegistryWire::toolRegistryId).contains(registered.toolRegistryId());
    }

    @Test
    void givenActiveTool_whenDeactivating_thenInactive() {
        String toolCode = "TOOL-" + UUID.randomUUID().toString().substring(0, 8);
        ToolRegistryWire registered = register(toolCode);
        ToolRegistryWire deactivated = tools.deactivate(identity(), "deact-" + UUID.randomUUID(),
                registered.toolRegistryId(), new ToolRegistryDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(ToolRegistryWire.StatusValue.INACTIVE);
    }

    @Test
    void givenActiveTool_whenPublishingVersion_thenTypeAndVersionAreUpdatedAtomically() {
        ToolRegistryWire registered = register("TOOL-" + UUID.randomUUID().toString().substring(0, 8));
        ToolRegistryWire published = tools.publishVersion(identity(), "version-" + UUID.randomUUID(),
                registered.toolRegistryId(), new ToolRegistryVersionRequestWire(
                        organization, facility, "病历检索工具 v2", "v2",
                        ToolRegistryVersionRequestWire.ToolTypeValue.API));
        assertThat(published.toolCode()).isEqualTo(registered.toolCode());
        assertThat(published.toolVersion()).isEqualTo("v2");
        assertThat(published.toolType()).isEqualTo(ToolRegistryWire.ToolTypeValue.API);
        assertThat(tools.listTools(identity(), "ACTIVE")).extracting(ToolRegistryWire::toolRegistryId)
                .contains(published.toolRegistryId()).doesNotContain(registered.toolRegistryId());
    }

    @Test
    void givenToolIdentity_whenTampered_thenDatabaseRejectsMutation() {
        String toolCode = "TOOL-" + UUID.randomUUID().toString().substring(0, 8);
        ToolRegistryWire registered = register(toolCode);
        assertThatThrownBy(() -> jdbc.sql("""
                update tool_registry set tool_code = 'TAMPERED'
                where tenant_id = cast(:tenant as uuid) and tool_registry_id = :registry
                """).param("tenant", TENANT).param("registry", registered.toolRegistryId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
