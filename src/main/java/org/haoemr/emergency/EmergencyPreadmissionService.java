package org.haoemr.emergency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyPreadmissionLinkRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionRegisterRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionUpdateRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionVoidRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class EmergencyPreadmissionService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EmergencyPreadmissionService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    EmergencyPreadmissionWire register(
            ClinicalIdentity identity, String idempotencyKey, EmergencyPreadmissionRegisterRequestWire request) {
        String temporaryIdentifier = requireText(request.temporaryIdentifier(), 2, "temporary_identifier");
        String reason = requireText(request.reason(), 2, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_PREADMISSION_REGISTER", idempotencyKey,
                    sha256(request.facilityId() + "|" + temporaryIdentifier + "|" + reason));
            UUID preadmissionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into emergency_preadmission(
                      tenant_id, preadmission_id, facility_id, temporary_identifier, reason, status)
                    values (:tenant, :preadmission, :facility, :identifier, :reason, 'UNREGISTERED')
                    """).param("tenant", identity.tenantId()).param("preadmission", preadmissionId)
                    .param("facility", request.facilityId()).param("identifier", temporaryIdentifier)
                    .param("reason", reason).update();
            appendEvidence(identity, preadmissionId, "EMERGENCY_PREADMISSION_REGISTERED",
                    "EmergencyPreadmissionRegistered");
            completeCommand(identity, "EMERGENCY_PREADMISSION_REGISTER", idempotencyKey, preadmissionId);
            return preadmission(identity.tenantId(), preadmissionId);
        });
    }

    EmergencyPreadmissionWire link(
            ClinicalIdentity identity, String idempotencyKey, UUID preadmissionId,
            EmergencyPreadmissionLinkRequestWire request) {
        if (request.registeredPatientId() == null) {
            throw invalid("registered_patient_id is required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_PREADMISSION_LINK", idempotencyKey,
                    sha256(preadmissionId + "|" + request.registeredPatientId() + "|" + request.expectedRowVersion()));
            PreadmissionHead current = jdbc.sql("""
                    select status, row_version from emergency_preadmission
                    where tenant_id = :tenant and preadmission_id = :preadmission
                      and facility_id = :facility and voided_at is null for update
                    """).param("tenant", identity.tenantId()).param("preadmission", preadmissionId)
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new PreadmissionHead(rs.getString("status"), rs.getLong("row_version")))
                    .optional().orElseThrow(EmergencyPreadmissionService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new EmergencyPreadmissionException(
                        "EMERGENCY_PREADMISSION_VERSION_CONFLICT", 409,
                        "The preadmission changed; reload before retrying");
            }
            if (!"UNREGISTERED".equals(current.status())) {
                throw new EmergencyPreadmissionException(
                        "EMERGENCY_PREADMISSION_STATE_INVALID", 409,
                        "Only an unregistered preadmission can be linked to a patient");
            }
            jdbc.sql("""
                    update emergency_preadmission set status = 'REGISTERED',
                      registered_patient_id = :patient, registered_at = now(),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and preadmission_id = :preadmission and row_version = :expected
                    """).param("patient", request.registeredPatientId())
                    .param("tenant", identity.tenantId()).param("preadmission", preadmissionId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, preadmissionId, "EMERGENCY_PREADMISSION_LINKED",
                    "EmergencyPreadmissionLinked");
            completeCommand(identity, "EMERGENCY_PREADMISSION_LINK", idempotencyKey, preadmissionId);
            return preadmission(identity.tenantId(), preadmissionId);
        });
    }

    EmergencyPreadmissionWire update(
            ClinicalIdentity identity, String idempotencyKey, UUID preadmissionId,
            EmergencyPreadmissionUpdateRequestWire request) {
        String temporaryIdentifier = requireText(request.temporaryIdentifier(), 2, "temporary_identifier");
        String reason = requireText(request.reason(), 2, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_PREADMISSION_UPDATE", idempotencyKey,
                    sha256(preadmissionId + "|" + request.expectedRowVersion() + "|" + temporaryIdentifier + "|" + reason));
            PreadmissionHead current = lockActive(identity.tenantId(), preadmissionId, request.facilityId());
            requireMutable(current, request.expectedRowVersion());
            String correctionReason = "急诊临时登记更正";
            jdbc.sql("""
                    update emergency_preadmission set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and preadmission_id = :preadmission and row_version = :expected
                    """).param("reason", correctionReason).param("tenant", identity.tenantId())
                    .param("preadmission", preadmissionId).param("expected", current.rowVersion()).update();
            UUID replacementId = UUID.randomUUID();
            jdbc.sql("""
                    insert into emergency_preadmission(
                      tenant_id, preadmission_id, facility_id, temporary_identifier, reason,
                      status, supersedes_preadmission_id)
                    values (:tenant, :preadmission, :facility, :identifier, :reason,
                      'UNREGISTERED', :supersedes)
                    """).param("tenant", identity.tenantId()).param("preadmission", replacementId)
                    .param("facility", request.facilityId()).param("identifier", temporaryIdentifier)
                    .param("reason", reason).param("supersedes", preadmissionId).update();
            appendEvidence(identity, preadmissionId, "EMERGENCY_PREADMISSION_SUPERSEDED", "EmergencyPreadmissionSuperseded");
            appendEvidence(identity, replacementId, "EMERGENCY_PREADMISSION_UPDATED", "EmergencyPreadmissionUpdated");
            completeCommand(identity, "EMERGENCY_PREADMISSION_UPDATE", idempotencyKey, replacementId);
            return preadmission(identity.tenantId(), replacementId);
        });
    }

    EmergencyPreadmissionWire voidPreadmission(
            ClinicalIdentity identity, String idempotencyKey, UUID preadmissionId,
            EmergencyPreadmissionVoidRequestWire request) {
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_PREADMISSION_VOID", idempotencyKey,
                    sha256(preadmissionId + "|" + request.expectedRowVersion() + "|" + reason));
            PreadmissionHead current = lockActive(identity.tenantId(), preadmissionId, request.facilityId());
            requireMutable(current, request.expectedRowVersion());
            jdbc.sql("""
                    update emergency_preadmission set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and preadmission_id = :preadmission and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("preadmission", preadmissionId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, preadmissionId, "EMERGENCY_PREADMISSION_VOIDED", "EmergencyPreadmissionVoided");
            completeCommand(identity, "EMERGENCY_PREADMISSION_VOID", idempotencyKey, preadmissionId);
            return preadmission(identity.tenantId(), preadmissionId);
        });
    }

    List<EmergencyPreadmissionWire> listPreadmissions(ClinicalIdentity identity, UUID facilityId) {
        return jdbc.sql("""
                select preadmission_id from emergency_preadmission
                where tenant_id = :tenant and facility_id = :facility
                  and voided_at is null
                order by created_at desc, preadmission_id desc limit 100
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> preadmission(identity.tenantId(), id)).toList();
    }

    private PreadmissionHead lockActive(UUID tenantId, UUID preadmissionId, UUID facilityId) {
        return jdbc.sql("""
                select status, row_version from emergency_preadmission
                where tenant_id = :tenant and preadmission_id = :preadmission
                  and facility_id = :facility and voided_at is null for update
                """).param("tenant", tenantId).param("preadmission", preadmissionId)
                .param("facility", facilityId)
                .query((rs, row) -> new PreadmissionHead(rs.getString("status"), rs.getLong("row_version")))
                .optional().orElseThrow(EmergencyPreadmissionService::contextDenied);
    }

    private static void requireMutable(PreadmissionHead current, Long expectedRowVersion) {
        if (expectedRowVersion == null || current.rowVersion() != expectedRowVersion) {
            throw new EmergencyPreadmissionException("EMERGENCY_PREADMISSION_VERSION_CONFLICT", 409,
                    "The preadmission changed; reload before retrying");
        }
        if (!"UNREGISTERED".equals(current.status())) {
            throw new EmergencyPreadmissionException("EMERGENCY_PREADMISSION_STATE_INVALID", 409,
                    "A registered preadmission cannot be edited or voided");
        }
    }

    private EmergencyPreadmissionWire preadmission(UUID tenantId, UUID preadmissionId) {
        return jdbc.sql("""
                select preadmission_id, facility_id, temporary_identifier, reason, status,
                  registered_patient_id, registered_at, row_version
                from emergency_preadmission
                where tenant_id = :tenant and preadmission_id = :preadmission
                """).param("tenant", tenantId).param("preadmission", preadmissionId)
                .query((rs, row) -> new EmergencyPreadmissionWire(
                        rs.getObject("preadmission_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("temporary_identifier"), rs.getString("reason"),
                        EmergencyPreadmissionWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("registered_patient_id", UUID.class),
                        rs.getObject("registered_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("registered_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(EmergencyPreadmissionService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new EmergencyPreadmissionException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new EmergencyPreadmissionException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID preadmissionId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", preadmissionId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID preadmissionId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + preadmissionId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'EMERGENCY_PREADMISSION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", preadmissionId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'EMERGENCY_PREADMISSION', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", preadmissionId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static EmergencyPreadmissionException invalid(String message) {
        return new EmergencyPreadmissionException("EMERGENCY_PREADMISSION_REQUEST_INVALID", 400, message);
    }

    static EmergencyPreadmissionException contextDenied() {
        return new EmergencyPreadmissionException("CONTEXT_NOT_PERMITTED", 403,
                "The requested emergency preadmission context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record PreadmissionHead(String status, long rowVersion) {}
}
