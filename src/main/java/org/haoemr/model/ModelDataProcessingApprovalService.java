package org.haoemr.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ModelDataProcessingApprovalService {

    private static final Set<String> ALLOWED_SCOPES = Set.of(
            "RECORDS", "ORDERS", "RESULTS", "TASKS", "ATTACHMENTS", "CONFIGURATION");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ModelDataProcessingApprovalService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    List<ApprovalView> list(ClinicalIdentity identity, UUID deploymentId) {
        requireDeployment(identity.tenantId(), deploymentId);
        return jdbc.sql("""
                select approval_id, model_deployment_id, legal_basis, pia_reference,
                  processor_agreement_reference, endpoint_region, retention_days,
                  allowed_context_scopes, status, approved_by, approved_at, expires_at,
                  revoked_by, revoked_at, revocation_reason, row_version
                from medical_ai_external_processing_approval
                where tenant_id = :tenant and model_deployment_id = :deployment
                order by approved_at desc, approval_id desc
                """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                .query((rs, row) -> new ApprovalView(
                        rs.getObject("approval_id", UUID.class),
                        rs.getObject("model_deployment_id", UUID.class), rs.getString("legal_basis"),
                        rs.getString("pia_reference"), rs.getString("processor_agreement_reference"),
                        rs.getString("endpoint_region"), rs.getInt("retention_days"),
                        List.of((String[]) rs.getArray("allowed_context_scopes").getArray()),
                        rs.getString("status"), rs.getObject("approved_by", UUID.class),
                        rs.getObject("approved_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("revoked_by", UUID.class), rs.getObject("revoked_at", OffsetDateTime.class),
                        rs.getString("revocation_reason"), rs.getLong("row_version"))).list();
    }

    ApprovalView approve(
            ClinicalIdentity identity, String idempotencyKey, UUID deploymentId, ApprovalCommand command) {
        validate(command);
        return transactions.execute(status -> {
            Deployment deployment = requireDeployment(identity.tenantId(), deploymentId);
            if (!"ACTIVE".equals(deployment.status()) || !"CLOUD_ALLOWED".equals(deployment.residencyPolicy())) {
                throw invalid("Only an active CLOUD_ALLOWED deployment can receive external-processing approval");
            }
            beginCommand(identity, "MODEL_EXTERNAL_PROCESSING_APPROVE", idempotencyKey,
                    sha256(deploymentId + "|" + command.piaReference() + "|" + command.expiresAt()
                            + "|" + command.allowedContextScopes()));
            long active = jdbc.sql("""
                    select count(*) from medical_ai_external_processing_approval
                    where tenant_id = :tenant and model_deployment_id = :deployment
                      and status = 'ACTIVE' and expires_at > now()
                    """).param("tenant", identity.tenantId()).param("deployment", deploymentId)
                    .query(Long.class).single();
            if (active > 0) {
                throw new ModelDeploymentException("MODEL_PROCESSING_APPROVAL_EXISTS", 409,
                        "Revoke the current external-processing approval before creating another one");
            }
            UUID approvalId = UUID.randomUUID();
            jdbc.sql("""
                    insert into medical_ai_external_processing_approval(
                      tenant_id, approval_id, model_deployment_id, legal_basis, pia_reference,
                      processor_agreement_reference, endpoint_region, retention_days,
                      allowed_context_scopes, status, approved_by, expires_at)
                    values (:tenant, :approval, :deployment, :legal_basis, :pia, :agreement,
                      :region, :retention, cast(:scopes as text[]), 'ACTIVE', :actor, :expires_at)
                    """).param("tenant", identity.tenantId()).param("approval", approvalId)
                    .param("deployment", deploymentId).param("legal_basis", command.legalBasis().trim())
                    .param("pia", command.piaReference().trim())
                    .param("agreement", command.processorAgreementReference().trim())
                    .param("region", command.endpointRegion().trim()).param("retention", command.retentionDays())
                    .param("scopes", "{" + String.join(",", command.allowedContextScopes()) + "}")
                    .param("actor", identity.userId()).param("expires_at", command.expiresAt()).update();
            appendEvidence(identity, approvalId, 1, "MODEL_EXTERNAL_PROCESSING_APPROVED",
                    "ModelExternalProcessingApproved");
            completeCommand(identity, "MODEL_EXTERNAL_PROCESSING_APPROVE", idempotencyKey, approvalId);
            return get(identity.tenantId(), approvalId);
        });
    }

    ApprovalView revoke(
            ClinicalIdentity identity, String idempotencyKey, UUID deploymentId, UUID approvalId,
            long expectedRowVersion, String reason) {
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < 2 || normalizedReason.length() > 500 || expectedRowVersion < 1) {
            throw invalid("expected_row_version and a 2 to 500 character revocation reason are required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "MODEL_EXTERNAL_PROCESSING_REVOKE", idempotencyKey,
                    sha256(deploymentId + "|" + approvalId + "|" + expectedRowVersion + "|" + normalizedReason));
            int updated = jdbc.sql("""
                    update medical_ai_external_processing_approval
                    set status = 'REVOKED', revoked_by = :actor, revoked_at = now(),
                      revocation_reason = :reason, row_version = row_version + 1
                    where tenant_id = :tenant and approval_id = :approval
                      and model_deployment_id = :deployment and status = 'ACTIVE'
                      and row_version = :expected
                    """).param("actor", identity.userId()).param("reason", normalizedReason)
                    .param("tenant", identity.tenantId()).param("approval", approvalId)
                    .param("deployment", deploymentId).param("expected", expectedRowVersion).update();
            if (updated != 1) {
                throw new ModelDeploymentException("MODEL_PROCESSING_APPROVAL_VERSION_CONFLICT", 409,
                        "The approval is no longer active or its version changed");
            }
            appendEvidence(identity, approvalId, expectedRowVersion + 1,
                    "MODEL_EXTERNAL_PROCESSING_REVOKED", "ModelExternalProcessingRevoked");
            completeCommand(identity, "MODEL_EXTERNAL_PROCESSING_REVOKE", idempotencyKey, approvalId);
            return get(identity.tenantId(), approvalId);
        });
    }

    private ApprovalView get(UUID tenantId, UUID approvalId) {
        return jdbc.sql("""
                select model_deployment_id from medical_ai_external_processing_approval
                where tenant_id = :tenant and approval_id = :approval
                """).param("tenant", tenantId).param("approval", approvalId).query(UUID.class)
                .optional().flatMap(deployment -> list(new ClinicalIdentity(tenantId, UUID.randomUUID(), List.of()), deployment)
                        .stream().filter(item -> item.approvalId().equals(approvalId)).findFirst())
                .orElseThrow(ModelDeploymentService::contextDenied);
    }

    private Deployment requireDeployment(UUID tenantId, UUID deploymentId) {
        return jdbc.sql("""
                select residency_policy, status from model_deployment
                where tenant_id = :tenant and model_deployment_id = :deployment
                """).param("tenant", tenantId).param("deployment", deploymentId)
                .query((rs, row) -> new Deployment(rs.getString("residency_policy"), rs.getString("status")))
                .optional().orElseThrow(ModelDeploymentService::contextDenied);
    }

    private static void validate(ApprovalCommand command) {
        if (command == null || shortText(command.legalBasis(), 4, 512)
                || shortText(command.piaReference(), 4, 256)
                || shortText(command.processorAgreementReference(), 4, 256)
                || shortText(command.endpointRegion(), 2, 128)
                || command.retentionDays() < 0 || command.retentionDays() > 3650
                || command.expiresAt() == null || !command.expiresAt().isAfter(OffsetDateTime.now())
                || command.allowedContextScopes() == null || command.allowedContextScopes().isEmpty()
                || !ALLOWED_SCOPES.containsAll(command.allowedContextScopes())) {
            throw invalid("Complete legal basis, PIA, processor agreement, endpoint region, retention, expiry and allowed scopes are required");
        }
    }

    private static boolean shortText(String value, int min, int max) {
        int length = value == null ? 0 : value.trim().length();
        return length < min || length > max;
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ModelDeploymentException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw new ModelDeploymentException("IDEMPOTENCY_REPLAY", 409,
                "This command key was already used");
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID approvalId, long version, String action, String eventType) {
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
                values (:tenant, :audit, now(), :actor, :action, 'MODEL_PROCESSING_APPROVAL', :resource,
                  null, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", approvalId)
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MODEL_PROCESSING_APPROVAL', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", approvalId).param("version", version).param("event_type", eventType).update();
    }

    private static ModelDeploymentException invalid(String message) {
        return new ModelDeploymentException("MODEL_PROCESSING_APPROVAL_REQUEST_INVALID", 400, message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record ApprovalCommand(String legalBasis, String piaReference, String processorAgreementReference,
            String endpointRegion, int retentionDays, List<String> allowedContextScopes, OffsetDateTime expiresAt) {}

    record ApprovalView(UUID approvalId, UUID modelDeploymentId, String legalBasis, String piaReference,
            String processorAgreementReference, String endpointRegion, int retentionDays,
            List<String> allowedContextScopes, String status, UUID approvedBy, OffsetDateTime approvedAt,
            OffsetDateTime expiresAt, UUID revokedBy, OffsetDateTime revokedAt,
            String revocationReason, long rowVersion) {}

    private record Deployment(String residencyPolicy, String status) {}
}
