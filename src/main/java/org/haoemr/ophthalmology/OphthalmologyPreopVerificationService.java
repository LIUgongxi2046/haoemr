package org.haoemr.ophthalmology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.OphthalmologyPreopVerificationCreateRequestWire;
import org.haoemr.contracts.OphthalmologyPreopVerificationWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class OphthalmologyPreopVerificationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    OphthalmologyPreopVerificationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    OphthalmologyPreopVerificationWire record(
            ClinicalIdentity identity, String idempotencyKey,
            OphthalmologyPreopVerificationCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.surgicalEye() == null
                || request.witnessedBy() == null || request.verifiedAt() == null) {
            throw invalid("patient_id, encounter_id, surgical_eye, witnessed_by and verified_at are required");
        }
        if (request.witnessedBy().equals(identity.userId())) {
            throw new OphthalmologyPreopVerificationException(
                    "SELF_VERIFICATION_FORBIDDEN", 400,
                    "An ophthalmology preop verification requires a different witness");
        }
        requireUser(identity.tenantId(), request.witnessedBy());
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "OPHTHALMOLOGY_PREOP_VERIFY", idempotencyKey,
                    sha256(request.patientId() + "|" + request.surgicalEye()));
            UUID verificationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into ophthalmology_preop_verification(
                      tenant_id, verification_id, patient_id, encounter_id, facility_id, surgical_eye,
                      verified_by, witnessed_by, verified_at)
                    values (:tenant, :verification, :patient, :encounter, :facility, :eye,
                      :verified_by, :witnessed_by, :verified_at)
                    """).param("tenant", identity.tenantId()).param("verification", verificationId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("eye", request.surgicalEye().name())
                    .param("verified_by", identity.userId()).param("witnessed_by", request.witnessedBy())
                    .param("verified_at", request.verifiedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), verificationId, 1, "OPHTHALMOLOGY_PREOP_VERIFIED",
                    "OphthalmologyPreopVerified");
            completeCommand(identity, "OPHTHALMOLOGY_PREOP_VERIFY", idempotencyKey, verificationId);
            return verification(identity.tenantId(), verificationId);
        });
    }

    List<OphthalmologyPreopVerificationWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select verification_id from ophthalmology_preop_verification
                where tenant_id = :tenant and patient_id = :patient
                order by verified_at desc, verification_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> verification(identity.tenantId(), id)).toList();
    }

    private OphthalmologyPreopVerificationWire verification(UUID tenantId, UUID verificationId) {
        return jdbc.sql("""
                select verification_id, patient_id, encounter_id, facility_id, surgical_eye,
                  verified_by, witnessed_by, verified_at, row_version
                from ophthalmology_preop_verification
                where tenant_id = :tenant and verification_id = :verification
                """).param("tenant", tenantId).param("verification", verificationId)
                .query((rs, row) -> new OphthalmologyPreopVerificationWire(
                        rs.getObject("verification_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        OphthalmologyPreopVerificationWire.SurgicalEyeValue.valueOf(rs.getString("surgical_eye")),
                        rs.getObject("verified_by", UUID.class),
                        rs.getObject("witnessed_by", UUID.class),
                        rs.getObject("verified_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(OphthalmologyPreopVerificationService::contextDenied);
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

    private void requireUser(UUID tenantId, UUID userId) {
        long count = jdbc.sql("""
                select count(*) from app_user where tenant_id = :tenant and user_id = :user
                """).param("tenant", tenantId).param("user", userId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new OphthalmologyPreopVerificationException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new OphthalmologyPreopVerificationException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID verificationId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", verificationId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID verificationId, long version,
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
                + verificationId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'OPHTHALMOLOGY_PREOP', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", verificationId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'OPHTHALMOLOGY_PREOP', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", verificationId).param("version", version).param("event_type", eventType).update();
    }

    private static OphthalmologyPreopVerificationException invalid(String message) {
        return new OphthalmologyPreopVerificationException(
                "OPHTHALMOLOGY_PREOP_REQUEST_INVALID", 400, message);
    }

    static OphthalmologyPreopVerificationException contextDenied() {
        return new OphthalmologyPreopVerificationException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested ophthalmology preop context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
