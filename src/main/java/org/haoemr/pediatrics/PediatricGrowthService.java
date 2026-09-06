package org.haoemr.pediatrics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PediatricGrowthRecordCreateRequestWire;
import org.haoemr.contracts.PediatricGrowthRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class PediatricGrowthService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PediatricGrowthService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    PediatricGrowthRecordWire record(
            ClinicalIdentity identity, String idempotencyKey, PediatricGrowthRecordCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.heightCm() == null
                || request.weightKg() == null || request.measuredAt() == null) {
            throw invalid("patient_id, encounter_id, height_cm, weight_kg and measured_at are required");
        }
        if (request.heightCm() < 30 || request.heightCm() > 220) {
            throw invalid("height_cm must be between 30 and 220");
        }
        if (request.weightKg() < 0.5 || request.weightKg() > 250) {
            throw invalid("weight_kg must be between 0.5 and 250");
        }
        if (request.headCircumferenceCm() != null
                && (request.headCircumferenceCm() < 20 || request.headCircumferenceCm() > 70)) {
            throw invalid("head_circumference_cm must be between 20 and 70");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "PEDIATRIC_GROWTH_RECORD", idempotencyKey,
                    sha256(request.patientId() + "|" + request.heightCm() + "|" + request.weightKg()
                            + "|" + request.measuredAt()));
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into pediatric_growth_record(
                      tenant_id, growth_record_id, patient_id, encounter_id, facility_id,
                      height_cm, weight_kg, head_circumference_cm, measured_at, recorded_by)
                    values (:tenant, :record, :patient, :encounter, :facility,
                      :height, :weight, :head, :measured_at, :recorded_by)
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .param("height", BigDecimal.valueOf(request.heightCm()))
                    .param("weight", BigDecimal.valueOf(request.weightKg()))
                    .param("head", request.headCircumferenceCm() == null
                            ? null : BigDecimal.valueOf(request.headCircumferenceCm()))
                    .param("measured_at", request.measuredAt().atOffset(ZoneOffset.UTC))
                    .param("recorded_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "PEDIATRIC_GROWTH_RECORDED",
                    "PediatricGrowthRecorded");
            completeCommand(identity, "PEDIATRIC_GROWTH_RECORD", idempotencyKey, recordId);
            return growth(identity.tenantId(), recordId);
        });
    }

    List<PediatricGrowthRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select growth_record_id from pediatric_growth_record
                where tenant_id = :tenant and patient_id = :patient
                order by measured_at desc, growth_record_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> growth(identity.tenantId(), id)).toList();
    }

    private PediatricGrowthRecordWire growth(UUID tenantId, UUID recordId) {
        return jdbc.sql("""
                select growth_record_id, patient_id, encounter_id, facility_id, height_cm, weight_kg,
                  head_circumference_cm, measured_at, recorded_by, row_version
                from pediatric_growth_record
                where tenant_id = :tenant and growth_record_id = :record
                """).param("tenant", tenantId).param("record", recordId)
                .query((rs, row) -> new PediatricGrowthRecordWire(
                        rs.getObject("growth_record_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getBigDecimal("height_cm").doubleValue(),
                        rs.getBigDecimal("weight_kg").doubleValue(),
                        rs.getBigDecimal("head_circumference_cm") == null
                                ? null : rs.getBigDecimal("head_circumference_cm").doubleValue(),
                        rs.getObject("measured_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(PediatricGrowthService::contextDenied);
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
            throw new PediatricGrowthException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new PediatricGrowthException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID recordId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", recordId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID recordId, long version,
            String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + recordId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'PEDIATRIC_GROWTH', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'PEDIATRIC_GROWTH', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
    }

    private static PediatricGrowthException invalid(String message) {
        return new PediatricGrowthException("PEDIATRIC_GROWTH_REQUEST_INVALID", 400, message);
    }

    static PediatricGrowthException contextDenied() {
        return new PediatricGrowthException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested pediatric growth context is not permitted");
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
