package org.haoemr.reminder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalReminderConversionCreateRequestWire;
import org.haoemr.contracts.ClinicalReminderConversionWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ClinicalReminderConversionService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ClinicalReminderConversionService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ClinicalReminderConversionWire convert(
            ClinicalIdentity identity, String idempotencyKey, ClinicalReminderConversionCreateRequestWire request) {
        if (request.reminderId() == null || request.patientId() == null
                || request.encounterId() == null || request.convertedAt() == null) {
            throw invalid("reminder_id, patient_id, encounter_id and converted_at are required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "CLINICAL_REMINDER_CONVERT", idempotencyKey, sha256(request.reminderId().toString()));
            ReminderHead reminder = lockReminder(identity.tenantId(), request.reminderId(),
                    request.patientId(), request.encounterId(), request.facilityId());
            if (!"PENDING".equals(reminder.status())) {
                throw new ClinicalReminderConversionException(
                        "REMINDER_NOT_PENDING", 409, "Only a pending reminder can be converted into a task");
            }
            if (alreadyConverted(identity.tenantId(), request.reminderId())) {
                throw new ClinicalReminderConversionException(
                        "REMINDER_ALREADY_CONVERTED", 409, "The reminder was already converted into a task");
            }
            UUID taskId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_task(
                      tenant_id, task_id, patient_id, encounter_id, facility_id,
                      source_type, source_id, task_type, title, risk_level, state, business_state, source_route)
                    values (:tenant, :task, :patient, :encounter, :facility,
                      'REMINDER', :reminder, :task_type, :title, :risk, 'PENDING', 'OPEN', :source_route)
                    """).param("tenant", identity.tenantId()).param("task", taskId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("reminder", request.reminderId())
                    .param("task_type", reminder.reminderType()).param("title", reminder.message().substring(0, Math.min(240, reminder.message().length())))
                    .param("risk", riskLevel(reminder.severity()))
                    .param("source_route", "#/ai-reminder-detail?reminder_id=" + request.reminderId()).update();
            UUID conversionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_reminder_conversion(
                      tenant_id, conversion_id, reminder_id, clinical_task_id, converted_by, converted_at)
                    values (:tenant, :conversion, :reminder, :task, :actor, :converted_at)
                    """).param("tenant", identity.tenantId()).param("conversion", conversionId)
                    .param("reminder", request.reminderId()).param("task", taskId)
                    .param("actor", identity.userId())
                    .param("converted_at", request.convertedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, conversionId, "CLINICAL_REMINDER_CONVERTED", "ClinicalReminderConverted");
            completeCommand(identity, "CLINICAL_REMINDER_CONVERT", idempotencyKey, conversionId);
            return conversion(identity.tenantId(), conversionId);
        });
    }

    List<ClinicalReminderConversionWire> list(
            ClinicalIdentity identity, UUID reminderId, UUID patientId, UUID facilityId, UUID encounterId) {
        requireReminderContext(identity.tenantId(), reminderId, patientId, facilityId, encounterId);
        return jdbc.sql("""
                select conversion_id from clinical_reminder_conversion
                where tenant_id = :tenant and reminder_id = :reminder
                order by converted_at desc, conversion_id desc limit 100
                """).param("tenant", identity.tenantId()).param("reminder", reminderId)
                .query(UUID.class).list().stream()
                .map(id -> conversion(identity.tenantId(), id)).toList();
    }

    private boolean alreadyConverted(UUID tenantId, UUID reminderId) {
        return jdbc.sql("""
                select count(*) from clinical_reminder_conversion
                where tenant_id = :tenant and reminder_id = :reminder
                """).param("tenant", tenantId).param("reminder", reminderId).query(Long.class).single() > 0;
    }

    private void requireReminderContext(
            UUID tenantId, UUID reminderId, UUID patientId, UUID facilityId, UUID encounterId) {
        long count = jdbc.sql("""
                select count(*) from clinical_reminder
                where tenant_id = :tenant and reminder_id = :reminder
                  and patient_id = :patient and facility_id = :facility and encounter_id = :encounter
                """).param("tenant", tenantId).param("reminder", reminderId)
                .param("patient", patientId).param("facility", facilityId).param("encounter", encounterId)
                .query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private ClinicalReminderConversionWire conversion(UUID tenantId, UUID conversionId) {
        return jdbc.sql("""
                select conversion_id, reminder_id, clinical_task_id, converted_by, converted_at, row_version
                from clinical_reminder_conversion
                where tenant_id = :tenant and conversion_id = :conversion
                """).param("tenant", tenantId).param("conversion", conversionId)
                .query((rs, row) -> new ClinicalReminderConversionWire(
                        rs.getObject("conversion_id", UUID.class),
                        rs.getObject("reminder_id", UUID.class),
                        rs.getObject("clinical_task_id", UUID.class),
                        rs.getObject("converted_by", UUID.class),
                        rs.getObject("converted_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ClinicalReminderConversionService::contextDenied);
    }

    private ReminderHead lockReminder(UUID tenantId, UUID reminderId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select status, reminder_type, message, severity from clinical_reminder
                where tenant_id = :tenant and reminder_id = :reminder and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility for update
                """).param("tenant", tenantId).param("reminder", reminderId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new ReminderHead(
                        rs.getString("status"), rs.getString("reminder_type"),
                        rs.getString("message"), rs.getString("severity")))
                .optional().orElseThrow(ClinicalReminderConversionService::contextDenied);
    }

    private static String riskLevel(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "CRITICAL";
            case "WARNING" -> "HIGH";
            default -> "ROUTINE";
        };
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ClinicalReminderConversionException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ClinicalReminderConversionException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID conversionId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", conversionId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID conversionId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + conversionId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CLINICAL_REMINDER_CONVERSION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", conversionId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CLINICAL_REMINDER_CONVERSION', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", conversionId).param("event_type", eventType).update();
    }

    private static ClinicalReminderConversionException invalid(String message) {
        return new ClinicalReminderConversionException("CLINICAL_REMINDER_CONVERSION_REQUEST_INVALID", 400, message);
    }

    static ClinicalReminderConversionException contextDenied() {
        return new ClinicalReminderConversionException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested clinical reminder conversion context is not permitted");
    }

    private record ReminderHead(String status, String reminderType, String message, String severity) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
