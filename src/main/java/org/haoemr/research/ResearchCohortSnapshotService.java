package org.haoemr.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortSnapshotRequestWire;
import org.haoemr.contracts.ResearchCohortSnapshotWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ResearchCohortSnapshotService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ResearchCohortSnapshotService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ResearchCohortSnapshotWire record(
            ClinicalIdentity identity, String idempotencyKey, ResearchCohortSnapshotRequestWire request) {
        if (request.researchCohortId() == null || request.computedAt() == null) {
            throw invalid("research_cohort_id, member_count and computed_at are required");
        }
        CohortHead cohort = loadCohort(identity.tenantId(), request.researchCohortId());
        if (!"ACTIVE".equals(cohort.status())) {
            throw new ResearchCohortSnapshotException(
                    "RESEARCH_COHORT_INACTIVE", 409, "Only an active cohort can be snapshotted");
        }
        String criteriaHash = sha256(identity.tenantId() + "|" + request.researchCohortId()
                + "|" + cohort.inclusionCriteria() + "|" + (cohort.exclusionCriteria() == null
                        ? "" : cohort.exclusionCriteria()));
        int actualMemberCount = jdbc.sql("""
                select count(*) from research_cohort_member
                where tenant_id = :tenant and research_cohort_id = :cohort
                """).param("tenant", identity.tenantId()).param("cohort", request.researchCohortId())
                .query(Integer.class).single();
        if (request.memberCount() != null && request.memberCount() != actualMemberCount) {
            throw new ResearchCohortSnapshotException(
                    "RESEARCH_COHORT_SNAPSHOT_COUNT_MISMATCH", 409,
                    "The submitted member count does not match the server-side cohort membership");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "RESEARCH_COHORT_SNAPSHOT_RECORD", idempotencyKey,
                    sha256(request.researchCohortId() + "|" + actualMemberCount + "|" + criteriaHash));
            UUID snapshotId = UUID.randomUUID();
            jdbc.sql("""
                    insert into research_cohort_snapshot(
                      tenant_id, research_cohort_snapshot_id, research_cohort_id, member_count,
                      criteria_hash, computed_at, computed_by, member_snapshot_materialized)
                    values (:tenant, :snapshot, :cohort, :count, :hash, :computed_at, :computed_by, true)
                    """).param("tenant", identity.tenantId()).param("snapshot", snapshotId)
                    .param("cohort", request.researchCohortId()).param("count", actualMemberCount)
                    .param("hash", criteriaHash)
                    .param("computed_at", request.computedAt().atOffset(ZoneOffset.UTC))
                    .param("computed_by", identity.userId()).update();
            jdbc.sql("""
                    insert into research_cohort_snapshot_member(
                      tenant_id, research_cohort_snapshot_id, cohort_member_id, patient_id, sex_code, birth_year)
                    select m.tenant_id, :snapshot, m.cohort_member_id, m.patient_id, p.sex_code,
                      extract(year from p.birth_date)::integer
                    from research_cohort_member m
                    join patient p on p.tenant_id = m.tenant_id and p.patient_id = m.patient_id
                    where m.tenant_id = :tenant and m.research_cohort_id = :cohort
                    """).param("tenant", identity.tenantId()).param("snapshot", snapshotId)
                    .param("cohort", request.researchCohortId()).update();
            appendEvidence(identity, snapshotId, "RESEARCH_COHORT_SNAPSHOT_RECORDED", "ResearchCohortSnapshotRecorded");
            completeCommand(identity, "RESEARCH_COHORT_SNAPSHOT_RECORD", idempotencyKey, snapshotId);
            return snapshot(identity.tenantId(), snapshotId);
        });
    }

    List<ResearchCohortSnapshotWire> listSnapshots(ClinicalIdentity identity, UUID researchCohortId) {
        return jdbc.sql("""
                select research_cohort_snapshot_id from research_cohort_snapshot
                where tenant_id = :tenant and research_cohort_id = :cohort
                order by computed_at desc, research_cohort_snapshot_id desc limit 100
                """).param("tenant", identity.tenantId()).param("cohort", researchCohortId)
                .query(UUID.class).list().stream()
                .map(id -> snapshot(identity.tenantId(), id)).toList();
    }

    private ResearchCohortSnapshotWire snapshot(UUID tenantId, UUID snapshotId) {
        return jdbc.sql("""
                select research_cohort_snapshot_id, research_cohort_id, member_count, criteria_hash,
                  computed_at, computed_by, member_snapshot_materialized, row_version
                from research_cohort_snapshot
                where tenant_id = :tenant and research_cohort_snapshot_id = :snapshot
                """).param("tenant", tenantId).param("snapshot", snapshotId)
                .query((rs, row) -> new ResearchCohortSnapshotWire(
                        rs.getObject("research_cohort_snapshot_id", UUID.class),
                        rs.getObject("research_cohort_id", UUID.class),
                        rs.getInt("member_count"),
                        rs.getString("criteria_hash"),
                        rs.getObject("computed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("computed_by", UUID.class),
                        rs.getBoolean("member_snapshot_materialized"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ResearchCohortSnapshotService::contextDenied);
    }

    private CohortHead loadCohort(UUID tenantId, UUID researchCohortId) {
        return jdbc.sql("""
                select inclusion_criteria, exclusion_criteria, status from research_cohort
                where tenant_id = :tenant and research_cohort_id = :cohort
                """).param("tenant", tenantId).param("cohort", researchCohortId)
                .query((rs, row) -> new CohortHead(
                        rs.getString("inclusion_criteria"),
                        rs.getString("exclusion_criteria"),
                        rs.getString("status")))
                .optional().orElseThrow(ResearchCohortSnapshotService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ResearchCohortSnapshotException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ResearchCohortSnapshotException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID snapshotId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", snapshotId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID snapshotId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + snapshotId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'RESEARCH_COHORT_SNAPSHOT', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", snapshotId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'RESEARCH_COHORT_SNAPSHOT', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", snapshotId).param("event_type", eventType).update();
    }

    private static ResearchCohortSnapshotException invalid(String message) {
        return new ResearchCohortSnapshotException(
                "RESEARCH_COHORT_SNAPSHOT_REQUEST_INVALID", 400, message);
    }

    static ResearchCohortSnapshotException contextDenied() {
        return new ResearchCohortSnapshotException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested research cohort snapshot context is not permitted");
    }

    private record CohortHead(String inclusionCriteria, String exclusionCriteria, String status) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
