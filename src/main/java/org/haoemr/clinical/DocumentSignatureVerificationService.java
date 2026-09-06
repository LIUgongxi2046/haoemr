package org.haoemr.clinical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class DocumentSignatureVerificationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ClinicalSignatureProvider signatures;
    private final ObjectMapper objectMapper;

    DocumentSignatureVerificationService(
            JdbcClient jdbc, TransactionTemplate transactions,
            ClinicalSignatureProvider signatures, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.signatures = signatures;
        this.objectMapper = objectMapper;
    }

    VerificationRun verify(
            ClinicalIdentity identity, String idempotencyKey, UUID documentId,
            UUID patientId, UUID encounterId, UUID documentVersionId) {
        return transactions.execute(tx -> {
            begin(identity, idempotencyKey, sha256(documentId + "|" + documentVersionId));
            VersionHead version = jdbc.sql("""
                    select version.content_hash
                    from clinical_document document
                    join clinical_document_version version on version.tenant_id = document.tenant_id
                      and version.document_id = document.document_id
                    where document.tenant_id = :tenant and document.document_id = :document
                      and document.patient_id = :patient and document.encounter_id = :encounter
                      and version.document_version_id = :version
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("patient", patientId).param("encounter", encounterId).param("version", documentVersionId)
                    .query((rs, row) -> new VersionHead(rs.getString("content_hash")))
                    .optional().orElseThrow(() -> new ClinicalCommandException(
                            "CONTEXT_NOT_PERMITTED", 403, "The requested document version is not permitted"));
            List<SignatureHead> stored = jdbc.sql("""
                    select signature_id, signature_status, content_hash, credential_ref, signed_at
                    from signature_evidence where tenant_id = :tenant and document_id = :document
                      and document_version_id = :version order by signed_at, signature_id
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("version", documentVersionId)
                    .query((rs, row) -> new SignatureHead(
                            rs.getObject("signature_id", UUID.class), rs.getString("signature_status"),
                            rs.getString("content_hash"), rs.getString("credential_ref"),
                            rs.getObject("signed_at", OffsetDateTime.class).toInstant()))
                    .list();
            int verified = 0;
            int invalid = 0;
            String provider = "NONE";
            List<Map<String, Object>> details = new ArrayList<>();
            for (SignatureHead signature : stored) {
                boolean storedHashMatches = version.contentHash().equals(signature.contentHash());
                ClinicalSignatureProvider.VerificationAttestation attestation = signatures.verify(
                        signature.credentialRef(), documentVersionId, signature.contentHash(), signature.signedAt());
                provider = attestation.providerCode();
                boolean valid = "VALID".equals(signature.status()) && storedHashMatches && attestation.valid();
                if (valid) verified++; else invalid++;
                details.add(Map.of(
                        "signature_id", signature.signatureId().toString(),
                        "stored_status", signature.status(),
                        "content_hash_matches", storedHashMatches,
                        "provider_evidence_code", attestation.evidenceCode(),
                        "valid", valid));
            }
            if (stored.isEmpty()) {
                invalid = 1;
                details.add(Map.of("evidence_code", "NO_SIGNATURE_EVIDENCE", "valid", false));
            }
            String outcome = invalid == 0 ? "VALID" : "INVALID";
            UUID runId = UUID.randomUUID();
            Instant verifiedAt = Instant.now();
            jdbc.sql("""
                    insert into document_signature_verification_run(
                      tenant_id, verification_run_id, document_id, document_version_id,
                      outcome, verified_count, invalid_count, provider_code, details, verified_by, verified_at)
                    values (:tenant, :run, :document, :version, :outcome, :verified, :invalid,
                      :provider, cast(:details as jsonb), :actor, :verified_at)
                    """).param("tenant", identity.tenantId()).param("run", runId)
                    .param("document", documentId).param("version", documentVersionId)
                    .param("outcome", outcome).param("verified", verified).param("invalid", invalid)
                    .param("provider", provider).param("details", json(details)).param("actor", identity.userId())
                    .param("verified_at", OffsetDateTime.ofInstant(verifiedAt, ZoneOffset.UTC)).update();
            appendAudit(identity, runId, documentId, patientId, outcome);
            jdbc.sql("""
                    insert into outbox_event(
                      tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                      event_type, schema_version, payload)
                    values (:tenant, :event, 'SIGNATURE_VERIFICATION_RUN', :run, 1,
                      'DocumentSignatureVerified', 1, jsonb_build_object(
                        'document_id', :document, 'document_version_id', :version, 'outcome', :outcome))
                    """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                    .param("run", runId).param("document", documentId).param("version", documentVersionId)
                    .param("outcome", outcome).update();
            complete(identity, idempotencyKey, runId);
            return new VerificationRun(runId, documentId, documentVersionId, outcome,
                    verified, invalid, provider, List.copyOf(details), verifiedAt);
        });
    }

    private void begin(ClinicalIdentity identity, String key, String hash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400, "Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'DOCUMENT_SIGNATURE_VERIFY', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours') on conflict do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", hash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw new ClinicalCommandException(
                "IDEMPOTENCY_REPLAY", 409, "This signature verification command was already used");
    }

    private void complete(ClinicalIdentity identity, String key, UUID runId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                  response_ref = jsonb_build_object('verification_run_id', :run)
                where tenant_id = :tenant and command_scope = 'DOCUMENT_SIGNATURE_VERIFY'
                  and idempotency_key = :key
                """).param("run", runId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void appendAudit(
            ClinicalIdentity identity, UUID runId, UUID documentId, UUID patientId, String outcome) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID audit = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + audit + "|DOCUMENT_SIGNATURE_VERIFIED|"
                + runId + "|" + trace + "|" + (previous == null ? "GENESIS" : previous));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, 'DOCUMENT_SIGNATURE_VERIFIED',
                  'SIGNATURE_VERIFICATION_RUN', :run, :patient_hash, :trace, :previous, :hash,
                  jsonb_build_object('document_id', :document, 'outcome', :outcome))
                """).param("tenant", identity.tenantId()).param("audit", audit).param("actor", identity.userId())
                .param("run", runId).param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previous).param("hash", eventHash)
                .param("document", documentId).param("outcome", outcome).update();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("Signature verification details cannot be serialized", invalid);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record VerificationRun(
            UUID verificationRunId, UUID documentId, UUID documentVersionId,
            String outcome, int verifiedCount, int invalidCount, String providerCode,
            List<Map<String, Object>> details, Instant verifiedAt) {}

    private record VersionHead(String contentHash) {}
    private record SignatureHead(
            UUID signatureId, String status, String contentHash, String credentialRef, Instant signedAt) {}
}
