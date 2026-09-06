package org.haoemr.reproductive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ArtPregnancyOutcomeCreateRequestWire;
import org.haoemr.contracts.ArtPregnancyOutcomeWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ArtPregnancyOutcomeService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ArtPregnancyOutcomeService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ArtPregnancyOutcomeWire record(
            ClinicalIdentity identity, String idempotencyKey, ArtPregnancyOutcomeCreateRequestWire request) {
        if (request.patientId() == null || request.cycleId() == null || request.encounterId() == null
                || request.pregnancyResult() == null || request.outcomeDate() == null
                || request.liveBirthCount() == null || request.recordedAt() == null) {
            throw invalid("patient_id, cycle_id, encounter_id, pregnancy_result, outcome_date, "
                    + "live_birth_count and recorded_at are required");
        }
        if (request.liveBirthCount() < 0) {
            throw invalid("live_birth_count must not be negative");
        }
        String complications = blankToNull(request.complications());
        if (request.pregnancyResult() == ArtPregnancyOutcomeCreateRequestWire.PregnancyResultValue.MISCARRIAGE
                && complications == null) {
            throw new ArtPregnancyOutcomeException(
                    "ART_MISCARRIAGE_COMPLICATION_REQUIRED", 400,
                    "A miscarriage outcome requires a complication description");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "ART_PREGNANCY_OUTCOME", idempotencyKey,
                    sha256(request.patientId() + "|" + request.cycleId() + "|" + request.pregnancyResult()));
            UUID outcomeId = UUID.randomUUID();
            jdbc.sql("""
                    insert into art_pregnancy_outcome(
                      tenant_id, outcome_id, patient_id, cycle_id, encounter_id, facility_id,
                      pregnancy_result, outcome_date, live_birth_count, complications, recorded_by, recorded_at)
                    values (:tenant, :outcome, :patient, :cycle, :encounter, :facility,
                      :result, :outcome_date, :live_birth, :complications, :recorded_by, :recorded_at)
                    """).param("tenant", identity.tenantId()).param("outcome", outcomeId)
                    .param("patient", request.patientId()).param("cycle", request.cycleId())
                    .param("encounter", request.encounterId()).param("facility", request.facilityId())
                    .param("result", request.pregnancyResult().name())
                    .param("outcome_date", request.outcomeDate().atOffset(ZoneOffset.UTC))
                    .param("live_birth", request.liveBirthCount())
                    .param("complications", complications)
                    .param("recorded_by", identity.userId())
                    .param("recorded_at", request.recordedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), outcomeId, 1, "ART_PREGNANCY_OUTCOME_RECORDED",
                    "ArtPregnancyOutcomeRecorded");
            completeCommand(identity, "ART_PREGNANCY_OUTCOME", idempotencyKey, outcomeId);
            return outcome(identity.tenantId(), outcomeId);
        });
    }

    List<ArtPregnancyOutcomeWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select outcome_id from art_pregnancy_outcome
                where tenant_id = :tenant and patient_id = :patient
                order by outcome_date desc, outcome_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> outcome(identity.tenantId(), id)).toList();
    }

    private ArtPregnancyOutcomeWire outcome(UUID tenantId, UUID outcomeId) {
        return jdbc.sql("""
                select outcome_id, patient_id, cycle_id, encounter_id, facility_id, pregnancy_result,
                  outcome_date, live_birth_count, complications, recorded_by, recorded_at, row_version
                from art_pregnancy_outcome
                where tenant_id = :tenant and outcome_id = :outcome
                """).param("tenant", tenantId).param("outcome", outcomeId)
                .query((rs, row) -> new ArtPregnancyOutcomeWire(
                        rs.getObject("outcome_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("cycle_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        ArtPregnancyOutcomeWire.PregnancyResultValue.valueOf(rs.getString("pregnancy_result")),
                        rs.getObject("outcome_date", OffsetDateTime.class).toInstant(),
                        rs.getInt("live_birth_count"),
                        rs.getString("complications"),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ArtPregnancyOutcomeService::contextDenied);
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
            throw new ArtPregnancyOutcomeException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ArtPregnancyOutcomeException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID outcomeId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", outcomeId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID outcomeId, long version,
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
                + outcomeId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ART_PREGNANCY_OUTCOME', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", outcomeId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ART_PREGNANCY_OUTCOME', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", outcomeId).param("version", version).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ArtPregnancyOutcomeException invalid(String message) {
        return new ArtPregnancyOutcomeException("ART_OUTCOME_REQUEST_INVALID", 400, message);
    }

    static ArtPregnancyOutcomeException contextDenied() {
        return new ArtPregnancyOutcomeException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested ART pregnancy outcome context is not permitted");
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
