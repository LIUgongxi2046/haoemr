package org.haoemr.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ActionExecutionCreateRequestWire;
import org.haoemr.contracts.ActionExecutionTransitionRequestWire;
import org.haoemr.contracts.ActionExecutionWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ActionExecutionService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ActionExecutionService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ActionExecutionWire create(
            ClinicalIdentity identity, String idempotencyKey, ActionExecutionCreateRequestWire request) {
        if (request.actionApprovalId() == null || request.patientId() == null) {
            throw invalid("action_approval_id and patient_id are required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "ACTION_EXECUTION_CREATE", idempotencyKey,
                    sha256(request.actionApprovalId() + "|" + request.patientId()));
            requireApprovedApproval(identity.tenantId(), request.patientId(), request.actionApprovalId());
            UUID executionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into action_execution(
                      tenant_id, execution_id, action_approval_id, patient_id, execution_status)
                    values (:tenant, :execution, :approval, :patient, 'PENDING')
                    """).param("tenant", identity.tenantId()).param("execution", executionId)
                    .param("approval", request.actionApprovalId()).param("patient", request.patientId()).update();
            appendEvidence(identity, request.patientId(), executionId, 1,
                    "ACTION_EXECUTION_CREATED", "ActionExecutionCreated");
            completeCommand(identity, "ACTION_EXECUTION_CREATE", idempotencyKey, executionId);
            return execution(identity.tenantId(), executionId);
        });
    }

    ActionExecutionWire succeed(
            ClinicalIdentity identity, String idempotencyKey, UUID executionId,
            ActionExecutionTransitionRequestWire request) {
        return transition(identity, idempotencyKey, executionId, request,
                "ACTION_EXECUTION_SUCCEED", "SUCCEEDED", "ActionExecutionSucceeded", false);
    }

    ActionExecutionWire fail(
            ClinicalIdentity identity, String idempotencyKey, UUID executionId,
            ActionExecutionTransitionRequestWire request) {
        return transition(identity, idempotencyKey, executionId, request,
                "ACTION_EXECUTION_FAIL", "FAILED", "ActionExecutionFailed", true);
    }

    List<ActionExecutionWire> list(ClinicalIdentity identity, UUID actionApprovalId) {
        return jdbc.sql("""
                select execution_id from action_execution
                where tenant_id = :tenant and action_approval_id = :approval
                order by created_at desc, execution_id desc limit 100
                """).param("tenant", identity.tenantId()).param("approval", actionApprovalId)
                .query(UUID.class).list().stream()
                .map(id -> execution(identity.tenantId(), id)).toList();
    }

    private ActionExecutionWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID executionId,
            ActionExecutionTransitionRequestWire request, String scope,
            String toStatus, String eventType, boolean failureRequiresReason) {
        return transactions.execute(status -> {
            beginCommand(identity, scope, idempotencyKey, sha256(executionId + "|" + request.expectedRowVersion()));
            ExecutionHead head = lockExecution(identity.tenantId(), executionId);
            requireVersion(head, request.expectedRowVersion());
            requireState(head, "PENDING", "Only a pending execution can be transitioned");
            if (!request.patientId().equals(head.patientId())) {
                throw new ActionExecutionException(
                        "ACTION_PATIENT_MISMATCH", 409, "The execution belongs to a different patient");
            }
            String resultNote = blankToNull(request.resultNote());
            if (failureRequiresReason && resultNote == null) {
                throw new ActionExecutionException(
                        "ACTION_EXECUTION_FAILURE_REASON_REQUIRED", 400, "A failed execution requires a reason");
            }
            jdbc.sql("""
                    update action_execution
                    set execution_status = :to_status, executed_by = :actor, executed_at = now(),
                      result_note = :result_note, row_version = row_version + 1
                    where tenant_id = :tenant and execution_id = :execution and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("execution", executionId)
                    .param("to_status", toStatus).param("actor", identity.userId())
                    .param("result_note", resultNote).param("expected", head.rowVersion()).update();
            appendEvidence(identity, head.patientId(), executionId, head.rowVersion() + 1, scope, eventType);
            completeCommand(identity, scope, idempotencyKey, executionId);
            return execution(identity.tenantId(), executionId);
        });
    }

    private ActionExecutionWire execution(UUID tenantId, UUID executionId) {
        return jdbc.sql("""
                select execution_id, action_approval_id, patient_id, execution_status,
                  executed_by, executed_at, result_note, row_version
                from action_execution where tenant_id = :tenant and execution_id = :execution
                """).param("tenant", tenantId).param("execution", executionId)
                .query((rs, row) -> new ActionExecutionWire(
                        rs.getObject("execution_id", UUID.class),
                        rs.getObject("action_approval_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        ActionExecutionWire.ExecutionStatusValue.valueOf(rs.getString("execution_status")),
                        rs.getObject("executed_by", UUID.class),
                        rs.getObject("executed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("executed_at", OffsetDateTime.class).toInstant(),
                        rs.getString("result_note"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ActionExecutionService::contextDenied);
    }

    private void requireApprovedApproval(UUID tenantId, UUID patientId, UUID approvalId) {
        ApprovalHead approval = jdbc.sql("""
                select status, patient_id from action_approval
                where tenant_id = :tenant and action_approval_id = :approval for update
                """).param("tenant", tenantId).param("approval", approvalId)
                .query((rs, row) -> new ApprovalHead(rs.getString("status"), rs.getObject("patient_id", UUID.class)))
                .optional().orElseThrow(ActionExecutionService::contextDenied);
        if (!"APPROVED".equals(approval.status())) {
            throw new ActionExecutionException(
                    "ACTION_NOT_APPROVED", 409, "Only an approved action can be executed");
        }
        if (!patientId.equals(approval.patientId())) {
            throw new ActionExecutionException(
                    "ACTION_PATIENT_MISMATCH", 409, "The approval belongs to a different patient");
        }
    }

    private ExecutionHead lockExecution(UUID tenantId, UUID executionId) {
        return jdbc.sql("""
                select execution_status, patient_id, row_version from action_execution
                where tenant_id = :tenant and execution_id = :execution for update
                """).param("tenant", tenantId).param("execution", executionId)
                .query((rs, row) -> new ExecutionHead(
                        rs.getString("execution_status"), rs.getObject("patient_id", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ActionExecutionService::contextDenied);
    }

    private static void requireVersion(ExecutionHead head, Long expected) {
        if (expected == null || head.rowVersion() != expected) {
            throw new ActionExecutionException(
                    "ACTION_EXECUTION_VERSION_CONFLICT", 409, "The execution changed; reload before retrying");
        }
    }

    private static void requireState(ExecutionHead head, String expected, String message) {
        if (!expected.equals(head.status())) {
            throw new ActionExecutionException(
                    "ACTION_EXECUTION_STATE_INVALID", 409, message);
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ActionExecutionException("INVALID_IDEMPOTENCY_KEY", 400,
                    "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ActionExecutionException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID executionId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", executionId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID executionId, long version,
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
                + executionId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ACTION_EXECUTION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", executionId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ACTION_EXECUTION', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", executionId).param("version", version).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ActionExecutionException invalid(String message) {
        return new ActionExecutionException("ACTION_EXECUTION_REQUEST_INVALID", 400, message);
    }

    static ActionExecutionException contextDenied() {
        return new ActionExecutionException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested action execution context is not permitted");
    }

    private record ApprovalHead(String status, UUID patientId) {}
    private record ExecutionHead(String status, UUID patientId, long rowVersion) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
