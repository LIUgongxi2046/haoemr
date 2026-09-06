package org.haoemr.emergency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyResuscitationCompleteRequestWire;
import org.haoemr.contracts.EmergencyResuscitationStartRequestWire;
import org.haoemr.contracts.EmergencyResuscitationWire;
import org.haoemr.contracts.EmergencyClinicalFactVoidRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class EmergencyResuscitationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EmergencyResuscitationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    EmergencyResuscitationWire start(
            ClinicalIdentity identity, String idempotencyKey, EmergencyResuscitationStartRequestWire request) {
        if (request.startedAt() == null) {
            throw invalid("started_at is required");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_RESUSCITATION_START", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.startedAt()));
            UUID resuscitationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into emergency_resuscitation(
                      tenant_id, resuscitation_id, patient_id, encounter_id, facility_id,
                      started_at, outcome, status)
                    values (:tenant, :resuscitation, :patient, :encounter, :facility,
                      :started_at, 'PENDING', 'IN_PROGRESS')
                    """).param("tenant", identity.tenantId()).param("resuscitation", resuscitationId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .param("started_at", request.startedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), resuscitationId, 1,
                    "EMERGENCY_RESUSCITATION_STARTED", "EmergencyResuscitationStarted");
            completeCommand(identity, "EMERGENCY_RESUSCITATION_START", idempotencyKey, resuscitationId);
            return resuscitation(identity.tenantId(), resuscitationId, request.patientId());
        });
    }

    EmergencyResuscitationWire complete(
            ClinicalIdentity identity, String idempotencyKey, UUID resuscitationId,
            EmergencyResuscitationCompleteRequestWire request) {
        if (request.outcome() == null) {
            throw invalid("outcome is required to complete a resuscitation");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_RESUSCITATION_COMPLETE", idempotencyKey,
                    sha256(resuscitationId + "|" + request.expectedRowVersion() + "|" + request.outcome()));
            ResuscitationHead current = jdbc.sql("""
                    select status, row_version, voided_at from emergency_resuscitation
                    where tenant_id = :tenant and resuscitation_id = :resuscitation
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                      for update
                    """).param("tenant", identity.tenantId()).param("resuscitation", resuscitationId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new ResuscitationHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("voided_at", OffsetDateTime.class)))
                    .optional().orElseThrow(EmergencyResuscitationService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new EmergencyResuscitationException(
                        "EMERGENCY_RESUSCITATION_VERSION_CONFLICT", 409,
                        "The resuscitation changed; reload before retrying");
            }
            if (!"IN_PROGRESS".equals(current.status()) || current.voidedAt() != null) {
                throw new EmergencyResuscitationException(
                        "EMERGENCY_RESUSCITATION_STATE_INVALID", 409,
                        "Only an in-progress resuscitation can be completed");
            }
            jdbc.sql("""
                    update emergency_resuscitation set status = 'COMPLETED', outcome = :outcome,
                      ended_at = now(), row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and resuscitation_id = :resuscitation and row_version = :expected
                    """).param("outcome", request.outcome().name())
                    .param("tenant", identity.tenantId()).param("resuscitation", resuscitationId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), resuscitationId, current.rowVersion() + 1,
                    "EMERGENCY_RESUSCITATION_COMPLETED", "EmergencyResuscitationCompleted");
            completeCommand(identity, "EMERGENCY_RESUSCITATION_COMPLETE", idempotencyKey, resuscitationId);
            return resuscitation(identity.tenantId(), resuscitationId, request.patientId());
        });
    }

    EmergencyResuscitationWire voidResuscitation(
            ClinicalIdentity identity, String idempotencyKey, UUID resuscitationId,
            EmergencyClinicalFactVoidRequestWire request) {
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_RESUSCITATION_VOID", idempotencyKey,
                    sha256(resuscitationId + "|" + request.expectedRowVersion() + "|" + reason));
            ResuscitationHead current = jdbc.sql("""
                    select status, row_version, voided_at from emergency_resuscitation
                    where tenant_id = :tenant and resuscitation_id = :resuscitation
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                    for update
                    """).param("tenant", identity.tenantId()).param("resuscitation", resuscitationId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new ResuscitationHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("voided_at", OffsetDateTime.class)))
                    .optional().orElseThrow(EmergencyResuscitationService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new EmergencyResuscitationException("EMERGENCY_RESUSCITATION_VERSION_CONFLICT", 409,
                        "The resuscitation changed; reload before retrying");
            }
            if (current.voidedAt() != null) {
                throw new EmergencyResuscitationException("EMERGENCY_RESUSCITATION_STATE_INVALID", 409,
                        "The resuscitation is already voided");
            }
            jdbc.sql("""
                    update emergency_resuscitation set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and resuscitation_id = :resuscitation and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("resuscitation", resuscitationId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), resuscitationId, current.rowVersion() + 1,
                    "EMERGENCY_RESUSCITATION_VOIDED", "EmergencyResuscitationVoided");
            completeCommand(identity, "EMERGENCY_RESUSCITATION_VOID", idempotencyKey, resuscitationId);
            return resuscitation(identity.tenantId(), resuscitationId, request.patientId());
        });
    }

    List<EmergencyResuscitationWire> listResuscitations(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select resuscitation_id from emergency_resuscitation
                where tenant_id = :tenant and patient_id = :patient
                order by started_at desc, resuscitation_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> resuscitation(identity.tenantId(), id, patientId)).toList();
    }

    private EmergencyResuscitationWire resuscitation(UUID tenantId, UUID resuscitationId, UUID patientId) {
        return jdbc.sql("""
                select resuscitation_id, patient_id, encounter_id, facility_id, started_at,
                  ended_at, outcome, status, voided_at, void_reason, row_version
                from emergency_resuscitation
                where tenant_id = :tenant and resuscitation_id = :resuscitation and patient_id = :patient
                """).param("tenant", tenantId).param("resuscitation", resuscitationId).param("patient", patientId)
                .query((rs, row) -> new EmergencyResuscitationWire(
                        rs.getObject("resuscitation_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ended_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("ended_at", OffsetDateTime.class).toInstant(),
                        EmergencyResuscitationWire.OutcomeValue.valueOf(rs.getString("outcome")),
                        EmergencyResuscitationWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("voided_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("voided_at", OffsetDateTime.class).toInstant(),
                        rs.getString("void_reason"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(EmergencyResuscitationService::contextDenied);
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
            throw new EmergencyResuscitationException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new EmergencyResuscitationException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID resuscitationId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resuscitationId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID resuscitationId, long version,
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
                + resuscitationId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'EMERGENCY_RESUSCITATION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resuscitationId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'EMERGENCY_RESUSCITATION', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", resuscitationId).param("version", version).param("event_type", eventType).update();
    }

    private static EmergencyResuscitationException invalid(String message) {
        return new EmergencyResuscitationException("EMERGENCY_RESUSCITATION_REQUEST_INVALID", 400, message);
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    static EmergencyResuscitationException contextDenied() {
        return new EmergencyResuscitationException("CONTEXT_NOT_PERMITTED", 403,
                "The requested emergency resuscitation context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ResuscitationHead(String status, long rowVersion, OffsetDateTime voidedAt) {}
}
