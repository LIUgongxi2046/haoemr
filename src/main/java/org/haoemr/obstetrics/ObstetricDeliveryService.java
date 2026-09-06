package org.haoemr.obstetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricDeliveryRecordCreateRequestWire;
import org.haoemr.contracts.ObstetricDeliveryRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ObstetricDeliveryService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ObstetricDeliveryService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ObstetricDeliveryRecordWire record(
            ClinicalIdentity identity, String idempotencyKey, ObstetricDeliveryRecordCreateRequestWire request) {
        if (request.patientId() == null || request.deliveryMethod() == null || request.deliveredAt() == null
                || request.bloodLossMl() == null || request.postpartumHemorrhage() == null) {
            throw invalid("patient_id, delivery_method, delivered_at, blood_loss_ml and postpartum_hemorrhage are required");
        }
        if (request.bloodLossMl() < 0) {
            throw invalid("blood_loss_ml must not be negative");
        }
        if (request.laborDurationMinutes() != null && request.laborDurationMinutes() < 0) {
            throw invalid("labor_duration_minutes must not be negative");
        }
        if (request.postpartumHemorrhage() && request.bloodLossMl() < 500) {
            throw new ObstetricDeliveryException(
                    "POSTPARTUM_HEMORRHAGE_BLOOD_LOSS", 400,
                    "A postpartum hemorrhage flag requires a blood loss of at least 500 ml");
        }
        if (request.neonatePatientId() != null && request.neonatePatientId().equals(request.patientId())) {
            throw new ObstetricDeliveryException(
                    "MOTHER_NEONATE_SAME_PATIENT", 400,
                    "The delivery mother and neonate must be different patients");
        }
        requireFemalePatient(identity.tenantId(), request.patientId());
        return transactions.execute(status -> {
            beginCommand(identity, "OBSTETRIC_DELIVERY_RECORD", idempotencyKey,
                    sha256(request.patientId() + "|" + request.deliveryMethod() + "|" + request.deliveredAt()
                            + "|" + request.bloodLossMl()));
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into obstetric_delivery_record(
                      tenant_id, delivery_record_id, patient_id, neonate_patient_id, delivery_method,
                      delivered_at, blood_loss_ml, labor_duration_minutes, postpartum_hemorrhage, recorded_by)
                    values (:tenant, :record, :patient, :neonate, :method,
                      :delivered_at, :blood_loss, :labor_duration, :hemorrhage, :recorded_by)
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("neonate", request.neonatePatientId())
                    .param("method", request.deliveryMethod().name())
                    .param("delivered_at", request.deliveredAt().atOffset(ZoneOffset.UTC))
                    .param("blood_loss", request.bloodLossMl())
                    .param("labor_duration", request.laborDurationMinutes())
                    .param("hemorrhage", request.postpartumHemorrhage())
                    .param("recorded_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "OBSTETRIC_DELIVERY_RECORDED",
                    "ObstetricDeliveryRecorded");
            completeCommand(identity, "OBSTETRIC_DELIVERY_RECORD", idempotencyKey, recordId);
            return delivery(identity.tenantId(), recordId);
        });
    }

    List<ObstetricDeliveryRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select delivery_record_id from obstetric_delivery_record
                where tenant_id = :tenant and patient_id = :patient
                order by delivered_at desc, delivery_record_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> delivery(identity.tenantId(), id)).toList();
    }

    private ObstetricDeliveryRecordWire delivery(UUID tenantId, UUID recordId) {
        return jdbc.sql("""
                select delivery_record_id, patient_id, neonate_patient_id, delivery_method, delivered_at,
                  blood_loss_ml, labor_duration_minutes, postpartum_hemorrhage, recorded_by, row_version
                from obstetric_delivery_record
                where tenant_id = :tenant and delivery_record_id = :record
                """).param("tenant", tenantId).param("record", recordId)
                .query((rs, row) -> new ObstetricDeliveryRecordWire(
                        rs.getObject("delivery_record_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("neonate_patient_id", UUID.class),
                        ObstetricDeliveryRecordWire.DeliveryMethodValue.valueOf(rs.getString("delivery_method")),
                        rs.getObject("delivered_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("blood_loss_ml"),
                        (Integer) rs.getObject("labor_duration_minutes"),
                        rs.getBoolean("postpartum_hemorrhage"),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ObstetricDeliveryService::contextDenied);
    }

    private void requireFemalePatient(UUID tenantId, UUID patientId) {
        String sexCode = jdbc.sql("""
                select sex_code from patient where tenant_id = :tenant and patient_id = :patient
                """).param("tenant", tenantId).param("patient", patientId)
                .query(String.class).optional().orElseThrow(ObstetricDeliveryService::contextDenied);
        if (!"F".equals(sexCode)) {
            throw new ObstetricDeliveryException(
                    "MOTHER_NOT_FEMALE", 400, "The delivery patient must be female");
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ObstetricDeliveryException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ObstetricDeliveryException("IDEMPOTENCY_REPLAY", 409,
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
                values (:tenant, :audit, now(), :actor, :action, 'OBSTETRIC_DELIVERY', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'OBSTETRIC_DELIVERY', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
    }

    private static ObstetricDeliveryException invalid(String message) {
        return new ObstetricDeliveryException("OBSTETRIC_DELIVERY_REQUEST_INVALID", 400, message);
    }

    static ObstetricDeliveryException contextDenied() {
        return new ObstetricDeliveryException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested obstetric delivery context is not permitted");
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
