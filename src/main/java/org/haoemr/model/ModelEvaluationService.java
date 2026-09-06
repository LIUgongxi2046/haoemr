package org.haoemr.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ModelEvaluationRecordRequestWire;
import org.haoemr.contracts.ModelEvaluationWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ModelEvaluationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ModelEvaluationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ModelEvaluationWire record(
            ClinicalIdentity identity, String idempotencyKey, ModelEvaluationRecordRequestWire request) {
        String evalName = requireText(request.evalName(), 2, "eval_name");
        if (request.modelDeploymentId() == null || request.score() == null || request.threshold() == null
                || request.evaluatedAt() == null) {
            throw invalid("model_deployment_id, score, threshold and evaluated_at are required");
        }
        if (request.score() < 0 || request.score() > 1 || request.threshold() < 0 || request.threshold() > 1) {
            throw invalid("score and threshold must be between 0 and 1");
        }
        boolean passed = request.score() >= request.threshold();
        return transactions.execute(status -> {
            beginCommand(identity, "MODEL_EVALUATION_RECORD", idempotencyKey,
                    sha256(request.modelDeploymentId() + "|" + evalName + "|" + request.score() + "|" + request.threshold()));
            String deploymentStatus = jdbc.sql("""
                    select status from model_deployment
                    where tenant_id = :tenant and model_deployment_id = :deployment for update
                    """).param("tenant", identity.tenantId()).param("deployment", request.modelDeploymentId())
                    .query(String.class).optional().orElseThrow(ModelEvaluationService::contextDenied);
            if (!"ACTIVE".equals(deploymentStatus)) {
                throw new ModelEvaluationException("MODEL_DEPLOYMENT_STATE_INVALID", 409,
                        "Only an active model deployment can be evaluated");
            }
            UUID evaluationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into model_evaluation(
                      tenant_id, model_evaluation_id, model_deployment_id, eval_name, score,
                      threshold, status, evaluated_at, evaluated_by)
                    values (:tenant, :evaluation, :deployment, :name, :score,
                      :threshold, :status, :evaluated_at, :evaluated_by)
                    """).param("tenant", identity.tenantId()).param("evaluation", evaluationId)
                    .param("deployment", request.modelDeploymentId()).param("name", evalName)
                    .param("score", BigDecimal.valueOf(request.score()))
                    .param("threshold", BigDecimal.valueOf(request.threshold()))
                    .param("status", passed ? "PASSED" : "FAILED")
                    .param("evaluated_at", request.evaluatedAt().atOffset(ZoneOffset.UTC))
                    .param("evaluated_by", identity.userId()).update();
            jdbc.sql("""
                    update model_deployment
                    set evaluation_status = :evaluation_status,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and model_deployment_id = :deployment
                    """).param("evaluation_status", passed ? "EVALUATING" : "REJECTED")
                    .param("tenant", identity.tenantId()).param("deployment", request.modelDeploymentId()).update();
            appendEvidence(identity, evaluationId, "MODEL_EVALUATION_RECORDED", "ModelEvaluationRecorded");
            completeCommand(identity, "MODEL_EVALUATION_RECORD", idempotencyKey, evaluationId);
            return evaluation(identity.tenantId(), evaluationId);
        });
    }

    List<ModelEvaluationWire> listEvaluations(ClinicalIdentity identity, UUID modelDeploymentId) {
        return jdbc.sql("""
                select model_evaluation_id from model_evaluation
                where tenant_id = :tenant and model_deployment_id = :deployment
                order by evaluated_at desc, model_evaluation_id desc limit 100
                """).param("tenant", identity.tenantId()).param("deployment", modelDeploymentId)
                .query(UUID.class).list().stream()
                .map(id -> evaluation(identity.tenantId(), id)).toList();
    }

    private ModelEvaluationWire evaluation(UUID tenantId, UUID evaluationId) {
        return jdbc.sql("""
                select model_evaluation_id, model_deployment_id, eval_name, score, threshold,
                  status, evaluated_at, evaluated_by
                from model_evaluation where tenant_id = :tenant and model_evaluation_id = :evaluation
                """).param("tenant", tenantId).param("evaluation", evaluationId)
                .query((rs, row) -> new ModelEvaluationWire(
                        rs.getObject("model_evaluation_id", UUID.class),
                        rs.getObject("model_deployment_id", UUID.class), rs.getString("eval_name"),
                        rs.getBigDecimal("score").doubleValue(), rs.getBigDecimal("threshold").doubleValue(),
                        ModelEvaluationWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("evaluated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("evaluated_by", UUID.class)))
                .optional().orElseThrow(ModelEvaluationService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ModelEvaluationException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ModelEvaluationException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID evaluationId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", evaluationId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID evaluationId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + evaluationId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'MODEL_EVALUATION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", evaluationId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MODEL_EVALUATION', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", evaluationId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static ModelEvaluationException invalid(String message) {
        return new ModelEvaluationException("MODEL_EVALUATION_REQUEST_INVALID", 400, message);
    }

    static ModelEvaluationException contextDenied() {
        return new ModelEvaluationException("CONTEXT_NOT_PERMITTED", 403,
                "The requested model evaluation context is not permitted");
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
