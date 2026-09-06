package org.haoemr.ent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EntAirwayRiskHandoverCreateRequestWire;
import org.haoemr.contracts.EntAirwayRiskHandoverWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class EntAirwayRiskHandoverService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EntAirwayRiskHandoverService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    EntAirwayRiskHandoverWire record(
            ClinicalIdentity identity, String idempotencyKey, EntAirwayRiskHandoverCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.airwayRiskLevel() == null
                || request.toProviderId() == null || request.handedOverAt() == null) {
            throw invalid("patient_id, encounter_id, airway_risk_level, to_provider_id and handed_over_at are required");
        }
        String airwayPrecautions = requireText(request.airwayPrecautions(), 2, "airway_precautions");
        if (request.toProviderId().equals(identity.userId())) {
            throw new EntAirwayRiskHandoverException(
                    "SELF_HANDOVER_FORBIDDEN", 400,
                    "An airway risk handover must target a different provider");
        }
        requireUser(identity.tenantId(), request.toProviderId());
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "ENT_AIRWAY_RISK_HANDOVER", idempotencyKey,
                    sha256(request.patientId() + "|" + request.airwayRiskLevel() + "|" + request.toProviderId()));
            UUID handoverId = UUID.randomUUID();
            jdbc.sql("""
                    insert into ent_airway_risk_handover(
                      tenant_id, handover_id, patient_id, encounter_id, facility_id, airway_risk_level,
                      airway_precautions, from_provider_id, to_provider_id, handed_over_at)
                    values (:tenant, :handover, :patient, :encounter, :facility, :risk,
                      :precautions, :from_provider, :to_provider, :handed_over_at)
                    """).param("tenant", identity.tenantId()).param("handover", handoverId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("risk", request.airwayRiskLevel().name())
                    .param("precautions", airwayPrecautions)
                    .param("from_provider", identity.userId()).param("to_provider", request.toProviderId())
                    .param("handed_over_at", request.handedOverAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), handoverId, 1, "ENT_AIRWAY_RISK_HANDED_OVER",
                    "EntAirwayRiskHandedOver");
            completeCommand(identity, "ENT_AIRWAY_RISK_HANDOVER", idempotencyKey, handoverId);
            return handover(identity.tenantId(), handoverId);
        });
    }

    List<EntAirwayRiskHandoverWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select handover_id from ent_airway_risk_handover
                where tenant_id = :tenant and patient_id = :patient
                order by handed_over_at desc, handover_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> handover(identity.tenantId(), id)).toList();
    }

    private EntAirwayRiskHandoverWire handover(UUID tenantId, UUID handoverId) {
        return jdbc.sql("""
                select handover_id, patient_id, encounter_id, facility_id, airway_risk_level,
                  airway_precautions, from_provider_id, to_provider_id, handed_over_at, row_version
                from ent_airway_risk_handover
                where tenant_id = :tenant and handover_id = :handover
                """).param("tenant", tenantId).param("handover", handoverId)
                .query((rs, row) -> new EntAirwayRiskHandoverWire(
                        rs.getObject("handover_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        EntAirwayRiskHandoverWire.AirwayRiskLevelValue.valueOf(rs.getString("airway_risk_level")),
                        rs.getString("airway_precautions"),
                        rs.getObject("from_provider_id", UUID.class),
                        rs.getObject("to_provider_id", UUID.class),
                        rs.getObject("handed_over_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(EntAirwayRiskHandoverService::contextDenied);
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
            throw new EntAirwayRiskHandoverException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new EntAirwayRiskHandoverException("IDEMPOTENCY_REPLAY", 409,
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
                values (:tenant, :audit, now(), :actor, :action, 'ENT_AIRWAY_HANDOVER', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", handoverId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ENT_AIRWAY_HANDOVER', :aggregate, :version, :event_type, 1,
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

    private static EntAirwayRiskHandoverException invalid(String message) {
        return new EntAirwayRiskHandoverException("ENT_AIRWAY_HANDOVER_REQUEST_INVALID", 400, message);
    }

    static EntAirwayRiskHandoverException contextDenied() {
        return new EntAirwayRiskHandoverException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested ENT airway handover context is not permitted");
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
