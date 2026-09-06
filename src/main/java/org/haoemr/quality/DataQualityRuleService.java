package org.haoemr.quality;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DataQualityRuleDeactivateRequestWire;
import org.haoemr.contracts.DataQualityRuleRegisterRequestWire;
import org.haoemr.contracts.DataQualityRuleWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DataQualityRuleService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DataQualityRuleService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DataQualityRuleWire register(
            ClinicalIdentity identity, String idempotencyKey, DataQualityRuleRegisterRequestWire request) {
        String ruleCode = requireText(request.ruleCode(), 2, "rule_code");
        String ruleName = requireText(request.ruleName(), 2, "rule_name");
        String target = requireText(request.targetEntity(), 2, "target_entity");
        if (request.dimension() == null || request.severity() == null || request.threshold() == null) {
            throw invalid("dimension, severity and threshold are required");
        }
        if (request.threshold() < 0 || request.threshold() > 1) {
            throw invalid("threshold must be between 0 and 1");
        }
        if (!DataQualityOperationsService.supportsScanner(ruleCode)) {
            throw invalid("rule_code has no registered fact scanner; unsupported rules cannot be activated");
        }
        if (!DataQualityOperationsService.requiredTargetEntity(ruleCode).equals(target)) {
            throw invalid("target_entity must match the registered fact scanner for " + ruleCode);
        }
        return transactions.execute(status -> {
            beginCommand(identity, "DATA_QUALITY_RULE_REGISTER", idempotencyKey,
                    sha256(ruleCode + "|" + request.dimension() + "|" + target + "|" + request.threshold()
                            + "|" + request.severity()));
            UUID ruleId = UUID.randomUUID();
            jdbc.sql("""
                    insert into data_quality_rule(
                      tenant_id, data_quality_rule_id, rule_code, rule_name, dimension,
                      target_entity, threshold, severity, status)
                    values (:tenant, :rule, :rule_code, :rule_name, :dimension,
                      :target, :threshold, :severity, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("rule", ruleId)
                    .param("rule_code", ruleCode).param("rule_name", ruleName)
                    .param("dimension", request.dimension().name()).param("target", target)
                    .param("threshold", BigDecimal.valueOf(request.threshold()))
                    .param("severity", request.severity().name()).update();
            appendEvidence(identity, ruleId, "DATA_QUALITY_RULE_REGISTERED", "DataQualityRuleRegistered");
            completeCommand(identity, "DATA_QUALITY_RULE_REGISTER", idempotencyKey, ruleId);
            return rule(identity.tenantId(), ruleId);
        });
    }

    DataQualityRuleWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID ruleId,
            DataQualityRuleDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "DATA_QUALITY_RULE_DEACTIVATE", idempotencyKey, sha256(ruleId.toString()));
            String currentStatus = jdbc.sql("""
                    select status from data_quality_rule
                    where tenant_id = :tenant and data_quality_rule_id = :rule for update
                    """).param("tenant", identity.tenantId()).param("rule", ruleId)
                    .query(String.class).optional().orElseThrow(DataQualityRuleService::contextDenied);
            if (!"ACTIVE".equals(currentStatus)) {
                throw new DataQualityRuleException(
                        "DATA_QUALITY_RULE_STATE_INVALID", 409, "Only an active rule can be deactivated");
            }
            jdbc.sql("""
                    update data_quality_rule set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and data_quality_rule_id = :rule
                    """).param("tenant", identity.tenantId()).param("rule", ruleId).update();
            appendEvidence(identity, ruleId, "DATA_QUALITY_RULE_DEACTIVATED", "DataQualityRuleDeactivated");
            completeCommand(identity, "DATA_QUALITY_RULE_DEACTIVATE", idempotencyKey, ruleId);
            return rule(identity.tenantId(), ruleId);
        });
    }

    List<DataQualityRuleWire> listRules(ClinicalIdentity identity, String dimension) {
        List<UUID> ids = dimension == null || dimension.isBlank()
                ? jdbc.sql("""
                        select data_quality_rule_id from data_quality_rule
                        where tenant_id = :tenant
                        order by rule_code, data_quality_rule_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select data_quality_rule_id from data_quality_rule
                        where tenant_id = :tenant and dimension = :dimension
                        order by rule_code, data_quality_rule_id limit 500
                        """).param("tenant", identity.tenantId()).param("dimension", dimension)
                        .query(UUID.class).list();
        return ids.stream().map(id -> rule(identity.tenantId(), id)).toList();
    }

    private DataQualityRuleWire rule(UUID tenantId, UUID ruleId) {
        return jdbc.sql("""
                select data_quality_rule_id, rule_code, rule_name, dimension, target_entity,
                  threshold, severity, status
                from data_quality_rule where tenant_id = :tenant and data_quality_rule_id = :rule
                """).param("tenant", tenantId).param("rule", ruleId)
                .query((rs, row) -> new DataQualityRuleWire(
                        rs.getObject("data_quality_rule_id", UUID.class), rs.getString("rule_code"),
                        rs.getString("rule_name"),
                        DataQualityRuleWire.DimensionValue.valueOf(rs.getString("dimension")),
                        rs.getString("target_entity"), rs.getBigDecimal("threshold").doubleValue(),
                        DataQualityRuleWire.SeverityValue.valueOf(rs.getString("severity")),
                        DataQualityRuleWire.StatusValue.valueOf(rs.getString("status"))))
                .optional().orElseThrow(DataQualityRuleService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new DataQualityRuleException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new DataQualityRuleException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID ruleId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", ruleId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID ruleId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + ruleId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'DATA_QUALITY_RULE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", ruleId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DATA_QUALITY_RULE', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", ruleId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static DataQualityRuleException invalid(String message) {
        return new DataQualityRuleException("DATA_QUALITY_RULE_REQUEST_INVALID", 400, message);
    }

    static DataQualityRuleException contextDenied() {
        return new DataQualityRuleException("CONTEXT_NOT_PERMITTED", 403,
                "The requested data quality rule context is not permitted");
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
