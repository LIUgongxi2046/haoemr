package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRunBudgetConsumptionRecordRequestWire;
import org.haoemr.contracts.AgentRunBudgetConsumptionWire;
import org.haoemr.contracts.AgentRunBudgetSummaryWire;
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
final class AgentRunBudgetConsumptionApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private AgentRunBudgetConsumptionService consumptions;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedBudget(String status, long maxTokens, int maxDuration) {
        UUID budgetId = UUID.randomUUID();
        jdbc.sql("""
                insert into agent_run_budget(
                  tenant_id, budget_id, budget_code, budget_name, max_tokens, max_duration_seconds, status)
                values (cast(:tenant as uuid), :budget, :code, '预算', :tokens, :duration, :status)
                """).param("tenant", TENANT).param("budget", budgetId)
                .param("code", "BUD-" + UUID.randomUUID().toString().substring(0, 8)).param("tokens", maxTokens)
                .param("duration", maxDuration).param("status", status).update();
        return budgetId;
    }

    private AgentRunBudgetConsumptionWire record(UUID budgetId, long tokens, long duration) {
        return consumptions.record(identity(), "cons-" + UUID.randomUUID(),
                new AgentRunBudgetConsumptionRecordRequestWire(organization, facility, budgetId,
                        UUID.randomUUID(), tokens, duration, Instant.now()));
    }

    @Test
    void givenActiveBudget_whenRecordingConsumption_thenRecorded() {
        UUID budgetId = seedBudget("ACTIVE", 100, 60);
        AgentRunBudgetConsumptionWire recorded = record(budgetId, 20, 10);
        assertThat(recorded.tokensConsumed()).isEqualTo(20);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<AgentRunBudgetConsumptionWire> listed = consumptions.list(identity(), budgetId);
        assertThat(listed).extracting(AgentRunBudgetConsumptionWire::consumptionId)
                .contains(recorded.consumptionId());
    }

    @Test
    void givenCumulativeWithinLimit_whenRecording_thenSummaryReflectsTotals() {
        UUID budgetId = seedBudget("ACTIVE", 100, 60);
        record(budgetId, 30, 20);
        record(budgetId, 40, 20);
        AgentRunBudgetSummaryWire summary = consumptions.summary(identity(), budgetId);
        assertThat(summary.totalTokens()).isEqualTo(70);
        assertThat(summary.totalDurationSeconds()).isEqualTo(40);
        assertThat(summary.maxTokens()).isEqualTo(100);
    }

    @Test
    void givenTokensExceeded_whenRecording_thenRejected() {
        UUID budgetId = seedBudget("ACTIVE", 100, 60);
        record(budgetId, 70, 10);
        assertThatThrownBy(() -> record(budgetId, 40, 10))
                .isInstanceOf(AgentRunBudgetConsumptionException.class)
                .satisfies(e -> assertThat(((AgentRunBudgetConsumptionException) e).code())
                        .isEqualTo("BUDGET_TOKENS_EXCEEDED"));
    }

    @Test
    void givenDurationExceeded_whenRecording_thenRejected() {
        UUID budgetId = seedBudget("ACTIVE", 100, 60);
        record(budgetId, 10, 40);
        assertThatThrownBy(() -> record(budgetId, 10, 30))
                .isInstanceOf(AgentRunBudgetConsumptionException.class)
                .satisfies(e -> assertThat(((AgentRunBudgetConsumptionException) e).code())
                        .isEqualTo("BUDGET_DURATION_EXCEEDED"));
    }

    @Test
    void givenInactiveBudget_whenRecording_thenRejected() {
        UUID budgetId = seedBudget("INACTIVE", 100, 60);
        assertThatThrownBy(() -> record(budgetId, 10, 10))
                .isInstanceOf(AgentRunBudgetConsumptionException.class)
                .satisfies(e -> assertThat(((AgentRunBudgetConsumptionException) e).code())
                        .isEqualTo("BUDGET_INACTIVE"));
    }

    @Test
    void givenConsumption_whenTampered_thenDatabaseRejectsMutation() {
        UUID budgetId = seedBudget("ACTIVE", 100, 60);
        AgentRunBudgetConsumptionWire recorded = record(budgetId, 10, 10);
        assertThatThrownBy(() -> jdbc.sql("""
                update agent_run_budget_consumption set tokens_consumed = 999
                where tenant_id = cast(:tenant as uuid) and consumption_id = :consumption
                """).param("tenant", TENANT).param("consumption", recorded.consumptionId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
