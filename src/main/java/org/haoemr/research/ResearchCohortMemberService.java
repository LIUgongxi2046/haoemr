package org.haoemr.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortMemberComputeRequestWire;
import org.haoemr.contracts.ResearchCohortMemberWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ResearchCohortMemberService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ResearchCohortCriteriaEvaluator criteriaEvaluator;

    ResearchCohortMemberService(
            JdbcClient jdbc, TransactionTemplate transactions, ResearchCohortCriteriaEvaluator criteriaEvaluator) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.criteriaEvaluator = criteriaEvaluator;
    }

    ResearchCohortMemberWire compute(
            ClinicalIdentity identity, String idempotencyKey, ResearchCohortMemberComputeRequestWire request) {
        if (request.researchCohortId() == null || request.patientId() == null || request.computedAt() == null) {
            throw invalid("research_cohort_id, patient_id and computed_at are required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "RESEARCH_COHORT_MEMBER_COMPUTE", idempotencyKey,
                    sha256(request.researchCohortId() + "|" + request.patientId()));
            CohortCriteria criteria = requireActiveCohort(identity.tenantId(), request.researchCohortId());
            requireActivePatient(identity.tenantId(), request.patientId());
            boolean included;
            try {
                included = criteriaEvaluator.matches(
                        identity.tenantId(), request.patientId(), criteria.inclusionCriteria());
                if (criteria.exclusionCriteria() != null && criteriaEvaluator.matches(
                        identity.tenantId(), request.patientId(), criteria.exclusionCriteria())) included = false;
            } catch (ResearchCohortCriteriaEvaluator.ResearchCriteriaException invalidCriteria) {
                throw new ResearchCohortMemberException("RESEARCH_COHORT_CRITERIA_UNSUPPORTED", 422,
                        invalidCriteria.getMessage());
            }
            if (!included) {
                throw new ResearchCohortMemberException("RESEARCH_COHORT_PATIENT_NOT_ELIGIBLE", 422,
                        "The patient does not satisfy the versioned inclusion and exclusion criteria");
            }
            UUID memberId = UUID.randomUUID();
            jdbc.sql("""
                    insert into research_cohort_member(
                      tenant_id, cohort_member_id, research_cohort_id, patient_id, computed_by, computed_at)
                    values (:tenant, :member, :cohort, :patient, :actor, :computed_at)
                    """).param("tenant", identity.tenantId()).param("member", memberId)
                    .param("cohort", request.researchCohortId()).param("patient", request.patientId())
                    .param("actor", identity.userId())
                    .param("computed_at", request.computedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, memberId, "RESEARCH_COHORT_MEMBER_COMPUTED", "ResearchCohortMemberComputed");
            completeCommand(identity, "RESEARCH_COHORT_MEMBER_COMPUTE", idempotencyKey, memberId);
            return member(identity.tenantId(), memberId);
        });
    }

    List<ResearchCohortMemberWire> list(ClinicalIdentity identity, UUID researchCohortId) {
        return jdbc.sql("""
                select cohort_member_id from research_cohort_member
                where tenant_id = :tenant and research_cohort_id = :cohort
                order by computed_at desc, cohort_member_id desc limit 500
                """).param("tenant", identity.tenantId()).param("cohort", researchCohortId)
                .query(UUID.class).list().stream()
                .map(id -> member(identity.tenantId(), id)).toList();
    }

    private ResearchCohortMemberWire member(UUID tenantId, UUID memberId) {
        return jdbc.sql("""
                select cohort_member_id, research_cohort_id, patient_id, computed_by, computed_at, row_version
                from research_cohort_member
                where tenant_id = :tenant and cohort_member_id = :member
                """).param("tenant", tenantId).param("member", memberId)
                .query((rs, row) -> new ResearchCohortMemberWire(
                        rs.getObject("cohort_member_id", UUID.class),
                        rs.getObject("research_cohort_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("computed_by", UUID.class),
                        rs.getObject("computed_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ResearchCohortMemberService::contextDenied);
    }

    private CohortCriteria requireActiveCohort(UUID tenantId, UUID researchCohortId) {
        CohortCriteria criteria = jdbc.sql("""
                select status, inclusion_criteria, exclusion_criteria from research_cohort
                where tenant_id = :tenant and research_cohort_id = :cohort for update
                """).param("tenant", tenantId).param("cohort", researchCohortId)
                .query((rs, row) -> new CohortCriteria(
                        rs.getString("status"), rs.getString("inclusion_criteria"),
                        rs.getString("exclusion_criteria")))
                .optional().orElseThrow(ResearchCohortMemberService::contextDenied);
        if (!"ACTIVE".equals(criteria.status())) {
            throw new ResearchCohortMemberException(
                    "RESEARCH_COHORT_INACTIVE", 409, "Only an active cohort can receive computed members");
        }
        return criteria;
    }

    private void requireActivePatient(UUID tenantId, UUID patientId) {
        String status = jdbc.sql("""
                select status from patient where tenant_id = :tenant and patient_id = :patient for update
                """).param("tenant", tenantId).param("patient", patientId)
                .query(String.class).optional().orElseThrow(ResearchCohortMemberService::contextDenied);
        if (!"ACTIVE".equals(status)) {
            throw new ResearchCohortMemberException(
                    "PATIENT_INACTIVE", 409, "Only an active patient can be computed into a cohort");
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ResearchCohortMemberException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ResearchCohortMemberException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID memberId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", memberId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID memberId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + memberId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'RESEARCH_COHORT_MEMBER', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", memberId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'RESEARCH_COHORT_MEMBER', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", memberId).param("event_type", eventType).update();
    }

    private static ResearchCohortMemberException invalid(String message) {
        return new ResearchCohortMemberException("RESEARCH_COHORT_MEMBER_REQUEST_INVALID", 400, message);
    }

    static ResearchCohortMemberException contextDenied() {
        return new ResearchCohortMemberException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested research cohort member context is not permitted");
    }

    private record CohortCriteria(String status, String inclusionCriteria, String exclusionCriteria) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
