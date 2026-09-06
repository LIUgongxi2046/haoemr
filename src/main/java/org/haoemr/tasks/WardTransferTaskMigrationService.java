package org.haoemr.tasks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.haoemr.contracts.WardTransferTaskMigrationRequestWire;
import org.haoemr.contracts.WardTransferTaskMigrationResultWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class WardTransferTaskMigrationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    WardTransferTaskMigrationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    WardTransferTaskMigrationResultWire migrateTasks(
            ClinicalIdentity identity, String idempotencyKey, WardTransferTaskMigrationRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null
                || request.fromWardId() == null || request.toWardId() == null) {
            throw invalid("patient_id, encounter_id, from_ward_id and to_ward_id are required");
        }
        if (request.fromWardId().equals(request.toWardId())) {
            throw new WardTransferTaskMigrationException(
                    "WARD_TRANSFER_SAME_WARD", 400,
                    "A ward task migration must move between different wards");
        }
        requireWard(identity.tenantId(), request.fromWardId());
        requireWard(identity.tenantId(), request.toWardId());
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "WARD_TASK_MIGRATION", idempotencyKey,
                    sha256(request.encounterId() + "|" + request.fromWardId() + "|" + request.toWardId()));
            int migrated = jdbc.sql("""
                    update clinical_task
                    set ward_id = :to_ward, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and encounter_id = :encounter
                      and state in ('PENDING', 'ASSIGNED', 'DELIVERED', 'VIEWED', 'CLAIMED', 'IN_PROGRESS', 'ESCALATED')
                      and (ward_id is null or ward_id = :from_ward)
                    """).param("tenant", identity.tenantId()).param("encounter", request.encounterId())
                    .param("to_ward", request.toWardId()).param("from_ward", request.fromWardId()).update();
            if (migrated > 0) {
                appendEvidence(identity, request.patientId(), request.encounterId(),
                        "WARD_TASKS_MIGRATED", "WardTasksMigrated");
            }
            completeCommand(identity, "WARD_TASK_MIGRATION", idempotencyKey, request.encounterId());
            return new WardTransferTaskMigrationResultWire(migrated, request.encounterId(), request.toWardId());
        });
    }

    private void requireWard(UUID tenantId, UUID wardId) {
        long count = jdbc.sql("""
                select count(*) from clinical_ward where tenant_id = :tenant and ward_id = :ward
                """).param("tenant", tenantId).param("ward", wardId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void requireActiveEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter
                where tenant_id = :tenant and encounter_id = :encounter and patient_id = :patient
                  and facility_id = :facility and status in ('ARRIVED', 'IN_PROGRESS', 'SUSPENDED')
                """).param("tenant", tenantId).param("encounter", encounterId).param("patient", patientId)
                .param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new WardTransferTaskMigrationException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new WardTransferTaskMigrationException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID encounterId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", encounterId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + encounterId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CLINICAL_TASK', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", encounterId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CLINICAL_TASK', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", encounterId).param("event_type", eventType).update();
    }

    private static WardTransferTaskMigrationException invalid(String message) {
        return new WardTransferTaskMigrationException("WARD_TASK_MIGRATION_REQUEST_INVALID", 400, message);
    }

    static WardTransferTaskMigrationException contextDenied() {
        return new WardTransferTaskMigrationException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested ward task migration context is not permitted");
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
