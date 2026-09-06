package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class AgentOrchestratorTest {

    private final AgentOrchestrator orchestrator = new AgentOrchestrator();

    @Test
    void resolvesRouteByIdentifier() {
        assertThat(orchestrator.resolve("record-qc"))
                .isEqualTo(new AgentOrchestrator.Routing("RECORD_QC", "ACTIVE_RECORD"));
        assertThat(orchestrator.resolve("billing"))
                .isEqualTo(new AgentOrchestrator.Routing("INSURANCE_COMPLIANCE", "CHARGE"));
    }

    @Test
    void fallsBackToIntentWhenRouteAbsent() {
        assertThat(orchestrator.resolve(null, "请为出院患者生成带药说明"))
                .isEqualTo(new AgentOrchestrator.Routing("PATIENT_EDUCATION", "MEDICATION_GUIDE"));
        assertThat(orchestrator.resolve("", "核对这例患者的 DRG 编码"))
                .isEqualTo(new AgentOrchestrator.Routing("INSURANCE_COMPLIANCE", "CHARGE"));
        assertThat(orchestrator.resolve(null, "请起草本次就诊的出院小结"))
                .isEqualTo(new AgentOrchestrator.Routing("DOCUMENT_DRAFTER", "OUTPATIENT"));
    }

    @Test
    void routesSpecialtyAgentsByIntentKeyword() {
        assertThat(orchestrator.resolve(null, "请给出胰岛素剂量调整建议"))
                .isEqualTo(new AgentOrchestrator.Routing("GLYCEMIC_MANAGEMENT", "ADMISSION_GLUCOSE"));
        assertThat(orchestrator.resolve(null, "请对这位胸痛患者做风险分层"))
                .isEqualTo(new AgentOrchestrator.Routing("CARDIOVASCULAR_CARE", "CHEST_PAIN"));
        assertThat(orchestrator.resolve(null, "请根据四诊信息给出辨证分型"))
                .isEqualTo(new AgentOrchestrator.Routing("TCM_SYNDROME_REVIEW", "SYNDROME"));
        assertThat(orchestrator.resolve(null, "请评估脓毒症风险"))
                .isEqualTo(new AgentOrchestrator.Routing("ICU_RISK_ASSESSMENT", "SEPSIS_RISK"));
    }

    @Test
    void defaultsToEncounterSummarizerWithoutSignals() {
        assertThat(orchestrator.resolve(null, "请总结一下这次就诊"))
                .isEqualTo(new AgentOrchestrator.Routing("ENCOUNTER_SUMMARIZER", "ACTIVE_ENCOUNTER"));
        assertThat(orchestrator.resolve(null, null))
                .isEqualTo(new AgentOrchestrator.Routing("ENCOUNTER_SUMMARIZER", "ACTIVE_ENCOUNTER"));
    }
}
