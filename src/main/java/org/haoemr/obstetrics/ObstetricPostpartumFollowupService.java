package org.haoemr.obstetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricPostpartumFollowupCreateRequestWire;
import org.haoemr.contracts.ObstetricPostpartumFollowupWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ObstetricPostpartumFollowupService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ObstetricPostpartumFollowupService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ObstetricPostpartumFollowupWire record(
            ClinicalIdentity identity, String idempotencyKey, ObstetricPostpartumFollowupCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.followupDate() == null
                || request.lochiaStatus() == null || request.woundHealing() == null || request.recordedAt() == null) {
            throw invalid("patient_id, encounter_id, followup_date, lochia_status, wound_healing and recorded_at are required");
        }
        String complications = blankToNull(request.complications());
        boolean abnormal = request.lochiaStatus() != ObstetricPostpartumFollowupCreateRequestWire.LochiaStatusValue.NORMAL
                || request.woundHealing() != ObstetricPostpartumFollowupCreateRequestWire.WoundHealingValue.GOOD;
        if (abnormal && complications == null) {
            throw new ObstetricPostpartumFollowupException(
                    "OBSTETRIC_POSTPARTUM_COMPLICATION_REQUIRED", 400,
                    "An abnormal postpartum followup requires a complication description");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "OBSTETRIC_POSTPARTUM_FOLLOWUP", idempotencyKey,
                    sha256(request.patientId() + "|" + request.followupDate()));
            UUID followupId = UUID.randomUUID();
            jdbc.sql("""
                    insert into obstetric_postpartum_followup(
                      tenant_id, followup_id, patient_id, encounter_id, facility_id, followup_date,
                      lochia_status, wound_healing, complications, recorded_by, recorded_at)
                    values (:tenant, :followup, :patient, :encounter, :facility, :followup_date,
                      :lochia, :wound, :complications, :recorded_by, :recorded_at)
                    """).param("tenant", identity.tenantId()).param("followup", followupId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .param("followup_date", request.followupDate().atOffset(ZoneOffset.UTC))
                    .param("lochia", request.lochiaStatus().name()).param("wound", request.woundHealing().name())
                    .param("complications", complications)
                    .param("recorded_by", identity.userId())
                    .param("recorded_at", request.recordedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), followupId, 1, "OBSTETRIC_POSTPARTUM_FOLLOWUP_RECORDED",
                    "ObstetricPostpartumFollowupRecorded");
            completeCommand(identity, "OBSTETRIC_POSTPARTUM_FOLLOWUP", idempotencyKey, followupId);
            return followup(identity.tenantId(), followupId);
        });
    }

    List<ObstetricPostpartumFollowupWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select followup_id from obstetric_postpartum_followup
                where tenant_id = :tenant and patient_id = :patient
                order by followup_date desc, followup_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> followup(identity.tenantId(), id)).toList();
    }

    private ObstetricPostpartumFollowupWire followup(UUID tenantId, UUID followupId) {
        return jdbc.sql("""
                select followup_id, patient_id, encounter_id, facility_id, followup_date, lochia_status,
                  wound_healing, complications, recorded_by, recorded_at, row_version
                from obstetric_postpartum_followup
                where tenant_id = :tenant and followup_id = :followup
                """).param("tenant", tenantId).param("followup", followupId)
                .query((rs, row) -> new ObstetricPostpartumFollowupWire(
                        rs.getObject("followup_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getObject("followup_date", OffsetDateTime.class).toInstant(),
                        ObstetricPostpartumFollowupWire.LochiaStatusValue.valueOf(rs.getString("lochia_status")),
                        ObstetricPostpartumFollowupWire.WoundHealingValue.valueOf(rs.getString("wound_healing")),
                        rs.getString("complications"),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ObstetricPostpartumFollowupService::contextDenied);
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
            throw new ObstetricPostpartumFollowupException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ObstetricPostpartumFollowupException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID followupId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", followupId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID followupId, long version,
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
                + followupId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'OBSTETRIC_POSTPARTUM_FOLLOWUP', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", followupId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'OBSTETRIC_POSTPARTUM_FOLLOWUP', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", followupId).param("version", version).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ObstetricPostpartumFollowupException invalid(String message) {
        return new ObstetricPostpartumFollowupException("OBSTETRIC_POSTPARTUM_REQUEST_INVALID", 400, message);
    }

    static ObstetricPostpartumFollowupException contextDenied() {
        return new ObstetricPostpartumFollowupException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested obstetric postpartum context is not permitted");
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
