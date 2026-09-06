package org.haoemr.referral;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ReferralCreateRequestWire;
import org.haoemr.contracts.ReferralTransitionRequestWire;
import org.haoemr.contracts.ReferralUpdateRequestWire;
import org.haoemr.contracts.ReferralWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ReferralService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ReferralService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ReferralWire create(
            ClinicalIdentity identity, String idempotencyKey, ReferralCreateRequestWire request) {
        String reason = requireText(request.reason(), 2, "reason");
        String summary = requireText(request.clinicalSummary(), 4, "clinical_summary");
        if (request.referralType() == null) {
            throw invalid("referral_type is required");
        }
        String department = blankToNull(request.targetDepartment());
        String organization = blankToNull(request.targetOrganization());
        if (request.referralType() == ReferralCreateRequestWire.ReferralTypeValue.INTERNAL && department == null) {
            throw invalid("target_department is required for INTERNAL referral");
        }
        if (request.referralType() == ReferralCreateRequestWire.ReferralTypeValue.EXTERNAL && organization == null) {
            throw invalid("target_organization is required for EXTERNAL referral");
        }
        requireClinicianRole(identity, request.facilityId());
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        if (department != null) requireActiveDepartment(identity.tenantId(), request.facilityId(), department);
        return transactions.execute(status -> {
            beginCommand(identity, "REFERRAL_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.referralType()
                            + "|" + department + "|" + organization + "|" + reason + "|" + summary));
            UUID referralId = UUID.randomUUID();
            jdbc.sql("""
                    insert into referral(
                      tenant_id, referral_id, patient_id, encounter_id, facility_id,
                      referral_type, target_department, target_organization, reason,
                      clinical_summary, status, requested_by)
                    values (:tenant, :referral, :patient, :encounter, :facility,
                      :type, :department, :organization, :reason, :summary, 'DRAFT', :requester)
                    """).param("tenant", identity.tenantId()).param("referral", referralId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("type", request.referralType().name())
                    .param("department", department).param("organization", organization)
                    .param("reason", reason).param("summary", summary)
                    .param("requester", identity.userId()).update();
            appendEvidence(identity, request.patientId(), referralId, 1, "REFERRAL_CREATED", "ReferralCreated");
            completeCommand(identity, "REFERRAL_CREATE", idempotencyKey, referralId);
            return referral(identity.tenantId(), referralId, request.patientId());
        });
    }

    ReferralWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID referralId,
            ReferralTransitionRequestWire request) {
        if (request.transition() == null) {
            throw invalid("transition is required");
        }
        requireClinicianRole(identity, request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "REFERRAL_TRANSITION", idempotencyKey,
                    sha256(referralId + "|" + request.expectedRowVersion() + "|" + request.transition()));
            ReferralHead current = jdbc.sql("""
                    select status, row_version, patient_id, encounter_id, facility_id,
                      target_department, requested_by
                    from referral
                    where tenant_id = :tenant and referral_id = :referral
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                      for update
                    """).param("tenant", identity.tenantId()).param("referral", referralId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new ReferralHead(
                            rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                            rs.getObject("facility_id", UUID.class), rs.getString("target_department"),
                            rs.getObject("requested_by", UUID.class)))
                    .optional().orElseThrow(ReferralService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new ReferralException(
                        "REFERRAL_VERSION_CONFLICT", 409, "The referral changed; reload before retrying");
            }
            switch (request.transition()) {
                case SEND -> {
                    if (!"DRAFT".equals(current.status())) throw stateInvalid();
                    jdbc.sql("""
                            update referral set status = 'SENT', sent_at = now(),
                              row_version = row_version + 1, updated_at = now()
                            where tenant_id = :tenant and referral_id = :referral and row_version = :expected
                            """).param("tenant", identity.tenantId()).param("referral", referralId)
                            .param("expected", current.rowVersion()).update();
                    createResponseTask(identity, referralId, current);
                }
                case ACCEPT, REJECT -> {
                    if (!"SENT".equals(current.status())) throw stateInvalid();
                    if (identity.userId().equals(current.requestedBy())) {
                        throw new ReferralException(
                                "REFERRAL_SELF_RESOLUTION_FORBIDDEN", 403,
                                "The requesting clinician cannot accept or reject the same referral");
                    }
                    String target = request.transition()
                            == ReferralTransitionRequestWire.TransitionValue.ACCEPT ? "ACCEPTED" : "REJECTED";
                    jdbc.sql("""
                            update referral set status = :status, resolved_at = now(), resolved_by = :actor,
                              row_version = row_version + 1, updated_at = now()
                            where tenant_id = :tenant and referral_id = :referral and row_version = :expected
                            """).param("status", target).param("actor", identity.userId())
                            .param("tenant", identity.tenantId())
                            .param("referral", referralId).param("expected", current.rowVersion()).update();
                    settleResponseTask(identity, referralId,
                            request.transition() == ReferralTransitionRequestWire.TransitionValue.ACCEPT
                                    ? "COMPLETED" : "WITHDRAWN");
                }
                case CANCEL -> {
                    if (!"DRAFT".equals(current.status())) throw stateInvalid();
                    jdbc.sql("""
                            update referral set status = 'CANCELLED', resolved_at = now(),
                              row_version = row_version + 1, updated_at = now()
                            where tenant_id = :tenant and referral_id = :referral and row_version = :expected
                            """).param("tenant", identity.tenantId()).param("referral", referralId)
                            .param("expected", current.rowVersion()).update();
                }
            }
            appendEvidence(identity, request.patientId(), referralId, current.rowVersion() + 1,
                    "REFERRAL_" + request.transition(), "Referral" + request.transition());
            completeCommand(identity, "REFERRAL_TRANSITION", idempotencyKey, referralId);
            return referral(identity.tenantId(), referralId, request.patientId());
        });
    }

    ReferralWire update(
            ClinicalIdentity identity, String idempotencyKey, UUID referralId,
            ReferralUpdateRequestWire request) {
        String reason = requireText(request.reason(), 2, "reason");
        String summary = requireText(request.clinicalSummary(), 4, "clinical_summary");
        if (request.referralType() == null || request.expectedRowVersion() == null) {
            throw invalid("referral_type and expected_row_version are required");
        }
        String department = blankToNull(request.targetDepartment());
        String organization = blankToNull(request.targetOrganization());
        if (request.referralType() == ReferralUpdateRequestWire.ReferralTypeValue.INTERNAL && department == null) {
            throw invalid("target_department is required for INTERNAL referral");
        }
        if (request.referralType() == ReferralUpdateRequestWire.ReferralTypeValue.EXTERNAL && organization == null) {
            throw invalid("target_organization is required for EXTERNAL referral");
        }
        requireClinicianRole(identity, request.facilityId());
        if (department != null) requireActiveDepartment(identity.tenantId(), request.facilityId(), department);
        return transactions.execute(status -> {
            beginCommand(identity, "REFERRAL_UPDATE", idempotencyKey,
                    sha256(referralId + "|" + request.expectedRowVersion() + "|" + request.referralType()
                            + "|" + department + "|" + organization + "|" + reason + "|" + summary));
            jdbc.sql("select set_config('haoemr.allow_referral_edit', 'true', true)")
                    .query(String.class).single();
            int updated = jdbc.sql("""
                    update referral set referral_type = :type, target_department = :department,
                      target_organization = :organization, reason = :reason, clinical_summary = :summary,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and referral_id = :referral
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                      and status = 'DRAFT' and row_version = :expected
                    """).param("type", request.referralType().name()).param("department", department)
                    .param("organization", organization).param("reason", reason).param("summary", summary)
                    .param("tenant", identity.tenantId()).param("referral", referralId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("expected", request.expectedRowVersion()).update();
            if (updated != 1) throw new ReferralException(
                    "REFERRAL_VERSION_CONFLICT", 409, "The draft referral changed; reload before retrying");
            appendEvidence(identity, request.patientId(), referralId, request.expectedRowVersion() + 1,
                    "REFERRAL_UPDATED", "ReferralUpdated");
            completeCommand(identity, "REFERRAL_UPDATE", idempotencyKey, referralId);
            return referral(identity.tenantId(), referralId, request.patientId());
        });
    }

    List<ReferralWire> listReferrals(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select referral_id from referral
                where tenant_id = :tenant and patient_id = :patient
                order by created_at desc, referral_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> referral(identity.tenantId(), id, patientId)).toList();
    }

    List<ReferralTarget> listTargets(ClinicalIdentity identity, UUID facilityId) {
        return jdbc.sql("""
                select department_id, department_code, display_name
                from clinical_department
                where tenant_id=:tenant and facility_id=:facility and status='ACTIVE'
                  and (effective_until is null or effective_until>now())
                  and unit_type in ('DEPARTMENT','MEDICAL_TECH')
                order by case unit_type when 'DEPARTMENT' then 0 else 1 end, display_name
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .query((rs, row) -> new ReferralTarget(
                        rs.getObject("department_id", UUID.class), rs.getString("department_code"),
                        rs.getString("display_name"))).list();
    }

    private void requireActiveDepartment(UUID tenantId, UUID facilityId, String displayName) {
        long count = jdbc.sql("""
                select count(*) from clinical_department
                where tenant_id=:tenant and facility_id=:facility and display_name=:display_name
                  and status='ACTIVE' and (effective_until is null or effective_until>now())
                  and unit_type in ('DEPARTMENT','MEDICAL_TECH')
                """).param("tenant", tenantId).param("facility", facilityId)
                .param("display_name", displayName).query(Long.class).single();
        if (count != 1) {
            throw new ReferralException(
                    "REFERRAL_TARGET_NOT_ACTIVE", 409,
                    "The internal referral target is not an active department in this facility");
        }
    }

    private void requireClinicianRole(ClinicalIdentity identity, UUID facilityId) {
        if (identity.roleAssignmentIds().isEmpty()) {
            throw clinicianRoleDenied();
        }
        long count = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id=:tenant and user_id=:user and role_assignment_id in (:assignments)
                  and role_code in ('CLINICIAN','ATTENDING_PHYSICIAN','CHIEF_PHYSICIAN')
                  and status='ACTIVE' and valid_from<=now()
                  and (valid_until is null or valid_until>now())
                  and (facility_id is null or facility_id=:facility)
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("facility", facilityId)
                .query(Long.class).single();
        if (count < 1) {
            throw clinicianRoleDenied();
        }
    }

    private static ReferralException clinicianRoleDenied() {
        return new ReferralException("REFERRAL_CLINICIAN_ROLE_REQUIRED", 403,
                "The active clinician role assignment is required for this referral workflow action");
    }

    private void createResponseTask(ClinicalIdentity identity, UUID referralId, ReferralHead referral) {
        UUID taskId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level,
                  state, business_state, due_at, source_route)
                values (:tenant, :task, :patient, :encounter, :facility,
                  'CONSULTATION', :referral, 'OUTPATIENT_REFERRAL_RESPONSE', :title, 'ROUTINE',
                  'PENDING', 'SENT', now() + interval '24 hours', '#/opd-consult')
                on conflict (tenant_id, source_type, source_id, task_type) do nothing
                """).param("tenant", identity.tenantId()).param("task", taskId)
                .param("patient", referral.patientId()).param("encounter", referral.encounterId())
                .param("facility", referral.facilityId()).param("referral", referralId)
                .param("title", (referral.targetDepartment() == null ? "院外转诊" : referral.targetDepartment())
                        + " 会诊/转诊待响应").update();
        if (inserted == 1) {
            jdbc.sql("""
                    insert into clinical_task_event(
                      tenant_id, task_event_id, task_id, event_type,
                      previous_state, resulting_state, actor_user_id)
                    values (:tenant, gen_random_uuid(), :task, 'CREATED', null, 'PENDING', :actor)
                    """).param("tenant", identity.tenantId()).param("task", taskId)
                    .param("actor", identity.userId()).update();
        }
    }

    private void settleResponseTask(ClinicalIdentity identity, UUID referralId, String state) {
        List<TaskProjection> tasks = jdbc.sql("""
                select task_id, state, row_version from clinical_task
                where tenant_id=:tenant and source_type='CONSULTATION' and source_id=:referral
                  and task_type='OUTPATIENT_REFERRAL_RESPONSE'
                  and state not in ('COMPLETED','WITHDRAWN','EXPIRED') for update
                """).param("tenant", identity.tenantId()).param("referral", referralId)
                .query((rs, row) -> new TaskProjection(
                        rs.getObject("task_id", UUID.class), rs.getString("state"), rs.getLong("row_version")))
                .list();
        for (TaskProjection task : tasks) {
            jdbc.sql("""
                    update clinical_task set state=:state, business_state=:state,
                      row_version=row_version+1, updated_at=now()
                    where tenant_id=:tenant and task_id=:task and row_version=:version
                    """).param("state", state).param("tenant", identity.tenantId())
                    .param("task", task.taskId()).param("version", task.rowVersion()).update();
            jdbc.sql("""
                    insert into clinical_task_event(
                      tenant_id, task_event_id, task_id, event_type,
                      previous_state, resulting_state, actor_user_id)
                    values (:tenant, gen_random_uuid(), :task, :event, :previous, :state, :actor)
                    """).param("tenant", identity.tenantId()).param("task", task.taskId())
                    .param("event", "COMPLETED".equals(state) ? "SOURCE_COMPLETED" : "SOURCE_WITHDRAWN")
                    .param("previous", task.state()).param("state", state)
                    .param("actor", identity.userId()).update();
        }
    }

    private ReferralWire referral(UUID tenantId, UUID referralId, UUID patientId) {
        return jdbc.sql("""
                select referral_id, patient_id, encounter_id, facility_id, referral_type,
                  target_department, target_organization, reason, clinical_summary, status,
                  sent_at, resolved_at, row_version
                from referral
                where tenant_id = :tenant and referral_id = :referral and patient_id = :patient
                """).param("tenant", tenantId).param("referral", referralId).param("patient", patientId)
                .query((rs, row) -> new ReferralWire(
                        rs.getObject("referral_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        ReferralWire.ReferralTypeValue.valueOf(rs.getString("referral_type")),
                        rs.getString("target_department"), rs.getString("target_organization"),
                        rs.getString("reason"), rs.getString("clinical_summary"),
                        ReferralWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("sent_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("sent_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("resolved_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("resolved_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ReferralService::contextDenied);
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
            throw new ReferralException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ReferralException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID referralId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", referralId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID referralId, long version,
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
                + referralId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'REFERRAL', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", referralId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'REFERRAL', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", referralId).param("version", version).param("event_type", eventType).update();
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

    private static ReferralException invalid(String message) {
        return new ReferralException("REFERRAL_REQUEST_INVALID", 400, message);
    }

    private static ReferralException stateInvalid() {
        return new ReferralException("REFERRAL_STATE_INVALID", 409,
                "The referral is not in a state that accepts this transition");
    }

    static ReferralException contextDenied() {
        return new ReferralException("CONTEXT_NOT_PERMITTED", 403, "The requested referral context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record ReferralTarget(UUID departmentId, String departmentCode, String displayName) {}
    private record TaskProjection(UUID taskId, String state, long rowVersion) {}
    private record ReferralHead(
            String status, long rowVersion, UUID patientId, UUID encounterId, UUID facilityId,
            String targetDepartment, UUID requestedBy) {}
}
