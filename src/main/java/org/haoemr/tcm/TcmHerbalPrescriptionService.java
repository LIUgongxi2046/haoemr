package org.haoemr.tcm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.TcmHerbalPrescriptionCreateRequestWire;
import org.haoemr.contracts.TcmHerbalPrescriptionWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class TcmHerbalPrescriptionService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    TcmHerbalPrescriptionService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    TcmHerbalPrescriptionWire record(
            ClinicalIdentity identity, String idempotencyKey, TcmHerbalPrescriptionCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.containsToxicHerb() == null
                || request.prescribedAt() == null) {
            throw invalid("patient_id, encounter_id, contains_toxic_herb and prescribed_at are required");
        }
        String formulaName = requireText(request.formulaName(), 2, "formula_name");
        String herbs = requireText(request.herbs(), 2, "herbs");
        String precautions = blankToNull(request.toxicHerbPrecautions());
        if (Boolean.TRUE.equals(request.containsToxicHerb()) && precautions == null) {
            throw new TcmHerbalPrescriptionException(
                    "TCM_TOXIC_HERB_PRECAUTIONS_REQUIRED", 400,
                    "A prescription containing a toxic herb requires precautions");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "TCM_HERBAL_PRESCRIPTION", idempotencyKey,
                    sha256(request.patientId() + "|" + formulaName + "|" + herbs));
            UUID prescriptionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into tcm_herbal_prescription(
                      tenant_id, prescription_id, patient_id, encounter_id, facility_id, formula_name,
                      herbs, contains_toxic_herb, toxic_herb_precautions, prescribed_at, prescribed_by)
                    values (:tenant, :prescription, :patient, :encounter, :facility, :formula,
                      :herbs, :toxic, :precautions, :prescribed_at, :prescribed_by)
                    """).param("tenant", identity.tenantId()).param("prescription", prescriptionId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("formula", formulaName)
                    .param("herbs", herbs).param("toxic", request.containsToxicHerb())
                    .param("precautions", precautions)
                    .param("prescribed_at", request.prescribedAt().atOffset(ZoneOffset.UTC))
                    .param("prescribed_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), prescriptionId, 1, "TCM_HERBAL_PRESCRIPTION_RECORDED",
                    "TcmHerbalPrescriptionRecorded");
            completeCommand(identity, "TCM_HERBAL_PRESCRIPTION", idempotencyKey, prescriptionId);
            return prescription(identity.tenantId(), prescriptionId);
        });
    }

    List<TcmHerbalPrescriptionWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select prescription_id from tcm_herbal_prescription
                where tenant_id = :tenant and patient_id = :patient
                order by prescribed_at desc, prescription_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> prescription(identity.tenantId(), id)).toList();
    }

    private TcmHerbalPrescriptionWire prescription(UUID tenantId, UUID prescriptionId) {
        return jdbc.sql("""
                select prescription_id, patient_id, encounter_id, facility_id, formula_name, herbs,
                  contains_toxic_herb, toxic_herb_precautions, prescribed_at, prescribed_by, row_version
                from tcm_herbal_prescription
                where tenant_id = :tenant and prescription_id = :prescription
                """).param("tenant", tenantId).param("prescription", prescriptionId)
                .query((rs, row) -> new TcmHerbalPrescriptionWire(
                        rs.getObject("prescription_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getString("formula_name"),
                        rs.getString("herbs"),
                        rs.getBoolean("contains_toxic_herb"),
                        rs.getString("toxic_herb_precautions"),
                        rs.getObject("prescribed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("prescribed_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(TcmHerbalPrescriptionService::contextDenied);
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
            throw new TcmHerbalPrescriptionException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new TcmHerbalPrescriptionException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID prescriptionId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", prescriptionId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID prescriptionId, long version,
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
                + prescriptionId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'TCM_HERBAL_PRESCRIPTION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", prescriptionId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'TCM_HERBAL_PRESCRIPTION', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", prescriptionId).param("version", version).param("event_type", eventType).update();
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

    private static TcmHerbalPrescriptionException invalid(String message) {
        return new TcmHerbalPrescriptionException("TCM_PRESCRIPTION_REQUEST_INVALID", 400, message);
    }

    static TcmHerbalPrescriptionException contextDenied() {
        return new TcmHerbalPrescriptionException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested TCM prescription context is not permitted");
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
