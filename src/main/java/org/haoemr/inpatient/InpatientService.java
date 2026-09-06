package org.haoemr.inpatient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.haoemr.clinical.ClinicalDocumentGateway;
import org.haoemr.clinical.ClinicalDocumentSigned;
import org.haoemr.contracts.DocumentVersionWire;
import org.haoemr.contracts.InpatientAdmissionCreateRequestWire;
import org.haoemr.contracts.InpatientAdmissionWire;
import org.haoemr.contracts.InpatientBedBoardItemWire;
import org.haoemr.contracts.InpatientClinicalEventCreateRequestWire;
import org.haoemr.contracts.InpatientClinicalEventWire;
import org.haoemr.contracts.InpatientDocumentTaskWire;
import org.haoemr.contracts.InpatientDocumentTaskCreateRequestWire;
import org.haoemr.contracts.InpatientDocumentRuleWire;
import org.haoemr.contracts.InpatientOverviewWire;
import org.haoemr.contracts.InpatientDocumentStartRequestWire;
import org.haoemr.contracts.InpatientDischargeRequestWire;
import org.haoemr.contracts.InpatientTransferRequestWire;
import org.haoemr.contracts.InpatientWorklistItemWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class InpatientService {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ClinicalDocumentGateway clinicalDocuments;

    InpatientService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ClinicalDocumentGateway clinicalDocuments) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clinicalDocuments = clinicalDocuments;
    }

    DocumentVersionWire startDocument(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID taskId,
            InpatientDocumentStartRequestWire request) {
        return transactions.execute(status -> {
            DocumentTaskView task = jdbc.sql("""
                    select task.task_id, task.admission_id, task.document_type_code, task.task_state,
                      task.working_document_id, task.row_version, admission.patient_id,
                      admission.encounter_id, admission.facility_id, admission.ward_id,
                      admission.status as admission_status, task.required_signature_level
                    from inpatient_document_task task
                    join inpatient_admission admission on admission.tenant_id = task.tenant_id
                      and admission.admission_id = task.admission_id
                    where task.tenant_id = :tenant and task.task_id = :task
                      and task.admission_id = :admission and admission.patient_id = :patient
                      and admission.encounter_id = :encounter and admission.facility_id = :facility
                    for update of task, admission
                    """).param("tenant", identity.tenantId()).param("task", taskId)
                    .param("admission", request.admissionId()).param("patient", request.patientId())
                    .param("encounter", request.encounterId()).param("facility", request.facilityId())
                    .query((rs, row) -> new DocumentTaskView(
                            rs.getObject("task_id", UUID.class), rs.getObject("admission_id", UUID.class),
                            rs.getString("document_type_code"), rs.getString("task_state"),
                            rs.getObject("working_document_id", UUID.class), rs.getLong("row_version"),
                            rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                            rs.getObject("facility_id", UUID.class), rs.getObject("ward_id", UUID.class),
                            rs.getString("admission_status"), rs.getString("required_signature_level")))
                    .optional().orElseThrow(InpatientService::contextDenied);
            requireWardScope(identity, task.facilityId(), task.wardId());
            if (!List.of("ADMITTED", "TRANSFER_PENDING", "DISCHARGE_PENDING").contains(task.admissionStatus())) {
                throw new InpatientException("ADMISSION_NOT_ACTIVE", 409, "The admission is not active");
            }
            if (task.rowVersion() != request.expectedTaskRowVersion()) {
                throw new InpatientException("TASK_VERSION_CONFLICT", 409, "The document task changed; reload before continuing");
            }
            if (task.workingDocumentId() != null || !"PENDING".equals(task.taskState())) {
                throw new InpatientException("TASK_ALREADY_STARTED", 409, "This inpatient document task already has a working document");
            }
            beginTaskCommand(identity, idempotencyKey, sha256(taskId + "|" + task.rowVersion()));
            DocumentVersionWire document = clinicalDocuments.createDocument(
                    identity, idempotencyKey, request.patientId(), request.encounterId(),
                    task.documentTypeCode(), request.sections());
            clinicalDocuments.configureSignaturePolicy(
                    identity, document.documentId(), document.documentVersionId(), task.requiredSignatureLevel());
            int updated = jdbc.sql("""
                    update inpatient_document_task
                    set task_state = 'IN_PROGRESS', working_document_id = :document,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and task_id = :task and row_version = :expected
                      and task_state = 'PENDING' and working_document_id is null
                    """).param("document", document.documentId()).param("tenant", identity.tenantId())
                    .param("task", taskId).param("expected", request.expectedTaskRowVersion()).update();
            if (updated != 1) {
                throw new InpatientException("TASK_VERSION_CONFLICT", 409, "The document task changed; reload before continuing");
            }
            appendTaskStartedEvidence(identity, task, document.documentId());
            completeTaskCommand(identity, idempotencyKey, document.documentId());
            return document;
        });
    }

    InpatientDocumentTaskWire createDocumentTaskFromRule(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID admissionId,
            InpatientDocumentTaskCreateRequestWire request) {
        if (request.ruleCode() == null || request.ruleCode().isBlank()
                || request.occurrenceKey() == null || request.occurrenceKey().isBlank()
                || request.occurrenceKey().length() > 128 || request.eventOccurredAt() == null) {
            throw new InpatientException("DOCUMENT_TASK_REQUEST_INVALID", 400, "Rule, occurrence key and event time are required");
        }
        return transactions.execute(status -> {
            TaskAdmission admission = lockTaskAdmission(
                    identity, admissionId, request.patientId(), request.encounterId(), request.facilityId());
            DocumentRuleConfig rule = activeDocumentRule(identity.tenantId(), request.ruleCode());
            if (rule.triggerType().equals("ADMISSION")) {
                throw new InpatientException(
                        "DOCUMENT_RULE_TRIGGER_INVALID", 409, "Admission rules can only be created by the admission workflow");
            }
            if (rule.triggerType().equals("EVENT") && request.sourceEventId() == null) {
                throw new InpatientException(
                        "SOURCE_EVENT_REQUIRED", 400, "Event-triggered document tasks require an immutable source event id");
            }
            if (request.eventOccurredAt().isBefore(admission.admittedAt())
                    || request.eventOccurredAt().isAfter(Instant.now().plusSeconds(300))) {
                throw new InpatientException(
                        "EVENT_TIME_INVALID", 409, "The document event time is outside the active admission timeline");
            }
            String requestHash = sha256(admissionId + "|" + request.ruleCode() + "|"
                    + request.occurrenceKey().trim() + "|" + request.eventOccurredAt() + "|"
                    + (request.sourceEventId() == null ? "" : request.sourceEventId()));
            beginDocumentTaskCreateCommand(identity, idempotencyKey, requestHash);
            UUID taskId = UUID.randomUUID();
            Instant dueAt = request.eventOccurredAt().plusSeconds(rule.dueMinutes() * 60L);
            try {
                insertConfiguredDocumentTask(
                        identity.tenantId(), taskId, admissionId, rule, dueAt,
                        request.occurrenceKey().trim(), request.sourceEventId());
            } catch (DataIntegrityViolationException duplicate) {
                throw new InpatientException(
                        "DOCUMENT_TASK_OCCURRENCE_CONFLICT", 409, "This document rule occurrence already exists");
            }
            appendTaskCreatedEvidence(
                    identity, admission.patientId(), admissionId, taskId, rule.documentTypeCode(),
                    request.occurrenceKey().trim(), request.sourceEventId());
            completeDocumentTaskCreateCommand(identity, idempotencyKey, taskId);
            return new InpatientDocumentTaskWire(
                    taskId, admissionId, rule.documentTypeCode(),
                    InpatientDocumentTaskWire.TaskStateValue.PENDING, dueAt, null, null,
                    InpatientDocumentTaskWire.RequiredSignatureLevelValue.valueOf(rule.requiredSignatureLevel()),
                    null, InpatientDocumentTaskWire.NextSignatureLevelValue.AUTHOR,
                    InpatientDocumentTaskWire.ReviewStatusValue.NOT_STARTED, 1L);
        });
    }

    InpatientClinicalEventWire createClinicalEvent(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID admissionId,
            InpatientClinicalEventCreateRequestWire request) {
        if (request.summary() == null || request.summary().isBlank() || request.summary().length() > 1000
                || request.sourceSystem() == null || request.sourceSystem().isBlank()
                || request.sourceSystem().length() > 96
                || request.sourceEventKey() == null || request.sourceEventKey().isBlank()
                || request.sourceEventKey().length() > 256 || request.occurredAt() == null) {
            throw new InpatientException(
                    "CLINICAL_EVENT_REQUEST_INVALID", 400,
                    "Event time, summary and stable source identity are required");
        }
        return transactions.execute(status -> {
            TaskAdmission admission = lockTaskAdmission(
                    identity, admissionId, request.patientId(), request.encounterId(), request.facilityId());
            if (request.occurredAt().isBefore(admission.admittedAt())
                    || request.occurredAt().isAfter(Instant.now().plusSeconds(300))) {
                throw new InpatientException(
                        "EVENT_TIME_INVALID", 409, "The clinical event time is outside the active admission timeline");
            }
            String eventType = request.eventType().name();
            String ruleCode = documentRuleForEvent(eventType);
            String requestHash = sha256(admissionId + "|" + eventType + "|" + request.occurredAt() + "|"
                    + request.sourceSystem().trim() + "|" + request.sourceEventKey().trim());
            beginClinicalEventCommand(identity, idempotencyKey, requestHash);
            UUID eventId = UUID.randomUUID();
            try {
                jdbc.sql("""
                        insert into inpatient_clinical_event(
                          tenant_id, clinical_event_id, admission_id, event_type, occurred_at,
                          summary, source_system, source_event_key, created_by)
                        values (:tenant, :event, :admission, :event_type, :occurred_at,
                          :summary, :source_system, :source_event_key, :actor)
                        """).param("tenant", identity.tenantId()).param("event", eventId)
                        .param("admission", admissionId).param("event_type", eventType)
                        .param("occurred_at", offset(request.occurredAt())).param("summary", request.summary().trim())
                        .param("source_system", request.sourceSystem().trim())
                        .param("source_event_key", request.sourceEventKey().trim())
                        .param("actor", identity.userId()).update();
            } catch (DataIntegrityViolationException duplicate) {
                throw new InpatientException(
                        "CLINICAL_EVENT_SOURCE_CONFLICT", 409,
                        "This source clinical event has already been recorded");
            }
            UUID taskId = createConfiguredEventTask(
                    identity, admission.patientId(), admissionId, ruleCode, eventId,
                    request.occurredAt(), "EVENT:" + eventId);
            appendClinicalEventEvidence(
                    identity, admission.patientId(), admissionId, eventId, taskId, eventType,
                    request.sourceSystem().trim(), request.sourceEventKey().trim());
            completeClinicalEventCommand(identity, idempotencyKey, eventId);
            return new InpatientClinicalEventWire(
                    eventId, admissionId,
                    InpatientClinicalEventWire.EventTypeValue.valueOf(eventType),
                    request.occurredAt(), request.summary().trim(), taskId);
        });
    }

    InpatientOverviewWire transfer(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID admissionId,
            InpatientTransferRequestWire request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new InpatientException("TRANSFER_REASON_REQUIRED", 400, "A transfer reason is required");
        }
        return transactions.execute(status -> {
            TransferAdmission admission = jdbc.sql("""
                    select admission_id, patient_id, encounter_id, facility_id, ward_id,
                      current_bed_id, attending_user_id, status, row_version
                    from inpatient_admission
                    where tenant_id = :tenant and admission_id = :admission
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                    for update
                    """).param("tenant", identity.tenantId()).param("admission", admissionId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new TransferAdmission(
                            rs.getObject("admission_id", UUID.class), rs.getObject("patient_id", UUID.class),
                            rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                            rs.getObject("ward_id", UUID.class), rs.getObject("current_bed_id", UUID.class),
                            rs.getObject("attending_user_id", UUID.class), rs.getString("status"),
                            rs.getLong("row_version")))
                    .optional().orElseThrow(InpatientService::contextDenied);
            requireWardScope(identity, admission.facilityId(), admission.wardId());
            requireWardScope(identity, admission.facilityId(), request.targetWardId());
            if (!"ADMITTED".equals(admission.status())) {
                throw new InpatientException("ADMISSION_NOT_TRANSFERABLE", 409, "Only an admitted patient can be transferred");
            }
            if (admission.rowVersion() != request.expectedAdmissionRowVersion()) {
                throw new InpatientException("ADMISSION_VERSION_CONFLICT", 409, "The admission changed; reload before transfer");
            }
            if (admission.currentBedId().equals(request.targetBedId())) {
                throw new InpatientException("TRANSFER_TARGET_UNCHANGED", 409, "The target bed must differ from the current bed");
            }
            if (lockActiveBed(identity.tenantId(), admission.facilityId(), request.targetWardId(), request.targetBedId()) == null) {
                throw new InpatientException("BED_NOT_AVAILABLE", 409, "The target bed is not active in the selected ward");
            }
            long occupied = jdbc.sql("""
                    select count(*) from bed_occupancy
                    where tenant_id = :tenant and bed_id = :bed and ended_at is null
                    """).param("tenant", identity.tenantId()).param("bed", request.targetBedId())
                    .query(Long.class).single();
            if (occupied > 0) {
                throw new InpatientException("BED_OCCUPIED", 409, "The target bed is already occupied");
            }
            beginTransferCommand(identity, idempotencyKey, sha256(admissionId + "|" + admission.rowVersion()
                    + "|" + request.targetWardId() + "|" + request.targetBedId() + "|" + request.reason().trim()));
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            int closed = jdbc.sql("""
                    update bed_occupancy set ended_at = :ended, end_reason = 'TRANSFER'
                    where tenant_id = :tenant and admission_id = :admission and ended_at is null
                    """).param("ended", now).param("tenant", identity.tenantId())
                    .param("admission", admissionId).update();
            if (closed != 1) {
                throw new InpatientException("BED_OCCUPANCY_CONFLICT", 409, "The current bed occupancy changed");
            }
            UUID transferId = UUID.randomUUID();
            jdbc.sql("""
                    insert into inpatient_transfer(
                      tenant_id, transfer_id, admission_id, from_ward_id, from_bed_id,
                      to_ward_id, to_bed_id, reason, status, requested_by, requested_at, completed_at)
                    values (:tenant, :transfer, :admission, :from_ward, :from_bed,
                      :to_ward, :to_bed, :reason, 'COMPLETED', :actor, :now, :now)
                    """).param("tenant", identity.tenantId()).param("transfer", transferId)
                    .param("admission", admissionId).param("from_ward", admission.wardId())
                    .param("from_bed", admission.currentBedId()).param("to_ward", request.targetWardId())
                    .param("to_bed", request.targetBedId()).param("reason", request.reason().trim())
                    .param("actor", identity.userId()).param("now", now).update();
            jdbc.sql("""
                    insert into bed_occupancy(
                      tenant_id, bed_occupancy_id, admission_id, ward_id, bed_id, started_at)
                    values (:tenant, :occupancy, :admission, :ward, :bed, :started)
                    """).param("tenant", identity.tenantId()).param("occupancy", UUID.randomUUID())
                    .param("admission", admissionId).param("ward", request.targetWardId())
                    .param("bed", request.targetBedId()).param("started", now).update();
            int updated = jdbc.sql("""
                    update inpatient_admission
                    set ward_id = :ward, current_bed_id = :bed, row_version = row_version + 1, updated_at = :now
                    where tenant_id = :tenant and admission_id = :admission and row_version = :expected
                    """).param("ward", request.targetWardId()).param("bed", request.targetBedId()).param("now", now)
                    .param("tenant", identity.tenantId()).param("admission", admissionId)
                    .param("expected", request.expectedAdmissionRowVersion()).update();
            if (updated != 1) {
                throw new InpatientException("ADMISSION_VERSION_CONFLICT", 409, "The admission changed; reload before transfer");
            }
            createConfiguredEventTask(
                    identity, admission.patientId(), admissionId, "IP-TRANSFER", transferId,
                    now.toInstant(), "TRANSFER:" + transferId);
            appendTransferEvidence(identity, admission, request, transferId);
            completeTransferCommand(identity, idempotencyKey, transferId);
            return overview(identity, admissionId, request.patientId(), request.encounterId());
        });
    }

    InpatientOverviewWire discharge(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID admissionId,
            InpatientDischargeRequestWire request) {
        if (request.dischargeDiagnosis() == null || request.dischargeDiagnosis().isBlank()
                || request.dischargeDiagnosis().length() > 2000) {
            throw new InpatientException("DISCHARGE_DIAGNOSIS_REQUIRED", 400, "A valid discharge diagnosis is required");
        }
        return transactions.execute(status -> {
            TransferAdmission admission = jdbc.sql("""
                    select admission_id, patient_id, encounter_id, facility_id, ward_id,
                      current_bed_id, attending_user_id, status, row_version
                    from inpatient_admission
                    where tenant_id = :tenant and admission_id = :admission
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                    for update
                    """).param("tenant", identity.tenantId()).param("admission", admissionId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new TransferAdmission(
                            rs.getObject("admission_id", UUID.class), rs.getObject("patient_id", UUID.class),
                            rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                            rs.getObject("ward_id", UUID.class), rs.getObject("current_bed_id", UUID.class),
                            rs.getObject("attending_user_id", UUID.class), rs.getString("status"),
                            rs.getLong("row_version")))
                    .optional().orElseThrow(InpatientService::contextDenied);
            requireWardScope(identity, admission.facilityId(), admission.wardId());
            if (!"ADMITTED".equals(admission.status())) {
                throw new InpatientException("ADMISSION_NOT_DISCHARGEABLE", 409, "Only an admitted patient can be discharged");
            }
            if (admission.rowVersion() != request.expectedAdmissionRowVersion()) {
                throw new InpatientException("ADMISSION_VERSION_CONFLICT", 409, "The admission changed; reload before discharge");
            }
            long activePathways = jdbc.sql("""
                    select count(*) from inpatient_pathway_instance
                    where tenant_id=:tenant and admission_id=:admission and status='ACTIVE'
                    """).param("tenant", identity.tenantId()).param("admission", admissionId)
                    .query(Long.class).single();
            if (activePathways > 0) {
                throw new InpatientException("DISCHARGE_PATHWAY_ACTIVE", 409,
                        "Complete the active clinical pathway or obtain an independently reviewed exit before discharge");
            }
            long outstanding = jdbc.sql("""
                    select count(*) from inpatient_document_task
                    where tenant_id = :tenant and admission_id = :admission
                      and task_state in ('PENDING', 'IN_PROGRESS', 'OVERDUE')
                    """).param("tenant", identity.tenantId()).param("admission", admissionId)
                    .query(Long.class).single();
            if (outstanding > 0) {
                throw new InpatientException(
                        "DISCHARGE_TASKS_OPEN", 409,
                        "Required inpatient document tasks must be completed before discharge");
            }
            beginDischargeCommand(identity, idempotencyKey, sha256(admissionId + "|" + admission.rowVersion()
                    + "|" + request.dischargeDiagnosis().trim() + "|" + request.dispositionCode()));
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            int occupancyClosed = jdbc.sql("""
                    update bed_occupancy set ended_at = :ended, end_reason = 'DISCHARGE'
                    where tenant_id = :tenant and admission_id = :admission and ended_at is null
                    """).param("ended", now).param("tenant", identity.tenantId())
                    .param("admission", admissionId).update();
            if (occupancyClosed != 1) {
                throw new InpatientException("BED_OCCUPANCY_CONFLICT", 409, "The active bed occupancy changed");
            }
            UUID dischargeId = UUID.randomUUID();
            jdbc.sql("""
                    insert into inpatient_discharge(
                      tenant_id, discharge_id, admission_id, discharge_diagnosis, disposition_code,
                      outstanding_task_waiver_reason, discharged_by, discharged_at)
                    values (:tenant, :discharge, :admission, :diagnosis, :disposition,
                      :waiver, :actor, :discharged)
                    """).param("tenant", identity.tenantId()).param("discharge", dischargeId)
                    .param("admission", admissionId).param("diagnosis", request.dischargeDiagnosis().trim())
                    .param("disposition", request.dispositionCode().name())
                    .param("waiver", null)
                    .param("actor", identity.userId()).param("discharged", now).update();
            int admissionUpdated = jdbc.sql("""
                    update inpatient_admission
                    set status = 'DISCHARGED', discharged_at = :discharged,
                      row_version = row_version + 1, updated_at = :discharged
                    where tenant_id = :tenant and admission_id = :admission
                      and status = 'ADMITTED' and row_version = :expected
                    """).param("discharged", now).param("tenant", identity.tenantId())
                    .param("admission", admissionId).param("expected", request.expectedAdmissionRowVersion()).update();
            if (admissionUpdated != 1) {
                throw new InpatientException("ADMISSION_VERSION_CONFLICT", 409, "The admission changed; reload before discharge");
            }
            jdbc.sql("select set_config('haoemr.encounter_transition_actor', :value, true)")
                    .param("value", identity.userId().toString()).query(String.class).single();
            jdbc.sql("select set_config('haoemr.encounter_transition_reason', :value, true)")
                    .param("value", "INPATIENT_DISCHARGE_COMPLETED").query(String.class).single();
            jdbc.sql("select set_config('haoemr.encounter_transition_time', :value, true)")
                    .param("value", now.toInstant().toString()).query(String.class).single();
            int encounterUpdated = jdbc.sql("""
                    update encounter set status = 'FINISHED', ended_at = :ended,
                      row_version = row_version + 1, updated_at = :ended
                    where tenant_id = :tenant and encounter_id = :encounter
                      and patient_id = :patient and status = 'IN_PROGRESS'
                    """).param("ended", now).param("tenant", identity.tenantId())
                    .param("encounter", admission.encounterId()).param("patient", admission.patientId()).update();
            if (encounterUpdated != 1) {
                throw new InpatientException("ENCOUNTER_STATE_CONFLICT", 409, "The inpatient encounter could not be closed");
            }
            appendDischargeEvidence(identity, admission, request, dischargeId, outstanding);
            completeDischargeCommand(identity, idempotencyKey, dischargeId);
            return overview(identity, admissionId, request.patientId(), request.encounterId());
        });
    }

    @EventListener
    void completeTaskWhenClinicalDocumentIsSigned(ClinicalDocumentSigned event) {
        TaskCompletion completion = jdbc.sql("""
                update inpatient_document_task task
                set task_state = 'COMPLETED', completed_document_id = :document,
                  row_version = task.row_version + 1, updated_at = :signed
                from inpatient_admission admission
                where task.tenant_id = :tenant and task.working_document_id = :document
                  and task.task_state in ('IN_PROGRESS', 'OVERDUE')
                  and admission.tenant_id = task.tenant_id and admission.admission_id = task.admission_id
                  and admission.patient_id = :patient and admission.encounter_id = :encounter
                returning task.task_id, task.admission_id, task.row_version
                """).param("document", event.documentId()).param("signed", offset(event.signedAt()))
                .param("tenant", event.tenantId()).param("patient", event.patientId())
                .param("encounter", event.encounterId())
                .query((rs, row) -> new TaskCompletion(
                        rs.getObject("task_id", UUID.class), rs.getObject("admission_id", UUID.class),
                        rs.getLong("row_version"))).optional().orElse(null);
        if (completion != null) {
            appendTaskCompletedEvidence(event, completion);
        }
    }

    InpatientOverviewWire admit(
            ClinicalIdentity identity, String idempotencyKey, InpatientAdmissionCreateRequestWire request) {
        return transactions.execute(status -> {
            requireWardScope(identity, request.facilityId(), request.wardId());
            requireActiveAttending(identity.tenantId(), request.attendingUserId());
            requireInpatientEncounter(identity.tenantId(), request);
            requireAdmissionRegistration(identity.tenantId(), request);
            Bed bed = lockActiveBed(identity.tenantId(), request.facilityId(), request.wardId(), request.bedId());
            if (bed == null) {
                throw new InpatientException("BED_NOT_AVAILABLE", 409, "The selected bed is not active in this ward");
            }
            long occupied = jdbc.sql("""
                    select count(*) from bed_occupancy
                    where tenant_id = :tenant and bed_id = :bed and ended_at is null
                    """).param("tenant", identity.tenantId()).param("bed", request.bedId())
                    .query(Long.class).single();
            if (occupied > 0) {
                throw new InpatientException("BED_OCCUPIED", 409, "The selected bed is already occupied");
            }
            String requestHash = sha256(request.patientId() + "|" + request.encounterId() + "|"
                    + request.wardId() + "|" + request.bedId() + "|" + request.attendingUserId()
                    + "|" + request.admittedAt() + "|" + request.departmentId() + "|"
                    + request.admissionSource() + "|" + request.admissionType() + "|"
                    + request.admittingDiagnosisText() + "|" + request.contactPhone());
            beginCommand(identity, idempotencyKey, requestHash);
            UUID admissionId = UUID.randomUUID();
            String admissionNo = "IP-" + java.time.LocalDate.now() + "-"
                    + admissionId.toString().substring(0, 8).toUpperCase();
            jdbc.sql("""
                    insert into inpatient_admission(
                      tenant_id, admission_id, encounter_id, patient_id, facility_id, ward_id,
                      current_bed_id, attending_user_id, status, admitted_at, admission_no, department_id,
                      admission_source, admission_type, condition_level, admitting_diagnosis_code,
                      admitting_diagnosis_text, payment_method_code, identity_verification_method,
                      contact_name, contact_relationship, contact_phone, admission_certificate_no,
                      transfer_from, remarks)
                    values (:tenant, :admission, :encounter, :patient, :facility, :ward,
                      :bed, :attending, 'ADMITTED', :admitted_at, :admission_no, :department,
                      :admission_source, :admission_type, :condition_level, :diagnosis_code,
                      :diagnosis_text, :payment_method, :verification_method,
                      :contact_name, :contact_relationship, :contact_phone, :certificate_no,
                      :transfer_from, :remarks)
                    """).param("tenant", identity.tenantId()).param("admission", admissionId)
                    .param("encounter", request.encounterId()).param("patient", request.patientId())
                    .param("facility", request.facilityId()).param("ward", request.wardId())
                    .param("bed", request.bedId()).param("attending", request.attendingUserId())
                    .param("admitted_at", offset(request.admittedAt())).param("admission_no", admissionNo)
                    .param("department", request.departmentId())
                    .param("admission_source", request.admissionSource().name())
                    .param("admission_type", request.admissionType().name())
                    .param("condition_level", request.conditionLevel().name())
                    .param("diagnosis_code", clean(request.admittingDiagnosisCode()))
                    .param("diagnosis_text", request.admittingDiagnosisText().trim())
                    .param("payment_method", request.paymentMethodCode().trim())
                    .param("verification_method", request.identityVerificationMethod().name())
                    .param("contact_name", request.contactName().trim())
                    .param("contact_relationship", request.contactRelationship().trim())
                    .param("contact_phone", request.contactPhone().trim())
                    .param("certificate_no", clean(request.admissionCertificateNo()))
                    .param("transfer_from", clean(request.transferFrom()))
                    .param("remarks", clean(request.remarks())).update();
            jdbc.sql("""
                    insert into bed_occupancy(
                      tenant_id, bed_occupancy_id, admission_id, ward_id, bed_id, started_at)
                    values (:tenant, :occupancy, :admission, :ward, :bed, :started_at)
                    """).param("tenant", identity.tenantId()).param("occupancy", UUID.randomUUID())
                    .param("admission", admissionId).param("ward", request.wardId()).param("bed", request.bedId())
                    .param("started_at", offset(request.admittedAt())).update();
            createAdmissionDocumentTasks(identity.tenantId(), admissionId, request.admittedAt());
            appendAuditAndOutbox(identity, admissionId, request.patientId(), request.wardId(), request.bedId());
            completeCommand(identity, idempotencyKey, admissionId);
            return overview(identity, admissionId, request.patientId(), request.encounterId());
        });
    }

    InpatientOverviewWire overview(
            ClinicalIdentity identity, UUID admissionId, UUID patientId, UUID encounterId) {
        AdmissionView view = jdbc.sql("""
                select admission.admission_id, admission.encounter_id, admission.patient_id,
                  admission.facility_id, admission.ward_id, admission.current_bed_id, admission.attending_user_id,
                  admission.status, admission.admitted_at, admission.discharged_at, admission.row_version,
                  admission.admission_no, admission.department_id, admission.admission_source,
                  admission.admission_type, admission.condition_level, admission.admitting_diagnosis_code,
                  admission.admitting_diagnosis_text, admission.payment_method_code,
                  admission.identity_verification_method, admission.contact_name,
                  admission.contact_relationship, admission.contact_phone,
                  admission.admission_certificate_no, admission.transfer_from, admission.remarks,
                  patient.display_name as patient_display_name, ward.display_name as ward_display_name,
                  bed.bed_label
                from inpatient_admission admission
                join patient on patient.tenant_id = admission.tenant_id and patient.patient_id = admission.patient_id
                join clinical_ward ward on ward.tenant_id = admission.tenant_id and ward.ward_id = admission.ward_id
                join clinical_bed bed on bed.tenant_id = admission.tenant_id and bed.bed_id = admission.current_bed_id
                where admission.tenant_id = :tenant and admission.admission_id = :admission
                  and admission.patient_id = :patient and admission.encounter_id = :encounter
                """).param("tenant", identity.tenantId()).param("admission", admissionId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new AdmissionView(
                        new InpatientAdmissionWire(
                                rs.getObject("admission_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                                rs.getObject("patient_id", UUID.class), rs.getObject("ward_id", UUID.class),
                                rs.getObject("current_bed_id", UUID.class),
                                rs.getObject("attending_user_id", UUID.class),
                                InpatientAdmissionWire.StatusValue.valueOf(rs.getString("status")),
                                rs.getObject("admitted_at", OffsetDateTime.class).toInstant(),
                                rs.getString("admission_no"), rs.getObject("department_id", UUID.class),
                                InpatientAdmissionWire.AdmissionSourceValue.valueOf(rs.getString("admission_source")),
                                InpatientAdmissionWire.AdmissionTypeValue.valueOf(rs.getString("admission_type")),
                                InpatientAdmissionWire.ConditionLevelValue.valueOf(rs.getString("condition_level")),
                                rs.getString("admitting_diagnosis_code"), rs.getString("admitting_diagnosis_text"),
                                rs.getString("payment_method_code"),
                                InpatientAdmissionWire.IdentityVerificationMethodValue.valueOf(
                                        rs.getString("identity_verification_method")),
                                rs.getString("contact_name"), rs.getString("contact_relationship"),
                                rs.getString("contact_phone"), rs.getString("admission_certificate_no"),
                                rs.getString("transfer_from"), rs.getString("remarks"),
                                toInstant(rs.getObject("discharged_at", OffsetDateTime.class)),
                                rs.getLong("row_version")),
                        rs.getObject("facility_id", UUID.class),
                        rs.getString("patient_display_name"), rs.getString("ward_display_name"),
                        rs.getString("bed_label")))
                .optional().orElseThrow(InpatientService::contextDenied);
        requireWardScope(identity, view.facilityId(), view.admission().wardId());
        List<InpatientDocumentTaskWire> tasks = documentTasks(identity.tenantId(), admissionId);
        String watermark = sha256(view.admission().admissionId() + "|" + view.admission().rowVersion() + "|"
                + tasks.stream().map(task -> task.taskId() + ":" + task.rowVersion() + ":" + task.taskState())
                        .reduce((left, right) -> left + "|" + right).orElse("NO_TASKS"));
        return new InpatientOverviewWire(
                view.admission(), view.patientDisplayName(), view.wardDisplayName(), view.bedLabel(), tasks, watermark);
    }

    List<InpatientWorklistItemWire> worklist(ClinicalIdentity identity, UUID facilityId, UUID wardId) {
        requireWardScope(identity, facilityId, wardId);
        return jdbc.sql("""
                select admission.admission_id, admission.encounter_id, admission.patient_id,
                  patient.display_name as patient_display_name, bed.bed_label,
                  admission.attending_user_id, admission.admitted_at, admission.row_version,
                  count(task.task_id) filter (where task.task_state in ('PENDING','IN_PROGRESS')
                    and task.due_at < now())::integer as overdue_task_count,
                  count(task.task_id) filter (where task.task_state in ('PENDING','IN_PROGRESS'))::integer
                    as pending_task_count
                from inpatient_admission admission
                join patient on patient.tenant_id = admission.tenant_id and patient.patient_id = admission.patient_id
                join clinical_bed bed on bed.tenant_id = admission.tenant_id and bed.bed_id = admission.current_bed_id
                left join inpatient_document_task task on task.tenant_id = admission.tenant_id
                  and task.admission_id = admission.admission_id
                where admission.tenant_id = :tenant and admission.facility_id = :facility
                  and admission.ward_id = :ward
                  and admission.status in ('ADMITTED','TRANSFER_PENDING','DISCHARGE_PENDING')
                group by admission.admission_id, admission.encounter_id, admission.patient_id,
                  patient.display_name, bed.bed_label, admission.attending_user_id,
                  admission.admitted_at, admission.row_version
                order by bed.bed_label, admission.admitted_at
                """).param("tenant", identity.tenantId()).param("facility", facilityId).param("ward", wardId)
                .query((rs, row) -> new InpatientWorklistItemWire(
                        rs.getObject("admission_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getString("patient_display_name"),
                        rs.getString("bed_label"), rs.getObject("attending_user_id", UUID.class),
                        rs.getObject("admitted_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("overdue_task_count"), rs.getInt("pending_task_count"),
                        rs.getLong("row_version"))).list();
    }

    List<InpatientBedBoardItemWire> bedBoard(ClinicalIdentity identity, UUID facilityId, UUID wardId) {
        requireWardScope(identity, facilityId, wardId);
        return jdbc.sql("""
                select bed.bed_id, bed.ward_id, ward.department_id, bed.bed_label, bed.status as bed_status,
                  facility.display_name as facility_name, department.display_name as department_name,
                  ward.display_name as ward_name,
                  admission.admission_id, admission.encounter_id, admission.patient_id,
                  patient.display_name as patient_display_name, admission.attending_user_id,
                  admission.admitted_at, admission.row_version as admission_row_version
                from clinical_bed bed
                join clinical_ward ward on ward.tenant_id = bed.tenant_id and ward.ward_id = bed.ward_id
                join facility on facility.tenant_id = ward.tenant_id and facility.facility_id = ward.facility_id
                join clinical_department department on department.tenant_id = ward.tenant_id
                  and department.facility_id = ward.facility_id and department.department_id = ward.department_id
                left join bed_occupancy occupancy on occupancy.tenant_id = bed.tenant_id
                  and occupancy.bed_id = bed.bed_id and occupancy.ended_at is null
                left join inpatient_admission admission on admission.tenant_id = occupancy.tenant_id
                  and admission.admission_id = occupancy.admission_id
                  and admission.status in ('ADMITTED','TRANSFER_PENDING','DISCHARGE_PENDING')
                left join patient on patient.tenant_id = admission.tenant_id
                  and patient.patient_id = admission.patient_id
                where bed.tenant_id = :tenant and bed.ward_id = :ward
                order by bed.bed_label, bed.bed_id
                """).param("tenant", identity.tenantId()).param("ward", wardId)
                .query((rs, row) -> {
                    UUID admissionId = rs.getObject("admission_id", UUID.class);
                    return new InpatientBedBoardItemWire(
                            rs.getObject("bed_id", UUID.class), rs.getObject("ward_id", UUID.class),
                            rs.getObject("department_id", UUID.class),
                            rs.getString("bed_label"), rs.getString("facility_name"),
                            rs.getString("department_name"), rs.getString("ward_name"),
                            rs.getString("department_name") + "-" + rs.getString("bed_label") + "床",
                            InpatientBedBoardItemWire.BedStatusValue.valueOf(rs.getString("bed_status")),
                            admissionId == null
                                    ? InpatientBedBoardItemWire.OccupancyStatusValue.AVAILABLE
                                    : InpatientBedBoardItemWire.OccupancyStatusValue.OCCUPIED,
                            admissionId, rs.getObject("encounter_id", UUID.class),
                            rs.getObject("patient_id", UUID.class), rs.getString("patient_display_name"),
                            rs.getObject("attending_user_id", UUID.class),
                            toInstant(rs.getObject("admitted_at", OffsetDateTime.class)),
                            rs.getObject("admission_row_version", Long.class));
                }).list();
    }

    List<InpatientDocumentRuleWire> documentRules(ClinicalIdentity identity) {
        return jdbc.sql("""
                select rule_code, document_type_code, display_name, category_code, trigger_type,
                  due_minutes, required_signature_level,
                  array(select jsonb_array_elements_text(template_sections)) as template_sections,
                  rule_version
                from inpatient_document_rule
                where tenant_id = :tenant and status = 'ACTIVE'
                  and effective_from <= now() and (effective_until is null or effective_until > now())
                order by category_code, display_name, rule_code
                """).param("tenant", identity.tenantId())
                .query((rs, row) -> new InpatientDocumentRuleWire(
                        rs.getString("rule_code"), rs.getString("document_type_code"),
                        rs.getString("display_name"),
                        InpatientDocumentRuleWire.CategoryCodeValue.valueOf(rs.getString("category_code")),
                        InpatientDocumentRuleWire.TriggerTypeValue.valueOf(rs.getString("trigger_type")),
                        rs.getInt("due_minutes"),
                        InpatientDocumentRuleWire.RequiredSignatureLevelValue.valueOf(
                                rs.getString("required_signature_level")),
                        Arrays.asList((String[]) rs.getArray("template_sections").getArray()),
                        rs.getLong("rule_version"))).list();
    }

    private List<InpatientDocumentTaskWire> documentTasks(UUID tenantId, UUID admissionId) {
        return jdbc.sql("""
                select task.task_id, task.admission_id, task.document_type_code,
                  case when task.task_state in ('PENDING','IN_PROGRESS') and task.due_at < now()
                    then 'OVERDUE' else task.task_state end as effective_state,
                  task.due_at, task.working_document_id, task.completed_document_id,
                  task.required_signature_level, policy.current_signature_level,
                  case
                    when task.task_state in ('COMPLETED','WAIVED') or policy.review_status = 'COMPLETED' then null
                    when policy.current_signature_level is null then 'AUTHOR'
                    when policy.current_signature_level = 'AUTHOR' then 'ATTENDING'
                    when policy.current_signature_level = 'ATTENDING' then 'CHIEF'
                    when policy.current_signature_level = 'CHIEF' then 'MEDICAL_RECORDS'
                    else null
                  end as next_signature_level,
                  coalesce(policy.review_status,
                    case when task.working_document_id is null then 'NOT_STARTED' else 'PENDING' end) as review_status,
                  task.row_version
                from inpatient_document_task task
                left join clinical_document document
                  on document.tenant_id = task.tenant_id
                  and document.document_id = task.working_document_id
                left join document_signature_policy policy
                  on policy.tenant_id = document.tenant_id
                  and policy.document_id = document.document_id
                  and policy.document_version_id = document.current_version_id
                where task.tenant_id = :tenant and task.admission_id = :admission
                order by task.due_at, task.document_type_code
                """).param("tenant", tenantId).param("admission", admissionId)
                .query((rs, row) -> new InpatientDocumentTaskWire(
                        rs.getObject("task_id", UUID.class), rs.getObject("admission_id", UUID.class),
                        rs.getString("document_type_code"),
                        InpatientDocumentTaskWire.TaskStateValue.valueOf(rs.getString("effective_state")),
                        rs.getObject("due_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("working_document_id", UUID.class),
                        rs.getObject("completed_document_id", UUID.class),
                        InpatientDocumentTaskWire.RequiredSignatureLevelValue.valueOf(
                                rs.getString("required_signature_level")),
                        rs.getString("current_signature_level") == null ? null
                                : InpatientDocumentTaskWire.CurrentSignatureLevelValue.valueOf(
                                        rs.getString("current_signature_level")),
                        rs.getString("next_signature_level") == null ? null
                                : InpatientDocumentTaskWire.NextSignatureLevelValue.valueOf(
                                        rs.getString("next_signature_level")),
                        InpatientDocumentTaskWire.ReviewStatusValue.valueOf(rs.getString("review_status")),
                        rs.getLong("row_version"))).list();
    }

    private void createAdmissionDocumentTasks(UUID tenantId, UUID admissionId, Instant admittedAt) {
        List<AdmissionDocumentRule> rules = jdbc.sql("""
                select document_type_code, due_minutes, rule_version, required_signature_level
                from inpatient_document_rule
                where tenant_id = :tenant and status = 'ACTIVE' and trigger_type = 'ADMISSION'
                  and effective_from <= now()
                  and (effective_until is null or effective_until > now())
                order by due_minutes, document_type_code
                """).param("tenant", tenantId)
                .query((rs, row) -> new AdmissionDocumentRule(
                        rs.getString("document_type_code"), rs.getInt("due_minutes"),
                        rs.getLong("rule_version"), rs.getString("required_signature_level"))).list();
        if (rules.isEmpty()) {
            throw new InpatientException(
                    "INPATIENT_DOCUMENT_RULES_MISSING", 503, "No active admission document rules are configured");
        }
        rules.forEach(rule -> createDocumentTask(
                tenantId, admissionId, rule.documentTypeCode(), admittedAt.plusSeconds(rule.dueMinutes() * 60L),
                "ADMISSION", rule.ruleVersion(), rule.requiredSignatureLevel()));
    }

    private void createDocumentTask(
            UUID tenantId, UUID admissionId, String type, Instant dueAt,
            String occurrenceKey, long ruleVersion, String requiredSignatureLevel) {
        jdbc.sql("""
                insert into inpatient_document_task(
                  tenant_id, task_id, admission_id, document_type_code, task_state, due_at,
                  occurrence_key, rule_version, required_signature_level)
                values (:tenant, :task, :admission, :type, 'PENDING', :due_at,
                  :occurrence_key, :rule_version, :required_signature_level)
                """).param("tenant", tenantId).param("task", UUID.randomUUID()).param("admission", admissionId)
                .param("type", type).param("due_at", offset(dueAt)).param("occurrence_key", occurrenceKey)
                .param("rule_version", ruleVersion).param("required_signature_level", requiredSignatureLevel).update();
    }

    private TaskAdmission lockTaskAdmission(
            ClinicalIdentity identity, UUID admissionId, UUID patientId, UUID encounterId, UUID facilityId) {
        TaskAdmission admission = jdbc.sql("""
                select admission_id, patient_id, encounter_id, facility_id, ward_id, admitted_at, status
                from inpatient_admission
                where tenant_id = :tenant and admission_id = :admission
                  and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                for update
                """).param("tenant", identity.tenantId()).param("admission", admissionId)
                .param("patient", patientId).param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new TaskAdmission(
                        rs.getObject("admission_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("ward_id", UUID.class),
                        rs.getObject("admitted_at", OffsetDateTime.class).toInstant(), rs.getString("status")))
                .optional().orElseThrow(InpatientService::contextDenied);
        requireWardScope(identity, admission.facilityId(), admission.wardId());
        if (!List.of("ADMITTED", "TRANSFER_PENDING", "DISCHARGE_PENDING").contains(admission.status())) {
            throw new InpatientException("ADMISSION_NOT_ACTIVE", 409, "The admission is not active");
        }
        return admission;
    }

    private DocumentRuleConfig activeDocumentRule(UUID tenantId, String ruleCode) {
        return jdbc.sql("""
                select document_type_code, trigger_type, due_minutes, rule_version, required_signature_level
                from inpatient_document_rule
                where tenant_id = :tenant and rule_code = :rule and status = 'ACTIVE'
                  and effective_from <= now() and (effective_until is null or effective_until > now())
                """).param("tenant", tenantId).param("rule", ruleCode.trim())
                .query((rs, row) -> new DocumentRuleConfig(
                        rs.getString("document_type_code"), rs.getString("trigger_type"),
                        rs.getInt("due_minutes"), rs.getLong("rule_version"),
                        rs.getString("required_signature_level")))
                .optional().orElseThrow(() -> new InpatientException(
                        "DOCUMENT_RULE_NOT_ACTIVE", 409, "The requested inpatient document rule is not active"));
    }

    private void insertConfiguredDocumentTask(
            UUID tenantId, UUID taskId, UUID admissionId, DocumentRuleConfig rule,
            Instant dueAt, String occurrenceKey, UUID sourceEventId) {
        jdbc.sql("""
                insert into inpatient_document_task(
                  tenant_id, task_id, admission_id, document_type_code, task_state, due_at,
                  occurrence_key, rule_version, source_event_id, required_signature_level)
                values (:tenant, :task, :admission, :type, 'PENDING', :due,
                  :occurrence, :rule_version, :source_event, :required_signature_level)
                """).param("tenant", tenantId).param("task", taskId).param("admission", admissionId)
                .param("type", rule.documentTypeCode()).param("due", offset(dueAt))
                .param("occurrence", occurrenceKey).param("rule_version", rule.ruleVersion())
                .param("source_event", sourceEventId)
                .param("required_signature_level", rule.requiredSignatureLevel()).update();
    }

    private UUID createConfiguredEventTask(
            ClinicalIdentity identity, UUID patientId, UUID admissionId, String ruleCode,
            UUID sourceEventId, Instant occurredAt, String occurrenceKey) {
        DocumentRuleConfig rule = activeDocumentRule(identity.tenantId(), ruleCode);
        UUID taskId = UUID.randomUUID();
        insertConfiguredDocumentTask(
                identity.tenantId(), taskId, admissionId, rule,
                occurredAt.plusSeconds(rule.dueMinutes() * 60L), occurrenceKey, sourceEventId);
        appendTaskCreatedEvidence(
                identity, patientId, admissionId, taskId, rule.documentTypeCode(), occurrenceKey, sourceEventId);
        return taskId;
    }

    private static String documentRuleForEvent(String eventType) {
        return switch (eventType) {
            case "CONSULTATION_REQUESTED" -> "IP-CONSULTATION";
            case "PREOPERATIVE_DECISION" -> "IP-PREOPERATIVE";
            case "OPERATION_COMPLETED" -> "IP-OPERATION";
            case "RESCUE_COMPLETED" -> "IP-RESCUE";
            case "TRANSFUSION_COMPLETED" -> "IP-TRANSFUSION";
            case "CRITICAL_ILLNESS_DECLARED" -> "IP-CRITICAL";
            case "DEATH_CONFIRMED" -> "IP-DEATH";
            default -> throw new InpatientException(
                    "CLINICAL_EVENT_TYPE_UNSUPPORTED", 400,
                    "The clinical event does not map to an active inpatient document rule");
        };
    }

    private void requireInpatientEncounter(UUID tenantId, InpatientAdmissionCreateRequestWire request) {
        long count = jdbc.sql("""
                select count(*) from encounter
                where tenant_id = :tenant and encounter_id = :encounter and patient_id = :patient
                  and organization_id = :organization and facility_id = :facility
                  and encounter_type = 'INPATIENT' and status = 'IN_PROGRESS'
                """).param("tenant", tenantId).param("encounter", request.encounterId())
                .param("patient", request.patientId()).param("organization", request.organizationId())
                .param("facility", request.facilityId()).query(Long.class).single();
        if (count != 1) {
            throw contextDenied();
        }
    }

    private void requireAdmissionRegistration(UUID tenantId, InpatientAdmissionCreateRequestWire request) {
        if (request.departmentId() == null || request.admissionSource() == null
                || request.admissionType() == null || request.conditionLevel() == null
                || request.identityVerificationMethod() == null || request.admittedAt() == null
                || blank(request.admittingDiagnosisText()) || blank(request.paymentMethodCode())
                || blank(request.contactName()) || blank(request.contactRelationship())
                || blank(request.contactPhone())) {
            throw new InpatientException(
                    "ADMISSION_VALIDATION_FAILED", 400, "入院登记必填信息不完整");
        }
        if (!request.contactPhone().trim().matches("[0-9+() -]{6,32}")) {
            throw new InpatientException(
                    "ADMISSION_VALIDATION_FAILED", 400, "联系人电话格式不正确");
        }
        long scope = jdbc.sql("""
                select count(*) from clinical_ward ward
                join clinical_bed bed on bed.tenant_id = ward.tenant_id and bed.ward_id = ward.ward_id
                where ward.tenant_id = :tenant and ward.facility_id = :facility
                  and ward.department_id = :department and ward.ward_id = :ward
                  and bed.bed_id = :bed and ward.status = 'ACTIVE' and bed.status = 'ACTIVE'
                """).param("tenant", tenantId).param("facility", request.facilityId())
                .param("department", request.departmentId()).param("ward", request.wardId())
                .param("bed", request.bedId()).query(Long.class).single();
        if (scope != 1) {
            throw new InpatientException(
                    "ADMISSION_VALIDATION_FAILED", 400, "科室、病区与床位不属于同一有效配置");
        }
        if (request.admissionSource().name().equals("TRANSFER") && blank(request.transferFrom())) {
            throw new InpatientException(
                    "ADMISSION_VALIDATION_FAILED", 400, "转院入院必须填写转出医疗机构");
        }
    }

    private Bed lockActiveBed(UUID tenantId, UUID facilityId, UUID wardId, UUID bedId) {
        return jdbc.sql("""
                select bed.bed_id from clinical_bed bed
                join clinical_ward ward on ward.tenant_id = bed.tenant_id and ward.ward_id = bed.ward_id
                where bed.tenant_id = :tenant and bed.bed_id = :bed and bed.ward_id = :ward
                  and bed.status = 'ACTIVE' and ward.status = 'ACTIVE'
                  and ward.facility_id = :facility
                for update of bed
                """).param("tenant", tenantId).param("bed", bedId).param("ward", wardId)
                .param("facility", facilityId).query((rs, row) -> new Bed(rs.getObject("bed_id", UUID.class)))
                .optional().orElse(null);
    }

    private void requireWardScope(ClinicalIdentity identity, UUID facilityId, UUID wardId) {
        String roles = "{" + identity.roleAssignmentIds().stream().map(UUID::toString)
                .reduce((left, right) -> left + "," + right).orElse("") + "}";
        long count = jdbc.sql("""
                select count(*) from ward_role_scope scope
                join clinical_ward ward on ward.tenant_id = scope.tenant_id and ward.ward_id = scope.ward_id
                join role_assignment assignment on assignment.tenant_id = scope.tenant_id
                  and assignment.role_assignment_id = scope.role_assignment_id
                where scope.tenant_id = :tenant and scope.ward_id = :ward
                  and (:facility is null or ward.facility_id = :facility)
                  and scope.role_assignment_id = any(cast(:roles as uuid[]))
                  and scope.valid_from <= now() and (scope.valid_until is null or scope.valid_until > now())
                  and assignment.user_id = :user and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= now()
                  and (assignment.valid_until is null or assignment.valid_until > now())
                """).param("tenant", identity.tenantId()).param("ward", wardId)
                .param("facility", facilityId).param("roles", roles).param("user", identity.userId())
                .query(Long.class).single();
        if (count < 1) {
            throw new InpatientException(
                    "WARD_SCOPE_DENIED", 403, "The current role has no active scope for this ward");
        }
    }

    private void requireActiveAttending(UUID tenantId, UUID userId) {
        long count = jdbc.sql("select count(*) from app_user where tenant_id = :tenant and user_id = :user and status = 'ACTIVE'")
                .param("tenant", tenantId).param("user", userId).query(Long.class).single();
        if (count != 1) {
            throw new InpatientException("ATTENDING_NOT_ACTIVE", 409, "The attending clinician is not active");
        }
    }

    private void beginCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InpatientException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'INPATIENT_ADMIT', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new InpatientException("IDEMPOTENCY_REPLAY", 409, "This admission command key has already been used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String key, UUID admissionId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'INPATIENT_ADMIT' and idempotency_key = :key
                """).param("resource", admissionId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void beginTaskCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InpatientException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'INPATIENT_DOCUMENT_START', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new InpatientException("IDEMPOTENCY_REPLAY", 409, "This document start command key has already been used");
        }
    }

    private void completeTaskCommand(ClinicalIdentity identity, String key, UUID documentId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'INPATIENT_DOCUMENT_START'
                  and idempotency_key = :key
                """).param("resource", documentId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void beginDocumentTaskCreateCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InpatientException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'INPATIENT_DOCUMENT_TASK_CREATE', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new InpatientException("IDEMPOTENCY_REPLAY", 409, "This document task command key has already been used");
        }
    }

    private void completeDocumentTaskCreateCommand(ClinicalIdentity identity, String key, UUID taskId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'INPATIENT_DOCUMENT_TASK_CREATE'
                  and idempotency_key = :key
                """).param("resource", taskId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void beginClinicalEventCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InpatientException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'INPATIENT_CLINICAL_EVENT', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new InpatientException("IDEMPOTENCY_REPLAY", 409, "This clinical event command was already used");
        }
    }

    private void completeClinicalEventCommand(ClinicalIdentity identity, String key, UUID eventId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'INPATIENT_CLINICAL_EVENT'
                  and idempotency_key = :key
                """).param("resource", eventId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void beginTransferCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InpatientException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'INPATIENT_TRANSFER', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new InpatientException("IDEMPOTENCY_REPLAY", 409, "This transfer command key has already been used");
        }
    }

    private void completeTransferCommand(ClinicalIdentity identity, String key, UUID transferId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'INPATIENT_TRANSFER'
                  and idempotency_key = :key
                """).param("resource", transferId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void beginDischargeCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InpatientException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'INPATIENT_DISCHARGE', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new InpatientException("IDEMPOTENCY_REPLAY", 409, "This discharge command key has already been used");
        }
    }

    private void completeDischargeCommand(ClinicalIdentity identity, String key, UUID dischargeId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'INPATIENT_DISCHARGE'
                  and idempotency_key = :key
                """).param("resource", dischargeId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void appendDischargeEvidence(
            ClinicalIdentity identity,
            TransferAdmission admission,
            InpatientDischargeRequestWire request,
            UUID dischargeId,
            long waivedTaskCount) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|INPATIENT_DISCHARGED|"
                + admission.admissionId() + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, 'INPATIENT_DISCHARGED', 'INPATIENT_ADMISSION',
                  :admission, :patient_hash, :trace, :previous, :hash,
                  jsonb_build_object('discharge_id', :discharge, 'disposition_code', :disposition,
                    'waived_task_count', :waived_count))
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("admission", admission.admissionId())
                .param("patient_hash", sha256(identity.tenantId() + "|" + admission.patientId()))
                .param("trace", trace).param("previous", previousHash).param("hash", eventHash)
                .param("discharge", dischargeId).param("disposition", request.dispositionCode().name())
                .param("waived_count", waivedTaskCount).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'INPATIENT_ADMISSION', :admission, :version,
                  'InpatientDischarged', 1,
                  jsonb_build_object('discharge_id', :discharge, 'disposition_code', :disposition,
                    'waived_task_count', :waived_count))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("admission", admission.admissionId()).param("version", admission.rowVersion() + 1)
                .param("discharge", dischargeId).param("disposition", request.dispositionCode().name())
                .param("waived_count", waivedTaskCount).update();
    }

    private void appendTransferEvidence(
            ClinicalIdentity identity,
            TransferAdmission admission,
            InpatientTransferRequestWire request,
            UUID transferId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|INPATIENT_TRANSFERRED|"
                + admission.admissionId() + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, 'INPATIENT_TRANSFERRED', 'INPATIENT_ADMISSION',
                  :admission, :patient_hash, :trace, :previous, :hash,
                  jsonb_build_object('transfer_id', :transfer, 'from_ward_id', :from_ward,
                    'from_bed_id', :from_bed, 'to_ward_id', :to_ward, 'to_bed_id', :to_bed))
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("admission", admission.admissionId())
                .param("patient_hash", sha256(identity.tenantId() + "|" + admission.patientId()))
                .param("trace", trace).param("previous", previousHash).param("hash", eventHash)
                .param("transfer", transferId).param("from_ward", admission.wardId())
                .param("from_bed", admission.currentBedId()).param("to_ward", request.targetWardId())
                .param("to_bed", request.targetBedId()).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'INPATIENT_ADMISSION', :admission, :version,
                  'InpatientTransferred', 1,
                  jsonb_build_object('transfer_id', :transfer, 'from_ward_id', :from_ward,
                    'from_bed_id', :from_bed, 'to_ward_id', :to_ward, 'to_bed_id', :to_bed))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("admission", admission.admissionId()).param("version", admission.rowVersion() + 1)
                .param("transfer", transferId).param("from_ward", admission.wardId())
                .param("from_bed", admission.currentBedId()).param("to_ward", request.targetWardId())
                .param("to_bed", request.targetBedId()).update();
    }

    private void appendTaskStartedEvidence(ClinicalIdentity identity, DocumentTaskView task, UUID documentId) {
        appendTaskAudit(
                identity.tenantId(), identity.userId(), task.patientId(), task.taskId(),
                "INPATIENT_DOCUMENT_TASK_STARTED", "CLINICAL_DOCUMENT", documentId);
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'INPATIENT_DOCUMENT_TASK', :task, :version,
                  'InpatientDocumentTaskStarted', 1,
                  jsonb_build_object('document_id', :document, 'admission_id', :admission))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("task", task.taskId()).param("version", task.rowVersion() + 1)
                .param("document", documentId).param("admission", task.admissionId()).update();
    }

    private void appendTaskCreatedEvidence(
            ClinicalIdentity identity,
            UUID patientId,
            UUID admissionId,
            UUID taskId,
            String documentTypeCode,
            String occurrenceKey,
            UUID sourceEventId) {
        appendTaskAudit(
                identity.tenantId(), identity.userId(), patientId, taskId,
                "INPATIENT_DOCUMENT_TASK_CREATED", "INPATIENT_DOCUMENT_TASK", taskId);
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'INPATIENT_DOCUMENT_TASK', :task, 1,
                  'InpatientDocumentTaskCreated', 1,
                  jsonb_build_object('admission_id', :admission, 'document_type_code', :type,
                    'occurrence_key', :occurrence, 'source_event_id', cast(:source_event as uuid)))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("task", taskId).param("admission", admissionId).param("type", documentTypeCode)
                .param("occurrence", occurrenceKey).param("source_event", sourceEventId).update();
    }

    private void appendClinicalEventEvidence(
            ClinicalIdentity identity,
            UUID patientId,
            UUID admissionId,
            UUID clinicalEventId,
            UUID taskId,
            String eventType,
            String sourceSystem,
            String sourceEventKey) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|INPATIENT_CLINICAL_EVENT_RECORDED|"
                + clinicalEventId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, 'INPATIENT_CLINICAL_EVENT_RECORDED',
                  'INPATIENT_CLINICAL_EVENT', :clinical_event, :patient_hash, :trace, :previous, :hash,
                  jsonb_build_object('admission_id', :admission, 'event_type', :event_type,
                    'document_task_id', :task, 'source_system', :source_system,
                    'source_event_key_hash', :source_event_key_hash))
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("clinical_event", clinicalEventId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("hash", eventHash)
                .param("admission", admissionId).param("event_type", eventType).param("task", taskId)
                .param("source_system", sourceSystem)
                .param("source_event_key_hash", sha256(sourceSystem + "|" + sourceEventKey)).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :outbox_event, 'INPATIENT_CLINICAL_EVENT', :clinical_event, 1,
                  'InpatientClinicalEventRecorded', 1,
                  jsonb_build_object('admission_id', :admission, 'event_type', :event_type,
                    'document_task_id', :task, 'source_system', :source_system,
                    'source_event_key_hash', :source_event_key_hash))
                """).param("tenant", identity.tenantId()).param("outbox_event", UUID.randomUUID())
                .param("clinical_event", clinicalEventId).param("admission", admissionId)
                .param("event_type", eventType).param("task", taskId).param("source_system", sourceSystem)
                .param("source_event_key_hash", sha256(sourceSystem + "|" + sourceEventKey)).update();
    }

    private void appendTaskCompletedEvidence(ClinicalDocumentSigned event, TaskCompletion completion) {
        appendTaskAudit(
                event.tenantId(), event.actorUserId(), event.patientId(), completion.taskId(),
                "INPATIENT_DOCUMENT_TASK_COMPLETED", "CLINICAL_DOCUMENT", event.documentId());
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'INPATIENT_DOCUMENT_TASK', :task, :version,
                  'InpatientDocumentTaskCompleted', 1,
                  jsonb_build_object('document_id', :document, 'document_version_id', :document_version,
                    'admission_id', :admission))
                """).param("tenant", event.tenantId()).param("event", UUID.randomUUID())
                .param("task", completion.taskId()).param("version", completion.rowVersion())
                .param("document", event.documentId()).param("document_version", event.documentVersionId())
                .param("admission", completion.admissionId()).update();
    }

    private void appendTaskAudit(
            UUID tenantId,
            UUID actorUserId,
            UUID patientId,
            UUID taskId,
            String actionCode,
            String relatedResourceType,
            UUID relatedResourceId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", tenantId).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", tenantId).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(tenantId + "|" + auditId + "|" + actionCode + "|" + taskId
                + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, 'INPATIENT_DOCUMENT_TASK',
                  :task, :patient_hash, :trace, :previous, :hash,
                  jsonb_build_object('related_resource_type', :related_type,
                    'related_resource_id', cast(:related_id as uuid)))
                """).param("tenant", tenantId).param("audit", auditId).param("actor", actorUserId)
                .param("action", actionCode).param("task", taskId)
                .param("patient_hash", sha256(tenantId + "|" + patientId)).param("trace", trace)
                .param("previous", previousHash).param("hash", eventHash)
                .param("related_type", relatedResourceType).param("related_id", relatedResourceId).update();
    }

    private void appendAuditAndOutbox(
            ClinicalIdentity identity, UUID admissionId, UUID patientId, UUID wardId, UUID bedId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|INPATIENT_ADMITTED|"
                + admissionId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, 'INPATIENT_ADMITTED', 'INPATIENT_ADMISSION',
                  :admission, :patient_hash, :trace, :previous, :hash,
                  jsonb_build_object('ward_id', :ward, 'bed_id', :bed))
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("admission", admissionId).param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("hash", eventHash)
                .param("ward", wardId).param("bed", bedId).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'INPATIENT_ADMISSION', :admission, 1,
                  'InpatientAdmitted', 1, jsonb_build_object('ward_id', :ward, 'bed_id', :bed))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("admission", admissionId).param("ward", wardId).param("bed", bedId).update();
    }

    private static InpatientException contextDenied() {
        return new InpatientException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested inpatient context is not permitted");
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String clean(String value) {
        return blank(value) ? null : value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Bed(UUID id) {}
    private record AdmissionView(
            InpatientAdmissionWire admission, UUID facilityId,
            String patientDisplayName, String wardDisplayName, String bedLabel) {}
    private record DocumentTaskView(
            UUID taskId, UUID admissionId, String documentTypeCode, String taskState,
            UUID workingDocumentId, long rowVersion, UUID patientId, UUID encounterId,
            UUID facilityId, UUID wardId, String admissionStatus, String requiredSignatureLevel) {}
    private record TaskCompletion(UUID taskId, UUID admissionId, long rowVersion) {}
    private record TransferAdmission(
            UUID admissionId, UUID patientId, UUID encounterId, UUID facilityId,
            UUID wardId, UUID currentBedId, UUID attendingUserId, String status, long rowVersion) {}
    private record AdmissionDocumentRule(
            String documentTypeCode, int dueMinutes, long ruleVersion, String requiredSignatureLevel) {}
    private record TaskAdmission(
            UUID admissionId, UUID patientId, UUID encounterId, UUID facilityId,
            UUID wardId, Instant admittedAt, String status) {}
    private record DocumentRuleConfig(
            String documentTypeCode,
            String triggerType,
            int dueMinutes,
            long ruleVersion,
            String requiredSignatureLevel) {}
}
