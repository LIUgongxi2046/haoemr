package org.haoemr.nursing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MedicationAdministrationRequestWire;
import org.haoemr.contracts.MedicationAdministrationWire;
import org.haoemr.contracts.NursingCarePlanCompleteRequestWire;
import org.haoemr.contracts.NursingCarePlanRequestWire;
import org.haoemr.contracts.NursingCarePlanWire;
import org.haoemr.contracts.NursingBedsideNoteCreateRequestWire;
import org.haoemr.contracts.NursingBedsideNoteWire;
import org.haoemr.contracts.NursingDischargeClosureRequestWire;
import org.haoemr.contracts.NursingDischargeClosureWire;
import org.haoemr.contracts.ShiftHandoverCompleteRequestWire;
import org.haoemr.contracts.ShiftHandoverCorrectionRequestWire;
import org.haoemr.contracts.ShiftHandoverCreateRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientCreateRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientCorrectionRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientVoidRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientWire;
import org.haoemr.contracts.ShiftHandoverVoidRequestWire;
import org.haoemr.contracts.ShiftHandoverWire;
import org.haoemr.contracts.VitalSignRecordRequestWire;
import org.haoemr.contracts.VitalSignRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class NursingService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    @Value("${haoemr.security.require-clinical-operation-roles:false}")
    boolean requireClinicalOperationRoles;

    NursingService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    VitalSignRecordWire recordVitalSigns(
            ClinicalIdentity identity, String idempotencyKey, VitalSignRecordRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        if (request.recordedAt() == null || request.source() == null) {
            throw invalid("recorded_at and source are required");
        }
        if (request.temperature() == null && request.pulse() == null && request.respiration() == null
                && request.systolicBp() == null && request.diastolicBp() == null && request.spo2() == null) {
            throw invalid("at least one vital sign observation is required");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            String requestHash = sha256(request.patientId() + "|" + request.encounterId() + "|" + request.recordedAt()
                    + "|" + request.source() + "|" + request.temperature() + "|" + request.pulse()
                    + "|" + request.respiration() + "|" + request.systolicBp() + "|" + request.diastolicBp()
                    + "|" + request.spo2());
            beginCommand(identity, "VITAL_SIGN_RECORD", idempotencyKey, requestHash);
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into vital_sign_record(
                      tenant_id, vital_sign_record_id, patient_id, encounter_id, facility_id,
                      admission_id, recorded_at, recorded_by, source, temperature, pulse,
                      respiration, systolic_bp, diastolic_bp, spo2)
                    values (:tenant, :record, :patient, :encounter, :facility,
                      :admission, :recorded_at, :actor, :source, :temperature, :pulse,
                      :respiration, :systolic_bp, :diastolic_bp, :spo2)
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("admission", request.admissionId())
                    .param("recorded_at", request.recordedAt().atOffset(ZoneOffset.UTC))
                    .param("actor", identity.userId()).param("source", request.source().name())
                    .param("temperature", decimal(request.temperature())).param("pulse", request.pulse())
                    .param("respiration", request.respiration()).param("systolic_bp", request.systolicBp())
                    .param("diastolic_bp", request.diastolicBp()).param("spo2", decimal(request.spo2())).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "VITAL_SIGN_RECORDED", "VitalSignRecorded");
            completeCommand(identity, "VITAL_SIGN_RECORD", idempotencyKey, recordId);
            return snapshot(identity.tenantId(), recordId, request.patientId(), request.encounterId());
        });
    }

    List<VitalSignRecordWire> listVitalSigns(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        requireActiveEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select vital_sign_record_id from vital_sign_record
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by recorded_at desc, vital_sign_record_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> snapshot(identity.tenantId(), id, patientId, encounterId)).toList();
    }

    NursingCarePlanWire createCarePlan(
            ClinicalIdentity identity, String idempotencyKey, NursingCarePlanRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        String problem = requireText(request.nursingProblem(), 2, "nursing_problem");
        String goal = requireText(request.goal(), 2, "goal");
        String intervention = requireText(request.intervention(), 2, "intervention");
        if (request.priority() == null) throw invalid("priority is required");
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            String requestHash = sha256(request.patientId() + "|" + request.encounterId() + "|" + problem
                    + "|" + goal + "|" + intervention + "|" + request.priority() + "|" + request.evaluation());
            beginCommand(identity, "NURSING_CARE_PLAN_CREATE", idempotencyKey, requestHash);
            UUID planId = UUID.randomUUID();
            jdbc.sql("""
                    insert into nursing_care_plan(
                      tenant_id, care_plan_id, patient_id, encounter_id, facility_id, admission_id,
                      nursing_problem, goal, intervention, evaluation, priority, status, created_by)
                    values (:tenant, :plan, :patient, :encounter, :facility, :admission,
                      :problem, :goal, :intervention, :evaluation, :priority, 'ACTIVE', :actor)
                    """).param("tenant", identity.tenantId()).param("plan", planId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("admission", request.admissionId())
                    .param("problem", problem).param("goal", goal).param("intervention", intervention)
                    .param("evaluation", blankToNull(request.evaluation()))
                    .param("priority", request.priority().name()).param("actor", identity.userId()).update();
            appendEvidence(identity, request.patientId(), planId, 1, "NURSING_CARE_PLAN_CREATED", "NursingCarePlanCreated");
            completeCommand(identity, "NURSING_CARE_PLAN_CREATE", idempotencyKey, planId);
            return carePlan(identity.tenantId(), planId, request.patientId(), request.encounterId());
        });
    }

    List<NursingCarePlanWire> listCarePlans(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        requireActiveEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select care_plan_id from nursing_care_plan
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by case priority when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end, created_at, care_plan_id
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> carePlan(identity.tenantId(), id, patientId, encounterId)).toList();
    }

    NursingCarePlanWire completeCarePlan(
            ClinicalIdentity identity, String idempotencyKey, UUID carePlanId,
            NursingCarePlanCompleteRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        if (request.disposition() == null) throw invalid("disposition is required");
        return transactions.execute(status -> {
            beginCommand(identity, "NURSING_CARE_PLAN_COMPLETE", idempotencyKey,
                    sha256(carePlanId + "|" + request.expectedRowVersion() + "|" + request.disposition()
                            + "|" + request.evaluation()));
            CarePlanHead current = jdbc.sql("""
                    select status, row_version, patient_id from nursing_care_plan
                    where tenant_id = :tenant and care_plan_id = :plan
                      and patient_id = :patient and encounter_id = :encounter
                      and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("plan", carePlanId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new CarePlanHead(
                            rs.getString("status"), rs.getLong("row_version"), rs.getObject("patient_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new NursingException(
                        "NURSING_CARE_PLAN_VERSION_CONFLICT", 409, "The care plan changed; reload before retrying");
            }
            if (!"ACTIVE".equals(current.status())) {
                throw new NursingException(
                        "NURSING_CARE_PLAN_STATE_INVALID", 409, "Only an active care plan can be closed");
            }
            String targetStatus = request.disposition().name();
            jdbc.sql("""
                    update nursing_care_plan set status = :status, completed_by = :actor,
                      completed_at = case when :status = 'COMPLETED' then now() else null end,
                      evaluation = coalesce(:evaluation, evaluation),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and care_plan_id = :plan and row_version = :expected
                    """).param("status", targetStatus).param("actor", identity.userId())
                    .param("evaluation", blankToNull(request.evaluation()))
                    .param("tenant", identity.tenantId()).param("plan", carePlanId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, current.patientId(), carePlanId, current.rowVersion() + 1,
                    "NURSING_CARE_PLAN_" + targetStatus, "NursingCarePlan" + title(targetStatus));
            completeCommand(identity, "NURSING_CARE_PLAN_COMPLETE", idempotencyKey, carePlanId);
            return carePlan(identity.tenantId(), carePlanId, request.patientId(), request.encounterId());
        });
    }

    MedicationAdministrationWire administerMedication(
            ClinicalIdentity identity, String idempotencyKey, MedicationAdministrationRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        if (request.executionTaskId() == null || request.drugCode() == null || request.doseValue() == null
                || request.doseUnit() == null || request.routeCode() == null || request.administeredAt() == null
                || request.verifiedBy() == null) {
            throw invalid("execution_task_id, drug_code, dose, dose_unit, route_code, administered_at and verified_by are required");
        }
        if (request.verifiedBy().equals(identity.userId())) {
            throw invalid("a second independent verifier is required for bedside administration");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            String requestHash = sha256(request.executionTaskId() + "|" + request.patientId() + "|"
                    + request.drugCode() + "|" + request.doseValue() + "|" + request.doseUnit() + "|"
                    + request.routeCode() + "|" + request.administeredAt() + "|" + request.verifiedBy());
            beginCommand(identity, "MEDICATION_ADMINISTER", idempotencyKey, requestHash);
            ExecutionTarget target = jdbc.sql("""
                    select task.order_id, task.patient_id, task.encounter_id,
                      item.drug_code, item.dose_value, item.dose_unit, item.route_code
                    from order_execution_task task
                    join clinical_order_item item on item.tenant_id = task.tenant_id
                      and item.order_item_id = task.order_item_id
                    where task.tenant_id = :tenant and task.execution_task_id = :task_id
                      and task.patient_id = :patient and task.encounter_id = :encounter
                    """).param("tenant", identity.tenantId()).param("task_id", request.executionTaskId())
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .query((rs, row) -> new ExecutionTarget(
                            rs.getObject("order_id", UUID.class), rs.getObject("patient_id", UUID.class),
                            rs.getObject("encounter_id", UUID.class),
                            rs.getString("drug_code"), rs.getBigDecimal("dose_value"),
                            rs.getString("dose_unit"), rs.getString("route_code")))
                    .optional().orElseThrow(() -> contextDenied());
            requireVerified(target, request);
            UUID administrationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into medication_administration(
                      tenant_id, administration_id, execution_task_id, order_id, patient_id, encounter_id,
                      facility_id, drug_code, dose_value, dose_unit, route_code, administered_at,
                      administered_by, verified_by, verification_note)
                    values (:tenant, :administration, :task_id, :order_id, :patient, :encounter,
                      :facility, :drug_code, :dose_value, :dose_unit, :route_code, :administered_at,
                      :actor, :verifier, :note)
                    """).param("tenant", identity.tenantId()).param("administration", administrationId)
                    .param("task_id", request.executionTaskId()).param("order_id", target.orderId())
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("drug_code", request.drugCode())
                    .param("dose_value", BigDecimal.valueOf(request.doseValue()))
                    .param("dose_unit", request.doseUnit()).param("route_code", request.routeCode())
                    .param("administered_at", request.administeredAt().atOffset(ZoneOffset.UTC))
                    .param("actor", identity.userId()).param("verifier", request.verifiedBy())
                    .param("note", blankToNull(request.verificationNote())).update();
            appendEvidence(identity, request.patientId(), administrationId, 1,
                    "MEDICATION_ADMINISTERED", "MedicationAdministered");
            completeCommand(identity, "MEDICATION_ADMINISTER", idempotencyKey, administrationId);
            return administration(identity.tenantId(), administrationId, request.patientId(), request.encounterId());
        });
    }

    List<MedicationAdministrationWire> listMedicationAdministrations(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        requireActiveEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select administration_id from medication_administration
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by administered_at desc, administration_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> administration(identity.tenantId(), id, patientId, encounterId)).toList();
    }

    private void requireVerified(ExecutionTarget target, MedicationAdministrationRequestWire request) {
        if (!target.drugCode().equals(request.drugCode())) {
            throw new NursingException("FIVE_RIGHTS_DRUG_MISMATCH", 409, "The administered drug does not match the order");
        }
        if (target.doseValue().compareTo(BigDecimal.valueOf(request.doseValue())) != 0
                || !target.doseUnit().equals(request.doseUnit())) {
            throw new NursingException("FIVE_RIGHTS_DOSE_MISMATCH", 409, "The administered dose does not match the order");
        }
        if (!target.routeCode().equals(request.routeCode())) {
            throw new NursingException("FIVE_RIGHTS_ROUTE_MISMATCH", 409, "The administered route does not match the order");
        }
    }

    private MedicationAdministrationWire administration(
            UUID tenantId, UUID administrationId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select administration_id, execution_task_id, order_id, patient_id, encounter_id, facility_id,
                  drug_code, dose_value, dose_unit, route_code, administered_at, administered_by,
                  verified_by, verification_note, row_version
                from medication_administration
                where tenant_id = :tenant and administration_id = :administration
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", tenantId).param("administration", administrationId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new MedicationAdministrationWire(
                        rs.getObject("administration_id", UUID.class), rs.getObject("execution_task_id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("drug_code"), nullableDouble(rs.getBigDecimal("dose_value")),
                        rs.getString("dose_unit"), rs.getString("route_code"),
                        rs.getObject("administered_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("administered_by", UUID.class), rs.getObject("verified_by", UUID.class),
                        rs.getString("verification_note"), rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    ShiftHandoverWire createHandover(
            ClinicalIdentity identity, String idempotencyKey, ShiftHandoverCreateRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        String summary = requireText(request.handoverSummary(), 4, "handover_summary");
        if (request.shiftFrom() == null || request.shiftTo() == null || request.shiftTo().isBefore(request.shiftFrom())) {
            throw invalid("shift_to must be after shift_from");
        }
        if (request.incomingUserId() == null || request.incomingUserId().equals(identity.userId())) {
            throw invalid("a different incoming nurse is required");
        }
        return transactions.execute(status -> {
            String requestHash = sha256(request.wardId() + "|" + request.shiftFrom() + "|" + request.shiftTo()
                    + "|" + request.incomingUserId() + "|" + summary);
            beginCommand(identity, "SHIFT_HANDOVER_CREATE", idempotencyKey, requestHash);
            UUID handoverId = UUID.randomUUID();
            jdbc.sql("""
                    insert into shift_handover(
                      tenant_id, handover_id, ward_id, facility_id, shift_from, shift_to,
                      outgoing_user_id, incoming_user_id, handover_summary, status)
                    values (:tenant, :handover, :ward, :facility, :shift_from, :shift_to,
                      :outgoing, :incoming, :summary, 'DRAFT')
                    """).param("tenant", identity.tenantId()).param("handover", handoverId)
                    .param("ward", request.wardId()).param("facility", request.facilityId())
                    .param("shift_from", request.shiftFrom().atOffset(ZoneOffset.UTC))
                    .param("shift_to", request.shiftTo().atOffset(ZoneOffset.UTC))
                    .param("outgoing", identity.userId()).param("incoming", request.incomingUserId())
                    .param("summary", summary).update();
            appendEvidence(identity, null, handoverId, 1, "SHIFT_HANDOVER_CREATED", "ShiftHandoverCreated");
            completeCommand(identity, "SHIFT_HANDOVER_CREATE", idempotencyKey, handoverId);
            return handover(identity.tenantId(), handoverId, request.facilityId());
        });
    }

    List<ShiftHandoverWire> listHandovers(ClinicalIdentity identity, UUID facilityId, UUID wardId) {
        return jdbc.sql("""
                select handover_id from shift_handover
                where tenant_id = :tenant and facility_id = :facility and ward_id = :ward
                order by shift_to desc, handover_id desc limit 200
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .param("ward", wardId).query(UUID.class).list().stream()
                .map(id -> handover(identity.tenantId(), id, facilityId)).toList();
    }

    ShiftHandoverPatientWire addHandoverPatient(
            ClinicalIdentity identity, String idempotencyKey, ShiftHandoverPatientCreateRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        String summary = requireText(request.summary(), 2, "summary");
        if (request.riskFlag() == null) throw invalid("risk_flag is required");
        return transactions.execute(status -> {
            beginCommand(identity, "SHIFT_HANDOVER_PATIENT_CREATE", idempotencyKey,
                    sha256(request.handoverId() + "|" + request.patientId() + "|" + summary + "|" + request.riskFlag()));
            String handoverStatus = jdbc.sql("""
                    select status from shift_handover
                    where tenant_id = :tenant and handover_id = :handover
                      and ward_id = :ward and facility_id = :facility
                      and voided_at is null
                    """).param("tenant", identity.tenantId()).param("handover", request.handoverId())
                    .param("ward", request.wardId()).param("facility", request.facilityId())
                    .query(String.class).optional().orElseThrow(() -> contextDenied());
            if (!"DRAFT".equals(handoverStatus)) {
                throw new NursingException(
                        "SHIFT_HANDOVER_STATE_INVALID", 409, "Only a draft handover accepts patient items");
            }
            long admissionCount = jdbc.sql("""
                    select count(*) from inpatient_admission
                    where tenant_id = :tenant and patient_id = :patient and ward_id = :ward
                      and status in ('ADMITTED', 'TRANSFER_PENDING', 'DISCHARGE_PENDING')
                    """).param("tenant", identity.tenantId()).param("patient", request.patientId())
                    .param("ward", request.wardId()).query(Long.class).single();
            if (admissionCount != 1) {
                throw new NursingException(
                        "SHIFT_HANDOVER_PATIENT_NOT_ADMITTED", 409, "The patient is not admitted to this ward");
            }
            UUID itemId = UUID.randomUUID();
            jdbc.sql("""
                    insert into shift_handover_patient(
                      tenant_id, shift_handover_patient_id, handover_id, patient_id, summary, risk_flag)
                    values (:tenant, :item, :handover, :patient, :summary, :risk)
                    """).param("tenant", identity.tenantId()).param("item", itemId)
                    .param("handover", request.handoverId()).param("patient", request.patientId())
                    .param("summary", summary).param("risk", request.riskFlag()).update();
            appendEvidence(identity, request.patientId(), itemId, 1,
                    "SHIFT_HANDOVER_PATIENT_ADDED", "ShiftHandoverPatientAdded");
            completeCommand(identity, "SHIFT_HANDOVER_PATIENT_CREATE", idempotencyKey, itemId);
            return handoverPatient(identity.tenantId(), itemId, request.handoverId());
        });
    }

    List<ShiftHandoverPatientWire> listHandoverPatients(ClinicalIdentity identity, UUID handoverId) {
        return jdbc.sql("""
                select shift_handover_patient_id from shift_handover_patient
                where tenant_id = :tenant and handover_id = :handover
                  and voided_at is null
                order by risk_flag desc, created_at desc, shift_handover_patient_id desc limit 500
                """).param("tenant", identity.tenantId()).param("handover", handoverId)
                .query(UUID.class).list().stream()
                .map(id -> handoverPatient(identity.tenantId(), id, handoverId)).toList();
    }

    ShiftHandoverPatientWire correctHandoverPatient(
            ClinicalIdentity identity, String idempotencyKey, UUID itemId,
            ShiftHandoverPatientCorrectionRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        String summary = requireText(request.summary(), 2, "summary");
        String reason = requireText(request.reason(), 4, "reason");
        if (request.riskFlag() == null) throw invalid("risk_flag is required");
        return transactions.execute(status -> {
            beginCommand(identity, "SHIFT_HANDOVER_PATIENT_CORRECT", idempotencyKey,
                    sha256(itemId + "|" + request.expectedRowVersion() + "|" + summary + "|" + request.riskFlag()));
            HandoverPatientHead current = lockHandoverPatient(identity.tenantId(), itemId, request);
            requireHandoverPatientMutable(current, request.expectedRowVersion());
            UUID replacementId = UUID.randomUUID();
            jdbc.sql("""
                    update shift_handover_patient set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1
                    where tenant_id = :tenant and shift_handover_patient_id = :item and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("item", itemId).param("expected", current.rowVersion()).update();
            jdbc.sql("""
                    insert into shift_handover_patient(
                      tenant_id, shift_handover_patient_id, handover_id, patient_id,
                      summary, risk_flag, supersedes_patient_item_id)
                    values (:tenant, :item, :handover, :patient, :summary, :risk, :supersedes)
                    """).param("tenant", identity.tenantId()).param("item", replacementId)
                    .param("handover", request.handoverId()).param("patient", request.patientId())
                    .param("summary", summary).param("risk", request.riskFlag()).param("supersedes", itemId).update();
            appendEvidence(identity, request.patientId(), itemId, current.rowVersion() + 1,
                    "SHIFT_HANDOVER_PATIENT_SUPERSEDED", "ShiftHandoverPatientSuperseded");
            appendEvidence(identity, request.patientId(), replacementId, 1,
                    "SHIFT_HANDOVER_PATIENT_CORRECTED", "ShiftHandoverPatientCorrected");
            completeCommand(identity, "SHIFT_HANDOVER_PATIENT_CORRECT", idempotencyKey, replacementId);
            return handoverPatient(identity.tenantId(), replacementId, request.handoverId());
        });
    }

    ShiftHandoverPatientWire voidHandoverPatient(
            ClinicalIdentity identity, String idempotencyKey, UUID itemId,
            ShiftHandoverPatientVoidRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "SHIFT_HANDOVER_PATIENT_VOID", idempotencyKey,
                    sha256(itemId + "|" + request.expectedRowVersion() + "|" + reason));
            HandoverPatientHead current = lockHandoverPatient(identity.tenantId(), itemId, request);
            requireHandoverPatientMutable(current, request.expectedRowVersion());
            jdbc.sql("""
                    update shift_handover_patient set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1
                    where tenant_id = :tenant and shift_handover_patient_id = :item and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("item", itemId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), itemId, current.rowVersion() + 1,
                    "SHIFT_HANDOVER_PATIENT_VOIDED", "ShiftHandoverPatientVoided");
            completeCommand(identity, "SHIFT_HANDOVER_PATIENT_VOID", idempotencyKey, itemId);
            return handoverPatient(identity.tenantId(), itemId, request.handoverId());
        });
    }

    private HandoverPatientHead lockHandoverPatient(
            UUID tenantId, UUID itemId, ShiftHandoverPatientVoidRequestWire request) {
        return lockHandoverPatient(tenantId, itemId, request.handoverId(), request.patientId(),
                request.wardId(), request.facilityId());
    }

    private HandoverPatientHead lockHandoverPatient(
            UUID tenantId, UUID itemId, ShiftHandoverPatientCorrectionRequestWire request) {
        return lockHandoverPatient(tenantId, itemId, request.handoverId(), request.patientId(),
                request.wardId(), request.facilityId());
    }

    private HandoverPatientHead lockHandoverPatient(
            UUID tenantId, UUID itemId, UUID handoverId, UUID patientId, UUID wardId, UUID facilityId) {
        return jdbc.sql("""
                select p.row_version, p.voided_at, h.status, h.voided_at handover_voided_at
                from shift_handover_patient p
                join shift_handover h on h.tenant_id = p.tenant_id and h.handover_id = p.handover_id
                where p.tenant_id = :tenant and p.shift_handover_patient_id = :item
                  and p.handover_id = :handover and p.patient_id = :patient
                  and h.ward_id = :ward and h.facility_id = :facility for update of p
                """).param("tenant", tenantId).param("item", itemId).param("handover", handoverId)
                .param("patient", patientId).param("ward", wardId).param("facility", facilityId)
                .query((rs, row) -> new HandoverPatientHead(rs.getLong("row_version"),
                        rs.getObject("voided_at", OffsetDateTime.class), rs.getString("status"),
                        rs.getObject("handover_voided_at", OffsetDateTime.class)))
                .optional().orElseThrow(NursingService::contextDenied);
    }

    private static void requireHandoverPatientMutable(HandoverPatientHead current, Long expectedRowVersion) {
        if (expectedRowVersion == null || current.rowVersion() != expectedRowVersion) {
            throw new NursingException("SHIFT_HANDOVER_PATIENT_VERSION_CONFLICT", 409,
                    "The handover patient item changed; reload before retrying");
        }
        if (current.voidedAt() != null || current.handoverVoidedAt() != null || !"DRAFT".equals(current.status())) {
            throw new NursingException("SHIFT_HANDOVER_PATIENT_STATE_INVALID", 409,
                    "Only an active item on a draft handover can be changed");
        }
    }

    private ShiftHandoverPatientWire handoverPatient(UUID tenantId, UUID itemId, UUID handoverId) {
        return jdbc.sql("""
                select shift_handover_patient_id, handover_id, patient_id, summary, risk_flag, row_version
                from shift_handover_patient
                where tenant_id = :tenant and shift_handover_patient_id = :item and handover_id = :handover
                """).param("tenant", tenantId).param("item", itemId).param("handover", handoverId)
                .query((rs, row) -> new ShiftHandoverPatientWire(
                        rs.getObject("shift_handover_patient_id", UUID.class),
                        rs.getObject("handover_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("summary"), rs.getBoolean("risk_flag"), rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    NursingDischargeClosureWire closeNursingDischarge(
            ClinicalIdentity identity, String idempotencyKey, NursingDischargeClosureRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "NURSING_DISCHARGE_CLOSE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId()));
            long openCarePlans = jdbc.sql("""
                    select count(*) from nursing_care_plan
                    where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter
                      and status = 'ACTIVE'
                    """).param("tenant", identity.tenantId()).param("patient", request.patientId())
                    .param("encounter", request.encounterId()).query(Long.class).single();
            if (openCarePlans != 0) {
                throw new NursingException(
                        "NURSING_CARE_PLANS_OPEN", 409,
                        "Active nursing care plans must be completed or discontinued before discharge closure");
            }
            long openMedicationTasks = jdbc.sql("""
                    select count(*) from order_execution_task t
                    join clinical_order_item i on i.tenant_id = t.tenant_id and i.order_item_id = t.order_item_id
                    where t.tenant_id = :tenant and t.encounter_id = :encounter
                      and i.item_type = 'MEDICATION'
                      and t.task_state in ('PENDING', 'ACCEPTED', 'IN_PROGRESS', 'PARTIAL')
                    """).param("tenant", identity.tenantId()).param("encounter", request.encounterId())
                    .query(Long.class).single();
            if (openMedicationTasks != 0) {
                throw new NursingException(
                        "MEDICATION_TASKS_OPEN", 409,
                        "Open medication execution tasks must be completed before discharge closure");
            }
            long openHandovers = jdbc.sql("""
                    select count(*) from shift_handover_patient p
                    join shift_handover h on h.tenant_id = p.tenant_id and h.handover_id = p.handover_id
                    where p.tenant_id = :tenant and p.patient_id = :patient
                      and h.status = 'DRAFT' and h.voided_at is null
                      and p.voided_at is null
                    """).param("tenant", identity.tenantId()).param("patient", request.patientId())
                    .query(Long.class).single();
            if (openHandovers != 0) {
                throw new NursingException(
                        "SHIFT_HANDOVERS_OPEN", 409,
                        "Incomplete shift handovers for the patient must be completed before discharge closure");
            }
            UUID closureId = UUID.randomUUID();
            jdbc.sql("""
                    insert into nursing_discharge_closure(
                      tenant_id, closure_id, patient_id, encounter_id, facility_id, closed_by)
                    values (:tenant, :closure, :patient, :encounter, :facility, :closed_by)
                    """).param("tenant", identity.tenantId()).param("closure", closureId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("closed_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), closureId, 1,
                    "NURSING_DISCHARGE_CLOSED", "NursingDischargeClosed");
            completeCommand(identity, "NURSING_DISCHARGE_CLOSE", idempotencyKey, closureId);
            return closure(identity.tenantId(), closureId, request.patientId());
        });
    }

    List<NursingDischargeClosureWire> listNursingDischargeClosures(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select closure_id from nursing_discharge_closure
                where tenant_id = :tenant and patient_id = :patient
                order by closed_at desc, closure_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> closure(identity.tenantId(), id, patientId)).toList();
    }

    private NursingDischargeClosureWire closure(UUID tenantId, UUID closureId, UUID patientId) {
        return jdbc.sql("""
                select closure_id, patient_id, encounter_id, facility_id, closed_by, closed_at
                from nursing_discharge_closure
                where tenant_id = :tenant and closure_id = :closure and patient_id = :patient
                """).param("tenant", tenantId).param("closure", closureId).param("patient", patientId)
                .query((rs, row) -> new NursingDischargeClosureWire(
                        rs.getObject("closure_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("closed_by", UUID.class),
                        rs.getObject("closed_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> contextDenied());
    }

    NursingBedsideNoteWire syncBedsideNote(
            ClinicalIdentity identity, String idempotencyKey, NursingBedsideNoteCreateRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        if (request.noteType() == null || request.recordedAt() == null || request.syncedAt() == null) {
            throw invalid("note_type, recorded_at and synced_at are required");
        }
        String deviceId = requireText(request.deviceId(), 2, "device_id");
        String content = requireText(request.content(), 2, "content");
        if (request.recordedAt().isAfter(request.syncedAt())) {
            throw new NursingException(
                    "NURSING_BEDSIDE_NOTE_TIME_ORDER_INVALID", 400,
                    "recorded_at must not be later than synced_at for an offline bedside note");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            String requestHash = sha256(request.patientId() + "|" + request.encounterId() + "|"
                    + request.noteType() + "|" + request.recordedAt() + "|" + request.syncedAt()
                    + "|" + request.deviceId() + "|" + request.content());
            beginCommand(identity, "NURSING_BEDSIDE_NOTE_SYNC", idempotencyKey, requestHash);
            UUID noteId = UUID.randomUUID();
            jdbc.sql("""
                    insert into nursing_bedside_note(
                      tenant_id, note_id, patient_id, encounter_id, facility_id,
                      note_type, recorded_at, synced_at, device_id, content)
                    values (:tenant, :note, :patient, :encounter, :facility,
                      :note_type, :recorded_at, :synced_at, :device, :content)
                    """).param("tenant", identity.tenantId()).param("note", noteId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("note_type", request.noteType().name())
                    .param("recorded_at", request.recordedAt().atOffset(ZoneOffset.UTC))
                    .param("synced_at", request.syncedAt().atOffset(ZoneOffset.UTC))
                    .param("device", deviceId).param("content", content).update();
            appendEvidence(identity, request.patientId(), noteId, 1,
                    "NURSING_BEDSIDE_NOTE_SYNCED", "NursingBedsideNoteSynced");
            completeCommand(identity, "NURSING_BEDSIDE_NOTE_SYNC", idempotencyKey, noteId);
            return bedsideNote(identity.tenantId(), noteId, request.patientId());
        });
    }

    List<NursingBedsideNoteWire> listBedsideNotes(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select note_id from nursing_bedside_note
                where tenant_id = :tenant and patient_id = :patient
                order by recorded_at desc, note_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> bedsideNote(identity.tenantId(), id, patientId)).toList();
    }

    private NursingBedsideNoteWire bedsideNote(UUID tenantId, UUID noteId, UUID patientId) {
        return jdbc.sql("""
                select note_id, patient_id, encounter_id, facility_id, note_type,
                  recorded_at, synced_at, device_id, content, row_version
                from nursing_bedside_note
                where tenant_id = :tenant and note_id = :note and patient_id = :patient
                """).param("tenant", tenantId).param("note", noteId).param("patient", patientId)
                .query((rs, row) -> new NursingBedsideNoteWire(
                        rs.getObject("note_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        NursingBedsideNoteWire.NoteTypeValue.valueOf(rs.getString("note_type")),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("synced_at", OffsetDateTime.class).toInstant(),
                        rs.getString("device_id"), rs.getString("content"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    ShiftHandoverWire completeHandover(
            ClinicalIdentity identity, String idempotencyKey, UUID handoverId,
            ShiftHandoverCompleteRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "SHIFT_HANDOVER_COMPLETE", idempotencyKey,
                    sha256(handoverId + "|" + request.expectedRowVersion()));
            HandoverHead current = jdbc.sql("""
                    select status, row_version, incoming_user_id, voided_at from shift_handover
                    where tenant_id = :tenant and handover_id = :handover
                      and ward_id = :ward and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("handover", handoverId)
                    .param("ward", request.wardId()).param("facility", request.facilityId())
                    .query((rs, row) -> new HandoverHead(
                            rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("incoming_user_id", UUID.class),
                            rs.getObject("voided_at", OffsetDateTime.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new NursingException(
                        "SHIFT_HANDOVER_VERSION_CONFLICT", 409, "The handover changed; reload before retrying");
            }
            if (!"DRAFT".equals(current.status())) {
                throw new NursingException(
                        "SHIFT_HANDOVER_STATE_INVALID", 409, "Only a draft handover can be completed");
            }
            if (current.voidedAt() != null) {
                throw new NursingException(
                        "SHIFT_HANDOVER_STATE_INVALID", 409, "A voided handover cannot be completed");
            }
            if (!identity.userId().equals(current.incomingUserId())) {
                throw new NursingException(
                        "SHIFT_HANDOVER_INCOMING_REQUIRED", 403, "Only the incoming nurse can confirm the handover");
            }
            jdbc.sql("""
                    update shift_handover set status = 'COMPLETED', completed_at = now(),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and handover_id = :handover and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("handover", handoverId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, null, handoverId, current.rowVersion() + 1,
                    "SHIFT_HANDOVER_COMPLETED", "ShiftHandoverCompleted");
            completeCommand(identity, "SHIFT_HANDOVER_COMPLETE", idempotencyKey, handoverId);
            return handover(identity.tenantId(), handoverId, request.facilityId());
        });
    }

    ShiftHandoverWire correctHandover(
            ClinicalIdentity identity, String idempotencyKey, UUID handoverId,
            ShiftHandoverCorrectionRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        String summary = requireText(request.handoverSummary(), 4, "handover_summary");
        String reason = requireText(request.reason(), 4, "reason");
        if (request.shiftFrom() == null || request.shiftTo() == null || !request.shiftTo().isAfter(request.shiftFrom())) {
            throw invalid("shift_to must be after shift_from");
        }
        if (request.incomingUserId() == null || request.incomingUserId().equals(identity.userId())) {
            throw invalid("a different incoming nurse is required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "SHIFT_HANDOVER_CORRECT", idempotencyKey,
                    sha256(handoverId + "|" + request.expectedRowVersion() + "|" + summary));
            HandoverHead current = jdbc.sql("""
                    select status, row_version, incoming_user_id, voided_at from shift_handover
                    where tenant_id = :tenant and handover_id = :handover
                      and ward_id = :ward and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("handover", handoverId)
                    .param("ward", request.wardId()).param("facility", request.facilityId())
                    .query((rs, row) -> new HandoverHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("incoming_user_id", UUID.class),
                            rs.getObject("voided_at", OffsetDateTime.class)))
                    .optional().orElseThrow(NursingService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new NursingException("SHIFT_HANDOVER_VERSION_CONFLICT", 409,
                        "The handover changed; reload before retrying");
            }
            if (!"DRAFT".equals(current.status()) || current.voidedAt() != null) {
                throw new NursingException("SHIFT_HANDOVER_STATE_INVALID", 409,
                        "Only an active draft handover can be corrected");
            }
            UUID replacementId = UUID.randomUUID();
            jdbc.sql("""
                    insert into shift_handover(
                      tenant_id, handover_id, ward_id, facility_id, shift_from, shift_to,
                      outgoing_user_id, incoming_user_id, handover_summary, status, supersedes_handover_id)
                    values (:tenant, :handover, :ward, :facility, :shift_from, :shift_to,
                      :outgoing, :incoming, :summary, 'DRAFT', :supersedes)
                    """).param("tenant", identity.tenantId()).param("handover", replacementId)
                    .param("ward", request.wardId()).param("facility", request.facilityId())
                    .param("shift_from", request.shiftFrom().atOffset(ZoneOffset.UTC))
                    .param("shift_to", request.shiftTo().atOffset(ZoneOffset.UTC))
                    .param("outgoing", identity.userId()).param("incoming", request.incomingUserId())
                    .param("summary", summary).param("supersedes", handoverId).update();
            jdbc.sql("""
                    insert into shift_handover_patient(
                      tenant_id, shift_handover_patient_id, handover_id, patient_id,
                      summary, risk_flag, supersedes_patient_item_id)
                    select tenant_id, gen_random_uuid(), :replacement, patient_id,
                      summary, risk_flag, shift_handover_patient_id
                    from shift_handover_patient
                    where tenant_id = :tenant and handover_id = :original and voided_at is null
                    """).param("replacement", replacementId).param("tenant", identity.tenantId())
                    .param("original", handoverId).update();
            jdbc.sql("""
                    update shift_handover_patient set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1
                    where tenant_id = :tenant and handover_id = :handover and voided_at is null
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("handover", handoverId).update();
            jdbc.sql("""
                    update shift_handover set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and handover_id = :handover and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("handover", handoverId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, null, handoverId, current.rowVersion() + 1,
                    "SHIFT_HANDOVER_SUPERSEDED", "ShiftHandoverSuperseded");
            appendEvidence(identity, null, replacementId, 1,
                    "SHIFT_HANDOVER_CORRECTED", "ShiftHandoverCorrected");
            completeCommand(identity, "SHIFT_HANDOVER_CORRECT", idempotencyKey, replacementId);
            return handover(identity.tenantId(), replacementId, request.facilityId());
        });
    }

    ShiftHandoverWire voidHandover(
            ClinicalIdentity identity, String idempotencyKey, UUID handoverId,
            ShiftHandoverVoidRequestWire request) {
        requireNursingRole(identity, request.facilityId());
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "SHIFT_HANDOVER_VOID", idempotencyKey,
                    sha256(handoverId + "|" + request.expectedRowVersion() + "|" + reason));
            HandoverHead current = jdbc.sql("""
                    select status, row_version, incoming_user_id, voided_at from shift_handover
                    where tenant_id = :tenant and handover_id = :handover
                      and ward_id = :ward and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("handover", handoverId)
                    .param("ward", request.wardId()).param("facility", request.facilityId())
                    .query((rs, row) -> new HandoverHead(
                            rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("incoming_user_id", UUID.class),
                            rs.getObject("voided_at", OffsetDateTime.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new NursingException(
                        "SHIFT_HANDOVER_VERSION_CONFLICT", 409, "The handover changed; reload before retrying");
            }
            if (current.voidedAt() != null) {
                throw new NursingException(
                        "SHIFT_HANDOVER_STATE_INVALID", 409, "The handover is already voided");
            }
            jdbc.sql("""
                    update shift_handover set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and handover_id = :handover and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("handover", handoverId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, null, handoverId, current.rowVersion() + 1,
                    "SHIFT_HANDOVER_VOIDED", "ShiftHandoverVoided");
            completeCommand(identity, "SHIFT_HANDOVER_VOID", idempotencyKey, handoverId);
            return handover(identity.tenantId(), handoverId, request.facilityId());
        });
    }

    private ShiftHandoverWire handover(UUID tenantId, UUID handoverId, UUID facilityId) {
        return jdbc.sql("""
                select handover_id, ward_id, facility_id, shift_from, shift_to,
                  outgoing_user_id, incoming_user_id, handover_summary, status, completed_at,
                  voided_at, void_reason, row_version
                from shift_handover
                where tenant_id = :tenant and handover_id = :handover and facility_id = :facility
                """).param("tenant", tenantId).param("handover", handoverId).param("facility", facilityId)
                .query((rs, row) -> new ShiftHandoverWire(
                        rs.getObject("handover_id", UUID.class), rs.getObject("ward_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getObject("shift_from", OffsetDateTime.class).toInstant(),
                        rs.getObject("shift_to", OffsetDateTime.class).toInstant(),
                        rs.getObject("outgoing_user_id", UUID.class), rs.getObject("incoming_user_id", UUID.class),
                        rs.getString("handover_summary"),
                        ShiftHandoverWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("completed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("voided_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("voided_at", OffsetDateTime.class).toInstant(),
                        rs.getString("void_reason"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private NursingCarePlanWire carePlan(UUID tenantId, UUID planId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select care_plan_id, patient_id, encounter_id, facility_id, admission_id,
                  nursing_problem, goal, intervention, evaluation, priority, status,
                  created_by, completed_by, completed_at, row_version
                from nursing_care_plan
                where tenant_id = :tenant and care_plan_id = :plan
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", tenantId).param("plan", planId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new NursingCarePlanWire(
                        rs.getObject("care_plan_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("admission_id", UUID.class), rs.getString("nursing_problem"),
                        rs.getString("goal"), rs.getString("intervention"), rs.getString("evaluation"),
                        NursingCarePlanWire.PriorityValue.valueOf(rs.getString("priority")),
                        NursingCarePlanWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("created_by", UUID.class), rs.getObject("completed_by", UUID.class),
                        rs.getObject("completed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private VitalSignRecordWire snapshot(
            UUID tenantId, UUID recordId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select vital_sign_record_id, patient_id, encounter_id, facility_id, admission_id,
                  recorded_at, recorded_by, source, temperature, pulse, respiration,
                  systolic_bp, diastolic_bp, spo2, row_version
                from vital_sign_record
                where tenant_id = :tenant and vital_sign_record_id = :record
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", tenantId).param("record", recordId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new VitalSignRecordWire(
                        rs.getObject("vital_sign_record_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("admission_id", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("recorded_by", UUID.class),
                        VitalSignRecordWire.SourceValue.valueOf(rs.getString("source")),
                        nullableDouble(rs.getBigDecimal("temperature")), rs.getObject("pulse", Integer.class),
                        rs.getObject("respiration", Integer.class), rs.getObject("systolic_bp", Integer.class),
                        rs.getObject("diastolic_bp", Integer.class), nullableDouble(rs.getBigDecimal("spo2")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private void requireNursingRole(ClinicalIdentity identity, UUID facilityId) {
        if (!requireClinicalOperationRoles) return;
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new NursingException(
                    "NURSING_ROLE_REQUIRED", 403, "An active registered nurse role is required");
        }
        long authorized = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:assignments)
                  and role_code in ('REGISTERED_NURSE', 'NURSE_MANAGER')
                  and (facility_id is null or facility_id = :facility)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("facility", facilityId)
                .query(Long.class).single();
        if (authorized == 0) {
            throw new NursingException(
                    "NURSING_ROLE_REQUIRED", 403, "An active registered nurse role is required");
        }
    }

    private void requireActiveEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter
                where tenant_id = :tenant and encounter_id = :encounter and patient_id = :patient
                  and facility_id = :facility and status in ('ARRIVED', 'IN_PROGRESS', 'SUSPENDED')
                """).param("tenant", tenantId).param("encounter", encounterId).param("patient", patientId)
                .param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new NursingException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new NursingException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID recordId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", recordId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID recordId, long version,
            String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + recordId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'VITAL_SIGN', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'VITAL_SIGN', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static Double nullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String title(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static NursingException invalid(String message) {
        return new NursingException("VITAL_SIGN_REQUEST_INVALID", 400, message);
    }

    static NursingException contextDenied() {
        return new NursingException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested vital sign context is not permitted");
    }

    private record CarePlanHead(String status, long rowVersion, UUID patientId) {}
    private record ExecutionTarget(
            UUID orderId, UUID patientId, UUID encounterId, String drugCode,
            BigDecimal doseValue, String doseUnit, String routeCode) {}
    private record HandoverHead(String status, long rowVersion, UUID incomingUserId, OffsetDateTime voidedAt) {}
    private record HandoverPatientHead(
            long rowVersion, OffsetDateTime voidedAt, String status, OffsetDateTime handoverVoidedAt) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
