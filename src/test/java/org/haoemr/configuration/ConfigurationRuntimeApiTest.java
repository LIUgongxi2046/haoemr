package org.haoemr.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeCommandRequest;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeExecutionWire;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeTransitionRequest;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class ConfigurationRuntimeApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");

    @Autowired
    private ConfigurationRuntimeService runtime;

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(TENANT, USER, List.of(ROLE));
    }

    private ConfigurationRuntimeService.RuntimeContext context() {
        return new ConfigurationRuntimeService.RuntimeContext(ORGANIZATION, FACILITY, null, null);
    }

    @Test
    void givenPublishedWorkflow_whenStartingAndTransitioning_thenVersionBoundExecutionIsPersisted() {
        RuntimeExecutionWire started = runtime.startWorkflow(identity(), context(), "runtime-" + UUID.randomUUID(),
                "runtime-workflow-consult-v1", new RuntimeCommandRequest("ENCOUNTER", UUID.randomUUID(), Map.of()));
        assertThat(started.state()).isEqualTo("ACTIVE");
        assertThat(started.currentNode()).isEqualTo("registration");
        assertThat(started.configurationWatermark()).startsWith("CONFIG:WORKFLOW:runtime-workflow-consult-v1:v");

        RuntimeExecutionWire transitioned = runtime.transitionWorkflow(identity(), context(), "runtime-" + UUID.randomUUID(),
                started.executionId(), new RuntimeTransitionRequest(started.rowVersion(), "REGISTRATION_TO_TRIAGE",
                        Map.of("events", Map.of("registration", Map.of("completed", true)))));
        assertThat(transitioned.currentNode()).isEqualTo("triage");
        assertThat(transitioned.rowVersion()).isEqualTo(2);
        assertThat(runtime.getExecution(identity(), context(), started.executionId()).currentNode()).isEqualTo("triage");
    }

    @Test
    void givenStaleWorkflowVersion_whenTransitioning_thenItFailsClosed() {
        RuntimeExecutionWire started = runtime.startWorkflow(identity(), context(), "runtime-" + UUID.randomUUID(),
                "runtime-workflow-consult-v1", new RuntimeCommandRequest(null, null, Map.of()));
        assertThatThrownBy(() -> runtime.transitionWorkflow(identity(), context(), "runtime-" + UUID.randomUUID(),
                started.executionId(), new RuntimeTransitionRequest(99L, "REGISTRATION_TO_TRIAGE", Map.of())))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(error -> assertThat(((ConfigurationException) error).code())
                        .isEqualTo("CONFIG_RUNTIME_VERSION_CONFLICT"));
    }

    @Test
    void givenPublishedForm_whenRequiredValuesAreMissing_thenValidationBlocksAndRecordsErrors() {
        RuntimeExecutionWire execution = runtime.validateForm(identity(), context(), "runtime-" + UUID.randomUUID(),
                "runtime-form-record-v1", new RuntimeCommandRequest("DOCUMENT", UUID.randomUUID(),
                        Map.of("patient_name", "张三")));
        assertThat(execution.state()).isEqualTo("BLOCKED");
        assertThat((List<?>) execution.outputPayload().get("errors")).isNotEmpty();
        assertThat(execution.outputPayload()).containsEntry("valid", false);
    }

    @Test
    void givenPublishedRuleSet_whenStructuredFactMatches_thenHardDecisionBlocks() {
        RuntimeExecutionWire execution = runtime.evaluateRules(identity(), context(), "runtime-" + UUID.randomUUID(),
                "runtime-rule-safety-v1", new RuntimeCommandRequest("ORDER", UUID.randomUUID(),
                        Map.of("allergy-block", true)));
        assertThat(execution.state()).isEqualTo("BLOCKED");
        assertThat(execution.outputPayload()).containsEntry("blocked", true);
        assertThat(execution.outputPayload().get("decisions").toString()).contains("allergy-block");
    }

    @Test
    void givenPublishedScope_whenPatientRelationshipIsMissing_thenMinimumNecessaryFailsClosed() {
        RuntimeExecutionWire denied = runtime.authorizeScope(identity(), context(), "runtime-" + UUID.randomUUID(),
                "runtime-scope-clinical-v1", new RuntimeCommandRequest("AUTHORIZATION", UUID.randomUUID(), Map.of(
                        "role", "门诊经治医生", "resource", "门诊病历", "action", "读写",
                        "scope", "本人接诊患者", "patient_relationship_verified", false,
                        "active_shift_verified", true)));
        assertThat(denied.state()).isEqualTo("DENIED");
        assertThat(denied.outputPayload()).containsEntry("authorized", false);
    }

    @Test
    void givenOversizedFacts_whenExecuting_thenJsonResourceExhaustionIsRejected() {
        Map<String, Object> facts = new LinkedHashMap<>();
        for (int index = 0; index < 129; index++) facts.put("fact-" + index, index);
        assertThatThrownBy(() -> runtime.evaluateRules(identity(), context(), "runtime-" + UUID.randomUUID(),
                "runtime-rule-safety-v1", new RuntimeCommandRequest(null, null, facts)))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(error -> assertThat(((ConfigurationException) error).code())
                        .isEqualTo("CONFIG_RUNTIME_INPUT_INVALID"));
    }

    @Test
    void givenExecutionInOneFacility_whenReadFromAnotherContext_thenItIsNotDisclosed() {
        RuntimeExecutionWire started = runtime.startWorkflow(identity(), context(), "runtime-" + UUID.randomUUID(),
                "runtime-workflow-consult-v1", new RuntimeCommandRequest(null, null, Map.of()));
        ConfigurationRuntimeService.RuntimeContext otherFacility = new ConfigurationRuntimeService.RuntimeContext(
                ORGANIZATION, UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> runtime.getExecution(identity(), otherFacility, started.executionId()))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(error -> assertThat(((ConfigurationException) error).code())
                        .isEqualTo("CONFIG_RUNTIME_EXECUTION_NOT_FOUND"));
    }
}
