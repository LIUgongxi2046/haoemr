package org.haoemr.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MedicationSafetyApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void givenAllergyDoseAndDuplicateRisks_whenCheckingAndSigning_thenHardRulesCannotBeBypassed()
            throws Exception {
        Context context = seedContext();
        Lease lease = issueLease(context);
        String catalogCode = "MED-AMOX-" + UUID.randomUUID();
        String ingredientCode = "ING-AMOX-" + UUID.randomUUID();
        seedMedication(catalogCode, ingredientCode, 125, 500, "mg");
        seedAllergy(context.patientId(), ingredientCode);

        JsonNode allergicDraft = createMedicationOrder(context, lease, catalogCode, 500);
        String allergicOrderId = allergicDraft.path("order_id").stringValue();
        HttpResponse<String> allergyCheck = safetyCheck(context, lease, allergicOrderId, 1);
        assertThat(allergyCheck.statusCode()).isEqualTo(200);
        JsonNode allergyEvaluation = objectMapper.readTree(allergyCheck.body());
        assertThat(allergyEvaluation.path("passed").booleanValue()).isFalse();
        assertThat(allergyEvaluation.path("findings").get(0).path("code").stringValue())
                .isEqualTo("ACTIVE_INGREDIENT_ALLERGY");
        assertThat(allergyEvaluation.path("findings").toString()).contains("CONFIG_RULE_MED_ALLERGY");
        long configurationRuns = jdbc.sql("""
                select count(*) from configuration_runtime_execution
                where tenant_id = cast(:tenant as uuid) and encounter_id = :encounter
                  and config_type = 'RULE' and config_key = 'runtime-rule-medication-safety-v1'
                  and operation = 'RULE_EVALUATE' and state = 'BLOCKED'
                """).param("tenant", TENANT).param("encounter", context.encounterId()).query(Long.class).single();
        assertThat(configurationRuns).isEqualTo(1);

        HttpResponse<String> allergicSign = sign(context, lease, allergicOrderId, 1);
        assertThat(allergicSign.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(allergicSign.body()).path("error").path("code").stringValue())
                .isEqualTo("MEDICATION_SAFETY_BLOCKED");
        assertThat(executionTaskCount(allergicOrderId)).isZero();

        clearAllergy(context.patientId(), ingredientCode);
        JsonNode excessiveDoseDraft = createMedicationOrder(context, lease, catalogCode, 1000);
        String excessiveDoseOrderId = excessiveDoseDraft.path("order_id").stringValue();
        HttpResponse<String> doseCheck = safetyCheck(context, lease, excessiveDoseOrderId, 1);
        assertThat(doseCheck.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(doseCheck.body()).path("findings").get(0).path("code").stringValue())
                .isEqualTo("SINGLE_DOSE_ABOVE_MAXIMUM");
        assertThat(sign(context, lease, excessiveDoseOrderId, 1).statusCode()).isEqualTo(409);

        JsonNode safeDraft = createMedicationOrder(context, lease, catalogCode, 500);
        String safeOrderId = safeDraft.path("order_id").stringValue();
        assertThat(objectMapper.readTree(safetyCheck(context, lease, safeOrderId, 1).body())
                .path("passed").booleanValue()).isTrue();
        assertThat(sign(context, lease, safeOrderId, 1).statusCode()).isEqualTo(200);

        JsonNode duplicateDraft = createMedicationOrder(context, lease, catalogCode, 250);
        String duplicateOrderId = duplicateDraft.path("order_id").stringValue();
        HttpResponse<String> duplicateCheck = safetyCheck(context, lease, duplicateOrderId, 1);
        assertThat(duplicateCheck.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(duplicateCheck.body()).path("findings").get(0).path("code").stringValue())
                .isEqualTo("ACTIVE_INGREDIENT_DUPLICATE");
        assertThat(sign(context, lease, duplicateOrderId, 1).statusCode()).isEqualTo(409);
        assertThat(executionTaskCount(duplicateOrderId)).isZero();
    }

    @Test
    void givenContraindicatedInteraction_whenCheckingAndSigning_thenBlockedAndEvidenceRecorded() throws Exception {
        Context context = seedContext();
        Lease lease = issueLease(context);
        String catalogA = "MED-A-" + UUID.randomUUID();
        String ingredientA = "ING-A-" + UUID.randomUUID();
        String catalogB = "MED-B-" + UUID.randomUUID();
        String ingredientB = "ING-B-" + UUID.randomUUID();
        seedMedication(catalogA, ingredientA, 100, 200, "mg");
        seedMedication(catalogB, ingredientB, 50, 100, "mg");
        seedInteraction(ingredientA, ingredientB, "CONTRAINDICATED", "严重药物相互作用",
                "两药合用可导致严重不良反应，禁止联合使用。");

        JsonNode orderA = createMedicationOrder(context, lease, catalogA, 150);
        String orderAId = orderA.path("order_id").stringValue();
        assertThat(sign(context, lease, orderAId, 1).statusCode()).isEqualTo(200);

        JsonNode orderB = createMedicationOrder(context, lease, catalogB, 80);
        String orderBId = orderB.path("order_id").stringValue();
        HttpResponse<String> check = safetyCheck(context, lease, orderBId, 1);
        assertThat(check.statusCode()).isEqualTo(200);
        JsonNode evaluation = objectMapper.readTree(check.body());
        assertThat(evaluation.path("passed").booleanValue()).isFalse();
        assertThat(evaluation.path("blocking_count").intValue()).isEqualTo(1);
        JsonNode finding = evaluation.path("findings").get(0);
        assertThat(finding.path("code").stringValue()).isEqualTo("DRUG_INTERACTION");
        assertThat(finding.path("severity").stringValue()).isEqualTo("BLOCKING");
        assertThat(finding.path("evidence_source").stringValue()).startsWith("MEDICATION_INTERACTION:");

        HttpResponse<String> blockedSign = sign(context, lease, orderBId, 1);
        assertThat(blockedSign.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(blockedSign.body()).path("error").path("code").stringValue())
                .isEqualTo("MEDICATION_SAFETY_BLOCKED");
        assertThat(executionTaskCount(orderBId)).isZero();
    }

    @Test
    void givenModerateInteraction_whenChecking_thenWarningRecordedWithoutBlockingSign() throws Exception {
        Context context = seedContext();
        Lease lease = issueLease(context);
        String catalogA = "MED-C-" + UUID.randomUUID();
        String ingredientA = "ING-C-" + UUID.randomUUID();
        String catalogB = "MED-D-" + UUID.randomUUID();
        String ingredientB = "ING-D-" + UUID.randomUUID();
        seedMedication(catalogA, ingredientA, 100, 200, "mg");
        seedMedication(catalogB, ingredientB, 50, 100, "mg");
        seedInteraction(ingredientA, ingredientB, "MODERATE", "中等药物相互作用",
                "两药合用需加强监测，建议谨慎使用。");

        JsonNode orderA = createMedicationOrder(context, lease, catalogA, 150);
        String orderAId = orderA.path("order_id").stringValue();
        assertThat(sign(context, lease, orderAId, 1).statusCode()).isEqualTo(200);

        JsonNode orderB = createMedicationOrder(context, lease, catalogB, 80);
        String orderBId = orderB.path("order_id").stringValue();
        HttpResponse<String> check = safetyCheck(context, lease, orderBId, 1);
        assertThat(check.statusCode()).isEqualTo(200);
        JsonNode evaluation = objectMapper.readTree(check.body());
        assertThat(evaluation.path("passed").booleanValue()).isTrue();
        JsonNode finding = evaluation.path("findings").get(0);
        assertThat(finding.path("code").stringValue()).isEqualTo("DRUG_INTERACTION");
        assertThat(finding.path("severity").stringValue()).isEqualTo("WARNING");
        assertThat(sign(context, lease, orderBId, 1).statusCode()).isEqualTo(200);
    }

    @Test
    void givenRestrictedMedication_whenNoActiveAuthorization_thenBlockedUntilAuthorized() throws Exception {
        Context context = seedContext();
        Lease lease = issueLease(context);
        String catalogCode = "MED-RESTRICT-" + UUID.randomUUID();
        String ingredientCode = "ING-RESTRICT-" + UUID.randomUUID();
        seedRestrictedMedication(catalogCode, ingredientCode, 100, 200, "mg", "RESTRICTED_ANTIBIOTIC");

        JsonNode draft = createMedicationOrder(context, lease, catalogCode, 150);
        String orderId = draft.path("order_id").stringValue();

        HttpResponse<String> blockedCheck = safetyCheck(context, lease, orderId, 1);
        assertThat(blockedCheck.statusCode()).isEqualTo(200);
        JsonNode blockedEvaluation = objectMapper.readTree(blockedCheck.body());
        assertThat(blockedEvaluation.path("passed").booleanValue()).isFalse();
        JsonNode finding = blockedEvaluation.path("findings").get(0);
        assertThat(finding.path("code").stringValue()).isEqualTo("RESTRICTED_MEDICATION_AUTHORIZATION_REQUIRED");
        assertThat(finding.path("severity").stringValue()).isEqualTo("BLOCKING");

        assertThat(sign(context, lease, orderId, 1).statusCode()).isEqualTo(409);
        assertThat(executionTaskCount(orderId)).isZero();

        seedAuthorization(context.patientId(), context.encounterId(), catalogCode, "RESTRICTED_ANTIBIOTIC");

        HttpResponse<String> authorizedCheck = safetyCheck(context, lease, orderId, 1);
        assertThat(objectMapper.readTree(authorizedCheck.body()).path("passed").booleanValue()).isTrue();
        assertThat(sign(context, lease, orderId, 1).statusCode()).isEqualTo(200);
    }

    @Test
    void givenWeightBasedMedication_whenMissingWeightOrOutOfRangePerKg_thenBlocked() throws Exception {
        Context context = seedContext();
        Lease lease = issueLease(context);
        String catalogCode = "MED-WT-" + UUID.randomUUID();
        String ingredientCode = "ING-WT-" + UUID.randomUUID();
        seedWeightBasedMedication(catalogCode, ingredientCode, 100, 5000, "mg", 20, 40);

        JsonNode noWeightDraft = createMedicationOrder(context, lease, catalogCode, 600);
        String noWeightOrderId = noWeightDraft.path("order_id").stringValue();
        JsonNode noWeightEval = objectMapper.readTree(safetyCheck(context, lease, noWeightOrderId, 1).body());
        assertThat(noWeightEval.path("passed").booleanValue()).isFalse();
        assertThat(noWeightEval.path("findings").get(0).path("code").stringValue())
                .isEqualTo("PEDIATRIC_WEIGHT_REQUIRED");
        assertThat(sign(context, lease, noWeightOrderId, 1).statusCode()).isEqualTo(409);

        setPatientWeight(context.patientId(), 20);

        JsonNode highPerKgDraft = createMedicationOrder(context, lease, catalogCode, 1000);
        String highPerKgOrderId = highPerKgDraft.path("order_id").stringValue();
        JsonNode highPerKgEval = objectMapper.readTree(safetyCheck(context, lease, highPerKgOrderId, 1).body());
        assertThat(highPerKgEval.path("findings").get(0).path("code").stringValue())
                .isEqualTo("DOSE_PER_KG_ABOVE_MAXIMUM");
        assertThat(sign(context, lease, highPerKgOrderId, 1).statusCode()).isEqualTo(409);

        JsonNode safeDraft = createMedicationOrder(context, lease, catalogCode, 600);
        String safeOrderId = safeDraft.path("order_id").stringValue();
        assertThat(objectMapper.readTree(safetyCheck(context, lease, safeOrderId, 1).body())
                .path("passed").booleanValue()).isTrue();
        assertThat(sign(context, lease, safeOrderId, 1).statusCode()).isEqualTo(200);
    }

    @Test
    void givenRenalOrHepaticContraindication_whenImpairmentMatches_thenBlocked() throws Exception {
        Context context = seedContext();
        Lease lease = issueLease(context);

        String renalCatalog = "MED-RENAL-" + UUID.randomUUID();
        String renalIngredient = "ING-RENAL-" + UUID.randomUUID();
        seedRenalContraindicatedMedication(renalCatalog, renalIngredient, 100, 200, "mg", "MODERATE");

        JsonNode renalSafeDraft = createMedicationOrder(context, lease, renalCatalog, 150);
        String renalSafeId = renalSafeDraft.path("order_id").stringValue();
        assertThat(objectMapper.readTree(safetyCheck(context, lease, renalSafeId, 1).body())
                .path("passed").booleanValue()).isTrue();

        setPatientRenalStage(context.patientId(), "SEVERE");
        JsonNode renalBlockedDraft = createMedicationOrder(context, lease, renalCatalog, 150);
        String renalBlockedId = renalBlockedDraft.path("order_id").stringValue();
        JsonNode renalEval = objectMapper.readTree(safetyCheck(context, lease, renalBlockedId, 1).body());
        assertThat(renalEval.path("passed").booleanValue()).isFalse();
        assertThat(renalEval.path("findings").get(0).path("code").stringValue())
                .isEqualTo("RENAL_IMPAIRMENT_CONTRAINDICATION");
        assertThat(sign(context, lease, renalBlockedId, 1).statusCode()).isEqualTo(409);

        String hepaticCatalog = "MED-HEPATIC-" + UUID.randomUUID();
        String hepaticIngredient = "ING-HEPATIC-" + UUID.randomUUID();
        seedHepaticContraindicatedMedication(hepaticCatalog, hepaticIngredient, 100, 200, "mg", "C");
        setPatientHepaticClass(context.patientId(), "C");
        JsonNode hepaticDraft = createMedicationOrder(context, lease, hepaticCatalog, 150);
        String hepaticId = hepaticDraft.path("order_id").stringValue();
        JsonNode hepaticEval = objectMapper.readTree(safetyCheck(context, lease, hepaticId, 1).body());
        assertThat(hepaticEval.path("passed").booleanValue()).isFalse();
        assertThat(hepaticEval.path("findings").get(0).path("code").stringValue())
                .isEqualTo("HEPATIC_IMPAIRMENT_CONTRAINDICATION");
        assertThat(sign(context, lease, hepaticId, 1).statusCode()).isEqualTo(409);
    }

    private JsonNode createMedicationOrder(Context context, Lease lease, String catalogCode, int dose)
            throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/orders", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "order_scope":"TEMPORARY","clinical_indication":"合成抗感染治疗",
                 "items":[{"item_type":"MEDICATION","catalog_code":"%s","display_name":"阿莫西林胶囊",
                   "requested_quantity":3,"quantity_unit":"粒","dose_value":%d,"dose_unit":"mg",
                   "route_code":"PO","frequency_code":"TID","instructions":"每次一粒"}]}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), catalogCode, dose),
                lease, context, UUID.randomUUID().toString());
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> safetyCheck(Context context, Lease lease, String orderId, long version)
            throws Exception {
        return send("POST", "/api/v1/orders/" + orderId + "/safety-check", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d,"rule_watermark":"RULESET-MEDICATION-6"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), version),
                lease, context, UUID.randomUUID().toString());
    }

    private HttpResponse<String> sign(Context context, Lease lease, String orderId, long version) throws Exception {
        return send("POST", "/api/v1/orders/" + orderId + "/sign", """
                {"organization_id":"%s","facility_id":"%s","patient_id":"%s","encounter_id":"%s",
                 "expected_row_version":%d,"rule_watermark":"RULESET-MEDICATION-6"}
                """.formatted(ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), version),
                lease, context, UUID.randomUUID().toString());
    }

    private void seedMedication(String catalogCode, String ingredientCode, int minimum, int maximum, String unit) {
        jdbc.sql("""
                insert into medication_catalog_version(
                  tenant_id, medication_catalog_version_id, catalog_code, drug_code, ingredient_code,
                  display_name, minimum_single_dose, maximum_single_dose, dose_unit,
                  effective_from, release_version, status)
                values (cast(:tenant as uuid), gen_random_uuid(), :catalog, :catalog, :ingredient,
                  '阿莫西林胶囊', :minimum, :maximum, :unit, current_date, 'SYNTHETIC-1', 'ACTIVE')
                """).param("tenant", TENANT).param("catalog", catalogCode).param("ingredient", ingredientCode)
                .param("minimum", minimum).param("maximum", maximum).param("unit", unit).update();
    }

    private void seedRestrictedMedication(String catalogCode, String ingredientCode, int minimum, int maximum,
            String unit, String restrictionCode) {
        jdbc.sql("""
                insert into medication_catalog_version(
                  tenant_id, medication_catalog_version_id, catalog_code, drug_code, ingredient_code,
                  display_name, minimum_single_dose, maximum_single_dose, dose_unit,
                  effective_from, release_version, status, prescribing_restriction_code)
                values (cast(:tenant as uuid), gen_random_uuid(), :catalog, :catalog, :ingredient,
                  '受限抗菌药物', :minimum, :maximum, :unit, current_date, 'SYNTHETIC-1', 'ACTIVE', :restriction)
                """).param("tenant", TENANT).param("catalog", catalogCode).param("ingredient", ingredientCode)
                .param("minimum", minimum).param("maximum", maximum).param("unit", unit)
                .param("restriction", restrictionCode).update();
    }

    private void seedWeightBasedMedication(String catalogCode, String ingredientCode, int minimum, int maximum,
            String unit, int minPerKg, int maxPerKg) {
        jdbc.sql("""
                insert into medication_catalog_version(
                  tenant_id, medication_catalog_version_id, catalog_code, drug_code, ingredient_code,
                  display_name, minimum_single_dose, maximum_single_dose, dose_unit,
                  effective_from, release_version, status, weight_based, min_dose_per_kg, max_dose_per_kg)
                values (cast(:tenant as uuid), gen_random_uuid(), :catalog, :catalog, :ingredient,
                  '按体重给药药品', :minimum, :maximum, :unit, current_date, 'SYNTHETIC-1', 'ACTIVE',
                  true, :minPerKg, :maxPerKg)
                """).param("tenant", TENANT).param("catalog", catalogCode).param("ingredient", ingredientCode)
                .param("minimum", minimum).param("maximum", maximum).param("unit", unit)
                .param("minPerKg", minPerKg).param("maxPerKg", maxPerKg).update();
    }

    private void setPatientWeight(UUID patientId, int weightKg) {
        jdbc.sql("""
                update patient set weight_kg = :weight, updated_at = now()
                where tenant_id = cast(:tenant as uuid) and patient_id = :patient
                """).param("tenant", TENANT).param("patient", patientId).param("weight", weightKg).update();
    }

    private void seedRenalContraindicatedMedication(String catalogCode, String ingredientCode, int minimum,
            int maximum, String unit, String contraindicationStage) {
        jdbc.sql("""
                insert into medication_catalog_version(
                  tenant_id, medication_catalog_version_id, catalog_code, drug_code, ingredient_code,
                  display_name, minimum_single_dose, maximum_single_dose, dose_unit,
                  effective_from, release_version, status, renal_contraindication_stage)
                values (cast(:tenant as uuid), gen_random_uuid(), :catalog, :catalog, :ingredient,
                  '肾功能禁忌药品', :minimum, :maximum, :unit, current_date, 'SYNTHETIC-1', 'ACTIVE', :stage)
                """).param("tenant", TENANT).param("catalog", catalogCode).param("ingredient", ingredientCode)
                .param("minimum", minimum).param("maximum", maximum).param("unit", unit)
                .param("stage", contraindicationStage).update();
    }

    private void seedHepaticContraindicatedMedication(String catalogCode, String ingredientCode, int minimum,
            int maximum, String unit, String contraindicationClass) {
        jdbc.sql("""
                insert into medication_catalog_version(
                  tenant_id, medication_catalog_version_id, catalog_code, drug_code, ingredient_code,
                  display_name, minimum_single_dose, maximum_single_dose, dose_unit,
                  effective_from, release_version, status, hepatic_contraindication_class)
                values (cast(:tenant as uuid), gen_random_uuid(), :catalog, :catalog, :ingredient,
                  '肝功能禁忌药品', :minimum, :maximum, :unit, current_date, 'SYNTHETIC-1', 'ACTIVE', :class)
                """).param("tenant", TENANT).param("catalog", catalogCode).param("ingredient", ingredientCode)
                .param("minimum", minimum).param("maximum", maximum).param("unit", unit)
                .param("class", contraindicationClass).update();
    }

    private void setPatientRenalStage(UUID patientId, String stage) {
        jdbc.sql("""
                update patient set renal_impairment_stage = :stage, updated_at = now()
                where tenant_id = cast(:tenant as uuid) and patient_id = :patient
                """).param("tenant", TENANT).param("patient", patientId).param("stage", stage).update();
    }

    private void setPatientHepaticClass(UUID patientId, String childPughClass) {
        jdbc.sql("""
                update patient set hepatic_impairment_class = :class, updated_at = now()
                where tenant_id = cast(:tenant as uuid) and patient_id = :patient
                """).param("tenant", TENANT).param("patient", patientId).param("class", childPughClass).update();
    }

    private void seedAuthorization(UUID patientId, UUID encounterId, String drugCode, String restrictionCode) {
        jdbc.sql("""
                insert into medication_prescribing_authorization(
                  tenant_id, authorization_id, patient_id, encounter_id, drug_code,
                  restriction_code, approved_by, approved_at, status)
                values (cast(:tenant as uuid), gen_random_uuid(), :patient, :encounter, :drug,
                  :restriction, cast(:user as uuid), now(), 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId).param("encounter", encounterId)
                .param("drug", drugCode).param("restriction", restrictionCode).param("user", USER).update();
    }

    private void seedInteraction(String ingredientA, String ingredientB, String severity, String title, String detail) {
        jdbc.sql("""
                insert into medication_interaction(
                  tenant_id, interaction_id, catalog_code, ingredient_a_code, ingredient_b_code,
                  severity, title, detail, evidence_source, effective_from, release_version, status)
                values (cast(:tenant as uuid), gen_random_uuid(), 'INTERACT-SYN', :a, :b,
                  :severity, :title, :detail, 'SYNTHETIC-INTERACTION-RULE', current_date, 'SYNTHETIC-1', 'ACTIVE')
                """).param("tenant", TENANT).param("a", ingredientA).param("b", ingredientB)
                .param("severity", severity).param("title", title).param("detail", detail).update();
    }

    private void seedAllergy(UUID patientId, String ingredientCode) {
        jdbc.sql("""
                insert into patient_allergy(
                  tenant_id, allergy_id, patient_id, substance_code, display_name,
                  verification_status, clinical_status, severity, recorded_by)
                values (cast(:tenant as uuid), gen_random_uuid(), :patient, :ingredient, '青霉素类',
                  'CONFIRMED', 'ACTIVE', 'SEVERE', cast(:user as uuid))
                """).param("tenant", TENANT).param("patient", patientId).param("ingredient", ingredientCode)
                .param("user", USER).update();
    }

    private void clearAllergy(UUID patientId, String ingredientCode) {
        jdbc.sql("""
                update patient_allergy set clinical_status = 'RESOLVED', updated_at = now()
                where tenant_id = cast(:tenant as uuid) and patient_id = :patient and substance_code = :ingredient
                """).param("tenant", TENANT).param("patient", patientId).param("ingredient", ingredientCode).update();
    }

    private long executionTaskCount(String orderId) {
        return jdbc.sql("""
                select count(*) from order_execution_task
                where tenant_id = cast(:tenant as uuid) and order_id = cast(:order_id as uuid)
                """).param("tenant", TENANT).param("order_id", orderId).query(Long.class).single();
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成用药安全患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-MEDICATION', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private Lease issueLease(Context context) throws Exception {
        HttpRequest request = baseRequest("/api/v1/context-leases")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organization_id\":\"" + ORGANIZATION
                        + "\",\"facility_id\":\"" + FACILITY + "\",\"patient_id\":\"" + context.patientId()
                        + "\",\"encounter_id\":\"" + context.encounterId()
                        + "\",\"purpose_code\":\"ORDER_WORKFLOW\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return new Lease(body.path("lease_id").stringValue(), body.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> send(
            String method, String path, String body, Lease lease, Context context, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request = baseRequest(path)
                .header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION)
                .header("X-Facility-Context", FACILITY)
                .header("X-Patient-Context", context.patientId().toString())
                .header("X-Encounter-Context", context.encounterId().toString());
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT)
                .header("X-HaoEMR-User-Id", USER)
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE);
    }

    private record Context(UUID patientId, UUID encounterId) {}
    private record Lease(String id, String watermark) {}
}
