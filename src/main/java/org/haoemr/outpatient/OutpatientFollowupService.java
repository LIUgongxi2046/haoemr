package org.haoemr.outpatient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.OutpatientFollowupCompleteRequestWire;
import org.haoemr.contracts.OutpatientFollowupCreateRequestWire;
import org.haoemr.contracts.OutpatientFollowupCancelRequestWire;
import org.haoemr.contracts.OutpatientFollowupUpdateRequestWire;
import org.haoemr.contracts.OutpatientFollowupWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class OutpatientFollowupService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    OutpatientFollowupService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    List<OutpatientFollowupWire> list(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select followup_id, patient_id, encounter_id, followup_type, content, outcome, status,
                       due_at, completed_at, row_version, created_at
                from outpatient_followup where tenant_id = :tenant and patient_id = :patient
                order by due_at desc nulls last, created_at desc limit 500
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query((rs, row) -> wire(rs)).list();
    }

    OutpatientFollowupWire create(
            ClinicalIdentity identity, String idempotencyKey, OutpatientFollowupCreateRequestWire request) {
        return transactions.execute(status -> {
            int inserted = jdbc.sql("""
                    insert into idempotency_record(
                      tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                    values (:tenant, 'FOLLOWUP_CREATE', :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                    on conflict (tenant_id, command_scope, idempotency_key) do nothing
                    """).param("tenant", identity.tenantId()).param("key", idempotencyKey)
                    .param("hash", sha256(idempotencyKey + "|" + request.patientId() + "|" + request.followupType().name()))
                    .param("trace", UUID.randomUUID().toString()).update();
            if (inserted != 1) {
                throw new OutpatientFollowupException("IDEMPOTENCY_REPLAY", 409, "该随访命令已提交");
            }
            UUID followupId = UUID.randomUUID();
            jdbc.sql("""
                    insert into outpatient_followup(
                      tenant_id, followup_id, patient_id, encounter_id, followup_type, content, due_at)
                    values (:tenant, :followup, :patient, :encounter, :type, :content, :due_at)
                    """).param("tenant", identity.tenantId()).param("followup", followupId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("type", request.followupType().name()).param("content", request.content())
                    .param("due_at", request.dueAt() == null ? null : request.dueAt().atOffset(ZoneOffset.UTC)).update();
            jdbc.sql("""
                    update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                      response_ref = jsonb_build_object('followup_id', :followup)
                    where tenant_id = :tenant and command_scope = 'FOLLOWUP_CREATE' and idempotency_key = :key
                    """).param("followup", followupId).param("tenant", identity.tenantId())
                    .param("key", idempotencyKey).update();
            return item(identity.tenantId(), followupId);
        });
    }

    OutpatientFollowupWire complete(
            ClinicalIdentity identity, UUID followupId, OutpatientFollowupCompleteRequestWire request) {
        return transactions.execute(status -> {
            int updated = jdbc.sql("""
                    update outpatient_followup set status = 'COMPLETED', outcome = :outcome,
                      completed_at = now(), row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and followup_id = :followup and status = 'PENDING'
                      and row_version = :expected
                    """).param("outcome", request.outcome()).param("tenant", identity.tenantId())
                    .param("followup", followupId).param("expected", request.expectedRowVersion()).update();
            if (updated != 1) {
                throw new OutpatientFollowupException("FOLLOWUP_STATE_CONFLICT", 409,
                        "随访不存在、已完成或版本冲突");
            }
            return item(identity.tenantId(), followupId);
        });
    }

    OutpatientFollowupWire update(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, String idempotencyKey, UUID followupId,
            OutpatientFollowupUpdateRequestWire request) {
        String content = requireText(request.content(), 2, "content");
        if (request.followupType() == null || request.expectedRowVersion() == null) {
            throw invalid("followup_type and expected_row_version are required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "FOLLOWUP_UPDATE", idempotencyKey,
                    sha256(followupId + "|" + request.expectedRowVersion() + "|" + request.followupType()
                            + "|" + content + "|" + request.dueAt()));
            int updated = jdbc.sql("""
                    update outpatient_followup set followup_type = :type, content = :content,
                      due_at = :due_at, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and followup_id = :followup
                      and patient_id = :patient and encounter_id = :encounter
                      and status = 'PENDING' and row_version = :expected
                    """).param("type", request.followupType().name()).param("content", content)
                    .param("due_at", request.dueAt() == null ? null : request.dueAt().atOffset(ZoneOffset.UTC))
                    .param("tenant", identity.tenantId()).param("followup", followupId)
                    .param("patient", patientId).param("encounter", encounterId)
                    .param("expected", request.expectedRowVersion()).update();
            if (updated != 1) throw stateConflict("随访不存在、已结束或版本冲突");
            appendEvidence(identity, patientId, followupId, request.expectedRowVersion() + 1,
                    "FOLLOWUP_UPDATED", "OutpatientFollowupUpdated");
            completeCommand(identity, "FOLLOWUP_UPDATE", idempotencyKey, followupId);
            return item(identity.tenantId(), followupId);
        });
    }

    OutpatientFollowupWire cancel(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, String idempotencyKey, UUID followupId,
            OutpatientFollowupCancelRequestWire request) {
        String reason = requireText(request.reason(), 2, "reason");
        if (request.expectedRowVersion() == null) throw invalid("expected_row_version is required");
        return transactions.execute(status -> {
            beginCommand(identity, "FOLLOWUP_CANCEL", idempotencyKey,
                    sha256(followupId + "|" + request.expectedRowVersion() + "|" + reason));
            int updated = jdbc.sql("""
                    update outpatient_followup set status = 'CANCELLED', outcome = :reason,
                      completed_at = now(), row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and followup_id = :followup
                      and patient_id = :patient and encounter_id = :encounter
                      and status = 'PENDING' and row_version = :expected
                    """).param("reason", "取消原因：" + reason).param("tenant", identity.tenantId())
                    .param("followup", followupId).param("patient", patientId)
                    .param("encounter", encounterId).param("expected", request.expectedRowVersion()).update();
            if (updated != 1) throw stateConflict("随访不存在、已结束或版本冲突");
            appendEvidence(identity, patientId, followupId, request.expectedRowVersion() + 1,
                    "FOLLOWUP_CANCELLED", "OutpatientFollowupCancelled");
            completeCommand(identity, "FOLLOWUP_CANCEL", idempotencyKey, followupId);
            return item(identity.tenantId(), followupId);
        });
    }

    private OutpatientFollowupWire item(UUID tenantId, UUID followupId) {
        return jdbc.sql("""
                select followup_id, patient_id, encounter_id, followup_type, content, outcome, status,
                       due_at, completed_at, row_version, created_at
                from outpatient_followup where tenant_id = :tenant and followup_id = :followup
                """).param("tenant", tenantId).param("followup", followupId)
                .query((rs, row) -> wire(rs))
                .optional().orElseThrow(() -> new OutpatientFollowupException(
                        "FOLLOWUP_NOT_FOUND", 404, "随访记录不存在"));
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new OutpatientFollowupException("INVALID_IDEMPOTENCY_KEY", 400, "需要有效的 Idempotency-Key");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw new OutpatientFollowupException("IDEMPOTENCY_REPLAY", 409, "该随访命令已提交");
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID followupId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('followup_id', :followup)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("followup", followupId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID followupId, long version, String action, String eventType) {
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + followupId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'OUTPATIENT_FOLLOWUP', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", followupId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'OUTPATIENT_FOLLOWUP', :aggregate, :version,
                  :event_type, 1, jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", followupId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) throw invalid(field + " 长度不足");
        return value.trim();
    }

    private static OutpatientFollowupException invalid(String message) {
        return new OutpatientFollowupException("FOLLOWUP_REQUEST_INVALID", 400, message);
    }

    private static OutpatientFollowupException stateConflict(String message) {
        return new OutpatientFollowupException("FOLLOWUP_STATE_CONFLICT", 409, message);
    }

    private OutpatientFollowupWire wire(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OutpatientFollowupWire(
                rs.getObject("followup_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                OutpatientFollowupWire.FollowupTypeValue.valueOf(rs.getString("followup_type")),
                rs.getString("content"),
                rs.getString("outcome"),
                OutpatientFollowupWire.StatusValue.valueOf(rs.getString("status")),
                rs.getObject("due_at", OffsetDateTime.class) == null ? null : rs.getObject("due_at", OffsetDateTime.class).toInstant(),
                rs.getObject("completed_at", OffsetDateTime.class) == null ? null : rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                rs.getLong("row_version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
