package org.haoemr.quality;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AdverseEventReportRequestWire;
import org.haoemr.contracts.AdverseEventReviewRequestWire;
import org.haoemr.contracts.AdverseEventWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AdverseEventService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    AdverseEventService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    AdverseEventWire reportEvent(
            ClinicalIdentity identity, String idempotencyKey, AdverseEventReportRequestWire request) {
        if (request.eventType() == null || request.severity() == null || request.description() == null
                || request.description().trim().length() < 4) {
            throw invalid("event_type, severity and a description are required");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "ADVERSE_EVENT_REPORT", idempotencyKey,
                    sha256(request.eventType() + "|" + request.severity() + "|" + request.description()));
            UUID eventId = UUID.randomUUID();
            jdbc.sql("""
                    insert into adverse_event(
                      tenant_id, adverse_event_id, patient_id, encounter_id, facility_id,
                      event_type, severity, description, status, reported_at, reported_by)
                    values (:tenant, :event, :patient, :encounter, :facility,
                      :event_type, :severity, :description, 'REPORTED', now(), :actor)
                    """).param("tenant", identity.tenantId()).param("event", eventId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("event_type", request.eventType().name())
                    .param("severity", request.severity().name()).param("description", request.description().trim())
                    .param("actor", identity.userId()).update();
            appendEvidence(identity, request.patientId(), eventId, 1, "ADVERSE_EVENT_REPORTED", "AdverseEventReported");
            completeCommand(identity, "ADVERSE_EVENT_REPORT", idempotencyKey, eventId);
            return event(identity.tenantId(), eventId, request.patientId(), request.encounterId());
        });
    }

    AdverseEventWire reviewEvent(
            ClinicalIdentity identity, String idempotencyKey, UUID eventId, AdverseEventReviewRequestWire request) {
        String conclusion = request.conclusion() == null ? null : request.conclusion().trim();
        if (conclusion == null || conclusion.length() < 4) throw invalid("a review conclusion is required");
        return transactions.execute(status -> {
            beginCommand(identity, "ADVERSE_EVENT_REVIEW", idempotencyKey,
                    sha256(eventId + "|" + request.expectedRowVersion() + "|" + conclusion + "|" + request.close()));
            EventHead current = jdbc.sql("""
                    select status, row_version, patient_id from adverse_event
                    where tenant_id = :tenant and adverse_event_id = :event
                      and patient_id = :patient and encounter_id = :encounter
                      and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("event", eventId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new EventHead(
                            rs.getString("status"), rs.getLong("row_version"), rs.getObject("patient_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new AdverseEventException("ADVERSE_EVENT_VERSION_CONFLICT", 409, "The event changed; reload before retrying");
            }
            if (!"REPORTED".equals(current.status())) {
                throw new AdverseEventException("ADVERSE_EVENT_STATE_INVALID", 409, "Only a reported event can be reviewed");
            }
            boolean close = Boolean.TRUE.equals(request.close());
            jdbc.sql("""
                    update adverse_event set status = :status, reviewed_at = now(), reviewed_by = :actor,
                      review_conclusion = :conclusion,
                      closed_at = case when :close then now() else null end,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and adverse_event_id = :event and row_version = :expected
                    """).param("status", close ? "CLOSED" : "REVIEWED").param("actor", identity.userId())
                    .param("conclusion", conclusion).param("close", close)
                    .param("tenant", identity.tenantId()).param("event", eventId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, current.patientId(), eventId, current.rowVersion() + 1,
                    close ? "ADVERSE_EVENT_CLOSED" : "ADVERSE_EVENT_REVIEWED",
                    close ? "AdverseEventClosed" : "AdverseEventReviewed");
            completeCommand(identity, "ADVERSE_EVENT_REVIEW", idempotencyKey, eventId);
            return event(identity.tenantId(), eventId, request.patientId(), request.encounterId());
        });
    }

    List<AdverseEventWire> listEvents(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        requireActiveEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select adverse_event_id from adverse_event
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by reported_at desc, adverse_event_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> event(identity.tenantId(), id, patientId, encounterId)).toList();
    }

    private AdverseEventWire event(UUID tenantId, UUID eventId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select adverse_event_id, patient_id, encounter_id, facility_id, event_type, severity,
                  description, status, reported_at, reported_by, reviewed_at, reviewed_by,
                  review_conclusion, closed_at, row_version
                from adverse_event
                where tenant_id = :tenant and adverse_event_id = :event
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", tenantId).param("event", eventId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new AdverseEventWire(
                        rs.getObject("adverse_event_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        AdverseEventWire.EventTypeValue.valueOf(rs.getString("event_type")),
                        AdverseEventWire.SeverityValue.valueOf(rs.getString("severity")),
                        rs.getString("description"), AdverseEventWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("reported_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("reported_by", UUID.class),
                        rs.getObject("reviewed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("reviewed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("reviewed_by", UUID.class), rs.getString("review_conclusion"),
                        rs.getObject("closed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("closed_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
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
            throw new AdverseEventException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new AdverseEventException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID eventId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", eventId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID eventId, long version,
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
                + eventId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ADVERSE_EVENT', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", eventId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ADVERSE_EVENT', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", eventId).param("version", version).param("event_type", eventType).update();
    }

    private static AdverseEventException invalid(String message) {
        return new AdverseEventException("ADVERSE_EVENT_REQUEST_INVALID", 400, message);
    }

    static AdverseEventException contextDenied() {
        return new AdverseEventException("CONTEXT_NOT_PERMITTED", 403, "The requested adverse event context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record EventHead(String status, long rowVersion, UUID patientId) {}
}
