package org.haoemr.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortDeactivateRequestWire;
import org.haoemr.contracts.ResearchCohortDefineRequestWire;
import org.haoemr.contracts.ResearchCohortWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ResearchCohortService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ResearchCohortCriteriaEvaluator criteriaEvaluator;

    ResearchCohortService(
            JdbcClient jdbc, TransactionTemplate transactions, ResearchCohortCriteriaEvaluator criteriaEvaluator) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.criteriaEvaluator = criteriaEvaluator;
    }

    ResearchCohortWire define(
            ClinicalIdentity identity, String idempotencyKey, ResearchCohortDefineRequestWire request) {
        String cohortCode = requireText(request.cohortCode(), 2, "cohort_code");
        String cohortName = requireText(request.cohortName(), 2, "cohort_name");
        String inclusion = requireText(request.inclusionCriteria(), 2, "inclusion_criteria");
        String exclusion = blankToNull(request.exclusionCriteria());
        try {
            criteriaEvaluator.validate(inclusion);
            if (exclusion != null) criteriaEvaluator.validate(exclusion);
        } catch (ResearchCohortCriteriaEvaluator.ResearchCriteriaException invalidCriteria) {
            throw new ResearchCohortException("RESEARCH_COHORT_CRITERIA_UNSUPPORTED", 422,
                    invalidCriteria.getMessage());
        }
        return transactions.execute(status -> {
            beginCommand(identity, "RESEARCH_COHORT_DEFINE", idempotencyKey,
                    sha256(cohortCode + "|" + inclusion));
            UUID cohortId = UUID.randomUUID();
            jdbc.sql("""
                    insert into research_cohort(
                      tenant_id, research_cohort_id, cohort_code, cohort_name,
                      inclusion_criteria, exclusion_criteria, status)
                    values (:tenant, :cohort, :code, :name, :inclusion, :exclusion, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("cohort", cohortId)
                    .param("code", cohortCode).param("name", cohortName)
                    .param("inclusion", inclusion).param("exclusion", exclusion).update();
            appendEvidence(identity, cohortId, "RESEARCH_COHORT_DEFINED", "ResearchCohortDefined");
            completeCommand(identity, "RESEARCH_COHORT_DEFINE", idempotencyKey, cohortId);
            return cohort(identity.tenantId(), cohortId);
        });
    }

    ResearchCohortWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID cohortId,
            ResearchCohortDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "RESEARCH_COHORT_DEACTIVATE", idempotencyKey, sha256(cohortId.toString()));
            String currentStatus = jdbc.sql("""
                    select status from research_cohort
                    where tenant_id = :tenant and research_cohort_id = :cohort for update
                    """).param("tenant", identity.tenantId()).param("cohort", cohortId)
                    .query(String.class).optional().orElseThrow(ResearchCohortService::contextDenied);
            if (!"ACTIVE".equals(currentStatus)) {
                throw new ResearchCohortException(
                        "RESEARCH_COHORT_STATE_INVALID", 409, "Only an active cohort can be deactivated");
            }
            jdbc.sql("""
                    update research_cohort set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and research_cohort_id = :cohort
                    """).param("tenant", identity.tenantId()).param("cohort", cohortId).update();
            appendEvidence(identity, cohortId, "RESEARCH_COHORT_DEACTIVATED", "ResearchCohortDeactivated");
            completeCommand(identity, "RESEARCH_COHORT_DEACTIVATE", idempotencyKey, cohortId);
            return cohort(identity.tenantId(), cohortId);
        });
    }

    List<ResearchCohortWire> listCohorts(ClinicalIdentity identity, String status) {
        List<UUID> ids = status == null || status.isBlank()
                ? jdbc.sql("""
                        select research_cohort_id from research_cohort
                        where tenant_id = :tenant order by cohort_code, research_cohort_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select research_cohort_id from research_cohort
                        where tenant_id = :tenant and status = :status
                        order by cohort_code, research_cohort_id limit 500
                        """).param("tenant", identity.tenantId()).param("status", status).query(UUID.class).list();
        return ids.stream().map(id -> cohort(identity.tenantId(), id)).toList();
    }

    private ResearchCohortWire cohort(UUID tenantId, UUID cohortId) {
        return jdbc.sql("""
                select research_cohort_id, cohort_code, cohort_name, inclusion_criteria,
                  exclusion_criteria, status
                from research_cohort where tenant_id = :tenant and research_cohort_id = :cohort
                """).param("tenant", tenantId).param("cohort", cohortId)
                .query((rs, row) -> new ResearchCohortWire(
                        rs.getObject("research_cohort_id", UUID.class), rs.getString("cohort_code"),
                        rs.getString("cohort_name"), rs.getString("inclusion_criteria"),
                        rs.getString("exclusion_criteria"),
                        ResearchCohortWire.StatusValue.valueOf(rs.getString("status"))))
                .optional().orElseThrow(ResearchCohortService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ResearchCohortException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ResearchCohortException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID cohortId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", cohortId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID cohortId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + cohortId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'RESEARCH_COHORT', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", cohortId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'RESEARCH_COHORT', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", cohortId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResearchCohortException invalid(String message) {
        return new ResearchCohortException("RESEARCH_COHORT_REQUEST_INVALID", 400, message);
    }

    static ResearchCohortException contextDenied() {
        return new ResearchCohortException("CONTEXT_NOT_PERMITTED", 403,
                "The requested research cohort context is not permitted");
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
