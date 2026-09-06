package org.haoemr.ophthalmology;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.OphthalmologyRecordCreateRequestWire;
import org.haoemr.contracts.OphthalmologyRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class OphthalmologyService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    OphthalmologyService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    OphthalmologyRecordWire createRecord(
            ClinicalIdentity identity, String idempotencyKey, OphthalmologyRecordCreateRequestWire request) {
        if (request.laterality() == null || request.surgicalEye() == null) {
            throw invalid("laterality and surgical_eye are required");
        }
        if (request.iopOdMmhg() != null && (request.iopOdMmhg() < 0 || request.iopOdMmhg() > 80)) {
            throw invalid("iop_od_mmhg must be between 0 and 80");
        }
        if (request.iopOsMmhg() != null && (request.iopOsMmhg() < 0 || request.iopOsMmhg() > 80)) {
            throw invalid("iop_os_mmhg must be between 0 and 80");
        }
        if (request.surgicalEye() != OphthalmologyRecordCreateRequestWire.SurgicalEyeValue.NONE
                && request.laterality() != OphthalmologyRecordCreateRequestWire.LateralityValue.OU
                && !request.surgicalEye().name().equals(request.laterality().name())) {
            throw invalid("surgical_eye must match laterality unless the record covers both eyes");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "OPHTHALMOLOGY_RECORD_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.laterality()
                            + "|" + request.surgicalEye() + "|" + request.iopOdMmhg() + "|" + request.iopOsMmhg()));
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into ophthalmology_record(
                      tenant_id, ophthalmology_record_id, patient_id, encounter_id, facility_id,
                      laterality, iop_od_mmhg, iop_os_mmhg, surgical_eye, status)
                    values (:tenant, :record, :patient, :encounter, :facility,
                      :laterality, :iop_od, :iop_os, :surgical_eye, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("laterality", request.laterality().name())
                    .param("iop_od", decimal(request.iopOdMmhg())).param("iop_os", decimal(request.iopOsMmhg()))
                    .param("surgical_eye", request.surgicalEye().name()).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "OPHTHALMOLOGY_RECORD_CREATED",
                    "OphthalmologyRecordCreated");
            completeCommand(identity, "OPHTHALMOLOGY_RECORD_CREATE", idempotencyKey, recordId);
            return record(identity.tenantId(), recordId, request.patientId());
        });
    }

    List<OphthalmologyRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select ophthalmology_record_id from ophthalmology_record
                where tenant_id = :tenant and patient_id = :patient
                order by created_at desc, ophthalmology_record_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> record(identity.tenantId(), id, patientId)).toList();
    }

    private OphthalmologyRecordWire record(UUID tenantId, UUID recordId, UUID patientId) {
        return jdbc.sql("""
                select ophthalmology_record_id, patient_id, encounter_id, facility_id, laterality,
                  iop_od_mmhg, iop_os_mmhg, surgical_eye, status, row_version
                from ophthalmology_record
                where tenant_id = :tenant and ophthalmology_record_id = :record and patient_id = :patient
                """).param("tenant", tenantId).param("record", recordId).param("patient", patientId)
                .query((rs, row) -> new OphthalmologyRecordWire(
                        rs.getObject("ophthalmology_record_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        OphthalmologyRecordWire.LateralityValue.valueOf(rs.getString("laterality")),
                        nullableDouble(rs.getBigDecimal("iop_od_mmhg")), nullableDouble(rs.getBigDecimal("iop_os_mmhg")),
                        OphthalmologyRecordWire.SurgicalEyeValue.valueOf(rs.getString("surgical_eye")),
                        OphthalmologyRecordWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(OphthalmologyService::contextDenied);
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
            throw new OphthalmologyException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new OphthalmologyException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'OPHTHALMOLOGY_RECORD', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'OPHTHALMOLOGY_RECORD', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static Double nullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static OphthalmologyException invalid(String message) {
        return new OphthalmologyException("OPHTHALMOLOGY_REQUEST_INVALID", 400, message);
    }

    static OphthalmologyException contextDenied() {
        return new OphthalmologyException("CONTEXT_NOT_PERMITTED", 403, "The requested ophthalmology record context is not permitted");
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
