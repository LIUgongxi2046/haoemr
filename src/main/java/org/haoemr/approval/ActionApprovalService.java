package org.haoemr.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ActionApprovalDecideRequestWire;
import org.haoemr.contracts.ActionApprovalProposeRequestWire;
import org.haoemr.contracts.ActionApprovalWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class ActionApprovalService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ActionApprovalService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ActionApprovalWire propose(
            ClinicalIdentity identity, String idempotencyKey, ActionApprovalProposeRequestWire request) {
        String summary = requireText(request.proposedActionSummary(), 2, "proposed_action_summary");
        if (request.actionType() == null || request.proposedAt() == null) {
            throw invalid("action_type and proposed_at are required");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "ACTION_APPROVAL_PROPOSE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.actionType()
                            + "|" + summary + "|" + request.proposedAt()));
            UUID approvalId = UUID.randomUUID();
            jdbc.sql("""
                    insert into action_approval(
                      tenant_id, action_approval_id, patient_id, encounter_id, facility_id,
                      action_type, proposed_action_summary, proposed_by, proposed_at, status)
                    values (:tenant, :approval, :patient, :encounter, :facility,
                      :action_type, :summary, :proposed_by, :proposed_at, 'PROPOSED')
                    """).param("tenant", identity.tenantId()).param("approval", approvalId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("action_type", request.actionType().name())
                    .param("summary", summary).param("proposed_by", identity.userId())
                    .param("proposed_at", request.proposedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), approvalId, 1,
                    "ACTION_APPROVAL_PROPOSED", "ActionApprovalProposed");
            completeCommand(identity, "ACTION_APPROPOSE_PROPOSE", idempotencyKey, approvalId);
            return approval(identity.tenantId(), approvalId, request.patientId());
        });
    }

    ActionApprovalWire decide(
            ClinicalIdentity identity, String idempotencyKey, UUID approvalId,
            ActionApprovalDecideRequestWire request) {
        if (request.decision() == null) {
            throw invalid("decision is required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "ACTION_APPROVAL_DECIDE", idempotencyKey,
                    sha256(approvalId + "|" + request.expectedRowVersion() + "|" + request.decision()));
            ApprovalHead current = jdbc.sql("""
                    select status, proposed_by, row_version from action_approval
                    where tenant_id = :tenant and action_approval_id = :approval
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                      for update
                    """).param("tenant", identity.tenantId()).param("approval", approvalId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new ApprovalHead(
                            rs.getString("status"), rs.getObject("proposed_by", UUID.class),
                            rs.getLong("row_version")))
                    .optional().orElseThrow(ActionApprovalService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new ActionApprovalException(
                        "ACTION_APPROVAL_VERSION_CONFLICT", 409, "The approval changed; reload before retrying");
            }
            if (!"PROPOSED".equals(current.status())) {
                throw new ActionApprovalException(
                        "ACTION_APPROVAL_STATE_INVALID", 409, "Only a proposed action can be decided");
            }
            if (identity.userId().equals(current.proposedBy())) {
                throw new ActionApprovalException(
                        "ACTION_SELF_APPROVAL_FORBIDDEN", 403,
                        "The same user cannot propose and approve an action");
            }
            String targetStatus = request.decision() == ActionApprovalDecideRequestWire.DecisionValue.APPROVE
                    ? "APPROVED" : "REJECTED";
            jdbc.sql("""
                    update action_approval set status = :status, decided_by = :decided_by,
                      decided_at = now(), row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and action_approval_id = :approval and row_version = :expected
                    """).param("status", targetStatus).param("decided_by", identity.userId())
                    .param("tenant", identity.tenantId()).param("approval", approvalId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), approvalId, current.rowVersion() + 1,
                    "ACTION_APPROVAL_" + targetStatus, "ActionApproval" + targetStatus);
            completeCommand(identity, "ACTION_APPROVAL_DECIDE", idempotencyKey, approvalId);
            return approval(identity.tenantId(), approvalId, request.patientId());
        });
    }

    List<ActionApprovalWire> listApprovals(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select action_approval_id from action_approval
                where tenant_id = :tenant and patient_id = :patient
                order by proposed_at desc, action_approval_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> approval(identity.tenantId(), id, patientId)).toList();
    }

    /**
     * Queues a medical-agent candidate for independent clinical review. The agent is never an actor,
     * and the only automatically proposed type is OTHER, which has no direct clinical side effect.
     */
    public AgentProposalReceipt proposeFromMedicalAgent(UUID tenantId, UUID runId) {
        return transactions.execute(status -> {
            AgentRunProposal run = jdbc.sql("""
                    select run.patient_id, run.encounter_id, lease.facility_id, run.created_by,
                      run.root_agent_code, run.output_payload::text
                    from medical_agent_run run
                    join context_lease lease on lease.tenant_id = run.tenant_id and lease.lease_id = run.context_lease_id
                    where run.tenant_id = :tenant and run.run_id = :run and run.state = 'WAITING_FOR_REVIEW'
                    for update
                    """).param("tenant", tenantId).param("run", runId)
                    .query((rs, row) -> new AgentRunProposal(
                            rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                            rs.getObject("facility_id", UUID.class), rs.getObject("created_by", UUID.class),
                            rs.getString("root_agent_code"), rs.getString("output_payload")))
                    .optional().orElseThrow(ActionApprovalService::contextDenied);
            UUID existing = jdbc.sql("""
                    select action_approval_id from action_approval
                    where tenant_id = :tenant and source_medical_agent_run_id = :run
                    """).param("tenant", tenantId).param("run", runId).query(UUID.class).optional().orElse(null);
            if (existing != null) return new AgentProposalReceipt(existing, false);
            UUID approvalId = UUID.randomUUID();
            String summary = "Eva 医助（" + run.rootAgentCode()
                    + "）已形成候选，请由独立临床人员结合固定来源证据审阅后决定。";
            int inserted = jdbc.sql("""
                    insert into action_approval(
                      tenant_id, action_approval_id, patient_id, encounter_id, facility_id,
                      action_type, proposed_action_summary, proposed_by, proposed_at, status,
                      source_medical_agent_run_id, source_payload_hash, source_references)
                    select :tenant, :approval, :patient, :encounter, :facility,
                      'OTHER', :summary, :actor, now(), 'PROPOSED', :run, :hash,
                      coalesce((select jsonb_agg(reference.value)
                        from medical_agent_child_run child
                        cross join lateral jsonb_array_elements(child.source_references) as reference(value)
                        where child.tenant_id = :tenant and child.root_run_id = :run), '[]'::jsonb)
                    """).param("tenant", tenantId).param("approval", approvalId).param("patient", run.patientId())
                    .param("encounter", run.encounterId()).param("facility", run.facilityId())
                    .param("summary", summary).param("actor", run.createdBy()).param("run", runId)
                    .param("hash", sha256(run.outputPayload())).update();
            if (inserted != 1) throw new ActionApprovalException(
                    "MEDICAL_AGENT_ACTION_PROPOSAL_FAILED", 409, "The medical-agent candidate could not be queued");
            appendEvidence(new ClinicalIdentity(tenantId, run.createdBy(), List.of()), run.patientId(), approvalId, 1,
                    "MEDICAL_AGENT_ACTION_APPROVAL_QUEUED", "MedicalAgentActionApprovalQueued");
            return new AgentProposalReceipt(approvalId, true);
        });
    }

    private ActionApprovalWire approval(UUID tenantId, UUID approvalId, UUID patientId) {
        return jdbc.sql("""
                select action_approval_id, patient_id, encounter_id, facility_id, action_type,
                  proposed_action_summary, proposed_by, proposed_at, status, decided_by, decided_at,
                  source_medical_agent_run_id, source_payload_hash,
                  jsonb_array_length(source_references) as source_reference_count, row_version
                from action_approval
                where tenant_id = :tenant and action_approval_id = :approval and patient_id = :patient
                """).param("tenant", tenantId).param("approval", approvalId).param("patient", patientId)
                .query((rs, row) -> new ActionApprovalWire(
                        rs.getObject("action_approval_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        ActionApprovalWire.ActionTypeValue.valueOf(rs.getString("action_type")),
                        rs.getString("proposed_action_summary"), rs.getObject("proposed_by", UUID.class),
                        rs.getObject("proposed_at", OffsetDateTime.class).toInstant(),
                        ActionApprovalWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("decided_by", UUID.class),
                        rs.getObject("decided_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("decided_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("source_medical_agent_run_id", UUID.class),
                        rs.getString("source_payload_hash"),
                        rs.getInt("source_reference_count"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ActionApprovalService::contextDenied);
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
            throw new ActionApprovalException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ActionApprovalException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID approvalId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", approvalId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID approvalId, long version,
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
                + approvalId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ACTION_APPROVAL', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", approvalId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ACTION_APPROVAL', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", approvalId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static ActionApprovalException invalid(String message) {
        return new ActionApprovalException("ACTION_APPROVAL_REQUEST_INVALID", 400, message);
    }

    static ActionApprovalException contextDenied() {
        return new ActionApprovalException("CONTEXT_NOT_PERMITTED", 403,
                "The requested action approval context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ApprovalHead(String status, UUID proposedBy, long rowVersion) {}
    private record AgentRunProposal(UUID patientId, UUID encounterId, UUID facilityId, UUID createdBy,
            String rootAgentCode, String outputPayload) {}

    public record AgentProposalReceipt(UUID approvalId, boolean created) {}
}
