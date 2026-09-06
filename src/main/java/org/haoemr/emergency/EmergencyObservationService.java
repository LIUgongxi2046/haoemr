package org.haoemr.emergency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyObservationCompleteRequestWire;
import org.haoemr.contracts.EmergencyObservationStartRequestWire;
import org.haoemr.contracts.EmergencyObservationWire;
import org.haoemr.contracts.EmergencyClinicalFactVoidRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class EmergencyObservationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EmergencyObservationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    EmergencyObservationWire startObservation(
            ClinicalIdentity identity, String idempotencyKey, EmergencyObservationStartRequestWire request) {
        if (request.observationStartedAt() == null) {
            throw invalid("observation_started_at is required");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_OBSERVATION_START", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.observationStartedAt()));
            UUID observationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into emergency_observation(
                      tenant_id, observation_id, patient_id, encounter_id, facility_id,
                      observation_started_at, disposition, status)
                    values (:tenant, :observation, :patient, :encounter, :facility,
                      :started_at, 'PENDING', 'OBSERVING')
                    """).param("tenant", identity.tenantId()).param("observation", observationId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .param("started_at", request.observationStartedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), observationId, 1,
                    "EMERGENCY_OBSERVATION_STARTED", "EmergencyObservationStarted");
            completeCommand(identity, "EMERGENCY_OBSERVATION_START", idempotencyKey, observationId);
            return observation(identity.tenantId(), observationId, request.patientId());
        });
    }

    EmergencyObservationWire completeObservation(
            ClinicalIdentity identity, String idempotencyKey, UUID observationId,
            EmergencyObservationCompleteRequestWire request) {
        if (request.disposition() == null) {
            throw invalid("disposition is required to complete an observation");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_OBSERVATION_COMPLETE", idempotencyKey,
                    sha256(observationId + "|" + request.expectedRowVersion() + "|" + request.disposition()));
            ObservationHead current = jdbc.sql("""
                    select status, row_version, voided_at from emergency_observation
                    where tenant_id = :tenant and observation_id = :observation
                      and patient_id = :patient and encounter_id = :encounter
                      and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("observation", observationId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new ObservationHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("voided_at", OffsetDateTime.class)))
                    .optional().orElseThrow(EmergencyObservationService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new EmergencyObservationException(
                        "EMERGENCY_OBSERVATION_VERSION_CONFLICT", 409, "The observation changed; reload before retrying");
            }
            if (!"OBSERVING".equals(current.status()) || current.voidedAt() != null) {
                throw new EmergencyObservationException(
                        "EMERGENCY_OBSERVATION_STATE_INVALID", 409, "Only an observing record can be completed");
            }
            jdbc.sql("""
                    update emergency_observation set disposition = :disposition, status = 'COMPLETED',
                      completed_at = now(), row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and observation_id = :observation and row_version = :expected
                    """).param("disposition", request.disposition().name())
                    .param("tenant", identity.tenantId()).param("observation", observationId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), observationId, current.rowVersion() + 1,
                    "EMERGENCY_OBSERVATION_COMPLETED", "EmergencyObservationCompleted");
            completeCommand(identity, "EMERGENCY_OBSERVATION_COMPLETE", idempotencyKey, observationId);
            return observation(identity.tenantId(), observationId, request.patientId());
        });
    }

    EmergencyObservationWire voidObservation(
            ClinicalIdentity identity, String idempotencyKey, UUID observationId,
            EmergencyClinicalFactVoidRequestWire request) {
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_OBSERVATION_VOID", idempotencyKey,
                    sha256(observationId + "|" + request.expectedRowVersion() + "|" + reason));
            ObservationHead current = jdbc.sql("""
                    select status, row_version, voided_at from emergency_observation
                    where tenant_id = :tenant and observation_id = :observation
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                    for update
                    """).param("tenant", identity.tenantId()).param("observation", observationId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new ObservationHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("voided_at", OffsetDateTime.class)))
                    .optional().orElseThrow(EmergencyObservationService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new EmergencyObservationException("EMERGENCY_OBSERVATION_VERSION_CONFLICT", 409,
                        "The observation changed; reload before retrying");
            }
            if (current.voidedAt() != null) {
                throw new EmergencyObservationException("EMERGENCY_OBSERVATION_STATE_INVALID", 409,
                        "The observation is already voided");
            }
            jdbc.sql("""
                    update emergency_observation set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and observation_id = :observation and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("observation", observationId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), observationId, current.rowVersion() + 1,
                    "EMERGENCY_OBSERVATION_VOIDED", "EmergencyObservationVoided");
            completeCommand(identity, "EMERGENCY_OBSERVATION_VOID", idempotencyKey, observationId);
            return observation(identity.tenantId(), observationId, request.patientId());
        });
    }

    List<EmergencyObservationWire> listObservations(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select observation_id from emergency_observation
                where tenant_id = :tenant and patient_id = :patient
                order by observation_started_at desc, observation_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> observation(identity.tenantId(), id, patientId)).toList();
    }

    private EmergencyObservationWire observation(UUID tenantId, UUID observationId, UUID patientId) {
        return jdbc.sql("""
                select observation_id, patient_id, encounter_id, facility_id, observation_started_at,
                  disposition, status, completed_at, voided_at, void_reason, row_version
                from emergency_observation
                where tenant_id = :tenant and observation_id = :observation and patient_id = :patient
                """).param("tenant", tenantId).param("observation", observationId).param("patient", patientId)
                .query((rs, row) -> new EmergencyObservationWire(
                        rs.getObject("observation_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("observation_started_at", OffsetDateTime.class).toInstant(),
                        EmergencyObservationWire.DispositionValue.valueOf(rs.getString("disposition")),
                        EmergencyObservationWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("completed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("voided_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("voided_at", OffsetDateTime.class).toInstant(),
                        rs.getString("void_reason"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(EmergencyObservationService::contextDenied);
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
            throw new EmergencyObservationException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new EmergencyObservationException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID observationId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", observationId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID observationId, long version,
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
                + observationId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'EMERGENCY_OBSERVATION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", observationId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'EMERGENCY_OBSERVATION', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", observationId).param("version", version).param("event_type", eventType).update();
    }

    private static EmergencyObservationException invalid(String message) {
        return new EmergencyObservationException("EMERGENCY_OBSERVATION_REQUEST_INVALID", 400, message);
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    static EmergencyObservationException contextDenied() {
        return new EmergencyObservationException("CONTEXT_NOT_PERMITTED", 403,
                "The requested emergency observation context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ObservationHead(String status, long rowVersion, OffsetDateTime voidedAt) {}
}
