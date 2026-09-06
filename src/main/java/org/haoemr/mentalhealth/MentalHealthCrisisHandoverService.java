package org.haoemr.mentalhealth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthCrisisHandoverCreateRequestWire;
import org.haoemr.contracts.MentalHealthCrisisHandoverWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class MentalHealthCrisisHandoverService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    MentalHealthCrisisHandoverService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    MentalHealthCrisisHandoverWire record(
            ClinicalIdentity identity, String idempotencyKey, MentalHealthCrisisHandoverCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.toProviderId() == null
                || request.riskLevel() == null || request.handedOverAt() == null) {
            throw invalid("patient_id, encounter_id, to_provider_id, risk_level and handed_over_at are required");
        }
        String crisisReason = requireText(request.crisisReason(), 2, "crisis_reason");
        if (request.toProviderId().equals(identity.userId())) {
            throw new MentalHealthCrisisHandoverException(
                    "SELF_HANDOVER_FORBIDDEN", 400, "A crisis handover must target a different provider");
        }
        boolean highRisk = request.riskLevel() == MentalHealthCrisisHandoverCreateRequestWire.RiskLevelValue.HIGH
                || request.riskLevel() == MentalHealthCrisisHandoverCreateRequestWire.RiskLevelValue.IMMINENT;
        String protectiveMeasures = blankToNull(request.protectiveMeasures());
        if (highRisk && protectiveMeasures == null) {
            throw new MentalHealthCrisisHandoverException(
                    "CRISIS_PROTECTIVE_MEASURES_REQUIRED", 400,
                    "A high or imminent risk crisis handover requires protective measures");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        requireUser(identity.tenantId(), request.toProviderId());
        return transactions.execute(status -> {
            beginCommand(identity, "MENTAL_HEALTH_CRISIS_HANDOVER", idempotencyKey,
                    sha256(request.patientId() + "|" + request.toProviderId() + "|" + request.riskLevel()));
            UUID handoverId = UUID.randomUUID();
            jdbc.sql("""
                    insert into mental_health_crisis_handover(
                      tenant_id, crisis_handover_id, patient_id, encounter_id, from_provider_id, to_provider_id,
                      crisis_reason, risk_level, protective_measures, data_classification, handed_over_at)
                    values (:tenant, :handover, :patient, :encounter, :from_provider, :to_provider,
                      :reason, :risk, :measures, 'RESTRICTED', :handed_over_at)
                    """).param("tenant", identity.tenantId()).param("handover", handoverId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("from_provider", identity.userId()).param("to_provider", request.toProviderId())
                    .param("reason", crisisReason).param("risk", request.riskLevel().name())
                    .param("measures", protectiveMeasures)
                    .param("handed_over_at", request.handedOverAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), handoverId, 1,
                    "MENTAL_HEALTH_CRISIS_HANDED_OVER", "MentalHealthCrisisHandedOver");
            completeCommand(identity, "MENTAL_HEALTH_CRISIS_HANDOVER", idempotencyKey, handoverId);
            return handover(identity.tenantId(), handoverId);
        });
    }

    List<MentalHealthCrisisHandoverWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select crisis_handover_id from mental_health_crisis_handover
                where tenant_id = :tenant and patient_id = :patient
                order by handed_over_at desc, crisis_handover_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> handover(identity.tenantId(), id)).toList();
    }

    private MentalHealthCrisisHandoverWire handover(UUID tenantId, UUID handoverId) {
        return jdbc.sql("""
                select crisis_handover_id, patient_id, encounter_id, from_provider_id, to_provider_id,
                  crisis_reason, risk_level, protective_measures, data_classification, handed_over_at, row_version
                from mental_health_crisis_handover
                where tenant_id = :tenant and crisis_handover_id = :handover
                """).param("tenant", tenantId).param("handover", handoverId)
                .query((rs, row) -> new MentalHealthCrisisHandoverWire(
                        rs.getObject("crisis_handover_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("from_provider_id", UUID.class),
                        rs.getObject("to_provider_id", UUID.class),
                        rs.getString("crisis_reason"),
                        MentalHealthCrisisHandoverWire.RiskLevelValue.valueOf(rs.getString("risk_level")),
                        rs.getString("protective_measures"),
                        MentalHealthCrisisHandoverWire.DataClassificationValue.valueOf(rs.getString("data_classification")),
                        rs.getObject("handed_over_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(MentalHealthCrisisHandoverService::contextDenied);
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

    private void requireUser(UUID tenantId, UUID userId) {
        long count = jdbc.sql("""
                select count(*) from app_user where tenant_id = :tenant and user_id = :user
                """).param("tenant", tenantId).param("user", userId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new MentalHealthCrisisHandoverException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new MentalHealthCrisisHandoverException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID handoverId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", handoverId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID handoverId, long version,
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
                + handoverId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'MENTAL_HEALTH_CRISIS_HANDOVER', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", handoverId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MENTAL_HEALTH_CRISIS_HANDOVER', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", handoverId).param("version", version).param("event_type", eventType).update();
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

    private static MentalHealthCrisisHandoverException invalid(String message) {
        return new MentalHealthCrisisHandoverException(
                "MENTAL_HEALTH_CRISIS_REQUEST_INVALID", 400, message);
    }

    static MentalHealthCrisisHandoverException contextDenied() {
        return new MentalHealthCrisisHandoverException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested mental health crisis context is not permitted");
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
