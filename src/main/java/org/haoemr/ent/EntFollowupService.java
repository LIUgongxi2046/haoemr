package org.haoemr.ent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EntFollowupRecordCreateRequestWire;
import org.haoemr.contracts.EntFollowupRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class EntFollowupService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EntFollowupService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    EntFollowupRecordWire record(
            ClinicalIdentity identity, String idempotencyKey, EntFollowupRecordCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.airwayHandoverId() == null || request.attended() == null
                || request.scheduledDate() == null || request.recordedAt() == null) {
            throw invalid("patient_id, encounter_id, airway_handover_id, attended, scheduled_date and recorded_at are required");
        }
        String followupReason = requireText(request.followupReason(), 2, "followup_reason");
        String noShowReason = blankToNull(request.noShowReason());
        if (!Boolean.TRUE.equals(request.attended()) && noShowReason == null) {
            throw new EntFollowupException(
                    "ENT_FOLLOWUP_NO_SHOW_REASON_REQUIRED", 400,
                    "A missed followup requires a no-show reason");
        }
        String outcomeNote = blankToNull(request.outcomeNote());
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            requireAirwayHandover(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId(),
                    request.airwayHandoverId());
            beginCommand(identity, "ENT_FOLLOWUP_RECORD", idempotencyKey,
                    sha256(request.patientId() + "|" + followupReason + "|" + request.scheduledDate()));
            UUID followupId = UUID.randomUUID();
            jdbc.sql("""
                    insert into ent_followup_record(
                      tenant_id, followup_id, patient_id, encounter_id, facility_id, airway_handover_id, followup_reason,
                      scheduled_date, attended, no_show_reason, outcome_note, recorded_by, recorded_at)
                    values (:tenant, :followup, :patient, :encounter, :facility, :handover, :reason,
                      :scheduled_date, :attended, :no_show, :outcome, :recorded_by, :recorded_at)
                    """).param("tenant", identity.tenantId()).param("followup", followupId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("handover", request.airwayHandoverId())
                    .param("reason", followupReason)
                    .param("scheduled_date", request.scheduledDate().atOffset(ZoneOffset.UTC))
                    .param("attended", request.attended()).param("no_show", noShowReason)
                    .param("outcome", outcomeNote)
                    .param("recorded_by", identity.userId())
                    .param("recorded_at", request.recordedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), followupId, 1, "ENT_FOLLOWUP_RECORDED",
                    "EntFollowupRecorded");
            completeCommand(identity, "ENT_FOLLOWUP_RECORD", idempotencyKey, followupId);
            return followup(identity.tenantId(), followupId);
        });
    }

    List<EntFollowupRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select followup_id from ent_followup_record
                where tenant_id = :tenant and patient_id = :patient
                order by scheduled_date desc, followup_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> followup(identity.tenantId(), id)).toList();
    }

    private EntFollowupRecordWire followup(UUID tenantId, UUID followupId) {
        return jdbc.sql("""
                select followup_id, patient_id, encounter_id, facility_id, airway_handover_id, followup_reason, scheduled_date,
                  attended, no_show_reason, outcome_note, recorded_by, recorded_at, row_version
                from ent_followup_record
                where tenant_id = :tenant and followup_id = :followup
                """).param("tenant", tenantId).param("followup", followupId)
                .query((rs, row) -> new EntFollowupRecordWire(
                        rs.getObject("followup_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getObject("airway_handover_id", UUID.class),
                        rs.getString("followup_reason"),
                        rs.getObject("scheduled_date", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("attended"),
                        rs.getString("no_show_reason"),
                        rs.getString("outcome_note"),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(EntFollowupService::contextDenied);
    }

    private void requireAirwayHandover(
            UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId, UUID handoverId) {
        long count = jdbc.sql("""
                select count(*) from ent_airway_risk_handover
                where tenant_id = :tenant and handover_id = :handover and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                """).param("tenant", tenantId).param("handover", handoverId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId).query(Long.class).single();
        if (count != 1) {
            throw new EntFollowupException("ENT_AIRWAY_HANDOVER_REFERENCE_NOT_FOUND", 422,
                    "The airway handover is not available in the current patient encounter context");
        }
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
            throw new EntFollowupException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new EntFollowupException("IDEMPOTENCY_REPLAY", 409,
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
                values (:tenant, :audit, now(), :actor, :action, 'ENT_FOLLOWUP', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", followupId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ENT_FOLLOWUP', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", followupId).param("version", version).param("event_type", eventType).update();
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

    private static EntFollowupException invalid(String message) {
        return new EntFollowupException("ENT_FOLLOWUP_REQUEST_INVALID", 400, message);
    }

    static EntFollowupException contextDenied() {
        return new EntFollowupException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested pediatric followup context is not permitted");
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
