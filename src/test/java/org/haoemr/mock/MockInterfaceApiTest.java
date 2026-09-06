package org.haoemr.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.MockInterfaceWire;
import org.haoemr.contracts.MockInvocationResultWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MockInterfaceApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private MockInterfaceService mocks;
    @Autowired
    private MockInterfaceExecutionService executions;
    @Autowired
    private JdbcClient jdbc;

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(UUID.fromString(TENANT), UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenRegistry_whenListing_thenAllSyntheticInterfacesAvailable() {
        List<MockInterfaceWire> interfaces = mocks.list();
        assertThat(interfaces).hasSize(18);
        assertThat(interfaces).extracting(MockInterfaceWire::code)
                .contains("LIS_RESULTS", "PACS_IMAGES", "HIS_INSURANCE", "CA_TIMESTAMP",
                        "HIE_DOCUMENT_EXCHANGE", "MODEL_PROVIDER", "DEVICE_GATEWAY", "DICTATION_ASR",
                        "IDP_AUTHENTICATE", "SCAN_CAPTURE", "MALWARE_SCAN", "CDA_VALIDATION",
                        "STORAGE_PRESERVE", "PATHOLOGY_DIAGNOSE",
                        "ANESTHESIA_EVENT", "THERAPY_EXECUTE",
                        "DIRECT_REPORT_GATEWAY", "EMPI_PATIENT_LOOKUP");
    }

    @Test
    void givenKnownInterface_whenInvoking_thenDeterministicSyntheticResponse() {
        Map<String, Object> input = Map.of("patient_id", "P1", "simulation_scenario", "SUCCESS");
        MockInvocationResultWire result = mocks.invoke("LIS_RESULTS", input);
        MockInvocationResultWire replay = mocks.invoke("LIS_RESULTS", input);
        assertThat(result.mockInterfaceCode()).isEqualTo("LIS_RESULTS");
        assertThat(result.payload()).containsKey("results");
        assertThat(result.payload()).containsKey("critical_values");
        assertThat(businessRecords(result)).hasSize(TertiaryMockBusinessDataGenerator.DEFAULT_RECORD_COUNT);
        assertThat(dataProfile(result))
                .containsEntry("hospital_level", "三级甲等")
                .containsEntry("generation_method", "DETERMINISTIC_SEEDED")
                .containsEntry("generator_version", TertiaryMockBusinessDataGenerator.GENERATOR_VERSION)
                .containsEntry("record_count", TertiaryMockBusinessDataGenerator.DEFAULT_RECORD_COUNT)
                .containsEntry("contains_real_phi", false);
        assertThat(result.scenario()).isEqualTo(MockInvocationResultWire.ScenarioValue.SUCCESS);
        assertThat(result.requestId()).isEqualTo(replay.requestId());
        assertThat(result.producedAt()).isEqualTo(replay.producedAt());
        assertThat(result.payload()).isEqualTo(replay.payload());
        assertThat(result.notice()).contains("合成");
    }

    @Test
    void givenEveryAdapter_whenGeneratingTertiaryHospitalBatch_thenRecordsAreScaledAndDiverse() {
        for (MockInterfaceWire adapter : mocks.list()) {
            MockInvocationResultWire result = mocks.invoke(adapter.code(), Map.of(
                    "simulation_scenario", "SUCCESS",
                    "profile_key", "tertiary-regression-v2",
                    "record_count", 48));
            List<Map<String, Object>> records = businessRecords(result);

            assertThat(records).as(adapter.code() + " batch size").hasSize(48);
            assertThat(records).extracting(item -> item.get("business_id"))
                    .as(adapter.code() + " unique business ids").doesNotHaveDuplicates();
            assertThat(records).extracting(item -> item.get("department"))
                    .as(adapter.code() + " department coverage").hasSizeGreaterThan(1);
            assertThat(records).extracting(item -> item.get("campus"))
                    .as(adapter.code() + " campus coverage").contains("本部院区", "东院区", "感染病院区");
            assertThat(dataProfile(result)).containsEntry("record_count", 48);
            if (!"IDP_AUTHENTICATE".equals(adapter.code())) {
                assertThat(dataProfile(result).get("patient_count")).as(adapter.code() + " patient coverage")
                        .isEqualTo(24L);
                assertThat(dataProfile(result).get("encounter_count")).as(adapter.code() + " encounter coverage")
                        .isEqualTo(48L);
            }
        }
    }

    @Test
    void givenDifferentBusinessSeed_whenGenerating_thenDatasetChangesWithoutLosingReplayability() {
        Map<String, Object> firstInput = Map.of("profile_key", "ward-a", "record_count", 24);
        Map<String, Object> secondInput = Map.of("profile_key", "ward-b", "record_count", 24);

        MockInvocationResultWire first = mocks.invoke("LIS_RESULTS", firstInput);
        MockInvocationResultWire replay = mocks.invoke("LIS_RESULTS", firstInput);
        MockInvocationResultWire second = mocks.invoke("LIS_RESULTS", secondInput);

        assertThat(first.payload()).isEqualTo(replay.payload());
        assertThat(first.payload()).isNotEqualTo(second.payload());
        assertThat(businessRecords(first)).hasSize(24);
        assertThat(businessRecords(second)).hasSize(24);
    }

    @Test
    void givenInvalidBatchSize_whenGenerating_thenRequestIsRejected() {
        assertThatThrownBy(() -> mocks.invoke("LIS_RESULTS", Map.of("record_count", 2)))
                .isInstanceOf(MockInterfaceException.class)
                .satisfies(error -> assertThat(((MockInterfaceException) error).code())
                        .isEqualTo("MOCK_RECORD_COUNT_INVALID"));
    }

    @Test
    void givenRegionalExchange_whenInvoking_thenPendingReceiptDoesNotFakeCompletion() {
        MockInvocationResultWire result = mocks.invoke("HIE_DOCUMENT_EXCHANGE", Map.of(
                "document_id", "CDA-21018", "content_hash", "sha256:test"));

        assertThat(result.payload())
                .containsEntry("receipt_status", "PENDING_ACK")
                .containsEntry("shared_at", null);
        assertThat(result.payload().get("clinical_impact")).asString().contains("不影响院内病历签署");
    }

    @Test
    void givenDegradedOrUnavailableScenario_whenInvoking_thenFailureStateIsExplicit() {
        MockInvocationResultWire degraded = mocks.invoke(
                "MODEL_PROVIDER", Map.of("simulation_scenario", "DEGRADED"));
        assertThat(degraded.scenario()).isEqualTo(MockInvocationResultWire.ScenarioValue.DEGRADED);
        assertThat(degraded.payload()).containsKey("_simulation");

        assertThatThrownBy(() -> mocks.invoke(
                "MODEL_PROVIDER", Map.of("simulation_scenario", "UNAVAILABLE")))
                .isInstanceOf(MockInterfaceException.class)
                .satisfies(e -> assertThat(((MockInterfaceException) e).code())
                        .isEqualTo("MOCK_DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void givenUnknownInterface_whenInvoking_thenRejected() {
        assertThatThrownBy(() -> mocks.invoke("NOT_EXIST", Map.of()))
                .isInstanceOf(MockInterfaceException.class)
                .satisfies(e -> assertThat(((MockInterfaceException) e).code()).isEqualTo("MOCK_INTERFACE_UNKNOWN"));
    }

    @Test
    void givenActivePublishedProfile_whenExecuting_thenRunAgentEventsAndEvidenceAreDurableAndIdempotent() {
        String idempotencyKey = "mock-test-" + UUID.randomUUID();
        Map<String, Object> request = Map.of(
                "profile_key", "integration-connectors-tertiary",
                "simulation_scenario", "SUCCESS",
                "patient_id", "018f0000-0000-7000-8000-000000000001",
                "encounter_id", "018f0000-0000-7000-8000-000000000101",
                "record_count", 24);

        MockInvocationResultWire first = executions.invoke(identity(), idempotencyKey, "LIS_RESULTS", request);
        MockInvocationResultWire replay = executions.invoke(identity(), idempotencyKey, "LIS_RESULTS", request);
        Map<String, Object> execution = map(first.payload().get("execution"));
        UUID runId = UUID.fromString(String.valueOf(execution.get("run_id")));

        assertThat(first.requestId()).isEqualTo(replay.requestId());
        assertThat(first.producedAt()).isEqualTo(replay.producedAt());
        Map<String, Object> replayExecution = map(replay.payload().get("execution"));
        assertThat(replayExecution.get("run_id")).isEqualTo(execution.get("run_id"));
        assertThat(replayExecution.get("evidence_hash")).isEqualTo(execution.get("evidence_hash"));
        assertThat(businessRecords(replay)).hasSize(businessRecords(first).size());
        assertThat(execution)
                .containsEntry("profile_key", "integration-connectors-tertiary")
                .containsEntry("clinical_write_allowed", false);
        assertThat(first.payload()).containsKey("safety_agent");
        assertThat((List<?>) executions.run(identity(), runId).get("events")).hasSize(4);
        assertThat(executions.evidence(identity(), runId).get("evidence_hash")).asString().hasSize(64);
        assertThat(jdbc.sql("select count(*) from mock_interface_run where tenant_id = :tenant and run_id = :run")
                .param("tenant", UUID.fromString(TENANT)).param("run", runId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenInactiveMismatchedOrUnsafeProfileRequest_whenExecuting_thenServerFailsClosed() {
        assertThatThrownBy(() -> executions.invoke(identity(), "mock-test-" + UUID.randomUUID(), "LIS_RESULTS", Map.of(
                "profile_key", "admin-auth-tertiary", "record_count", 24)))
                .isInstanceOf(MockInterfaceException.class)
                .satisfies(error -> assertThat(((MockInterfaceException) error).code())
                        .isEqualTo("MOCK_PROFILE_INTERFACE_MISMATCH"));

        assertThatThrownBy(() -> executions.invoke(identity(), "mock-test-" + UUID.randomUUID(), "LIS_RESULTS", Map.of(
                "profile_key", "integration-connectors-tertiary", "contains_real_phi", true)))
                .isInstanceOf(MockInterfaceException.class)
                .satisfies(error -> assertThat(((MockInterfaceException) error).code())
                        .isEqualTo("MOCK_REAL_PHI_FORBIDDEN"));

        assertThatThrownBy(() -> executions.invoke(identity(), "mock-test-" + UUID.randomUUID(), "LIS_RESULTS", Map.of(
                "profile_key", "integration-connectors-tertiary", "contains_real_phi", "true")))
                .isInstanceOf(MockInterfaceException.class)
                .satisfies(error -> assertThat(((MockInterfaceException) error).code())
                        .isEqualTo("MOCK_REAL_PHI_FORBIDDEN"));

        assertThatThrownBy(() -> executions.invoke(identity(), "mock-test-" + UUID.randomUUID(), "LIS_RESULTS", Map.of(
                "profile_key", "integration-connectors-tertiary", "patient_id", UUID.randomUUID().toString())))
                .isInstanceOf(MockInterfaceException.class)
                .satisfies(error -> assertThat(((MockInterfaceException) error).code())
                        .isEqualTo("MOCK_REAL_PHI_FORBIDDEN"));

        assertThatThrownBy(() -> executions.invoke(identity(), "mock-test-" + UUID.randomUUID(), "LIS_RESULTS", Map.of(
                "profile_key", "missing-profile")))
                .isInstanceOf(MockInterfaceException.class)
                .satisfies(error -> assertThat(((MockInterfaceException) error).code())
                        .isEqualTo("MOCK_PROFILE_NOT_ACTIVE"));
    }

    @Test
    void givenChinaMedicalSimulationRules_whenGenerating_thenUnsafeDemoSemanticsAreRemoved() {
        MockInvocationResultWire lis = mocks.invoke("LIS_RESULTS", Map.of("record_count", 48));
        List<Map<String, Object>> critical = businessRecords(lis).stream()
                .filter(record -> Boolean.TRUE.equals(record.get("critical"))).toList();
        assertThat(critical).isNotEmpty();
        assertThat(critical).allSatisfy(record -> {
            assertThat(record.get("critical_policy_code")).isEqualTo("JC-LAB-CRITICAL-2026-01");
            assertThat(record.get("critical_recheck_status")).isEqualTo("RECHECK_CONFIRMED");
            assertThat(record.get("critical_receiver")).isNotNull();
            assertThat(record.get("critical_closed_loop_status")).isEqualTo("SIMULATION_CLOSED");
        });

        MockInvocationResultWire therapy = mocks.invoke("THERAPY_EXECUTE", Map.of("record_count", 48));
        assertThat(businessRecords(therapy).stream().anyMatch(record ->
                Boolean.FALSE.equals(record.get("dual_sign")) && "COMPLETED".equals(record.get("status"))))
                .isFalse();

        MockInvocationResultWire ca = mocks.invoke("CA_TIMESTAMP", Map.of("record_count", 24));
        assertThat(businessRecords(ca)).allSatisfy(record -> {
            assertThat(record.get("verification_status")).isEqualTo("SYNTHETIC_NOT_LEGAL_EVIDENCE");
            assertThat(record.get("legal_effect")).isEqualTo(false);
        });

        MockInvocationResultWire insurance = mocks.invoke("HIS_INSURANCE", Map.of("record_count", 24));
        assertThat(businessRecords(insurance)).allSatisfy(record -> {
            assertThat(record.get("item_code")).asString().startsWith("SYN-NHSA-");
            assertThat(record.get("code_system")).asString().contains("国家医疗保障信息业务编码");
            assertThat(record.get("reconciliation_status")).isNotNull();
        });
    }

    @Test
    void givenMalwareAndCdaAdapters_whenGenerating_thenFailClosedSemanticsSurface() {
        MockInvocationResultWire malware = mocks.invoke("MALWARE_SCAN", Map.of(
                "content_ref", "synthetic://scan/eicar-sample-001", "record_count", 48));
        List<Map<String, Object>> infected = businessRecords(malware).stream()
                .filter(record -> "FOUND".equals(record.get("verdict"))).toList();
        assertThat(infected).as("恶意扫描批次应含检出记录").isNotEmpty();
        assertThat(infected).allSatisfy(record -> {
            assertThat(record.get("signature")).asString().isNotBlank();
            assertThat(record.get("action")).isEqualTo("ISOLATE_AND_BLOCK");
            assertThat(record.get("engine")).isEqualTo("haoemr-synthetic-clamav-v1");
        });
        assertThat(businessRecords(malware).stream()
                .filter(record -> "CLEAN".equals(record.get("verdict"))).toList())
                .allSatisfy(record -> assertThat(record.get("signature")).isNull());
        assertThat(malware.payload()).containsEntry("verdict", "INFECTED")
                .containsEntry("batch_verdict", "ISOLATE_AND_BLOCK");

        MockInvocationResultWire cda = mocks.invoke("CDA_VALIDATION", Map.of(
                "document_id", "CDA-SYNTHETIC-001", "record_count", 48));
        List<Map<String, Object>> invalid = businessRecords(cda).stream()
                .filter(record -> Boolean.FALSE.equals(record.get("structural_valid"))).toList();
        assertThat(invalid).as("CDA 批次应含结构失败文书").isNotEmpty();
        assertThat(invalid).allSatisfy(record -> {
            assertThat(record.get("validation_status")).isEqualTo("STRUCTURE_INVALID");
            assertThat((List<?>) record.get("checks")).asString().contains("必填章节缺失");
        });
        assertThat(businessRecords(cda).stream()
                .filter(record -> Boolean.TRUE.equals(record.get("structural_valid"))))
                .allSatisfy(record -> assertThat(record.get("validation_status"))
                        .isIn("VALID", "SEMANTIC_WARNING"));
        assertThat(cda.payload()).containsEntry("structural_valid", false);
    }

    @Test
    void givenPublishedMalwareAndCdaProfiles_whenExecuting_thenAgentBlocksOrRequestsReview() {
        MockInvocationResultWire malware = executions.invoke(identity(), "mock-av-" + UUID.randomUUID(),
                "MALWARE_SCAN", Map.of("profile_key", "malware-scan-tertiary",
                        "simulation_scenario", "SUCCESS", "content_ref", "synthetic://scan/eicar-sample-001"));
        Map<String, Object> malwareAgent = map(map(malware.payload().get("safety_agent")));
        assertThat(malwareAgent.get("decision")).isEqualTo("BLOCK");
        assertThat(map(malware.payload().get("execution")).get("status")).isEqualTo("BLOCKED");

        MockInvocationResultWire cda = executions.invoke(identity(), "mock-cda-" + UUID.randomUUID(),
                "CDA_VALIDATION", Map.of("profile_key", "cda-validation-tertiary",
                        "simulation_scenario", "SUCCESS", "record_count", 24));
        Map<String, Object> cdaAgent = map(map(cda.payload().get("safety_agent")));
        assertThat(cdaAgent.get("decision")).isEqualTo("REVIEW");
        assertThat(map(cda.payload().get("execution")).get("status")).isEqualTo("REVIEW_REQUIRED");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> businessRecords(MockInvocationResultWire result) {
        return (List<Map<String, Object>>) result.payload().get("business_records");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataProfile(MockInvocationResultWire result) {
        return (Map<String, Object>) result.payload().get("data_profile");
    }
}
