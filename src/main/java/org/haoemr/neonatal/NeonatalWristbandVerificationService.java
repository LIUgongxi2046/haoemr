package org.haoemr.neonatal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalWristbandVerificationCreateRequestWire;
import org.haoemr.contracts.NeonatalWristbandVerificationWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class NeonatalWristbandVerificationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    NeonatalWristbandVerificationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    NeonatalWristbandVerificationWire record(
            ClinicalIdentity identity, String idempotencyKey, NeonatalWristbandVerificationCreateRequestWire request) {
        if (request.patientId() == null || request.motherPatientId() == null || request.witnessedBy() == null
                || request.verifiedAt() == null) {
            throw invalid("patient_id, mother_patient_id, witnessed_by and verified_at are required");
        }
        String wristbandCode = requireText(request.wristbandCode(), 2, "wristband_code");
        String specimenCode = requireText(request.specimenCode(), 2, "specimen_code");
        if (request.witnessedBy().equals(identity.userId())) {
            throw new NeonatalWristbandVerificationException(
                    "SELF_VERIFICATION_FORBIDDEN", 400,
                    "A neonatal wristband verification requires a different witness");
        }
        if (request.motherPatientId().equals(request.patientId())) {
            throw new NeonatalWristbandVerificationException(
                    "MOTHER_NEONATE_SAME_PATIENT", 400,
                    "The neonate and mother must be different patients");
        }
        requireMotherFemale(identity.tenantId(), request.motherPatientId());
        requireUser(identity.tenantId(), request.witnessedBy());
        return transactions.execute(status -> {
            beginCommand(identity, "NEONATAL_WRISTBAND_VERIFY", idempotencyKey,
                    sha256(request.patientId() + "|" + wristbandCode + "|" + specimenCode));
            UUID verificationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into neonatal_wristband_verification(
                      tenant_id, verification_id, patient_id, mother_patient_id, wristband_code,
                      specimen_code, verified_by, witnessed_by, verified_at)
                    values (:tenant, :verification, :patient, :mother, :wristband,
                      :specimen, :verified_by, :witnessed_by, :verified_at)
                    """).param("tenant", identity.tenantId()).param("verification", verificationId)
                    .param("patient", request.patientId()).param("mother", request.motherPatientId())
                    .param("wristband", wristbandCode).param("specimen", specimenCode)
                    .param("verified_by", identity.userId()).param("witnessed_by", request.witnessedBy())
                    .param("verified_at", request.verifiedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), verificationId, 1,
                    "NEONATAL_WRISTBAND_VERIFIED", "NeonatalWristbandVerified");
            completeCommand(identity, "NEONATAL_WRISTBAND_VERIFY", idempotencyKey, verificationId);
            return verification(identity.tenantId(), verificationId);
        });
    }

    List<NeonatalWristbandVerificationWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select verification_id from neonatal_wristband_verification
                where tenant_id = :tenant and patient_id = :patient
                order by verified_at desc, verification_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> verification(identity.tenantId(), id)).toList();
    }

    private NeonatalWristbandVerificationWire verification(UUID tenantId, UUID verificationId) {
        return jdbc.sql("""
                select verification_id, patient_id, mother_patient_id, wristband_code, specimen_code,
                  verified_by, witnessed_by, verified_at, row_version
                from neonatal_wristband_verification
                where tenant_id = :tenant and verification_id = :verification
                """).param("tenant", tenantId).param("verification", verificationId)
                .query((rs, row) -> new NeonatalWristbandVerificationWire(
                        rs.getObject("verification_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("mother_patient_id", UUID.class),
                        rs.getString("wristband_code"),
                        rs.getString("specimen_code"),
                        rs.getObject("verified_by", UUID.class),
                        rs.getObject("witnessed_by", UUID.class),
                        rs.getObject("verified_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(NeonatalWristbandVerificationService::contextDenied);
    }

    private void requireMotherFemale(UUID tenantId, UUID motherPatientId) {
        String sexCode = jdbc.sql("""
                select sex_code from patient where tenant_id = :tenant and patient_id = :mother
                """).param("tenant", tenantId).param("mother", motherPatientId)
                .query(String.class).optional().orElseThrow(NeonatalWristbandVerificationService::contextDenied);
        if (!"F".equals(sexCode)) {
            throw new NeonatalWristbandVerificationException(
                    "MOTHER_NOT_FEMALE", 400, "mother_patient_id must reference a female patient");
        }
    }

    private void requireUser(UUID tenantId, UUID userId) {
        long count = jdbc.sql("""
                select count(*) from app_user where tenant_id = :tenant and user_id = :user
                """).param("tenant", tenantId).param("user", userId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new NeonatalWristbandVerificationException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new NeonatalWristbandVerificationException("IDEMPOTENCY_REPLAY", 409,
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
                values (:tenant, :audit, now(), :actor, :action, 'NEONATAL_WRISTBAND', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", verificationId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'NEONATAL_WRISTBAND', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", verificationId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static NeonatalWristbandVerificationException invalid(String message) {
        return new NeonatalWristbandVerificationException(
                "NEONATAL_WRISTBAND_REQUEST_INVALID", 400, message);
    }

    static NeonatalWristbandVerificationException contextDenied() {
        return new NeonatalWristbandVerificationException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested neonatal wristband context is not permitted");
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
