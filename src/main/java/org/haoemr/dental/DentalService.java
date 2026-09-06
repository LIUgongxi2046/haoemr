package org.haoemr.dental;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DentalRecordCreateRequestWire;
import org.haoemr.contracts.DentalRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DentalService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DentalService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DentalRecordWire createRecord(
            ClinicalIdentity identity, String idempotencyKey, DentalRecordCreateRequestWire request) {
        String tooth = normalize(request.toothNotation());
        if (!isValidFdi(tooth)) {
            throw invalid("tooth_notation must be a valid FDI code (quadrant 1-8, tooth 1-5 for deciduous)");
        }
        String procedure = normalize(request.procedureTooth());
        if (procedure != null) {
            if (!isValidFdi(procedure)) {
                throw invalid("procedure_tooth must be a valid FDI code");
            }
            if (!procedure.equals(tooth)) {
                throw invalid("procedure_tooth must match tooth_notation");
            }
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "DENTAL_RECORD_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + tooth + "|" + procedure));
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into dental_record(
                      tenant_id, dental_record_id, patient_id, encounter_id, facility_id,
                      tooth_notation, procedure_tooth, status)
                    values (:tenant, :record, :patient, :encounter, :facility,
                      :tooth, :procedure, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("tooth", tooth)
                    .param("procedure", procedure).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "DENTAL_RECORD_CREATED", "DentalRecordCreated");
            completeCommand(identity, "DENTAL_RECORD_CREATE", idempotencyKey, recordId);
            return record(identity.tenantId(), recordId, request.patientId());
        });
    }

    List<DentalRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select dental_record_id from dental_record
                where tenant_id = :tenant and patient_id = :patient
                order by created_at desc, dental_record_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> record(identity.tenantId(), id, patientId)).toList();
    }

    private DentalRecordWire record(UUID tenantId, UUID recordId, UUID patientId) {
        return jdbc.sql("""
                select dental_record_id, patient_id, encounter_id, facility_id, tooth_notation,
                  procedure_tooth, status, row_version
                from dental_record where tenant_id = :tenant and dental_record_id = :record and patient_id = :patient
                """).param("tenant", tenantId).param("record", recordId).param("patient", patientId)
                .query((rs, row) -> new DentalRecordWire(
                        rs.getObject("dental_record_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("tooth_notation"), rs.getString("procedure_tooth"),
                        DentalRecordWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(DentalService::contextDenied);
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
            throw new DentalException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new DentalException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'DENTAL_RECORD', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DENTAL_RECORD', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isValidFdi(String notation) {
        if (notation == null || notation.length() != 2) return false;
        char quadrant = notation.charAt(0);
        char tooth = notation.charAt(1);
        if (quadrant < '1' || quadrant > '8' || tooth < '1' || tooth > '8') return false;
        return quadrant <= '4' || tooth <= '5';
    }

    private static DentalException invalid(String message) {
        return new DentalException("DENTAL_REQUEST_INVALID", 400, message);
    }

    static DentalException contextDenied() {
        return new DentalException("CONTEXT_NOT_PERMITTED", 403, "The requested dental record context is not permitted");
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
