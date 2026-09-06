package org.haoemr.dental;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DentalTreatmentRecordCreateRequestWire;
import org.haoemr.contracts.DentalTreatmentRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DentalTreatmentService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DentalTreatmentService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DentalTreatmentRecordWire record(
            ClinicalIdentity identity, String idempotencyKey, DentalTreatmentRecordCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.toothNotation() == null
                || request.treatmentType() == null || request.treatedAt() == null) {
            throw invalid("patient_id, encounter_id, tooth_notation, treatment_type and treated_at are required");
        }
        String toothNotation = request.toothNotation().trim();
        if (!toothNotation.matches("^[1-8][1-8]$")) {
            throw invalid("tooth_notation must be a two-digit FDI notation");
        }
        String materialBatch = blankToNull(request.materialBatch());
        boolean restorative = request.treatmentType() == DentalTreatmentRecordCreateRequestWire.TreatmentTypeValue.FILLING
                || request.treatmentType() == DentalTreatmentRecordCreateRequestWire.TreatmentTypeValue.CROWN;
        if (restorative && materialBatch == null) {
            throw new DentalTreatmentException(
                    "DENTAL_TREATMENT_MATERIAL_BATCH_REQUIRED", 400,
                    "Restorative treatments require a material batch for traceability");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "DENTAL_TREATMENT_RECORD", idempotencyKey,
                    sha256(request.patientId() + "|" + toothNotation + "|" + request.treatmentType()));
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into dental_treatment_record(
                      tenant_id, dental_treatment_record_id, patient_id, encounter_id, facility_id,
                      tooth_notation, treatment_type, material_batch, treated_at, performed_by)
                    values (:tenant, :record, :patient, :encounter, :facility,
                      :tooth, :treatment, :batch, :treated_at, :performed_by)
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("tooth", toothNotation)
                    .param("treatment", request.treatmentType().name()).param("batch", materialBatch)
                    .param("treated_at", request.treatedAt().atOffset(ZoneOffset.UTC))
                    .param("performed_by", identity.userId()).update();
            UUID completedDentalRecordId = completeActiveDentalRecord(identity.tenantId(), request.patientId(),
                    request.encounterId(), request.facilityId(), toothNotation);
            if (completedDentalRecordId != null) {
                appendEvidence(identity, request.patientId(), completedDentalRecordId, 2, "DENTAL_RECORD_COMPLETED",
                        "DentalRecordCompleted", "DENTAL_RECORD");
            }
            appendEvidence(identity, request.patientId(), recordId, 1, "DENTAL_TREATMENT_RECORDED",
                    "DentalTreatmentRecorded", "DENTAL_TREATMENT");
            completeCommand(identity, "DENTAL_TREATMENT_RECORD", idempotencyKey, recordId);
            return treatment(identity.tenantId(), recordId);
        });
    }

    List<DentalTreatmentRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select dental_treatment_record_id from dental_treatment_record
                where tenant_id = :tenant and patient_id = :patient
                order by treated_at desc, dental_treatment_record_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> treatment(identity.tenantId(), id)).toList();
    }

    private DentalTreatmentRecordWire treatment(UUID tenantId, UUID recordId) {
        return jdbc.sql("""
                select dental_treatment_record_id, patient_id, encounter_id, facility_id, tooth_notation,
                  treatment_type, material_batch, treated_at, performed_by, row_version
                from dental_treatment_record
                where tenant_id = :tenant and dental_treatment_record_id = :record
                """).param("tenant", tenantId).param("record", recordId)
                .query((rs, row) -> new DentalTreatmentRecordWire(
                        rs.getObject("dental_treatment_record_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getString("tooth_notation"),
                        DentalTreatmentRecordWire.TreatmentTypeValue.valueOf(rs.getString("treatment_type")),
                        rs.getString("material_batch"),
                        rs.getObject("treated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("performed_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(DentalTreatmentService::contextDenied);
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
            throw new DentalTreatmentException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new DentalTreatmentException("IDEMPOTENCY_REPLAY", 409,
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

    private UUID completeActiveDentalRecord(
            UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId, String toothNotation) {
        return jdbc.sql("""
                update dental_record set status = 'COMPLETED', row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter
                  and facility_id = :facility and tooth_notation = :tooth and status = 'ACTIVE'
                returning dental_record_id
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .param("facility", facilityId).param("tooth", toothNotation).query(UUID.class).optional().orElse(null);
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID recordId, long version,
            String action, String eventType, String aggregateType) {
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
                values (:tenant, :audit, now(), :actor, :action, :resource_type, :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("resource_type", aggregateType)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, :aggregate_type, :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate_type", aggregateType).param("aggregate", recordId)
                .param("version", version).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DentalTreatmentException invalid(String message) {
        return new DentalTreatmentException("DENTAL_TREATMENT_REQUEST_INVALID", 400, message);
    }

    static DentalTreatmentException contextDenied() {
        return new DentalTreatmentException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested dental treatment context is not permitted");
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
