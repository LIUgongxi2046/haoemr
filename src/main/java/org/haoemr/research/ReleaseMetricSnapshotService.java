package org.haoemr.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ReleaseMetricSnapshotCreateRequestWire;
import org.haoemr.contracts.ReleaseMetricSnapshotWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ReleaseMetricSnapshotService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ReleaseMetricSnapshotService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ReleaseMetricSnapshotWire record(
            ClinicalIdentity identity, String idempotencyKey, ReleaseMetricSnapshotCreateRequestWire request) {
        if (request.metricType() == null || request.metricValue() == null || request.snapshotDate() == null) {
            throw invalid("metric_type, metric_value and snapshot_date are required");
        }
        if (request.metricValue() < 0) {
            throw invalid("metric_value must not be negative");
        }
        String source = requireText(request.source(), 2, "source");
        return transactions.execute(status -> {
            beginCommand(identity, "RELEASE_METRIC_SNAPSHOT", idempotencyKey,
                    sha256(request.metricType() + "|" + source + "|" + request.snapshotDate()));
            UUID snapshotId = UUID.randomUUID();
            jdbc.sql("""
                    insert into release_metric_snapshot(
                      tenant_id, snapshot_id, metric_type, metric_value, source, snapshot_date)
                    values (:tenant, :snapshot, :metric_type, :metric_value, :source, :snapshot_date)
                    """).param("tenant", identity.tenantId()).param("snapshot", snapshotId)
                    .param("metric_type", request.metricType().name()).param("metric_value", request.metricValue())
                    .param("source", source).param("snapshot_date", request.snapshotDate()).update();
            appendEvidence(identity, snapshotId, "RELEASE_METRIC_SNAPSHOT_RECORDED", "ReleaseMetricSnapshotRecorded");
            completeCommand(identity, "RELEASE_METRIC_SNAPSHOT", idempotencyKey, snapshotId);
            return snapshot(identity.tenantId(), snapshotId);
        });
    }

    List<ReleaseMetricSnapshotWire> listRecords(ClinicalIdentity identity, ReleaseMetricSnapshotWire.MetricTypeValue metricType) {
        return jdbc.sql("""
                select snapshot_id from release_metric_snapshot
                where tenant_id = :tenant and metric_type = :metric_type
                order by snapshot_date desc, snapshot_id desc limit 200
                """).param("tenant", identity.tenantId()).param("metric_type", metricType.name())
                .query(UUID.class).list().stream()
                .map(id -> snapshot(identity.tenantId(), id)).toList();
    }

    private ReleaseMetricSnapshotWire snapshot(UUID tenantId, UUID snapshotId) {
        return jdbc.sql("""
                select snapshot_id, metric_type, metric_value, source, snapshot_date, row_version
                from release_metric_snapshot
                where tenant_id = :tenant and snapshot_id = :snapshot
                """).param("tenant", tenantId).param("snapshot", snapshotId)
                .query((rs, row) -> new ReleaseMetricSnapshotWire(
                        rs.getObject("snapshot_id", UUID.class),
                        ReleaseMetricSnapshotWire.MetricTypeValue.valueOf(rs.getString("metric_type")),
                        rs.getInt("metric_value"),
                        rs.getString("source"),
                        rs.getObject("snapshot_date", LocalDate.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ReleaseMetricSnapshotService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ReleaseMetricSnapshotException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ReleaseMetricSnapshotException("IDEMPOTENCY_REPLAY", 409,
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
                values (:tenant, :audit, now(), :actor, :action, 'RELEASE_METRIC', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", snapshotId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'RELEASE_METRIC', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", snapshotId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static ReleaseMetricSnapshotException invalid(String message) {
        return new ReleaseMetricSnapshotException("RELEASE_METRIC_REQUEST_INVALID", 400, message);
    }

    static ReleaseMetricSnapshotException contextDenied() {
        return new ReleaseMetricSnapshotException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested release metric context is not permitted");
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
