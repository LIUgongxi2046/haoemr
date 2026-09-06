package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRunBudgetDeactivateRequestWire;
import org.haoemr.contracts.AgentRunBudgetDefineRequestWire;
import org.haoemr.contracts.AgentRunBudgetUpdateRequestWire;
import org.haoemr.contracts.AgentRunBudgetWire;
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
final class AgentRunBudgetApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private AgentRunBudgetService budgets;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private AgentRunBudgetWire define(String budgetCode, long tokens, int durationSeconds) {
        return budgets.define(identity(), "budget-" + UUID.randomUUID(),
                new AgentRunBudgetDefineRequestWire(organization, facility, budgetCode,
                        "临床助手运行预算", tokens, durationSeconds));
    }

    private AgentRunBudgetWire defineForActiveAgent(long tokens, int durationSeconds) {
        String agentCode = "TEST_AGENT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbc.sql("""
                insert into agent_registry(
                  tenant_id, agent_registry_id, agent_code, agent_name, agent_version, status)
                values (cast(:tenant as uuid), :registry, :code, '测试医助团队', 'test-v1', 'ACTIVE')
                """).param("tenant", TENANT).param("registry", UUID.randomUUID()).param("code", agentCode).update();
        return define("BUDGET_" + agentCode, tokens, durationSeconds);
    }

    @Test
    void givenBudget_whenDefiningAndListing_thenActiveBudgetRecorded() {
        AgentRunBudgetWire defined = defineForActiveAgent(100_000L, 300);
        assertThat(defined.status()).isEqualTo(AgentRunBudgetWire.StatusValue.ACTIVE);
        assertThat(defined.maxTokens()).isEqualTo(100_000L);

        List<AgentRunBudgetWire> listed = budgets.listBudgets(identity(), "ACTIVE");
        assertThat(listed).extracting(AgentRunBudgetWire::budgetId).contains(defined.budgetId());
    }

    @Test
    void givenNonPositiveLimits_whenDefining_thenRejected() {
        assertThatThrownBy(() -> define("BUDGET_ENCOUNTER_SUMMARIZER", 0L, 300))
                .isInstanceOf(AgentRunBudgetException.class)
                .satisfies(e -> assertThat(((AgentRunBudgetException) e).code())
                        .isEqualTo("AGENT_RUN_BUDGET_REQUEST_INVALID"));
    }

    @Test
    void givenActiveBudget_whenDeactivating_thenInactive() {
        AgentRunBudgetWire defined = defineForActiveAgent(50_000L, 120);
        AgentRunBudgetWire deactivated = budgets.deactivate(identity(), "deact-" + UUID.randomUUID(),
                defined.budgetId(), new AgentRunBudgetDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(AgentRunBudgetWire.StatusValue.INACTIVE);
    }

    @Test
    void givenActiveBudget_whenUpdating_thenNewLimitsAffectItsVersionedDefinition() {
        AgentRunBudgetWire defined = defineForActiveAgent(50_000L, 120);
        AgentRunBudgetWire updated = budgets.update(identity(), "update-" + UUID.randomUUID(),
                defined.budgetId(), new AgentRunBudgetUpdateRequestWire(
                        organization, facility, "门诊高峰处理额度", 75_000L, 180, defined.rowVersion()));
        assertThat(updated.budgetName()).isEqualTo("门诊高峰处理额度");
        assertThat(updated.maxTokens()).isEqualTo(75_000L);
        assertThat(updated.rowVersion()).isEqualTo(defined.rowVersion() + 1);

        AgentRunBudgetWire updatedAgain = budgets.update(identity(), "update-again-" + UUID.randomUUID(),
                updated.budgetId(), new AgentRunBudgetUpdateRequestWire(
                        organization, facility, "门诊高峰处理额度（第二版）", 80_000L, 210, updated.rowVersion()));
        assertThat(updatedAgain.maxTokens()).isEqualTo(80_000L);
        assertThat(updatedAgain.rowVersion()).isEqualTo(updated.rowVersion() + 1);
    }

    @Test
    void givenBudgetIdentity_whenTampered_thenDatabaseRejectsMutation() {
        AgentRunBudgetWire defined = defineForActiveAgent(80_000L, 200);
        assertThatThrownBy(() -> jdbc.sql("""
                update agent_run_budget set max_tokens = 1
                where tenant_id = cast(:tenant as uuid) and budget_id = :budget
                """).param("tenant", TENANT).param("budget", defined.budgetId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenUnregisteredAgentCode_whenDefining_thenRejectedBeforeCreatingFreeFormQuota() {
        assertThatThrownBy(() -> define("BUDGET_NOT_A_REGISTERED_MEDICAL_AGENT", 80_000L, 200))
                .isInstanceOf(AgentRunBudgetException.class)
                .satisfies(e -> assertThat(((AgentRunBudgetException) e).code())
                        .isEqualTo("AGENT_RUN_BUDGET_REQUEST_INVALID"));
    }
}
