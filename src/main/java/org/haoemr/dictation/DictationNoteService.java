package org.haoemr.dictation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DictationNoteCreateRequestWire;
import org.haoemr.contracts.DictationNoteTransitionRequestWire;
import org.haoemr.contracts.DictationNoteWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DictationNoteService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DictationNoteService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DictationNoteWire create(
            ClinicalIdentity identity, String idempotencyKey, DictationNoteCreateRequestWire request) {
        String transcript = requireText(request.transcript(), 2, "transcript");
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "DICTATION_NOTE_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + transcript));
            UUID noteId = UUID.randomUUID();
            jdbc.sql("""
                    insert into dictation_note(
                      tenant_id, dictation_note_id, patient_id, encounter_id, facility_id, transcript, status)
                    values (:tenant, :note, :patient, :encounter, :facility, :transcript, 'DRAFT')
                    """).param("tenant", identity.tenantId()).param("note", noteId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("transcript", transcript).update();
            appendEvidence(identity, request.patientId(), noteId, 1, "DICTATION_NOTE_CREATED", "DictationNoteCreated");
            completeCommand(identity, "DICTATION_NOTE_CREATE", idempotencyKey, noteId);
            return note(identity.tenantId(), noteId, request.patientId());
        });
    }

    DictationNoteWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID noteId,
            DictationNoteTransitionRequestWire request) {
        if (request.transition() == null) {
            throw invalid("transition is required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "DICTATION_NOTE_TRANSITION", idempotencyKey,
                    sha256(noteId + "|" + request.expectedRowVersion() + "|" + request.transition()));
            NoteHead current = jdbc.sql("""
                    select status, row_version from dictation_note
                    where tenant_id = :tenant and dictation_note_id = :note
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                      for update
                    """).param("tenant", identity.tenantId()).param("note", noteId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new NoteHead(rs.getString("status"), rs.getLong("row_version")))
                    .optional().orElseThrow(DictationNoteService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new DictationNoteException(
                        "DICTATION_NOTE_VERSION_CONFLICT", 409, "The dictation note changed; reload before retrying");
            }
            switch (request.transition()) {
                case REVIEW -> {
                    if (!"DRAFT".equals(current.status())) throw stateInvalid();
                    jdbc.sql("""
                            update dictation_note set status = 'REVIEWED', reviewed_at = now(),
                              row_version = row_version + 1, updated_at = now()
                            where tenant_id = :tenant and dictation_note_id = :note and row_version = :expected
                            """).param("tenant", identity.tenantId()).param("note", noteId)
                            .param("expected", current.rowVersion()).update();
                }
                case SIGN -> {
                    if (!"REVIEWED".equals(current.status())) throw stateInvalid();
                    jdbc.sql("""
                            update dictation_note set status = 'SIGNED', signed_at = now(),
                              row_version = row_version + 1, updated_at = now()
                            where tenant_id = :tenant and dictation_note_id = :note and row_version = :expected
                            """).param("tenant", identity.tenantId()).param("note", noteId)
                            .param("expected", current.rowVersion()).update();
                }
            }
            appendEvidence(identity, request.patientId(), noteId, current.rowVersion() + 1,
                    "DICTATION_NOTE_" + request.transition(), "DictationNote" + request.transition());
            completeCommand(identity, "DICTATION_NOTE_TRANSITION", idempotencyKey, noteId);
            return note(identity.tenantId(), noteId, request.patientId());
        });
    }

    List<DictationNoteWire> listNotes(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select dictation_note_id from dictation_note
                where tenant_id = :tenant and patient_id = :patient
                order by created_at desc, dictation_note_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> note(identity.tenantId(), id, patientId)).toList();
    }

    private DictationNoteWire note(UUID tenantId, UUID noteId, UUID patientId) {
        return jdbc.sql("""
                select dictation_note_id, patient_id, encounter_id, facility_id, transcript,
                  status, reviewed_at, signed_at, row_version
                from dictation_note where tenant_id = :tenant and dictation_note_id = :note and patient_id = :patient
                """).param("tenant", tenantId).param("note", noteId).param("patient", patientId)
                .query((rs, row) -> new DictationNoteWire(
                        rs.getObject("dictation_note_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("transcript"),
                        DictationNoteWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("reviewed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("reviewed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("signed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("signed_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(DictationNoteService::contextDenied);
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
            throw new DictationNoteException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new DictationNoteException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID noteId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", noteId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID noteId, long version,
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
                + noteId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'DICTATION_NOTE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", noteId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DICTATION_NOTE', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", noteId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static DictationNoteException invalid(String message) {
        return new DictationNoteException("DICTATION_NOTE_REQUEST_INVALID", 400, message);
    }

    private static DictationNoteException stateInvalid() {
        return new DictationNoteException("DICTATION_NOTE_STATE_INVALID", 409,
                "The dictation note is not in a state that accepts this transition");
    }

    static DictationNoteException contextDenied() {
        return new DictationNoteException("CONTEXT_NOT_PERMITTED", 403,
                "The requested dictation note context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record NoteHead(String status, long rowVersion) {}
}
