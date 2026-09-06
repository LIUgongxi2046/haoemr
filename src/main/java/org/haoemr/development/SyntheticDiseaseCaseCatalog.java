package org.haoemr.development;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record SyntheticDiseaseCaseCatalog(String datasetVersion, List<DiseaseCase> cases) {

    static SyntheticDiseaseCaseCatalog parse(ObjectMapper objectMapper, String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!root.path("synthetic").asBoolean(false)) {
            throw new IllegalArgumentException("Only explicitly synthetic disease case catalogs may be imported");
        }
        List<DiseaseCase> cases = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        Set<String> diseaseCodes = new HashSet<>();
        Set<String> patientNames = new HashSet<>();
        for (JsonNode item : root.path("cases")) {
            DiseaseCase next = new DiseaseCase(
                    required(item, "case_id"), required(item, "domain"),
                    required(item, "disease_code"), required(item, "disease_name"),
                    required(item, "patient_name"), required(item, "sex"), required(item, "birth_date"),
                    required(item, "chief_complaint"), required(item, "present_illness"),
                    required(item, "past_history"), required(item, "allergy_history"),
                    required(item, "physical_examination"), required(item, "vital_signs"),
                    required(item, "diagnosis_text"), required(item, "evidence_summary"),
                    required(item, "treatment_plan"), required(item, "order_type"),
                    required(item, "order_code"), required(item, "order_name"),
                    required(item, "result_code"), required(item, "result_name"),
                    item.path("result_value"), nullableText(item, "result_unit"),
                    nullableNumber(item, "reference_low"), nullableNumber(item, "reference_high"),
                    required(item, "abnormal_flag"), nullableText(item, "triage_level"));
            validate(next);
            if (!caseIds.add(next.caseId())) throw new IllegalArgumentException("Duplicate case_id: " + next.caseId());
            if (!diseaseCodes.add(next.diseaseCode())) {
                throw new IllegalArgumentException("Duplicate disease_code: " + next.diseaseCode());
            }
            if (!patientNames.add(next.patientName())) {
                throw new IllegalArgumentException("Duplicate patient_name: " + next.patientName());
            }
            cases.add(next);
        }
        if (cases.size() != 50) throw new IllegalArgumentException("Disease case catalog must contain exactly 50 cases");
        Map<String, Long> domains = cases.stream().collect(java.util.stream.Collectors.groupingBy(
                DiseaseCase::domain, java.util.stream.Collectors.counting()));
        if (!Map.of("OUTPATIENT", 20L, "EMERGENCY", 15L, "INPATIENT", 15L).equals(domains)) {
            throw new IllegalArgumentException("Disease case catalog domain split must be 20/15/15");
        }
        return new SyntheticDiseaseCaseCatalog(required(root, "dataset_version"), List.copyOf(cases));
    }

    private static void validate(DiseaseCase item) {
        if (!item.patientName().matches("\\p{IsHan}{2,4}")
                || item.patientName().matches(".*(合成|测试|患者).*")) {
            throw new IllegalArgumentException("Patient name must be a natural simulated Chinese name for "
                    + item.caseId());
        }
        if (!Set.of("OUTPATIENT", "EMERGENCY", "INPATIENT").contains(item.domain())) {
            throw new IllegalArgumentException("Unsupported domain for " + item.caseId());
        }
        if (!Set.of("M", "F").contains(item.sex())) throw new IllegalArgumentException("Unsupported sex for " + item.caseId());
        if (!Set.of("LAB", "IMAGING").contains(item.orderType())) {
            throw new IllegalArgumentException("Only result-producing orders are allowed for " + item.caseId());
        }
        if (!Set.of("NORMAL", "HIGH", "LOW", "CRITICAL_HIGH", "CRITICAL_LOW").contains(item.abnormalFlag())) {
            throw new IllegalArgumentException("Unsupported result flag for " + item.caseId());
        }
        if (item.resultValue().isMissingNode() || item.resultValue().isNull()
                || !(item.resultValue().isNumber() || item.resultValue().isTextual())) {
            throw new IllegalArgumentException("Result value is required for " + item.caseId());
        }
        if (item.presentIllness().length() < 20 || item.evidenceSummary().length() < 20
                || item.treatmentPlan().length() < 20 || item.physicalExamination().length() < 12) {
            throw new IllegalArgumentException("Clinical detail is incomplete for " + item.caseId());
        }
        if ("EMERGENCY".equals(item.domain())
                && !Set.of("LEVEL_1", "LEVEL_2", "LEVEL_3", "LEVEL_4").contains(item.triageLevel())) {
            throw new IllegalArgumentException("Emergency triage level is required for " + item.caseId());
        }
    }

    private static String required(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required field: " + field);
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.stringValue();
    }

    private static Double nullableNumber(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }

    record DiseaseCase(
            String caseId, String domain, String diseaseCode, String diseaseName,
            String patientName, String sex, String birthDate,
            String chiefComplaint, String presentIllness, String pastHistory, String allergyHistory,
            String physicalExamination, String vitalSigns, String diagnosisText,
            String evidenceSummary, String treatmentPlan,
            String orderType, String orderCode, String orderName,
            String resultCode, String resultName, JsonNode resultValue, String resultUnit,
            Double referenceLow, Double referenceHigh, String abnormalFlag, String triageLevel) {}
}
