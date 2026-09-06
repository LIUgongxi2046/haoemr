package org.haoemr.reproductive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ArtEmbryoTransferRecordCreateRequestWire;
import org.haoemr.contracts.ArtEmbryoTransferRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ArtEmbryoTransferService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ArtEmbryoTransferService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ArtEmbryoTransferRecordWire record(
            ClinicalIdentity identity, String idempotencyKey, ArtEmbryoTransferRecordCreateRequestWire request) {
        if (request.patientId() == null || request.cycleId() == null || request.embryoCount() == null
                || request.verifierId() == null || request.transferredAt() == null) {
            throw invalid("patient_id, cycle_id, embryo_count, verifier_id and transferred_at are required");
        }
        if (request.embryoCount() < 1) {
            throw invalid("embryo_count must be at least 1");
        }
        if (request.verifierId().equals(identity.userId())) {
            throw new ArtEmbryoTransferException(
                    "SELF_VERIFICATION_FORBIDDEN", 400,
                    "An embryo transfer requires a different verifier");
        }
        requireUser(identity.tenantId(), request.verifierId());
        CycleHead cycle = requireCycle(identity.tenantId(), request.cycleId(), request.patientId());
        if (cycle.ethicsConsentDate().isAfter(request.transferredAt().atOffset(ZoneOffset.UTC).toLocalDate())) {
            throw new ArtEmbryoTransferException(
                    "ETHICS_CONSENT_REQUIRED", 409,
                    "The embryo transfer requires ethics consent dated on or before the transfer");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "ART_EMBRYO_TRANSFER_RECORD", idempotencyKey,
                    sha256(request.patientId() + "|" + request.cycleId() + "|" + request.embryoCount()));
            UUID transferId = UUID.randomUUID();
            jdbc.sql("""
                    insert into art_embryo_transfer_record(
                      tenant_id, embryo_transfer_id, cycle_id, patient_id, embryo_count,
                      transferred_at, operator_id, verifier_id)
                    values (:tenant, :transfer, :cycle, :patient, :count,
                      :transferred_at, :operator, :verifier)
                    """).param("tenant", identity.tenantId()).param("transfer", transferId)
                    .param("cycle", request.cycleId()).param("patient", request.patientId())
                    .param("count", request.embryoCount())
                    .param("transferred_at", request.transferredAt().atOffset(ZoneOffset.UTC))
                    .param("operator", identity.userId()).param("verifier", request.verifierId()).update();
            appendEvidence(identity, request.patientId(), transferId, 1, "ART_EMBRYO_TRANSFER_RECORDED",
                    "ArtEmbryoTransferRecorded");
            completeCommand(identity, "ART_EMBRYO_TRANSFER_RECORD", idempotencyKey, transferId);
            return transfer(identity.tenantId(), transferId);
        });
    }

    List<ArtEmbryoTransferRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select embryo_transfer_id from art_embryo_transfer_record
                where tenant_id = :tenant and patient_id = :patient
                order by transferred_at desc, embryo_transfer_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> transfer(identity.tenantId(), id)).toList();
    }

    private ArtEmbryoTransferRecordWire transfer(UUID tenantId, UUID transferId) {
        return jdbc.sql("""
                select embryo_transfer_id, cycle_id, patient_id, embryo_count, transferred_at,
                  operator_id, verifier_id, row_version
                from art_embryo_transfer_record
                where tenant_id = :tenant and embryo_transfer_id = :transfer
                """).param("tenant", tenantId).param("transfer", transferId)
                .query((rs, row) -> new ArtEmbryoTransferRecordWire(
                        rs.getObject("embryo_transfer_id", UUID.class),
                        rs.getObject("cycle_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getInt("embryo_count"),
                        rs.getObject("transferred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("operator_id", UUID.class),
                        rs.getObject("verifier_id", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ArtEmbryoTransferService::contextDenied);
    }

    private CycleHead requireCycle(UUID tenantId, UUID cycleId, UUID patientId) {
        return jdbc.sql("""
                select patient_id, ethics_consent_date from art_cycle_record
                where tenant_id = :tenant and cycle_id = :cycle
                """).param("tenant", tenantId).param("cycle", cycleId)
                .query((rs, row) -> new CycleHead(
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("ethics_consent_date", LocalDate.class)))
                .optional().orElseThrow(ArtEmbryoTransferService::contextDenied);
    }

    private void requireUser(UUID tenantId, UUID userId) {
        long count = jdbc.sql("""
                select count(*) from app_user where tenant_id = :tenant and user_id = :user
                """).param("tenant", tenantId).param("user", userId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ArtEmbryoTransferException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ArtEmbryoTransferException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID transferId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", transferId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID transferId, long version,
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
                + transferId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ART_EMBRYO_TRANSFER', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", transferId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ART_EMBRYO_TRANSFER', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", transferId).param("version", version).param("event_type", eventType).update();
    }

    private static ArtEmbryoTransferException invalid(String message) {
        return new ArtEmbryoTransferException("ART_EMBRYO_TRANSFER_REQUEST_INVALID", 400, message);
    }

    static ArtEmbryoTransferException contextDenied() {
        return new ArtEmbryoTransferException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested ART embryo transfer context is not permitted");
    }

    private record CycleHead(UUID patientId, LocalDate ethicsConsentDate) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
