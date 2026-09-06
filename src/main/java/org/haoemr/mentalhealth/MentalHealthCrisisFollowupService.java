package org.haoemr.mentalhealth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthCrisisFollowupCreateRequestWire;
import org.haoemr.contracts.MentalHealthCrisisFollowupWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class MentalHealthCrisisFollowupService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    MentalHealthCrisisFollowupService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    MentalHealthCrisisFollowupWire record(
            ClinicalIdentity identity, String idempotencyKey, MentalHealthCrisisFollowupCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.crisisHandoverId() == null || request.followupDate() == null
                || request.riskLevel() == null || request.recordedAt() == null) {
            throw invalid("patient_id, encounter_id, crisis_handover_id, followup_date, risk_level and recorded_at are required");
        }
        String protectiveMeasures = blankToNull(request.protectiveMeasures());
        boolean highRisk = request.riskLevel() == MentalHealthCrisisFollowupCreateRequestWire.RiskLevelValue.HIGH
                || request.riskLevel() == MentalHealthCrisisFollowupCreateRequestWire.RiskLevelValue.IMMINENT;
        if (highRisk && protectiveMeasures == null) {
            throw new MentalHealthCrisisFollowupException(
                    "MENTAL_HEALTH_CRISIS_FOLLOWUP_PROTECTION_REQUIRED", 400,
                    "A high or imminent risk followup requires protective measures");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            requireCrisisHandover(identity.tenantId(), request.patientId(), request.encounterId(), request.crisisHandoverId());
            beginCommand(identity, "MENTAL_HEALTH_CRISIS_FOLLOWUP", idempotencyKey,
                    sha256(request.patientId() + "|" + request.riskLevel() + "|" + request.followupDate()));
            UUID followupId = UUID.randomUUID();
            jdbc.sql("""
                    insert into mental_health_crisis_followup(
                      tenant_id, followup_id, patient_id, encounter_id, facility_id, crisis_handover_id, followup_date,
                      risk_level, protective_measures, data_classification, recorded_by, recorded_at)
                    values (:tenant, :followup, :patient, :encounter, :facility, :handover, :followup_date,
                      :risk, :measures, 'RESTRICTED', :recorded_by, :recorded_at)
                    """).param("tenant", identity.tenantId()).param("followup", followupId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("handover", request.crisisHandoverId())
                    .param("followup_date", request.followupDate().atOffset(ZoneOffset.UTC))
                    .param("risk", request.riskLevel().name()).param("measures", protectiveMeasures)
                    .param("recorded_by", identity.userId())
                    .param("recorded_at", request.recordedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), followupId, 1, "MENTAL_HEALTH_CRISIS_FOLLOWUP_RECORDED",
                    "MentalHealthCrisisFollowupRecorded");
            completeCommand(identity, "MENTAL_HEALTH_CRISIS_FOLLOWUP", idempotencyKey, followupId);
            return followup(identity.tenantId(), followupId);
        });
    }

    List<MentalHealthCrisisFollowupWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select followup_id from mental_health_crisis_followup
                where tenant_id = :tenant and patient_id = :patient
                order by followup_date desc, followup_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> followup(identity.tenantId(), id)).toList();
    }

    private MentalHealthCrisisFollowupWire followup(UUID tenantId, UUID followupId) {
        return jdbc.sql("""
                select followup_id, patient_id, encounter_id, facility_id, crisis_handover_id, followup_date, risk_level,
                  protective_measures, data_classification, recorded_by, recorded_at, row_version
                from mental_health_crisis_followup
                where tenant_id = :tenant and followup_id = :followup
                """).param("tenant", tenantId).param("followup", followupId)
                .query((rs, row) -> new MentalHealthCrisisFollowupWire(
                        rs.getObject("followup_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getObject("crisis_handover_id", UUID.class),
                        rs.getObject("followup_date", OffsetDateTime.class).toInstant(),
                        MentalHealthCrisisFollowupWire.RiskLevelValue.valueOf(rs.getString("risk_level")),
                        rs.getString("protective_measures"),
                        MentalHealthCrisisFollowupWire.DataClassificationValue.valueOf(rs.getString("data_classification")),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(MentalHealthCrisisFollowupService::contextDenied);
    }

    private void requireCrisisHandover(UUID tenantId, UUID patientId, UUID encounterId, UUID handoverId) {
        long count = jdbc.sql("""
                select count(*) from mental_health_crisis_handover
                where tenant_id = :tenant and crisis_handover_id = :handover and patient_id = :patient
                  and encounter_id = :encounter
                """).param("tenant", tenantId).param("handover", handoverId).param("patient", patientId)
                .param("encounter", encounterId).query(Long.class).single();
        if (count != 1) {
            throw new MentalHealthCrisisFollowupException("MENTAL_HEALTH_CRISIS_HANDOVER_REFERENCE_NOT_FOUND", 422,
                    "The crisis handover is not available in the current patient encounter context");
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
            throw new MentalHealthCrisisFollowupException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new MentalHealthCrisisFollowupException("IDEMPOTENCY_REPLAY", 409,
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
                values (:tenant, :audit, now(), :actor, :action, 'MENTAL_HEALTH_CRISIS_FOLLOWUP', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", followupId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MENTAL_HEALTH_CRISIS_FOLLOWUP', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", followupId).param("version", version).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static MentalHealthCrisisFollowupException invalid(String message) {
        return new MentalHealthCrisisFollowupException(
                "MENTAL_HEALTH_CRISIS_FOLLOWUP_REQUEST_INVALID", 400, message);
    }

    static MentalHealthCrisisFollowupException contextDenied() {
        return new MentalHealthCrisisFollowupException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested mental health crisis followup context is not permitted");
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
