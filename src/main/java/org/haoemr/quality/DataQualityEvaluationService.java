package org.haoemr.quality;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DataQualityEvaluationRecordRequestWire;
import org.haoemr.contracts.DataQualityEvaluationWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DataQualityEvaluationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DataQualityEvaluationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DataQualityEvaluationWire record(
            ClinicalIdentity identity, String idempotencyKey, DataQualityEvaluationRecordRequestWire request) {
        if (request.dataQualityRuleId() == null || request.targetEntityId() == null
                || request.measuredValue() == null || request.evaluatedAt() == null) {
            throw invalid("data_quality_rule_id, target_entity_id, measured_value and evaluated_at are required");
        }
        double measured = request.measuredValue();
        if (measured < 0 || measured > 1) {
            throw invalid("measured_value must be between 0 and 1");
        }
        RuleHead rule = loadRule(identity.tenantId(), request.dataQualityRuleId());
        if (!"ACTIVE".equals(rule.status())) {
            throw new DataQualityEvaluationException(
                    "DATA_QUALITY_RULE_INACTIVE", 409, "Only an active rule can be evaluated");
        }
        boolean passed = measured >= rule.threshold();
        return transactions.execute(status -> {
            beginCommand(identity, "DATA_QUALITY_EVALUATION_RECORD", idempotencyKey,
                    sha256(request.dataQualityRuleId() + "|" + request.targetEntityId()
                            + "|" + measured + "|" + request.evaluatedAt()));
            UUID evaluationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into data_quality_evaluation(
                      tenant_id, data_quality_evaluation_id, data_quality_rule_id, target_entity_id,
                      measured_value, threshold, status, evaluated_at, evaluated_by)
                    values (:tenant, :evaluation, :rule, :target,
                      :measured, :threshold, :status, :evaluated_at, :evaluated_by)
                    """).param("tenant", identity.tenantId()).param("evaluation", evaluationId)
                    .param("rule", request.dataQualityRuleId()).param("target", request.targetEntityId())
                    .param("measured", BigDecimal.valueOf(measured))
                    .param("threshold", BigDecimal.valueOf(rule.threshold()))
                    .param("status", passed ? "PASSED" : "FAILED")
                    .param("evaluated_at", request.evaluatedAt().atOffset(ZoneOffset.UTC))
                    .param("evaluated_by", identity.userId()).update();
            appendEvidence(identity, evaluationId, "DATA_QUALITY_EVALUATION_RECORDED", "DataQualityEvaluationRecorded");
            completeCommand(identity, "DATA_QUALITY_EVALUATION_RECORD", idempotencyKey, evaluationId);
            return evaluation(identity.tenantId(), evaluationId);
        });
    }

    List<DataQualityEvaluationWire> listEvaluations(ClinicalIdentity identity, UUID dataQualityRuleId) {
        return jdbc.sql("""
                select data_quality_evaluation_id from data_quality_evaluation
                where tenant_id = :tenant and data_quality_rule_id = :rule
                order by evaluated_at desc, data_quality_evaluation_id desc limit 100
                """).param("tenant", identity.tenantId()).param("rule", dataQualityRuleId)
                .query(UUID.class).list().stream()
                .map(id -> evaluation(identity.tenantId(), id)).toList();
    }

    private DataQualityEvaluationWire evaluation(UUID tenantId, UUID evaluationId) {
        return jdbc.sql("""
                select data_quality_evaluation_id, data_quality_rule_id, target_entity_id,
                  measured_value, threshold, status, evaluated_at, evaluated_by, row_version
                from data_quality_evaluation
                where tenant_id = :tenant and data_quality_evaluation_id = :evaluation
                """).param("tenant", tenantId).param("evaluation", evaluationId)
                .query((rs, row) -> new DataQualityEvaluationWire(
                        rs.getObject("data_quality_evaluation_id", UUID.class),
                        rs.getObject("data_quality_rule_id", UUID.class),
                        rs.getObject("target_entity_id", UUID.class),
                        rs.getBigDecimal("measured_value").doubleValue(),
                        rs.getBigDecimal("threshold").doubleValue(),
                        DataQualityEvaluationWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("evaluated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("evaluated_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(DataQualityEvaluationService::contextDenied);
    }

    private RuleHead loadRule(UUID tenantId, UUID dataQualityRuleId) {
        return jdbc.sql("""
                select threshold, status from data_quality_rule
                where tenant_id = :tenant and data_quality_rule_id = :rule
                """).param("tenant", tenantId).param("rule", dataQualityRuleId)
                .query((rs, row) -> new RuleHead(
                        rs.getBigDecimal("threshold").doubleValue(), rs.getString("status")))
                .optional().orElseThrow(DataQualityEvaluationService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new DataQualityEvaluationException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new DataQualityEvaluationException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'DATA_QUALITY_EVALUATION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", evaluationId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DATA_QUALITY_EVALUATION', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", evaluationId).param("event_type", eventType).update();
    }

    private static DataQualityEvaluationException invalid(String message) {
        return new DataQualityEvaluationException(
                "DATA_QUALITY_EVALUATION_REQUEST_INVALID", 400, message);
    }

    static DataQualityEvaluationException contextDenied() {
        return new DataQualityEvaluationException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested data quality evaluation context is not permitted");
    }

    private record RuleHead(double threshold, String status) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
