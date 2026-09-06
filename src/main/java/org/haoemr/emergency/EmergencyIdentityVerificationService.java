package org.haoemr.emergency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyIdentityVerificationCreateRequestWire;
import org.haoemr.contracts.EmergencyIdentityVerificationWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class EmergencyIdentityVerificationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EmergencyIdentityVerificationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    EmergencyIdentityVerificationWire verify(
            ClinicalIdentity identity, String idempotencyKey,
            EmergencyIdentityVerificationCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.facilityId() == null
                || request.verificationPurpose() == null || request.verifiedAt() == null) {
            throw invalid("patient_id, encounter_id, facility_id, verification_purpose and verified_at are required");
        }
        String identifier = requireText(request.identifierValue(), 2, 128, "identifier_value");
        return transactions.execute(status -> {
            requireActiveEmergencyEncounter(identity.tenantId(), request.patientId(),
                    request.encounterId(), request.facilityId());
            String identifierHash = sha256(identifier);
            Match targetMatch = jdbc.sql("""
                    select identifier_type from patient_identifier
                    where tenant_id = :tenant and patient_id = :patient and active
                      and identifier_hash = decode(:hash, 'hex')
                    order by patient_identifier_id limit 1
                    """).param("tenant", identity.tenantId()).param("patient", request.patientId())
                    .param("hash", identifierHash)
                    .query((rs, row) -> new Match(rs.getString("identifier_type")))
                    .optional().orElse(null);
            long otherMatchCount = targetMatch == null ? jdbc.sql("""
                    select count(*) from patient_identifier
                    where tenant_id = :tenant and patient_id <> :patient and active
                      and identifier_hash = decode(:hash, 'hex')
                    """).param("tenant", identity.tenantId()).param("patient", request.patientId())
                    .param("hash", identifierHash).query(Long.class).single() : 0L;
            EmergencyIdentityVerificationWire.OutcomeValue outcome = targetMatch != null
                    ? EmergencyIdentityVerificationWire.OutcomeValue.MATCHED
                    : otherMatchCount > 0
                            ? EmergencyIdentityVerificationWire.OutcomeValue.MISMATCHED
                            : EmergencyIdentityVerificationWire.OutcomeValue.NOT_FOUND;
            String requestHash = sha256(request.patientId() + "|" + request.encounterId() + "|"
                    + identifierHash + "|" + request.verificationPurpose() + "|" + request.verifiedAt());
            beginCommand(identity, idempotencyKey, requestHash);
            UUID verificationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into emergency_identity_verification(
                      tenant_id, verification_id, patient_id, encounter_id, facility_id,
                      identifier_type, masked_identifier, verification_purpose, outcome,
                      verified_by, verified_at)
                    values (:tenant, :verification, :patient, :encounter, :facility,
                      :identifier_type, :masked, :purpose, :outcome, :verified_by, :verified_at)
                    """).param("tenant", identity.tenantId()).param("verification", verificationId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .param("identifier_type", targetMatch == null ? null : targetMatch.identifierType())
                    .param("masked", mask(identifier)).param("purpose", request.verificationPurpose().name())
                    .param("outcome", outcome.name()).param("verified_by", identity.userId())
                    .param("verified_at", request.verifiedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), verificationId, outcome);
            completeCommand(identity, idempotencyKey, verificationId);
            return find(identity.tenantId(), verificationId);
        });
    }

    List<EmergencyIdentityVerificationWire> list(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select verification_id from emergency_identity_verification
                where tenant_id = :tenant and patient_id = :patient
                order by verified_at desc, verification_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream().map(id -> find(identity.tenantId(), id)).toList();
    }

    private EmergencyIdentityVerificationWire find(UUID tenantId, UUID verificationId) {
        return jdbc.sql("""
                select verification_id, patient_id, encounter_id, facility_id, identifier_type,
                  masked_identifier, verification_purpose, outcome, verified_by, verified_at, row_version
                from emergency_identity_verification
                where tenant_id = :tenant and verification_id = :verification
                """).param("tenant", tenantId).param("verification", verificationId)
                .query((rs, row) -> new EmergencyIdentityVerificationWire(
                        rs.getObject("verification_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("identifier_type"), rs.getString("masked_identifier"),
                        EmergencyIdentityVerificationWire.VerificationPurposeValue.valueOf(rs.getString("verification_purpose")),
                        EmergencyIdentityVerificationWire.OutcomeValue.valueOf(rs.getString("outcome")),
                        rs.getObject("verified_by", UUID.class),
                        rs.getObject("verified_at", OffsetDateTime.class).toInstant(), rs.getLong("row_version")))
                .optional().orElseThrow(EmergencyIdentityVerificationService::contextDenied);
    }

    private void requireActiveEmergencyEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter
                where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter
                  and facility_id = :facility and encounter_type = 'EMERGENCY' and status = 'IN_PROGRESS'
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new EmergencyIdentityVerificationException(
                    "INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'EMERGENCY_IDENTITY_VERIFY', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new EmergencyIdentityVerificationException(
                    "IDEMPOTENCY_REPLAY", 409, "This identity verification command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String key, UUID verificationId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'EMERGENCY_IDENTITY_VERIFY'
                  and idempotency_key = :key
                """).param("resource", verificationId).param("tenant", identity.tenantId())
                .param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID patientId, UUID verificationId,
                                EmergencyIdentityVerificationWire.OutcomeValue outcome) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String action = "EMERGENCY_IDENTITY_" + outcome.name();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + verificationId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'EMERGENCY_IDENTITY_VERIFICATION',
                  :resource, :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", verificationId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'EMERGENCY_IDENTITY_VERIFICATION', :aggregate, 1,
                  :event_type, 1, jsonb_build_object('resource_id', :aggregate, 'outcome', :outcome))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", verificationId).param("event_type", "EmergencyIdentity" + outcome.name())
                .param("outcome", outcome.name()).update();
    }

    private static String requireText(String value, int minimum, int maximum, String field) {
        if (value == null || value.trim().length() < minimum || value.trim().length() > maximum) {
            throw invalid(field + " must contain between " + minimum + " and " + maximum + " characters");
        }
        return value.trim();
    }

    private static String mask(String value) {
        int visible = Math.min(2, value.length());
        return "*".repeat(Math.max(0, value.length() - visible)) + value.substring(value.length() - visible);
    }

    private static EmergencyIdentityVerificationException invalid(String message) {
        return new EmergencyIdentityVerificationException(
                "EMERGENCY_IDENTITY_REQUEST_INVALID", 400, message);
    }

    static EmergencyIdentityVerificationException contextDenied() {
        return new EmergencyIdentityVerificationException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested emergency identity context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Match(String identifierType) {}
}
