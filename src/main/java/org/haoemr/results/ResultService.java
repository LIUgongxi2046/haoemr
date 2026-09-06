package org.haoemr.results;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.configuration.ConfigurationRuntimeService;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeCommandRequest;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeContext;
import org.haoemr.contracts.ClinicalResultCorrectionRequestWire;
import org.haoemr.contracts.ClinicalResultCreateRequestWire;
import org.haoemr.contracts.ClinicalResultObservationWire;
import org.haoemr.contracts.ClinicalResultWire;
import org.haoemr.contracts.CriticalValueAcknowledgeRequestWire;
import org.haoemr.contracts.CriticalValueDispositionRequestWire;
import org.haoemr.contracts.CriticalValueWire;
import org.haoemr.contracts.ResultObservationInputWire;
import org.haoemr.security.ClinicalIdentity;
import org.haoemr.tasks.ClinicalTaskRuleResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ResultService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ClinicalTaskRuleResolver taskRules;
    private final ConfigurationRuntimeService configurationRuntime;

    ResultService(JdbcClient jdbc, TransactionTemplate transactions, ClinicalTaskRuleResolver taskRules,
            ConfigurationRuntimeService configurationRuntime) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.taskRules = taskRules;
        this.configurationRuntime = configurationRuntime;
    }

    ClinicalResultWire create(
            ClinicalIdentity identity, String idempotencyKey, ClinicalResultCreateRequestWire request) {
        validateReport(request.conclusion(), request.reportedAt(), request.observations(), null);
        if (blank(request.sourceSystem()) || request.sourceSystem().length() > 128
                || blank(request.sourceReportKey()) || request.sourceReportKey().length() > 256
                || request.executionTaskId() == null || request.reportType() == null) {
            throw new ResultException("RESULT_REQUEST_INVALID", 400, "Result source, execution and report type are required");
        }
        requireReportAuthorRole(identity, request.facilityId(), request.reportType().name());
        return transactions.execute(status -> {
            ExecutionFact execution = requireCompletedExecution(identity.tenantId(), request.executionTaskId(),
                    request.patientId(), request.encounterId(), request.facilityId());
            if (!execution.itemType().equals(request.reportType().name())) {
                throw new ResultException("RESULT_TYPE_MISMATCH", 409, "Report type does not match the ordered item");
            }
            String requestHash = sha256(request.patientId() + "|" + request.encounterId() + "|"
                    + request.executionTaskId() + "|" + request.sourceSystem() + "|"
                    + request.sourceReportKey() + "|" + request.conclusion() + "|" + request.observations());
            beginCommand(identity, "RESULT_CREATE", idempotencyKey, requestHash);
            UUID resultId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_result(
                      tenant_id, result_id, patient_id, encounter_id, facility_id, order_id,
                      execution_task_id, report_type, source_system, source_report_key,
                      current_version_id, author_user_id)
                    values (:tenant, :result_id, :patient, :encounter, :facility, :order_id,
                      :task_id, :report_type, :source_system, :source_key, :version_id, :author)
                    """).param("tenant", identity.tenantId()).param("result_id", resultId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("order_id", execution.orderId())
                    .param("task_id", request.executionTaskId()).param("report_type", request.reportType().name())
                    .param("source_system", request.sourceSystem().trim())
                    .param("source_key", request.sourceReportKey().trim()).param("version_id", versionId)
                    .param("author", identity.userId()).update();
            insertVersion(identity, resultId, versionId, 1, "FINAL", request.conclusion(),
                    request.reportedAt(), "INITIAL", null, null);
            insertObservationsAndCriticalValues(
                    identity, resultId, versionId, request.patientId(), request.encounterId(),
                    request.facilityId(), request.observations());
            appendEvidence(identity, request.patientId(), "CLINICAL_RESULT", resultId, 1,
                    "RESULT_REPORTED", "ClinicalResultReported");
            completeCommand(identity, "RESULT_CREATE", idempotencyKey, 201, resultId);
            return snapshot(identity.tenantId(), resultId, request.patientId(),
                    request.encounterId(), request.facilityId());
        });
    }

    List<ClinicalResultWire> list(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, UUID facilityId) {
        requireReadableEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select result_id from clinical_result
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by updated_at desc, result_id
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> snapshot(identity.tenantId(), id, patientId, encounterId, facilityId)).toList();
    }

    ClinicalResultWire correct(
            ClinicalIdentity identity, String idempotencyKey, UUID resultId,
            ClinicalResultCorrectionRequestWire request) {
        validateReport(request.conclusion(), request.reportedAt(), request.observations(), request.correctionReason());
        if (request.expectedRowVersion() == null || blank(request.correctionReason())
                || request.correctionReason().length() > 1000) {
            throw new ResultException(
                    "RESULT_CORRECTION_INVALID", 400,
                    "Expected result version and correction reason are required");
        }
        return transactions.execute(status -> {
            LockedResult locked = lockResult(identity.tenantId(), resultId, request.patientId(),
                    request.encounterId(), request.facilityId());
            requireReportAuthorRole(identity, request.facilityId(), locked.reportType());
            if (locked.rowVersion() != request.expectedRowVersion()) throw resultVersionConflict();
            String requestHash = sha256(resultId + "|" + request.expectedRowVersion() + "|"
                    + request.correctionReason() + "|" + request.conclusion() + "|" + request.observations());
            beginCommand(identity, "RESULT_CORRECT", idempotencyKey, requestHash);
            UUID versionId = UUID.randomUUID();
            long versionNo = locked.versionNo() + 1;
            insertVersion(identity, resultId, versionId, versionNo, "CORRECTED", request.conclusion(),
                    request.reportedAt(), "CORRECTION", request.correctionReason().trim(), locked.currentVersionId());
            insertObservationsAndCriticalValues(
                    identity, resultId, versionId, request.patientId(), request.encounterId(),
                    request.facilityId(), request.observations());
            long rowVersion = jdbc.sql("""
                    update clinical_result set current_version_id = :version_id,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and result_id = :result_id and row_version = :expected
                    returning row_version
                    """).param("version_id", versionId).param("tenant", identity.tenantId())
                    .param("result_id", resultId).param("expected", request.expectedRowVersion())
                    .query(Long.class).optional().orElseThrow(ResultService::resultVersionConflict);
            appendEvidence(identity, request.patientId(), "CLINICAL_RESULT", resultId, rowVersion,
                    "RESULT_CORRECTED", "ClinicalResultCorrected");
            completeCommand(identity, "RESULT_CORRECT", idempotencyKey, 200, resultId);
            return snapshot(identity.tenantId(), resultId, request.patientId(),
                    request.encounterId(), request.facilityId());
        });
    }

    CriticalValueWire acknowledge(
            ClinicalIdentity identity, String idempotencyKey, UUID criticalValueId,
            CriticalValueAcknowledgeRequestWire request) {
        if (request.expectedRowVersion() == null || blank(request.notificationMethod())
                || request.notificationMethod().length() > 64 || !Boolean.TRUE.equals(request.recipientConfirmed())) {
            throw new ResultException(
                    "CRITICAL_ACKNOWLEDGEMENT_INVALID", 400,
                    "Notification method and explicit recipient read-back confirmation are required");
        }
        requireRole(identity, request.facilityId(), List.of(
                "CLINICIAN", "CHIEF_PHYSICIAN", "ATTENDING_PHYSICIAN", "SURGEON",
                "EMERGENCY_PHYSICIAN", "PEDIATRICIAN", "ICU_PHYSICIAN",
                "REGISTERED_NURSE", "NURSE_MANAGER"), "CRITICAL_VALUE_RECEIVER_ROLE_REQUIRED");
        return transactions.execute(status -> {
            LockedCritical critical = lockCritical(identity.tenantId(), criticalValueId, request.patientId(),
                    request.encounterId(), request.facilityId());
            if (critical.rowVersion() != request.expectedRowVersion()) throw criticalVersionConflict();
            if (!"OPEN".equals(critical.state())) {
                throw new ResultException("CRITICAL_STATE_INVALID", 409, "Only an open critical value can be acknowledged");
            }
            String requestHash = sha256(criticalValueId + "|" + request.expectedRowVersion() + "|"
                    + request.notificationMethod() + "|" + request.recipientConfirmed());
            beginCommand(identity, "CRITICAL_ACKNOWLEDGE", idempotencyKey, requestHash);
            long rowVersion = updateCriticalState(
                    identity.tenantId(), criticalValueId, request.expectedRowVersion(), "OPEN", "ACKNOWLEDGED");
            insertCriticalEvent(identity, criticalValueId, "ACKNOWLEDGED", request.notificationMethod().trim(),
                    true, null, null, null, null);
            completeCriticalTask(identity, criticalValueId, "CRITICAL_VALUE_RECEIPT", "ACKNOWLEDGED");
            createCriticalTask(identity, criticalValueId, request.patientId(), request.encounterId(),
                    request.facilityId(), "CRITICAL_VALUE_DISPOSITION", "危急值临床处置", "ACKNOWLEDGED");
            advanceCriticalWorkflow(identity, criticalValueId, request.patientId(), request.encounterId(),
                    request.facilityId(), "notification", Map.of(
                            "critical_value_state", "ACKNOWLEDGED",
                            "notification_method", request.notificationMethod().trim()));
            advanceCriticalWorkflow(identity, criticalValueId, request.patientId(), request.encounterId(),
                    request.facilityId(), "acknowledge", Map.of(
                            "critical_value_state", "ACKNOWLEDGED",
                            "recipient_confirmed", true));
            appendEvidence(identity, request.patientId(), "CRITICAL_VALUE", criticalValueId, rowVersion,
                    "CRITICAL_VALUE_ACKNOWLEDGED", "CriticalValueAcknowledged");
            completeCommand(identity, "CRITICAL_ACKNOWLEDGE", idempotencyKey, 200, criticalValueId);
            return criticalSnapshot(identity.tenantId(), criticalValueId);
        });
    }

    CriticalValueWire dispose(
            ClinicalIdentity identity, String idempotencyKey, UUID criticalValueId,
            CriticalValueDispositionRequestWire request) {
        if (request.expectedRowVersion() == null || blank(request.assessment()) || blank(request.actionTaken())
                || blank(request.outcome()) || request.retestRequired() == null
                || request.assessment().length() > 2000 || request.actionTaken().length() > 2000
                || request.outcome().length() > 2000) {
            throw new ResultException(
                    "CRITICAL_DISPOSITION_INVALID", 400,
                    "Assessment, action, outcome and retest decision are required");
        }
        requireRole(identity, request.facilityId(), List.of(
                "CLINICIAN", "CHIEF_PHYSICIAN", "ATTENDING_PHYSICIAN", "SURGEON",
                "EMERGENCY_PHYSICIAN", "PEDIATRICIAN", "ICU_PHYSICIAN"),
                "CRITICAL_VALUE_DISPOSITION_ROLE_REQUIRED");
        return transactions.execute(status -> {
            LockedCritical critical = lockCritical(identity.tenantId(), criticalValueId, request.patientId(),
                    request.encounterId(), request.facilityId());
            if (critical.rowVersion() != request.expectedRowVersion()) throw criticalVersionConflict();
            if (!"ACKNOWLEDGED".equals(critical.state())) {
                throw new ResultException(
                        "CRITICAL_STATE_INVALID", 409,
                        "Critical value receipt must be confirmed before clinical disposition");
            }
            String requestHash = sha256(criticalValueId + "|" + request.expectedRowVersion() + "|"
                    + request.assessment() + "|" + request.actionTaken() + "|" + request.outcome()
                    + "|" + request.retestRequired());
            beginCommand(identity, "CRITICAL_DISPOSE", idempotencyKey, requestHash);
            long rowVersion = updateCriticalState(identity.tenantId(), criticalValueId,
                    request.expectedRowVersion(), "ACKNOWLEDGED", "DISPOSED");
            insertCriticalEvent(identity, criticalValueId, "DISPOSED", null, null,
                    request.assessment().trim(), request.actionTaken().trim(), request.outcome().trim(),
                    request.retestRequired());
            completeCriticalTask(identity, criticalValueId, "CRITICAL_VALUE_DISPOSITION", "DISPOSED");
            advanceCriticalWorkflow(identity, criticalValueId, request.patientId(), request.encounterId(),
                    request.facilityId(), "action", Map.of(
                            "critical_value_state", "DISPOSED",
                            "retest_required", request.retestRequired()));
            appendEvidence(identity, request.patientId(), "CRITICAL_VALUE", criticalValueId, rowVersion,
                    "CRITICAL_VALUE_DISPOSED", "CriticalValueDisposed");
            completeCommand(identity, "CRITICAL_DISPOSE", idempotencyKey, 200, criticalValueId);
            return criticalSnapshot(identity.tenantId(), criticalValueId);
        });
    }

    private void validateReport(
            String conclusion, Instant reportedAt, List<ResultObservationInputWire> observations,
            String correctionReason) {
        if (blank(conclusion) || conclusion.length() > 4000 || reportedAt == null
                || observations == null || observations.isEmpty() || observations.size() > 500
                || (correctionReason != null && (blank(correctionReason) || correctionReason.length() > 1000))) {
            throw new ResultException(
                    "RESULT_REPORT_INVALID", 400, "Conclusion, report time and one to five hundred observations are required");
        }
        Set<String> codes = new HashSet<>();
        for (ResultObservationInputWire observation : observations) validateObservation(observation, codes);
    }

    private void validateObservation(ResultObservationInputWire observation, Set<String> codes) {
        boolean numeric = observation.valueType() == ResultObservationInputWire.ValueTypeValue.NUMERIC;
        boolean text = observation.valueType() == ResultObservationInputWire.ValueTypeValue.TEXT;
        boolean critical = observation.abnormalFlag() == ResultObservationInputWire.AbnormalFlagValue.CRITICAL_HIGH
                || observation.abnormalFlag() == ResultObservationInputWire.AbnormalFlagValue.CRITICAL_LOW;
        if (blank(observation.itemCode()) || observation.itemCode().length() > 128
                || !codes.add(observation.itemCode().trim()) || blank(observation.itemName())
                || observation.itemName().length() > 256 || observation.valueType() == null
                || observation.abnormalFlag() == null
                || (numeric && (observation.numericValue() == null || observation.textValue() != null
                    || blank(observation.unit())))
                || (text && (blank(observation.textValue()) || observation.numericValue() != null))
                || (!numeric && !text) || (critical && !numeric)
                || (observation.referenceLow() != null && observation.referenceHigh() != null
                    && observation.referenceLow() > observation.referenceHigh())) {
            throw new ResultException("RESULT_OBSERVATION_INVALID", 400, "Observation value, unit or reference range is invalid");
        }
    }

    private ExecutionFact requireCompletedExecution(
            UUID tenantId, UUID taskId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select task.order_id, item.item_type from order_execution_task task
                join clinical_order clinical_order on clinical_order.tenant_id = task.tenant_id
                  and clinical_order.order_id = task.order_id
                join clinical_order_item item on item.tenant_id = task.tenant_id
                  and item.order_item_id = task.order_item_id
                where task.tenant_id = :tenant and task.execution_task_id = :task_id
                  and task.patient_id = :patient and task.encounter_id = :encounter
                  and clinical_order.facility_id = :facility and task.task_state = 'COMPLETED'
                """).param("tenant", tenantId).param("task_id", taskId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new ExecutionFact(
                        rs.getObject("order_id", UUID.class), rs.getString("item_type")))
                .optional().orElseThrow(ResultService::contextDenied);
    }

    private void insertVersion(
            ClinicalIdentity identity, UUID resultId, UUID versionId, long versionNo,
            String reportStatus, String conclusion, Instant reportedAt, String changeType,
            String correctionReason, UUID supersedes) {
        jdbc.sql("""
                insert into clinical_result_version(
                  tenant_id, result_version_id, result_id, version_no, report_status,
                  conclusion, reported_at, change_type, correction_reason, supersedes_version_id, authored_by)
                values (:tenant, :version_id, :result_id, :version_no, :report_status,
                  :conclusion, :reported_at, :change_type, :reason, :supersedes, :author)
                """).param("tenant", identity.tenantId()).param("version_id", versionId)
                .param("result_id", resultId).param("version_no", versionNo)
                .param("report_status", reportStatus).param("conclusion", conclusion.trim())
                .param("reported_at", OffsetDateTime.ofInstant(reportedAt, ZoneOffset.UTC))
                .param("change_type", changeType).param("reason", correctionReason)
                .param("supersedes", supersedes).param("author", identity.userId()).update();
    }

    private void insertObservationsAndCriticalValues(
            ClinicalIdentity identity, UUID resultId, UUID versionId, UUID patientId,
            UUID encounterId, UUID facilityId, List<ResultObservationInputWire> observations) {
        for (ResultObservationInputWire observation : observations) {
            UUID observationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_result_observation(
                      tenant_id, observation_id, result_version_id, item_code, item_name,
                      value_type, numeric_value, text_value, unit, reference_low, reference_high, abnormal_flag)
                    values (:tenant, :observation_id, :version_id, :item_code, :item_name,
                      :value_type, :numeric_value, :text_value, :unit, :reference_low, :reference_high, :flag)
                    """).param("tenant", identity.tenantId()).param("observation_id", observationId)
                    .param("version_id", versionId).param("item_code", observation.itemCode().trim())
                    .param("item_name", observation.itemName().trim()).param("value_type", observation.valueType().name())
                    .param("numeric_value", decimal(observation.numericValue())).param("text_value", blankToNull(observation.textValue()))
                    .param("unit", blankToNull(observation.unit())).param("reference_low", decimal(observation.referenceLow()))
                    .param("reference_high", decimal(observation.referenceHigh()))
                    .param("flag", observation.abnormalFlag().name()).update();
            if (observation.abnormalFlag() == ResultObservationInputWire.AbnormalFlagValue.CRITICAL_HIGH
                    || observation.abnormalFlag() == ResultObservationInputWire.AbnormalFlagValue.CRITICAL_LOW) {
                UUID criticalValueId = UUID.randomUUID();
                jdbc.sql("""
                        insert into critical_value_case(
                          tenant_id, critical_value_id, result_id, observation_id,
                          patient_id, encounter_id, state)
                        values (:tenant, :critical_id, :result_id, :observation_id,
                          :patient, :encounter, 'OPEN')
                        """).param("tenant", identity.tenantId()).param("critical_id", criticalValueId)
                        .param("result_id", resultId).param("observation_id", observationId)
                        .param("patient", patientId).param("encounter", encounterId).update();
                insertCriticalEvent(identity, criticalValueId, "CREATED", null, null,
                        null, null, null, null);
                createCriticalTask(identity, criticalValueId, patientId, encounterId, facilityId,
                        "CRITICAL_VALUE_RECEIPT", observation.itemName().trim() + "危急值接收", "OPEN");
                startCriticalWorkflow(identity, criticalValueId, patientId, encounterId, facilityId,
                        observation.itemCode().trim(), observation.abnormalFlag().name());
            }
        }
    }

    /**
     * The result/critical-value state machine remains the source of truth. A published workflow
     * records the same completed business steps as a version-bound, optional projection.
     */
    private void startCriticalWorkflow(
            ClinicalIdentity identity, UUID criticalValueId, UUID patientId, UUID encounterId, UUID facilityId,
            String observationCode, String abnormalFlag) {
        RuntimeContext context = criticalWorkflowContext(identity.tenantId(), facilityId, patientId, encounterId);
        configurationRuntime.startWorkflowIfActive(identity, context, "critical-workflow-start-" + criticalValueId,
                "runtime-workflow-critical-result-loop-v1", new RuntimeCommandRequest(
                        "CRITICAL_VALUE", criticalValueId, Map.of(
                                "critical_value_state", "OPEN", "observation_code", observationCode,
                                "abnormal_flag", abnormalFlag)))
                .ifPresent(ignored -> advanceCriticalWorkflow(identity, criticalValueId, patientId, encounterId,
                        facilityId, "verification", Map.of("critical_value_state", "OPEN")));
    }

    private void advanceCriticalWorkflow(
            ClinicalIdentity identity, UUID criticalValueId, UUID patientId, UUID encounterId, UUID facilityId,
            String completedStep, Map<String, Object> facts) {
        RuntimeContext context = criticalWorkflowContext(identity.tenantId(), facilityId, patientId, encounterId);
        configurationRuntime.advanceWorkflowIfActive(identity, context,
                "critical-workflow-" + completedStep + "-" + criticalValueId,
                "runtime-workflow-critical-result-loop-v1", "CRITICAL_VALUE", criticalValueId, facts);
    }

    private RuntimeContext criticalWorkflowContext(
            UUID tenantId, UUID facilityId, UUID patientId, UUID encounterId) {
        UUID organizationId = jdbc.sql("""
                select organization_id from facility
                where tenant_id = :tenant and facility_id = :facility and status = 'ACTIVE'
                """).param("tenant", tenantId).param("facility", facilityId).query(UUID.class).single();
        return new RuntimeContext(organizationId, facilityId, patientId, encounterId);
    }

    private void createCriticalTask(
            ClinicalIdentity identity, UUID criticalValueId, UUID patientId, UUID encounterId,
            UUID facilityId, String taskType, String title, String businessState) {
        String careDomain = jdbc.sql("""
                select encounter_type from encounter where tenant_id = :tenant and encounter_id = :encounter
                """).param("tenant", identity.tenantId()).param("encounter", encounterId)
                .query(String.class).single();
        Duration defaultDue = "CRITICAL_VALUE_RECEIPT".equals(taskType)
                ? Duration.ofMinutes(10) : Duration.ofMinutes(30);
        ClinicalTaskRuleResolver.ResolvedTaskRule rule = taskRules.resolve(
                identity.tenantId(), taskType, careDomain, "CRITICAL", defaultDue);
        UUID taskId = jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level,
                  state, business_state, due_at, source_route, task_rule_config_id,
                  task_rule_version, rule_snapshot, escalation_at)
                select :tenant, gen_random_uuid(), :patient, :encounter, :facility,
                  'CRITICAL_VALUE', :critical_id, :task_type, :title, :risk_level,
                  'PENDING', :business_state, :due_at,
                  case encounter_type when 'INPATIENT' then '#/ip-results' else '#/opd-results' end,
                  :rule_config, :rule_version, cast(:rule_snapshot as jsonb), :escalation_at
                from encounter where tenant_id = :tenant and encounter_id = :encounter
                on conflict (tenant_id, source_type, source_id, task_type) do nothing
                returning task_id
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .param("critical_id", criticalValueId).param("task_type", taskType)
                .param("title", title).param("business_state", businessState)
                .param("risk_level", rule.riskLevel()).param("due_at", rule.dueAt())
                .param("rule_config", rule.configId()).param("rule_version", rule.configVersion())
                .param("rule_snapshot", rule.snapshotJson()).param("escalation_at", rule.escalationAt())
                .query(UUID.class).optional().orElse(null);
        if (taskId == null) return;
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type,
                  previous_state, resulting_state, actor_user_id)
                values (:tenant, gen_random_uuid(), :task_id, 'CREATED', null, 'PENDING', :actor)
                """).param("tenant", identity.tenantId()).param("task_id", taskId)
                .param("actor", identity.userId()).update();
        appendCriticalTaskOutbox(identity.tenantId(), taskId, criticalValueId, 1, "ClinicalTaskCreated");
    }

    private void completeCriticalTask(
            ClinicalIdentity identity, UUID criticalValueId, String taskType, String businessState) {
        CriticalTaskProjection task = jdbc.sql("""
                select task_id, state, row_version from clinical_task
                where tenant_id = :tenant and source_type = 'CRITICAL_VALUE'
                  and source_id = :critical_id and task_type = :task_type for update
                """).param("tenant", identity.tenantId()).param("critical_id", criticalValueId)
                .param("task_type", taskType)
                .query((rs, row) -> new CriticalTaskProjection(
                        rs.getObject("task_id", UUID.class), rs.getString("state"), rs.getLong("row_version")))
                .optional().orElse(null);
        if (task == null || "COMPLETED".equals(task.state())) return;
        long nextVersion = jdbc.sql("""
                update clinical_task set state = 'COMPLETED', business_state = :business_state,
                  claimed_by = coalesce(claimed_by, :actor), row_version = row_version + 1,
                  updated_at = now()
                where tenant_id = :tenant and task_id = :task_id and row_version = :expected
                returning row_version
                """).param("business_state", businessState).param("actor", identity.userId())
                .param("tenant", identity.tenantId()).param("task_id", task.taskId())
                .param("expected", task.rowVersion()).query(Long.class).single();
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type,
                  previous_state, resulting_state, actor_user_id)
                values (:tenant, gen_random_uuid(), :task_id, 'SOURCE_COMPLETED',
                  :previous, 'COMPLETED', :actor)
                """).param("tenant", identity.tenantId()).param("task_id", task.taskId())
                .param("previous", task.state()).param("actor", identity.userId()).update();
        appendCriticalTaskOutbox(
                identity.tenantId(), task.taskId(), criticalValueId, nextVersion,
                "ClinicalTaskSourceCompleted");
    }

    private void appendCriticalTaskOutbox(
            UUID tenantId, UUID taskId, UUID criticalValueId, long version, String eventType) {
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, gen_random_uuid(), 'CLINICAL_TASK', :task_id, :version,
                  :event_type, 1, jsonb_build_object(
                    'task_id', :task_id, 'critical_value_id', :critical_id))
                """).param("tenant", tenantId).param("task_id", taskId).param("version", version)
                .param("event_type", eventType).param("critical_id", criticalValueId).update();
    }

    private void insertCriticalEvent(
            ClinicalIdentity identity, UUID criticalValueId, String eventType,
            String notificationMethod, Boolean recipientConfirmed, String assessment,
            String actionTaken, String outcome, Boolean retestRequired) {
        jdbc.sql("""
                insert into critical_value_event(
                  tenant_id, critical_value_event_id, critical_value_id, event_type, actor_user_id,
                  notification_method, recipient_confirmed, assessment, action_taken, outcome, retest_required)
                values (:tenant, :event_id, :critical_id, :event_type, :actor,
                  :method, :confirmed, :assessment, :action, :outcome, :retest)
                """).param("tenant", identity.tenantId()).param("event_id", UUID.randomUUID())
                .param("critical_id", criticalValueId).param("event_type", eventType)
                .param("actor", identity.userId()).param("method", notificationMethod)
                .param("confirmed", recipientConfirmed).param("assessment", assessment)
                .param("action", actionTaken).param("outcome", outcome).param("retest", retestRequired).update();
    }

    private long updateCriticalState(
            UUID tenantId, UUID criticalValueId, long expected, String previous, String next) {
        return jdbc.sql("""
                update critical_value_case set state = :next, row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and critical_value_id = :critical_id
                  and state = :previous and row_version = :expected
                returning row_version
                """).param("next", next).param("tenant", tenantId).param("critical_id", criticalValueId)
                .param("previous", previous).param("expected", expected).query(Long.class)
                .optional().orElseThrow(ResultService::criticalVersionConflict);
    }

    private LockedResult lockResult(
            UUID tenantId, UUID resultId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select result.current_version_id, result.row_version, version.version_no, result.report_type
                from clinical_result result
                join clinical_result_version version on version.tenant_id = result.tenant_id
                  and version.result_version_id = result.current_version_id
                where result.tenant_id = :tenant and result.result_id = :result_id
                  and result.patient_id = :patient and result.encounter_id = :encounter
                  and result.facility_id = :facility
                for update of result
                """).param("tenant", tenantId).param("result_id", resultId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new LockedResult(
                        rs.getObject("current_version_id", UUID.class), rs.getLong("row_version"),
                        rs.getLong("version_no"), rs.getString("report_type")))
                .optional().orElseThrow(ResultService::contextDenied);
    }

    private void requireReportAuthorRole(ClinicalIdentity identity, UUID facilityId, String reportType) {
        List<String> allowedRoles = "LAB".equals(reportType)
                ? List.of("LAB_TECHNICIAN")
                : List.of("RADIOLOGIST");
        requireRole(identity, facilityId, allowedRoles,
                "LAB".equals(reportType) ? "LAB_RESULT_AUTHOR_ROLE_REQUIRED" : "IMAGING_RESULT_AUTHOR_ROLE_REQUIRED");
    }

    private void requireRole(
            ClinicalIdentity identity, UUID facilityId, List<String> allowedRoles, String failureCode) {
        long count = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id=:tenant and user_id=:user and role_assignment_id in (:assignments)
                  and role_code in (:roles) and status='ACTIVE' and valid_from<=now()
                  and (valid_until is null or valid_until>now())
                  and (facility_id is null or facility_id=:facility)
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("roles", allowedRoles)
                .param("facility", facilityId).query(Long.class).single();
        if (count < 1) {
            throw new ResultException(failureCode, 403,
                    "The active role assignment is not permitted to perform this result workflow action");
        }
    }

    private LockedCritical lockCritical(
            UUID tenantId, UUID criticalValueId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select critical.state, critical.row_version from critical_value_case critical
                join clinical_result result on result.tenant_id = critical.tenant_id
                  and result.result_id = critical.result_id
                where critical.tenant_id = :tenant and critical.critical_value_id = :critical_id
                  and critical.patient_id = :patient and critical.encounter_id = :encounter
                  and result.facility_id = :facility
                for update of critical
                """).param("tenant", tenantId).param("critical_id", criticalValueId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new LockedCritical(rs.getString("state"), rs.getLong("row_version")))
                .optional().orElseThrow(ResultService::contextDenied);
    }

    private ClinicalResultWire snapshot(
            UUID tenantId, UUID resultId, UUID patientId, UUID encounterId, UUID facilityId) {
        ResultHead head = jdbc.sql("""
                select result.patient_id, result.encounter_id, result.order_id, result.execution_task_id,
                  result.report_type, result.source_system, result.source_report_key, result.row_version,
                  version.result_version_id, version.report_status, version.conclusion,
                  version.reported_at, version.version_no
                from clinical_result result
                join clinical_result_version version on version.tenant_id = result.tenant_id
                  and version.result_version_id = result.current_version_id
                where result.tenant_id = :tenant and result.result_id = :result_id
                  and result.patient_id = :patient and result.encounter_id = :encounter
                  and result.facility_id = :facility
                """).param("tenant", tenantId).param("result_id", resultId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new ResultHead(
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getObject("execution_task_id", UUID.class),
                        rs.getString("report_type"), rs.getString("source_system"), rs.getString("source_report_key"),
                        rs.getLong("row_version"), rs.getObject("result_version_id", UUID.class),
                        rs.getString("report_status"), rs.getString("conclusion"),
                        rs.getObject("reported_at", OffsetDateTime.class).toInstant(), rs.getLong("version_no")))
                .optional().orElseThrow(ResultService::contextDenied);
        List<ClinicalResultObservationWire> observations = jdbc.sql("""
                select observation_id, item_code, item_name, value_type, numeric_value, text_value,
                  unit, reference_low, reference_high, abnormal_flag
                from clinical_result_observation
                where tenant_id = :tenant and result_version_id = :version_id
                order by created_at, observation_id
                """).param("tenant", tenantId).param("version_id", head.versionId())
                .query((rs, row) -> new ClinicalResultObservationWire(
                        rs.getObject("observation_id", UUID.class), rs.getString("item_code"),
                        rs.getString("item_name"),
                        ClinicalResultObservationWire.ValueTypeValue.valueOf(rs.getString("value_type")),
                        number(rs.getBigDecimal("numeric_value")), rs.getString("text_value"), rs.getString("unit"),
                        number(rs.getBigDecimal("reference_low")), number(rs.getBigDecimal("reference_high")),
                        ClinicalResultObservationWire.AbnormalFlagValue.valueOf(rs.getString("abnormal_flag"))))
                .list();
        List<CriticalValueWire> criticalValues = jdbc.sql("""
                select critical_value_id, result_id, observation_id, state, row_version
                from critical_value_case where tenant_id = :tenant and result_id = :result_id
                order by created_at, critical_value_id
                """).param("tenant", tenantId).param("result_id", resultId)
                .query((rs, row) -> criticalWire(
                        rs.getObject("critical_value_id", UUID.class), rs.getObject("result_id", UUID.class),
                        rs.getObject("observation_id", UUID.class), rs.getString("state"),
                        rs.getLong("row_version"))).list();
        String watermark = sha256(resultId + "|" + head.reportStatus() + "|" + head.rowVersion() + "|"
                + head.versionNo() + "|" + observations.stream()
                .map(item -> item.itemCode() + ":" + item.abnormalFlag()).toList() + "|"
                + criticalValues.stream().map(item -> item.state() + ":" + item.rowVersion()).toList());
        return new ClinicalResultWire(
                resultId, head.patientId(), head.encounterId(), head.orderId(), head.executionTaskId(),
                ClinicalResultWire.ReportTypeValue.valueOf(head.reportType()), head.sourceSystem(),
                head.sourceReportKey(), ClinicalResultWire.ReportStatusValue.valueOf(head.reportStatus()),
                head.conclusion(), head.reportedAt(), observations, criticalValues,
                head.versionNo(), head.rowVersion(), watermark);
    }

    private CriticalValueWire criticalSnapshot(UUID tenantId, UUID criticalValueId) {
        return jdbc.sql("""
                select critical_value_id, result_id, observation_id, state, row_version
                from critical_value_case where tenant_id = :tenant and critical_value_id = :critical_id
                """).param("tenant", tenantId).param("critical_id", criticalValueId)
                .query((rs, row) -> criticalWire(
                        rs.getObject("critical_value_id", UUID.class), rs.getObject("result_id", UUID.class),
                        rs.getObject("observation_id", UUID.class), rs.getString("state"),
                        rs.getLong("row_version"))).single();
    }

    private static CriticalValueWire criticalWire(
            UUID criticalValueId, UUID resultId, UUID observationId, String state, long rowVersion) {
        return new CriticalValueWire(criticalValueId, resultId, observationId,
                CriticalValueWire.StateValue.valueOf(state), rowVersion);
    }

    private void requireEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter where tenant_id = :tenant and encounter_id = :encounter
                  and patient_id = :patient and facility_id = :facility and status = 'IN_PROGRESS'
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void requireReadableEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter where tenant_id = :tenant and encounter_id = :encounter
                  and patient_id = :patient and facility_id = :facility
                  and status in ('PLANNED', 'ARRIVED', 'IN_PROGRESS', 'SUSPENDED', 'FINISHED')
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (blank(key) || key.length() > 128) {
            throw new ResultException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ResultException("IDEMPOTENCY_REPLAY", 409, "This result command key was already used");
        }
    }

    private void completeCommand(
            ClinicalIdentity identity, String scope, String key, int responseStatus, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", responseStatus).param("resource", resourceId)
                .param("tenant", identity.tenantId()).param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, String resourceType, UUID resourceId,
            long aggregateVersion, String actionCode, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + actionCode + "|"
                + resourceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, :resource_type, :resource_id,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", actionCode).param("resource_type", resourceType)
                .param("resource_id", resourceId).param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event_id, :aggregate_type, :resource_id, :aggregate_version,
                  :event_type, 1, jsonb_build_object('resource_id', :resource_id))
                """).param("tenant", identity.tenantId()).param("event_id", UUID.randomUUID())
                .param("aggregate_type", resourceType).param("resource_id", resourceId)
                .param("aggregate_version", aggregateVersion).param("event_type", eventType).update();
    }

    private static ResultException resultVersionConflict() {
        return new ResultException("RESULT_VERSION_CONFLICT", 409, "The result changed; reload before retrying");
    }

    private static ResultException criticalVersionConflict() {
        return new ResultException(
                "CRITICAL_VALUE_VERSION_CONFLICT", 409, "The critical value changed; reload before retrying");
    }

    static ResultException contextDenied() {
        return new ResultException("CONTEXT_NOT_PERMITTED", 403, "The requested result context is not permitted");
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(6);
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
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

    private record ExecutionFact(UUID orderId, String itemType) {}
    private record LockedResult(UUID currentVersionId, long rowVersion, long versionNo, String reportType) {}
    private record LockedCritical(String state, long rowVersion) {}
    private record CriticalTaskProjection(UUID taskId, String state, long rowVersion) {}
    private record ResultHead(
            UUID patientId, UUID encounterId, UUID orderId, UUID executionTaskId,
            String reportType, String sourceSystem, String sourceReportKey, long rowVersion,
            UUID versionId, String reportStatus, String conclusion, Instant reportedAt, long versionNo) {}
}
