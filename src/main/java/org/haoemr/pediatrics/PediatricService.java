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
import org.haoemr.contracts.PediatricRecordCreateRequestWire;
import org.haoemr.contracts.PediatricRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class PediatricService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PediatricService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    PediatricRecordWire createRecord(
            ClinicalIdentity identity, String idempotencyKey, PediatricRecordCreateRequestWire request) {
        String guardianName = requireText(request.guardianName(), 2, "guardian_name");
        if (request.guardianRelationship() == null || request.ageInMonths() == null
                || request.weightKg() == null || request.measuredAt() == null) {
            throw invalid("guardian_relationship, age_in_months, weight_kg and measured_at are required");
        }
        if (request.ageInMonths() < 0 || request.ageInMonths() > 216) {
            throw invalid("age_in_months must be between 0 and 216");
        }
        if (request.weightKg() < 0.5 || request.weightKg() > 250) {
            throw invalid("weight_kg must be between 0.5 and 250");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "PEDIATRIC_RECORD_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.ageInMonths()
                            + "|" + request.weightKg() + "|" + request.measuredAt()));
            UUID recordId = UUID.randomUUID();
            boolean critical = request.criticalFlag() != null && request.criticalFlag();
            jdbc.sql("""
                    insert into pediatric_record(
                      tenant_id, pediatric_record_id, patient_id, encounter_id, facility_id,
                      guardian_name, guardian_relationship, guardian_phone, age_in_months, weight_kg,
                      measured_at, critical_flag, status)
                    values (:tenant, :record, :patient, :encounter, :facility,
                      :guardian_name, :guardian_relationship, :guardian_phone, :age, :weight,
                      :measured_at, :critical, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("guardian_name", guardianName)
                    .param("guardian_relationship", request.guardianRelationship().name())
                    .param("guardian_phone", blankToNull(request.guardianPhone()))
                    .param("age", request.ageInMonths()).param("weight", BigDecimal.valueOf(request.weightKg()))
                    .param("measured_at", request.measuredAt().atOffset(ZoneOffset.UTC))
                    .param("critical", critical).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "PEDIATRIC_RECORD_CREATED", "PediatricRecordCreated");
            completeCommand(identity, "PEDIATRIC_RECORD_CREATE", idempotencyKey, recordId);
            return record(identity.tenantId(), recordId, request.patientId());
        });
    }

    List<PediatricRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select pediatric_record_id from pediatric_record
                where tenant_id = :tenant and patient_id = :patient
                order by measured_at desc, pediatric_record_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> record(identity.tenantId(), id, patientId)).toList();
    }

    private PediatricRecordWire record(UUID tenantId, UUID recordId, UUID patientId) {
        return jdbc.sql("""
                select pediatric_record_id, patient_id, encounter_id, facility_id, guardian_name,
                  guardian_relationship, guardian_phone, age_in_months, weight_kg, measured_at,
                  critical_flag, status, row_version
                from pediatric_record where tenant_id = :tenant and pediatric_record_id = :record and patient_id = :patient
                """).param("tenant", tenantId).param("record", recordId).param("patient", patientId)
                .query((rs, row) -> new PediatricRecordWire(
                        rs.getObject("pediatric_record_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("guardian_name"),
                        PediatricRecordWire.GuardianRelationshipValue.valueOf(rs.getString("guardian_relationship")),
                        rs.getString("guardian_phone"), rs.getInt("age_in_months"),
                        rs.getBigDecimal("weight_kg").doubleValue(),
                        rs.getObject("measured_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("critical_flag"),
                        PediatricRecordWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(PediatricService::contextDenied);
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
            throw new PediatricException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new PediatricException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'PEDIATRIC_RECORD', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'PEDIATRIC_RECORD', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
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

    private static PediatricException invalid(String message) {
        return new PediatricException("PEDIATRIC_REQUEST_INVALID", 400, message);
    }

    static PediatricException contextDenied() {
        return new PediatricException("CONTEXT_NOT_PERMITTED", 403, "The requested pediatric record context is not permitted");
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
