package org.haoemr.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ClinicalTaskRuleResolverTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");

    @Autowired
    private ClinicalTaskRuleResolver resolver;

    @Test
    void activeApprovedTertiaryRuleControlsRiskDueTimeEscalationAndTraceSnapshot() {
        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);

        ClinicalTaskRuleResolver.ResolvedTaskRule rule = resolver.resolve(
                TENANT, "SEPSIS_BUNDLE", "INPATIENT", "ROUTINE", Duration.ofHours(8));

        assertThat(rule.configId()).isNotNull();
        assertThat(rule.configVersion()).isPositive();
        assertThat(rule.riskLevel()).isEqualTo("CRITICAL");
        assertThat(rule.dueAt()).isBetween(started.plusMinutes(59), started.plusMinutes(61));
        assertThat(rule.escalationAt()).isBetween(started.plusMinutes(44), started.plusMinutes(46));
        assertThat(rule.snapshotJson()).contains(
                "tertiary-task-center-v2", "SEPSIS_BUNDLE", "assignment_strategy");
    }

    @Test
    void missingRuleFallsBackWithoutInventingAConfigurationReference() {
        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);

        ClinicalTaskRuleResolver.ResolvedTaskRule rule = resolver.resolve(
                TENANT, "UNKNOWN_TASK_TYPE", "OUTPATIENT", "HIGH", Duration.ofMinutes(30));

        assertThat(rule.configId()).isNull();
        assertThat(rule.configVersion()).isNull();
        assertThat(rule.riskLevel()).isEqualTo("HIGH");
        assertThat(rule.dueAt()).isBetween(started.plusMinutes(29), started.plusMinutes(31));
        assertThat(rule.escalationAt()).isNull();
        assertThat(rule.snapshotJson()).isEqualTo("{}");
    }
}
