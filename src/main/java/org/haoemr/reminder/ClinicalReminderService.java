package org.haoemr.reminder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalReminderAcknowledgeRequestWire;
import org.haoemr.contracts.ClinicalReminderCreateRequestWire;
import org.haoemr.contracts.ClinicalReminderSilenceRequestWire;
import org.haoemr.contracts.ClinicalReminderWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ClinicalReminderService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ClinicalReminderService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ClinicalReminderWire create(
            ClinicalIdentity identity, String idempotencyKey, ClinicalReminderCreateRequestWire request) {
        if (request.reminderType() == null || request.message() == null || request.message().trim().length() < 4 || request.message().trim().length() > 2000
                || request.severity() == null) {
            throw invalid("reminder_type, message and severity are required");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "CLINICAL_REMINDER_CREATE", idempotencyKey,
                    sha256(request.reminderType() + "|" + request.message() + "|" + request.severity()
                            + "|" + request.patientId() + "|" + request.encounterId() + "|" + request.sourceMedicalAgentRunId()));
            String agentPayload = request.sourceMedicalAgentRunId() == null ? null : jdbc.sql("""
                    select output_payload::text from medical_agent_run
                    where tenant_id = :tenant and run_id = :run
                      and patient_id = :patient and encounter_id = :encounter
                      and state in ('WAITING_FOR_REVIEW', 'COMPLETED')
                      and output_payload ->> 'execution_mode' = 'LIVE_MODEL'
                    """).param("tenant", identity.tenantId()).param("run", request.sourceMedicalAgentRunId())
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .query(String.class).optional().orElseThrow(() -> new ClinicalReminderException(
                            "MEDICAL_AGENT_REMINDER_SOURCE_INVALID", 409,
                            "A completed live candidate from this patient and encounter is required"));
            UUID reminderId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_reminder(
                      tenant_id, reminder_id, patient_id, encounter_id, facility_id,
                      reminder_type, message, severity, status, source_task_id)
                    values (:tenant, :reminder, :patient, :encounter, :facility,
                      :reminder_type, :message, :severity, 'PENDING', :source_task_id)
                    """).param("tenant", identity.tenantId()).param("reminder", reminderId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("reminder_type", request.reminderType().name())
                    .param("message", request.message().trim()).param("severity", request.severity().name())
                    .param("source_task_id", request.sourceTaskId()).update();
            if (agentPayload != null) {
                jdbc.sql("""
                        insert into medical_agent_reminder_adoption(tenant_id, reminder_id, source_run_id,
                          source_payload_hash, confirmed_by) values (:tenant, :reminder, :run, :hash, :actor)
                        """).param("tenant", identity.tenantId()).param("reminder", reminderId)
                        .param("run", request.sourceMedicalAgentRunId()).param("hash", sha256(agentPayload))
                        .param("actor", identity.userId()).update();
                appendEvidence(identity, request.patientId(), reminderId, 1,
                        "MEDICAL_AGENT_REMINDER_ADOPTED", "MedicalAgentReminderAdopted");
            }
            appendEvidence(identity, request.patientId(), reminderId, 1, "CLINICAL_REMINDER_CREATED", "ClinicalReminderCreated");
            completeCommand(identity, "CLINICAL_REMINDER_CREATE", idempotencyKey, reminderId);
            return reminder(identity.tenantId(), reminderId, request.patientId(), request.encounterId());
        });
    }

    ClinicalReminderWire acknowledge(
            ClinicalIdentity identity, String idempotencyKey, UUID reminderId,
            ClinicalReminderAcknowledgeRequestWire request) {
        return transition(identity, idempotencyKey, reminderId, request.expectedRowVersion(),
                request.patientId(), request.encounterId(), request.facilityId(),
                "ACKNOWLEDGED", "CLINICAL_REMINDER_ACKNOWLEDGED", "ClinicalReminderAcknowledged");
    }

    ClinicalReminderWire silence(
            ClinicalIdentity identity, String idempotencyKey, UUID reminderId,
            ClinicalReminderSilenceRequestWire request) {
        return transition(identity, idempotencyKey, reminderId, request.expectedRowVersion(),
                request.patientId(), request.encounterId(), request.facilityId(),
                "SILENCED", "CLINICAL_REMINDER_SILENCED", "ClinicalReminderSilenced");
    }

    List<ClinicalReminderWire> list(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        requireActiveEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select reminder_id from clinical_reminder
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by created_at desc, reminder_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> reminder(identity.tenantId(), id, patientId, encounterId)).toList();
    }

    private ClinicalReminderWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID reminderId, Long expectedRowVersion,
            UUID patientId, UUID encounterId, UUID facilityId, String targetStatus,
            String action, String eventType) {
        return transactions.execute(status -> {
            beginCommand(identity, "CLINICAL_REMINDER_" + targetStatus, idempotencyKey,
                    sha256(reminderId + "|" + expectedRowVersion));
            ReminderHead current = jdbc.sql("""
                    select status, row_version, patient_id from clinical_reminder
                    where tenant_id = :tenant and reminder_id = :reminder
                      and patient_id = :patient and encounter_id = :encounter
                      and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("reminder", reminderId)
                    .param("patient", patientId).param("encounter", encounterId).param("facility", facilityId)
                    .query((rs, row) -> new ReminderHead(
                            rs.getString("status"), rs.getLong("row_version"), rs.getObject("patient_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (expectedRowVersion == null || current.rowVersion() != expectedRowVersion) {
                throw new ClinicalReminderException("CLINICAL_REMINDER_VERSION_CONFLICT", 409, "The reminder changed; reload before retrying");
            }
            if (!"PENDING".equals(current.status())) {
                throw new ClinicalReminderException("CLINICAL_REMINDER_STATE_INVALID", 409, "Only a pending reminder can change");
            }
            jdbc.sql("""
                    update clinical_reminder set status = :status,
                      acknowledged_at = case when :status = 'ACKNOWLEDGED' then now() else acknowledged_at end,
                      acknowledged_by = case when :status = 'ACKNOWLEDGED' then :actor else acknowledged_by end,
                      silenced_at = case when :status = 'SILENCED' then now() else silenced_at end,
                      silenced_by = case when :status = 'SILENCED' then :actor else silenced_by end,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and reminder_id = :reminder and row_version = :expected
                    """).param("status", targetStatus).param("actor", identity.userId())
                    .param("tenant", identity.tenantId()).param("reminder", reminderId)
                    .param("expected", current.rowVersion()).update();
            closeConvertedTasks(identity, reminderId, targetStatus);
            appendEvidence(identity, current.patientId(), reminderId, current.rowVersion() + 1, action, eventType);
            completeCommand(identity, "CLINICAL_REMINDER_" + targetStatus, idempotencyKey, reminderId);
            return reminder(identity.tenantId(), reminderId, patientId, encounterId);
        });
    }

    /** The task represents processing this reminder; acknowledgement does not certify a treatment outcome. */
    private void closeConvertedTasks(ClinicalIdentity identity, UUID reminderId, String reminderStatus) {
        String state = "ACKNOWLEDGED".equals(reminderStatus) ? "COMPLETED" : "WITHDRAWN";
        String event = "ACKNOWLEDGED".equals(reminderStatus) ? "SOURCE_COMPLETED" : "SOURCE_WITHDRAWN";
        var tasks = jdbc.sql("""
                select task_id, state from clinical_task where tenant_id = :tenant
                  and source_type = 'REMINDER' and source_id = :reminder
                  and state not in ('COMPLETED','WITHDRAWN') for update
                """).param("tenant", identity.tenantId()).param("reminder", reminderId)
                .query((rs, row) -> new ConvertedTask(rs.getObject("task_id", UUID.class), rs.getString("state"))).list();
        for (var task : tasks) {
            jdbc.sql("""
                    update clinical_task set state=:state, business_state=:business,
                      row_version=row_version+1, updated_at=now() where tenant_id=:tenant and task_id=:task
                    """).param("state", state).param("business", reminderStatus).param("tenant", identity.tenantId())
                    .param("task", task.id()).update();
            jdbc.sql("""
                    insert into clinical_task_event(tenant_id,task_event_id,task_id,event_type,
                      previous_state,resulting_state,actor_user_id,reason)
                    values (:tenant,:id,:task,:event,:previous,:result,:actor,:reason)
                    """).param("tenant",identity.tenantId()).param("id",UUID.randomUUID()).param("task",task.id())
                    .param("event",event).param("previous",task.state()).param("result",state)
                    .param("actor",identity.userId()).param("reason","REMINDER_" + reminderStatus).update();
        }
    }

    private record ConvertedTask(UUID id, String state) {}

    private ClinicalReminderWire reminder(UUID tenantId, UUID reminderId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select reminder_id, patient_id, encounter_id, facility_id, reminder_type, message,
                  severity, status, source_task_id, acknowledged_at, acknowledged_by,
                  silenced_at, silenced_by, row_version
                from clinical_reminder
                where tenant_id = :tenant and reminder_id = :reminder
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", tenantId).param("reminder", reminderId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new ClinicalReminderWire(
                        rs.getObject("reminder_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        ClinicalReminderWire.ReminderTypeValue.valueOf(rs.getString("reminder_type")),
                        rs.getString("message"), ClinicalReminderWire.SeverityValue.valueOf(rs.getString("severity")),
                        ClinicalReminderWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("source_task_id", UUID.class),
                        rs.getObject("acknowledged_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("acknowledged_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("acknowledged_by", UUID.class),
                        rs.getObject("silenced_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("silenced_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("silenced_by", UUID.class), rs.getLong("row_version")))
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
            throw new ClinicalReminderException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ClinicalReminderException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID reminderId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", reminderId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID reminderId, long version,
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
                + reminderId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CLINICAL_REMINDER', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", reminderId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CLINICAL_REMINDER', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", reminderId).param("version", version).param("event_type", eventType).update();
    }

    private static ClinicalReminderException invalid(String message) {
        return new ClinicalReminderException("CLINICAL_REMINDER_REQUEST_INVALID", 400, message);
    }

    static ClinicalReminderException contextDenied() {
        return new ClinicalReminderException("CONTEXT_NOT_PERMITTED", 403, "The requested reminder context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ReminderHead(String status, long rowVersion, UUID patientId) {}
}
