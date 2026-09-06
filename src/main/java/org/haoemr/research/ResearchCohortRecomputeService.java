package org.haoemr.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortRecomputeRequestWire;
import org.haoemr.contracts.ResearchCohortRecomputeResultWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reuses the cohort criteria evaluator to materialize all currently eligible active patients. */
@Service
final class ResearchCohortRecomputeService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ResearchCohortCriteriaEvaluator evaluator;

    ResearchCohortRecomputeService(JdbcClient jdbc, TransactionTemplate transactions,
            ResearchCohortCriteriaEvaluator evaluator) {
        this.jdbc = jdbc; this.transactions = transactions; this.evaluator = evaluator;
    }

    ResearchCohortRecomputeResultWire recompute(ClinicalIdentity identity, String key, UUID cohortId,
            ResearchCohortRecomputeRequestWire request) {
        if (request.computedAt() == null) throw invalid("computed_at is required");
        return transactions.execute(status -> {
            if (key == null || key.isBlank() || key.length() > 128) throw invalid("a valid Idempotency-Key is required");
            Cohort criteria = lockActiveCohort(identity.tenantId(), cohortId);
            int command = jdbc.sql("""
                    insert into idempotency_record(tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                    values (:tenant, 'RESEARCH_COHORT_RECOMPUTE', :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                    on conflict (tenant_id, command_scope, idempotency_key) do nothing
                    """).param("tenant", identity.tenantId()).param("key", key)
                    .param("hash", sha256(cohortId + "|" + request.computedAt())).param("trace", UUID.randomUUID().toString()).update();
            if (command != 1) throw new ResearchCohortMemberException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
            List<UUID> patients = jdbc.sql("select patient_id from patient where tenant_id = :tenant and status = 'ACTIVE' order by patient_id")
                    .param("tenant", identity.tenantId()).query(UUID.class).list();
            int matched = 0, added = 0;
            for (UUID patientId : patients) {
                boolean include = evaluator.matches(identity.tenantId(), patientId, criteria.inclusion());
                if (include && criteria.exclusion() != null) include = !evaluator.matches(identity.tenantId(), patientId, criteria.exclusion());
                if (!include) continue;
                matched++;
                int inserted = jdbc.sql("""
                        insert into research_cohort_member(tenant_id, cohort_member_id, research_cohort_id, patient_id, computed_by, computed_at)
                        values (:tenant, :member, :cohort, :patient, :actor, :at)
                        on conflict (tenant_id, research_cohort_id, patient_id) do nothing
                        """).param("tenant", identity.tenantId()).param("member", UUID.randomUUID()).param("cohort", cohortId)
                        .param("patient", patientId).param("actor", identity.userId()).param("at", request.computedAt().atOffset(ZoneOffset.UTC)).update();
                added += inserted;
            }
            int count = jdbc.sql("select count(*) from research_cohort_member where tenant_id = :tenant and research_cohort_id = :cohort")
                    .param("tenant", identity.tenantId()).param("cohort", cohortId).query(Integer.class).single();
            UUID snapshotId = UUID.randomUUID();
            jdbc.sql("""
                    insert into research_cohort_snapshot(
                      tenant_id, research_cohort_snapshot_id, research_cohort_id, member_count,
                      criteria_hash, computed_at, computed_by, member_snapshot_materialized)
                    values (:tenant, :snapshot, :cohort, :count, :hash, :at, :actor, true)
                    """).param("tenant", identity.tenantId()).param("snapshot", snapshotId).param("cohort", cohortId)
                    .param("count", count).param("hash", criteria.hash()).param("at", request.computedAt().atOffset(ZoneOffset.UTC)).param("actor", identity.userId()).update();
            jdbc.sql("""
                    insert into research_cohort_snapshot_member(
                      tenant_id, research_cohort_snapshot_id, cohort_member_id, patient_id, sex_code, birth_year)
                    select m.tenant_id, :snapshot, m.cohort_member_id, m.patient_id, p.sex_code,
                      extract(year from p.birth_date)::integer
                    from research_cohort_member m
                    join patient p on p.tenant_id = m.tenant_id and p.patient_id = m.patient_id
                    where m.tenant_id = :tenant and m.research_cohort_id = :cohort
                    """).param("tenant", identity.tenantId()).param("snapshot", snapshotId).param("cohort", cohortId).update();
            appendEvidence(identity, cohortId, snapshotId, patients.size(), matched, added, count);
            jdbc.sql("""
                    update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                      response_ref = jsonb_build_object('resource_id', :resource)
                    where tenant_id = :tenant and command_scope = 'RESEARCH_COHORT_RECOMPUTE' and idempotency_key = :key
                    """).param("tenant", identity.tenantId()).param("key", key).param("resource", snapshotId).update();
            return new ResearchCohortRecomputeResultWire(cohortId, patients.size(), matched, added, snapshotId, count, request.computedAt());
        });
    }

    private Cohort lockActiveCohort(UUID tenant, UUID cohortId) {
        Cohort cohort = jdbc.sql("select status, inclusion_criteria, exclusion_criteria from research_cohort where tenant_id = :tenant and research_cohort_id = :cohort for update")
                .param("tenant", tenant).param("cohort", cohortId).query((rs, row) -> new Cohort(rs.getString("status"), rs.getString("inclusion_criteria"), rs.getString("exclusion_criteria")))
                .optional().orElseThrow(ResearchCohortMemberService::contextDenied);
        if (!"ACTIVE".equals(cohort.status())) throw new ResearchCohortMemberException(
                "RESEARCH_COHORT_INACTIVE", 409, "Only an active cohort can be recomputed");
        return cohort;
    }

    private void appendEvidence(ClinicalIdentity identity, UUID cohortId, UUID snapshotId,
            int evaluated, int matched, int added, int memberCount) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID(); String trace = UUID.randomUUID().toString();
        String hash = sha256(identity.tenantId() + "|" + auditId + "|RESEARCH_COHORT_RECOMPUTED|" + cohortId + "|" + trace + "|" + (previous == null ? "GENESIS" : previous));
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id, action_code, resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, 'RESEARCH_COHORT_RECOMPUTED', 'RESEARCH_COHORT', :resource, :patient_hash, :trace, :previous, :hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("resource", cohortId).param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previous).param("hash", hash).update();
        jdbc.sql("""
                insert into outbox_event(tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, event_type, schema_version, payload)
                values (:tenant, :event, 'RESEARCH_COHORT_SNAPSHOT', :snapshot, 1, 'ResearchCohortRecomputed', 1,
                  jsonb_build_object('cohort_id', :cohort, 'snapshot_id', :snapshot, 'evaluated_count', :evaluated, 'matched_count', :matched, 'new_member_count', :added, 'member_count', :member_count))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID()).param("cohort", cohortId)
                .param("snapshot", snapshotId).param("evaluated", evaluated).param("matched", matched)
                .param("added", added).param("member_count", memberCount).update();
    }

    private static ResearchCohortMemberException invalid(String message) { return new ResearchCohortMemberException("RESEARCH_COHORT_RECOMPUTE_REQUEST_INVALID", 400, message); }
    private record Cohort(String status, String inclusion, String exclusion) {
        String hash() { return sha256(inclusion + "|" + (exclusion == null ? "" : exclusion)); }
    }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
