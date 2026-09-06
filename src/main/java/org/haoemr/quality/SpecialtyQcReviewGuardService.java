package org.haoemr.quality;

import java.util.Map;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Verifies that a specialty QC review points at an immutable record in the
 * current patient context and creates the non-clinical remediation work item
 * that follows a failed review.
 */
@Service
public final class SpecialtyQcReviewGuardService {

    private static final Map<String, Map<String, Source>> SOURCES = Map.of(
            "OBSTETRIC", Map.of(
                    "DELIVERY", source("obstetric_delivery_record", "delivery_record_id", false, false),
                    "ANTENATAL_EXAM", source("obstetric_antenatal_exam", "exam_id", true, true)),
            "TCM", Map.of(
                    "HERBAL_PRESCRIPTION", source("tcm_herbal_prescription", "prescription_id", true, true),
                    "FOUR_EXAMINATIONS", source("tcm_four_examinations", "exam_id", true, true)),
            "REPRODUCTIVE", Map.of(
                    "ART_CYCLE", source("art_cycle_record", "cycle_id", true, true),
                    "EMBRYO_TRANSFER", source("art_embryo_transfer_record", "embryo_transfer_id", false, false)),
            "PEDIATRIC", Map.of(
                    "GROWTH_RECORD", source("pediatric_growth_record", "growth_record_id", true, true),
                    "FOLLOWUP_RECORD", source("pediatric_followup_record", "followup_id", true, true)),
            "NEONATAL", Map.of(
                    "WRISTBAND_VERIFICATION", source("neonatal_wristband_verification", "verification_id", false, false),
                    "SCREENING_RECORD", source("neonatal_screening_record", "screening_id", true, true)),
            "MENTAL_HEALTH", Map.of(
                    "CRISIS_HANDOVER", source("mental_health_crisis_handover", "crisis_handover_id", true, false),
                    "CRISIS_FOLLOWUP", source("mental_health_crisis_followup", "followup_id", true, true)),
            "OPHTHALMOLOGY", Map.of(
                    "PREOP_VERIFICATION", source("ophthalmology_preop_verification", "verification_id", true, true),
                    "POSTOP_FOLLOWUP", source("ophthalmology_postop_followup", "followup_id", true, true)),
            "ENT", Map.of(
                    "AIRWAY_RISK_HANDOVER", source("ent_airway_risk_handover", "handover_id", true, true),
                    "RECORD", source("ent_record", "ent_record_id", true, true)),
            "DENTAL", Map.of(
                    "TREATMENT_RECORD", source("dental_treatment_record", "dental_treatment_record_id", true, true),
                    "RECORD", source("dental_record", "dental_record_id", true, true)),
            "DERMATOLOGY", Map.of(
                    "BIOLOGIC_SCREENING", source("dermatology_biologic_screening", "screening_id", true, true),
                    "BIOLOGIC_FOLLOWUP", source("dermatology_biologic_followup", "followup_id", true, true)));

    private static final Map<String, String> QC_ROUTES = Map.of(
            "OBSTETRIC", "#/obgyn-qc",
            "TCM", "#/tcm-qc",
            "REPRODUCTIVE", "#/reproductive-qc",
            "PEDIATRIC", "#/pediatrics-qc",
            "NEONATAL", "#/neonatal-qc",
            "MENTAL_HEALTH", "#/mental-qc",
            "OPHTHALMOLOGY", "#/ophthalmology-qc",
            "ENT", "#/ent-qc",
            "DENTAL", "#/dental-qc",
            "DERMATOLOGY", "#/dermatology-qc");

    private static final Map<String, String> SPECIALTY_LABELS = Map.of(
            "OBSTETRIC", "妇产科",
            "TCM", "中医",
            "REPRODUCTIVE", "生殖医学",
            "PEDIATRIC", "儿科",
            "NEONATAL", "新生儿科",
            "MENTAL_HEALTH", "精神心理科",
            "OPHTHALMOLOGY", "眼科",
            "ENT", "耳鼻咽喉科",
            "DENTAL", "口腔科",
            "DERMATOLOGY", "皮肤科");

    private final JdbcClient jdbc;

    public SpecialtyQcReviewGuardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isReviewable(
            UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId,
            String specialty, String recordType, UUID recordId) {
        Source source = SOURCES.getOrDefault(specialty, Map.of()).get(recordType);
        if (source == null) {
            return false;
        }
        String sql = "select count(*) from " + source.table() + " source"
                + " where source.tenant_id = :tenant and source." + source.idColumn() + " = :record"
                + " and source.patient_id = :patient"
                + (source.hasEncounter() ? " and source.encounter_id = :encounter" : "")
                + (source.hasFacility() ? " and source.facility_id = :facility" : "");
        long count = jdbc.sql(sql).param("tenant", tenantId).param("record", recordId)
                .param("patient", patientId).param("encounter", encounterId).param("facility", facilityId)
                .query(Long.class).single();
        return count == 1;
    }

    public void createRemediationTask(
            ClinicalIdentity identity, UUID reviewId, UUID patientId, UUID encounterId, UUID facilityId,
            String specialty, String recordType, UUID recordId, String defectDescription) {
        UUID taskId = jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level,
                  state, business_state, due_at, source_route)
                values (:tenant, gen_random_uuid(), :patient, :encounter, :facility,
                  'SPECIALTY_QC_REVIEW', :review, 'QC_REMEDIATION', :title, 'HIGH',
                  'PENDING', 'QC_FAIL', now() + interval '24 hours', :route)
                on conflict (tenant_id, source_type, source_id, task_type) do nothing
                returning task_id
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId).param("review", reviewId)
                .param("title", SPECIALTY_LABELS.getOrDefault(specialty, specialty) + "质控不通过整改")
                .param("route", QC_ROUTES.getOrDefault(specialty, "#/clinical-tasks"))
                .query(UUID.class).optional().orElse(null);
        if (taskId == null) {
            return;
        }
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type, previous_state,
                  resulting_state, actor_user_id, reason)
                values (:tenant, gen_random_uuid(), :task, 'CREATED', null,
                  'PENDING', :actor, :reason)
                """).param("tenant", identity.tenantId()).param("task", taskId).param("actor", identity.userId())
                .param("reason", defectDescription).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, gen_random_uuid(), 'CLINICAL_TASK', :task, 1,
                  'ClinicalTaskCreated', 1,
                  jsonb_build_object('source_review_id', :review, 'specialty', :specialty,
                    'reviewed_record_type', :record_type, 'reviewed_record_id', :record))
                """).param("tenant", identity.tenantId()).param("task", taskId).param("review", reviewId)
                .param("specialty", specialty).param("record_type", recordType).param("record", recordId).update();
    }

    private static Source source(String table, String idColumn, boolean hasEncounter, boolean hasFacility) {
        return new Source(table, idColumn, hasEncounter, hasFacility);
    }

    private record Source(String table, String idColumn, boolean hasEncounter, boolean hasFacility) { }
}
