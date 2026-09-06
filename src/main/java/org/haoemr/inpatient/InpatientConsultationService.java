package org.haoemr.inpatient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.InpatientConsultationActionRequestWire;
import org.haoemr.contracts.InpatientConsultationCreateRequestWire;
import org.haoemr.contracts.InpatientConsultationOpinionRequestWire;
import org.haoemr.contracts.InpatientConsultationRejectRequestWire;
import org.haoemr.contracts.InpatientConsultationWire;
import org.haoemr.security.ClinicalIdentity;
import org.haoemr.tasks.ClinicalTaskRuleResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class InpatientConsultationService {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ClinicalTaskRuleResolver taskRules;

    InpatientConsultationService(
            JdbcClient jdbc, TransactionTemplate transactions, ClinicalTaskRuleResolver taskRules) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.taskRules = taskRules;
    }

    List<InpatientConsultationWire> list(
            ClinicalIdentity identity, UUID admissionId, UUID organizationId,
            UUID facilityId, UUID patientId, UUID encounterId) {
        AdmissionContext admission = requireAdmission(
                identity, admissionId, organizationId, facilityId, patientId, encounterId, false, false);
        requireWardScope(identity, admission.facilityId(), admission.wardId());
        return jdbc.sql("""
                select * from inpatient_consultation
                where tenant_id = :tenant and admission_id = :admission
                order by requested_at desc, consultation_id desc
                """).param("tenant", identity.tenantId()).param("admission", admissionId)
                .query((rs, row) -> map(new ConsultationRow(
                        rs.getObject("consultation_id", UUID.class), rs.getObject("admission_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getString("requested_department"), rs.getString("urgency"), rs.getString("reason"),
                        rs.getString("clinical_question"), rs.getString("status"),
                        instant(rs.getObject("due_at", OffsetDateTime.class)),
                        rs.getObject("requested_by", UUID.class), instant(rs.getObject("requested_at", OffsetDateTime.class)),
                        rs.getObject("accepted_by", UUID.class), instant(rs.getObject("accepted_at", OffsetDateTime.class)),
                        rs.getString("rejection_reason"), rs.getString("opinion"), rs.getString("recommendation"),
                        rs.getObject("opinion_signed_by", UUID.class), instant(rs.getObject("opinion_signed_at", OffsetDateTime.class)),
                        rs.getObject("completed_by", UUID.class), instant(rs.getObject("completed_at", OffsetDateTime.class)),
                        rs.getLong("row_version")))).list();
    }

    InpatientConsultationWire create(
            ClinicalIdentity identity, String idempotencyKey, UUID admissionId,
            InpatientConsultationCreateRequestWire request) {
        return transactions.execute(status -> {
            AdmissionContext admission = requireAdmission(
                    identity, admissionId, request.organizationId(), request.facilityId(),
                    request.patientId(), request.encounterId(), true, true);
            requireWardScope(identity, admission.facilityId(), admission.wardId());
            String department = requireText(request.requestedDepartment(), 2, 128, "requested_department");
            String reason = requireText(request.reason(), 4, 1000, "reason");
            String question = requireText(request.clinicalQuestion(), 4, 2000, "clinical_question");
            if (request.urgency() == null || request.dueAt() == null || !request.dueAt().isAfter(Instant.now())) {
                throw invalid("urgency and a future due_at are required");
            }
            String requestHash = sha256(admissionId + "|" + department + "|" + request.urgency()
                    + "|" + reason + "|" + question + "|" + request.dueAt());
            beginCommand(identity, "INPATIENT_CONSULTATION_CREATE", idempotencyKey, requestHash);
            UUID consultationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into inpatient_consultation(
                      tenant_id, consultation_id, admission_id, organization_id, facility_id,
                      patient_id, encounter_id, requested_department, urgency, reason,
                      clinical_question, status, due_at, requested_by)
                    values (:tenant, :consultation, :admission, :organization, :facility,
                      :patient, :encounter, :department, :urgency, :reason,
                      :question, 'REQUESTED', :due_at, :actor)
                    """).param("tenant", identity.tenantId()).param("consultation", consultationId)
                    .param("admission", admissionId).param("organization", request.organizationId())
                    .param("facility", request.facilityId()).param("patient", request.patientId())
                    .param("encounter", request.encounterId()).param("department", department)
                    .param("urgency", request.urgency().name()).param("reason", reason)
                    .param("question", question).param("due_at", request.dueAt().atOffset(java.time.ZoneOffset.UTC))
                    .param("actor", identity.userId()).update();
            appendEvidence(identity, request.patientId(), consultationId, 1,
                    "INPATIENT_CONSULTATION_REQUESTED", "InpatientConsultationRequested");
            createConsultationTask(identity, consultationId, request.patientId(), request.encounterId(),
                    request.facilityId(), request.urgency().name(),
                    request.dueAt().atOffset(java.time.ZoneOffset.UTC), department, reason);
            completeCommand(identity, "INPATIENT_CONSULTATION_CREATE", idempotencyKey, 201, consultationId);
            return get(identity, consultationId, request.patientId(), request.encounterId());
        });
    }

    InpatientConsultationWire accept(
            ClinicalIdentity identity, String idempotencyKey, UUID consultationId,
            InpatientConsultationActionRequestWire request) {
        return change(identity, idempotencyKey, consultationId, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId(), request.expectedRowVersion(), Action.ACCEPT, null, null);
    }

    InpatientConsultationWire reject(
            ClinicalIdentity identity, String idempotencyKey, UUID consultationId,
            InpatientConsultationRejectRequestWire request) {
        String reason = requireText(request.reason(), 4, 1000, "reason");
        return change(identity, idempotencyKey, consultationId, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId(), request.expectedRowVersion(), Action.REJECT, reason, null);
    }

    InpatientConsultationWire signOpinion(
            ClinicalIdentity identity, String idempotencyKey, UUID consultationId,
            InpatientConsultationOpinionRequestWire request) {
        String opinion = requireText(request.opinion(), 4, 8000, "opinion");
        String recommendation = requireText(request.recommendation(), 4, 8000, "recommendation");
        return change(identity, idempotencyKey, consultationId, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId(), request.expectedRowVersion(),
                Action.SIGN_OPINION, opinion, recommendation);
    }

    InpatientConsultationWire complete(
            ClinicalIdentity identity, String idempotencyKey, UUID consultationId,
            InpatientConsultationActionRequestWire request) {
        return change(identity, idempotencyKey, consultationId, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId(), request.expectedRowVersion(), Action.COMPLETE, null, null);
    }

    private InpatientConsultationWire change(
            ClinicalIdentity identity, String idempotencyKey, UUID consultationId,
            UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId,
            Long expectedRowVersion, Action action, String firstText, String secondText) {
        return transactions.execute(status -> {
            ConsultationContext current = lock(identity, consultationId, organizationId, facilityId, patientId, encounterId);
            requireWardScope(identity, current.facilityId(), current.wardId());
            if (!List.of("ADMITTED", "TRANSFER_PENDING", "DISCHARGE_PENDING").contains(current.admissionStatus())) {
                throw new InpatientException("ADMISSION_NOT_ACTIVE", 409,
                        "A historical consultation remains readable but can no longer change");
            }
            if (expectedRowVersion == null || current.rowVersion() != expectedRowVersion) {
                throw new InpatientException("CONSULTATION_VERSION_CONFLICT", 409,
                        "The consultation changed; reload before retrying");
            }
            validateTransition(identity, current, action);
            String scope = "INPATIENT_CONSULTATION_" + action.name();
            beginCommand(identity, scope, idempotencyKey,
                    sha256(consultationId + "|" + expectedRowVersion + "|" + firstText + "|" + secondText));
            String sql = switch (action) {
                case ACCEPT -> """
                        update inpatient_consultation set status='ACCEPTED', accepted_by=:actor,
                          accepted_at=now(), row_version=row_version+1, updated_at=now()
                        where tenant_id=:tenant and consultation_id=:consultation and row_version=:expected
                        """;
                case REJECT -> """
                        update inpatient_consultation set status='REJECTED', rejection_reason=:first,
                          rejected_by=:actor, rejected_at=now(), row_version=row_version+1, updated_at=now()
                        where tenant_id=:tenant and consultation_id=:consultation and row_version=:expected
                        """;
                case SIGN_OPINION -> """
                        update inpatient_consultation set status='OPINION_SIGNED', opinion=:first,
                          recommendation=:second, opinion_signed_by=:actor, opinion_signed_at=now(),
                          row_version=row_version+1, updated_at=now()
                        where tenant_id=:tenant and consultation_id=:consultation and row_version=:expected
                        """;
                case COMPLETE -> """
                        update inpatient_consultation set status='COMPLETED', completed_by=:actor,
                          completed_at=now(), row_version=row_version+1, updated_at=now()
                        where tenant_id=:tenant and consultation_id=:consultation and row_version=:expected
                        """;
            };
            JdbcClient.StatementSpec statement = jdbc.sql(sql).param("tenant", identity.tenantId())
                    .param("consultation", consultationId).param("expected", expectedRowVersion)
                    .param("actor", identity.userId());
            if (action == Action.REJECT || action == Action.SIGN_OPINION) statement = statement.param("first", firstText);
            if (action == Action.SIGN_OPINION) statement = statement.param("second", secondText);
            if (statement.update() != 1) {
                throw new InpatientException("CONSULTATION_VERSION_CONFLICT", 409,
                        "The consultation changed; reload before retrying");
            }
            long newVersion = current.rowVersion() + 1;
            appendEvidence(identity, patientId, consultationId, newVersion, action.auditCode, action.eventType);
            if (action == Action.COMPLETE) {
                settleConsultationTask(identity, consultationId, "COMPLETED", "SOURCE_COMPLETED");
            } else if (action == Action.REJECT) {
                settleConsultationTask(identity, consultationId, "WITHDRAWN", "SOURCE_WITHDRAWN");
            }
            completeCommand(identity, scope, idempotencyKey, 200, consultationId);
            return get(identity, consultationId, patientId, encounterId);
        });
    }

    private void createConsultationTask(
            ClinicalIdentity identity, UUID consultationId, UUID patientId, UUID encounterId,
            UUID facilityId, String urgency, OffsetDateTime dueAt, String department, String reason) {
        String defaultRisk = switch (urgency) {
            case "EMERGENCY" -> "CRITICAL";
            case "URGENT" -> "HIGH";
            default -> "ROUTINE";
        };
        Duration defaultDue = switch (urgency) {
            case "EMERGENCY" -> Duration.ofMinutes(10);
            case "URGENT" -> Duration.ofHours(2);
            default -> Duration.ofHours(24);
        };
        ClinicalTaskRuleResolver.ResolvedTaskRule rule = taskRules.resolve(
                identity.tenantId(), "CONSULTATION_RESPONSE", "INPATIENT", defaultRisk, defaultDue);
        OffsetDateTime effectiveDue = dueAt.isBefore(rule.dueAt()) ? dueAt : rule.dueAt();
        String fullTitle = "会诊：" + department + " · " + reason;
        String title = fullTitle.length() > 250 ? fullTitle.substring(0, 250) : fullTitle;
        UUID taskId = jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level,
                  state, business_state, due_at, source_route, task_rule_config_id,
                  task_rule_version, rule_snapshot, escalation_at)
                values (:tenant, gen_random_uuid(), :patient, :encounter, :facility,
                  'CONSULTATION', :consultation, 'CONSULTATION_RESPONSE', :title, :risk,
                  'PENDING', 'REQUESTED', :due_at, '#/ip-consult', :rule_config,
                  :rule_version, cast(:rule_snapshot as jsonb), :escalation_at)
                on conflict (tenant_id, source_type, source_id, task_type) do nothing
                returning task_id
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .param("consultation", consultationId).param("title", title)
                .param("risk", rule.riskLevel()).param("due_at", effectiveDue)
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
    }

    private void settleConsultationTask(
            ClinicalIdentity identity, UUID consultationId, String targetState, String eventType) {
        ConsultationTaskProjection task = jdbc.sql("""
                select task_id, state, row_version from clinical_task
                where tenant_id = :tenant and source_type = 'CONSULTATION'
                  and source_id = :consultation and task_type = 'CONSULTATION_RESPONSE' for update
                """).param("tenant", identity.tenantId()).param("consultation", consultationId)
                .query((rs, row) -> new ConsultationTaskProjection(
                        rs.getObject("task_id", UUID.class), rs.getString("state"), rs.getLong("row_version")))
                .optional().orElse(null);
        if (task == null || "COMPLETED".equals(task.state()) || "WITHDRAWN".equals(task.state())) return;
        jdbc.sql("""
                update clinical_task set state = :target, business_state = :target,
                  claimed_by = coalesce(claimed_by, :actor), row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and task_id = :task_id and row_version = :expected
                """).param("target", targetState).param("actor", identity.userId())
                .param("tenant", identity.tenantId()).param("task_id", task.taskId())
                .param("expected", task.rowVersion()).update();
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type, previous_state, resulting_state, actor_user_id)
                values (:tenant, gen_random_uuid(), :task_id, :event_type, :previous, :target, :actor)
                """).param("tenant", identity.tenantId()).param("task_id", task.taskId())
                .param("event_type", eventType).param("previous", task.state())
                .param("target", targetState).param("actor", identity.userId()).update();
    }

    private void validateTransition(ClinicalIdentity identity, ConsultationContext current, Action action) {
        switch (action) {
            case ACCEPT, REJECT -> {
                if (!"REQUESTED".equals(current.status())) invalidState("Only a requested consultation can be accepted or rejected");
                if (identity.userId().equals(current.requestedBy())) {
                    throw new InpatientException("CONSULTATION_SELF_ACCEPT_FORBIDDEN", 409,
                            "The requester cannot accept or reject their own consultation");
                }
            }
            case SIGN_OPINION -> {
                if (!"ACCEPTED".equals(current.status())) invalidState("Only an accepted consultation can receive an opinion");
                if (!identity.userId().equals(current.acceptedBy())) {
                    throw new InpatientException("CONSULTATION_ASSIGNEE_REQUIRED", 403,
                            "Only the accepting clinician can sign the consultation opinion");
                }
            }
            case COMPLETE -> {
                if (!"OPINION_SIGNED".equals(current.status())) invalidState("Only a signed opinion can be completed");
                if (!identity.userId().equals(current.requestedBy())) {
                    throw new InpatientException("CONSULTATION_REQUESTER_REQUIRED", 403,
                            "Only the requesting clinician can complete the consultation");
                }
            }
        }
    }

    private ConsultationContext lock(
            ClinicalIdentity identity, UUID consultationId, UUID organizationId,
            UUID facilityId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select c.status, c.requested_by, c.accepted_by, c.row_version,
                  a.facility_id, a.ward_id, a.status as admission_status
                from inpatient_consultation c
                join inpatient_admission a on a.tenant_id=c.tenant_id and a.admission_id=c.admission_id
                where c.tenant_id=:tenant and c.consultation_id=:consultation
                  and c.organization_id=:organization and c.facility_id=:facility
                  and c.patient_id=:patient and c.encounter_id=:encounter
                for update of c, a
                """).param("tenant", identity.tenantId()).param("consultation", consultationId)
                .param("organization", organizationId).param("facility", facilityId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new ConsultationContext(
                        rs.getString("status"), rs.getObject("requested_by", UUID.class),
                        rs.getObject("accepted_by", UUID.class), rs.getLong("row_version"),
                        rs.getObject("facility_id", UUID.class), rs.getObject("ward_id", UUID.class),
                        rs.getString("admission_status"))).optional().orElseThrow(InpatientConsultationService::contextDenied);
    }

    private AdmissionContext requireAdmission(
            ClinicalIdentity identity, UUID admissionId, UUID organizationId, UUID facilityId,
            UUID patientId, UUID encounterId, boolean lock, boolean requireActive) {
        String sql = """
                select admission.facility_id, admission.ward_id, admission.status
                from inpatient_admission admission
                join encounter on encounter.tenant_id=admission.tenant_id
                  and encounter.encounter_id=admission.encounter_id
                where admission.tenant_id=:tenant and admission.admission_id=:admission
                  and encounter.organization_id=:organization
                  and admission.facility_id=:facility and admission.patient_id=:patient
                  and admission.encounter_id=:encounter
                """ + (lock ? " for update" : "");
        AdmissionContext admission = jdbc.sql(sql).param("tenant", identity.tenantId()).param("admission", admissionId)
                .param("organization", organizationId).param("facility", facilityId).param("patient", patientId)
                .param("encounter", encounterId).query((rs, row) -> new AdmissionContext(
                        rs.getObject("facility_id", UUID.class), rs.getObject("ward_id", UUID.class),
                        rs.getString("status"))).optional().orElseThrow(InpatientConsultationService::contextDenied);
        if (requireActive && !List.of("ADMITTED", "TRANSFER_PENDING", "DISCHARGE_PENDING").contains(admission.status())) {
            throw new InpatientException("ADMISSION_NOT_ACTIVE", 409, "The admission is not active");
        }
        return admission;
    }

    private InpatientConsultationWire get(ClinicalIdentity identity, UUID consultationId, UUID patientId, UUID encounterId) {
        return jdbc.sql("select * from inpatient_consultation where tenant_id=:tenant and consultation_id=:consultation and patient_id=:patient and encounter_id=:encounter")
                .param("tenant", identity.tenantId()).param("consultation", consultationId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> map(new ConsultationRow(
                        rs.getObject("consultation_id", UUID.class), rs.getObject("admission_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getString("requested_department"), rs.getString("urgency"), rs.getString("reason"),
                        rs.getString("clinical_question"), rs.getString("status"), instant(rs.getObject("due_at", OffsetDateTime.class)),
                        rs.getObject("requested_by", UUID.class), instant(rs.getObject("requested_at", OffsetDateTime.class)),
                        rs.getObject("accepted_by", UUID.class), instant(rs.getObject("accepted_at", OffsetDateTime.class)),
                        rs.getString("rejection_reason"), rs.getString("opinion"), rs.getString("recommendation"),
                        rs.getObject("opinion_signed_by", UUID.class), instant(rs.getObject("opinion_signed_at", OffsetDateTime.class)),
                        rs.getObject("completed_by", UUID.class), instant(rs.getObject("completed_at", OffsetDateTime.class)),
                        rs.getLong("row_version")))).optional().orElseThrow(InpatientConsultationService::contextDenied);
    }

    private InpatientConsultationWire map(ConsultationRow row) {
        boolean overdue = List.of("REQUESTED", "ACCEPTED").contains(row.status()) && row.dueAt().isBefore(Instant.now());
        String watermark = sha256(row.consultationId() + "|" + row.status() + "|" + row.rowVersion()
                + "|" + row.dueAt() + "|" + row.opinionSignedAt());
        return new InpatientConsultationWire(
                row.consultationId(), row.admissionId(), row.patientId(), row.encounterId(),
                row.requestedDepartment(), InpatientConsultationWire.UrgencyValue.valueOf(row.urgency()),
                row.reason(), row.clinicalQuestion(), InpatientConsultationWire.StatusValue.valueOf(row.status()),
                row.dueAt(), row.requestedBy(), row.requestedAt(), row.acceptedBy(), row.acceptedAt(),
                row.rejectionReason(), row.opinion(), row.recommendation(), row.opinionSignedBy(),
                row.opinionSignedAt(), row.completedBy(), row.completedAt(), overdue, row.rowVersion(), watermark);
    }

    private void requireWardScope(ClinicalIdentity identity, UUID facilityId, UUID wardId) {
        String roles = "{" + identity.roleAssignmentIds().stream().map(UUID::toString)
                .reduce((left, right) -> left + "," + right).orElse("") + "}";
        long count = jdbc.sql("""
                select count(*) from ward_role_scope scope
                join clinical_ward ward on ward.tenant_id=scope.tenant_id and ward.ward_id=scope.ward_id
                join role_assignment assignment on assignment.tenant_id=scope.tenant_id
                  and assignment.role_assignment_id=scope.role_assignment_id
                where scope.tenant_id=:tenant and scope.ward_id=:ward and ward.facility_id=:facility
                  and scope.role_assignment_id=any(cast(:roles as uuid[]))
                  and scope.valid_from<=now() and (scope.valid_until is null or scope.valid_until>now())
                  and assignment.user_id=:user and assignment.status='ACTIVE'
                  and assignment.valid_from<=now() and (assignment.valid_until is null or assignment.valid_until>now())
                """).param("tenant", identity.tenantId()).param("ward", wardId).param("facility", facilityId)
                .param("roles", roles).param("user", identity.userId()).query(Long.class).single();
        if (count < 1) throw new InpatientException("WARD_SCOPE_DENIED", 403, "The current role has no active scope for this ward");
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InpatientException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(tenant_id, command_scope, idempotency_key, request_hash,
                  state, trace_id, expires_at)
                values (:tenant,:scope,:key,:hash,'IN_PROGRESS',:trace,now()+interval '24 hours')
                on conflict (tenant_id,command_scope,idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw new InpatientException("IDEMPOTENCY_REPLAY", 409, "This consultation command key was already used");
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, int responseStatus, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state='SUCCEEDED', response_status=:status,
                  response_ref=jsonb_build_object('resource_id',:resource)
                where tenant_id=:tenant and command_scope=:scope and idempotency_key=:key
                """).param("status", responseStatus).param("resource", resourceId)
                .param("tenant", identity.tenantId()).param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID consultationId,
            long aggregateVersion, String actionCode, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id=:tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("select event_hash from audit_event where tenant_id=:tenant order by occurred_at desc,audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + actionCode + "|"
                + consultationId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(tenant_id,audit_event_id,occurred_at,actor_user_id,action_code,
                  resource_type,resource_id,patient_ref_hash,trace_id,previous_hash,event_hash,details)
                values (:tenant,:audit,now(),:actor,:action,'INPATIENT_CONSULTATION',:consultation,
                  :patient_hash,:trace,:previous,:event_hash,jsonb_build_object('aggregate_version',:version))
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("action", actionCode).param("consultation", consultationId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId)).param("trace", trace)
                .param("previous", previousHash, java.sql.Types.VARCHAR).param("event_hash", eventHash)
                .param("version", aggregateVersion).update();
        jdbc.sql("""
                insert into outbox_event(tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,
                  event_type,schema_version,payload)
                values (:tenant,:event,'INPATIENT_CONSULTATION',:consultation,:version,:event_type,1,
                  jsonb_build_object('consultation_id',:consultation))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("consultation", consultationId).param("version", aggregateVersion)
                .param("event_type", eventType).update();
    }

    private static String requireText(String value, int min, int max, String field) {
        if (value == null || value.trim().length() < min || value.trim().length() > max) {
            throw invalid(field + " must contain " + min + " to " + max + " characters");
        }
        return value.trim();
    }

    private static void invalidState(String message) {
        throw new InpatientException("CONSULTATION_STATE_INVALID", 409, message);
    }

    private static InpatientException invalid(String message) {
        return new InpatientException("CONSULTATION_VALIDATION_FAILED", 400, message);
    }

    private static InpatientException contextDenied() {
        return new InpatientException("CONTEXT_NOT_PERMITTED", 403, "The consultation context is not permitted");
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private enum Action {
        ACCEPT("INPATIENT_CONSULTATION_ACCEPTED", "InpatientConsultationAccepted"),
        REJECT("INPATIENT_CONSULTATION_REJECTED", "InpatientConsultationRejected"),
        SIGN_OPINION("INPATIENT_CONSULTATION_OPINION_SIGNED", "InpatientConsultationOpinionSigned"),
        COMPLETE("INPATIENT_CONSULTATION_COMPLETED", "InpatientConsultationCompleted");
        final String auditCode;
        final String eventType;
        Action(String auditCode, String eventType) { this.auditCode = auditCode; this.eventType = eventType; }
    }

    private record AdmissionContext(UUID facilityId, UUID wardId, String status) {}
    private record ConsultationTaskProjection(UUID taskId, String state, long rowVersion) {}
    private record ConsultationContext(
            String status, UUID requestedBy, UUID acceptedBy, long rowVersion,
            UUID facilityId, UUID wardId, String admissionStatus) {}
    private record ConsultationRow(
            UUID consultationId, UUID admissionId, UUID patientId, UUID encounterId,
            String requestedDepartment, String urgency, String reason, String clinicalQuestion,
            String status, Instant dueAt, UUID requestedBy, Instant requestedAt,
            UUID acceptedBy, Instant acceptedAt, String rejectionReason, String opinion,
            String recommendation, UUID opinionSignedBy, Instant opinionSignedAt,
            UUID completedBy, Instant completedAt, long rowVersion) {}
}
