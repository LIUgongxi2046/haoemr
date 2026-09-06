package org.haoemr.transfusion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.BloodTransfusionReactionRequestWire;
import org.haoemr.contracts.BloodTransfusionRecordRequestWire;
import org.haoemr.contracts.BloodTransfusionWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class BloodTransfusionService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    BloodTransfusionService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    BloodTransfusionWire recordTransfusion(
            ClinicalIdentity identity, String idempotencyKey, BloodTransfusionRecordRequestWire request) {
        if (request.bloodProduct() == null || request.bloodType() == null || request.unitNumber() == null
                || request.unitNumber().trim().length() < 2 || request.volumeMl() == null || request.volumeMl() <= 0
                || request.startedAt() == null || request.verifiedBy() == null) {
            throw invalid("blood_product, blood_type, unit_number, positive volume_ml, started_at and verified_by are required");
        }
        if (request.verifiedBy().equals(identity.userId())) {
            throw invalid("a second independent verifier is required for blood transfusion");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "BLOOD_TRANSFUSION_RECORD", idempotencyKey,
                    sha256(request.patientId() + "|" + request.bloodProduct() + "|" + request.unitNumber()
                            + "|" + request.volumeMl() + "|" + request.verifiedBy()));
            UUID transfusionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into blood_transfusion(
                      tenant_id, transfusion_id, patient_id, encounter_id, facility_id,
                      blood_product, blood_type, unit_number, volume_ml, started_at,
                      administered_by, verified_by, verification_note)
                    values (:tenant, :transfusion, :patient, :encounter, :facility,
                      :blood_product, :blood_type, :unit_number, :volume_ml, :started_at,
                      :actor, :verifier, :note)
                    """).param("tenant", identity.tenantId()).param("transfusion", transfusionId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("blood_product", request.bloodProduct().name())
                    .param("blood_type", request.bloodType().name()).param("unit_number", request.unitNumber().trim())
                    .param("volume_ml", request.volumeMl()).param("started_at", request.startedAt().atOffset(ZoneOffset.UTC))
                    .param("actor", identity.userId()).param("verifier", request.verifiedBy())
                    .param("note", request.verificationNote()).update();
            appendEvidence(identity, request.patientId(), transfusionId, 1,
                    "BLOOD_TRANSFUSION_RECORDED", "BloodTransfusionRecorded");
            completeCommand(identity, "BLOOD_TRANSFUSION_RECORD", idempotencyKey, transfusionId);
            return transfusion(identity.tenantId(), transfusionId, request.patientId(), request.encounterId());
        });
    }

    BloodTransfusionWire recordReaction(
            ClinicalIdentity identity, String idempotencyKey, UUID transfusionId,
            BloodTransfusionReactionRequestWire request) {
        if (request.reactionType() == null) throw invalid("reaction_type is required");
        return transactions.execute(status -> {
            beginCommand(identity, "BLOOD_TRANSFUSION_REACTION", idempotencyKey,
                    sha256(transfusionId + "|" + request.expectedRowVersion() + "|" + request.reactionType()));
            TransfusionHead current = jdbc.sql("""
                    select row_version, patient_id, reaction_type from blood_transfusion
                    where tenant_id = :tenant and transfusion_id = :transfusion
                      and patient_id = :patient and encounter_id = :encounter
                      and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("transfusion", transfusionId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new TransfusionHead(
                            rs.getLong("row_version"), rs.getObject("patient_id", UUID.class),
                            rs.getString("reaction_type")))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new BloodTransfusionException("BLOOD_TRANSFUSION_VERSION_CONFLICT", 409, "The transfusion changed; reload before retrying");
            }
            if (current.reactionType() != null) {
                throw new BloodTransfusionException("BLOOD_TRANSFUSION_STATE_INVALID", 409, "A reaction was already recorded");
            }
            jdbc.sql("""
                    update blood_transfusion set reaction_type = :reaction, reaction_noted_at = now(),
                      reaction_noted_by = :actor, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and transfusion_id = :transfusion and row_version = :expected
                    """).param("reaction", request.reactionType().name()).param("actor", identity.userId())
                    .param("tenant", identity.tenantId()).param("transfusion", transfusionId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, current.patientId(), transfusionId, current.rowVersion() + 1,
                    "BLOOD_TRANSFUSION_REACTION_NOTED", "BloodTransfusionReactionNoted");
            completeCommand(identity, "BLOOD_TRANSFUSION_REACTION", idempotencyKey, transfusionId);
            return transfusion(identity.tenantId(), transfusionId, request.patientId(), request.encounterId());
        });
    }

    List<BloodTransfusionWire> listTransfusions(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        requireActiveEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select transfusion_id from blood_transfusion
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by started_at desc, transfusion_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> transfusion(identity.tenantId(), id, patientId, encounterId)).toList();
    }

    private BloodTransfusionWire transfusion(UUID tenantId, UUID transfusionId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select transfusion_id, patient_id, encounter_id, facility_id, blood_product, blood_type,
                  unit_number, volume_ml, started_at, administered_by, verified_by, verification_note,
                  reaction_type, reaction_noted_at, reaction_noted_by, row_version
                from blood_transfusion
                where tenant_id = :tenant and transfusion_id = :transfusion
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", tenantId).param("transfusion", transfusionId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new BloodTransfusionWire(
                        rs.getObject("transfusion_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        BloodTransfusionWire.BloodProductValue.valueOf(rs.getString("blood_product")),
                        BloodTransfusionWire.BloodTypeValue.valueOf(rs.getString("blood_type")),
                        rs.getString("unit_number"), rs.getInt("volume_ml"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("administered_by", UUID.class), rs.getObject("verified_by", UUID.class),
                        rs.getString("verification_note"),
                        rs.getString("reaction_type") == null ? null
                                : BloodTransfusionWire.ReactionTypeValue.valueOf(rs.getString("reaction_type")),
                        rs.getObject("reaction_noted_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("reaction_noted_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("reaction_noted_by", UUID.class), rs.getLong("row_version")))
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
            throw new BloodTransfusionException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new BloodTransfusionException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID transfusionId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", transfusionId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID transfusionId, long version,
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
                + transfusionId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'BLOOD_TRANSFUSION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", transfusionId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'BLOOD_TRANSFUSION', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", transfusionId).param("version", version).param("event_type", eventType).update();
    }

    private static BloodTransfusionException invalid(String message) {
        return new BloodTransfusionException("BLOOD_TRANSFUSION_REQUEST_INVALID", 400, message);
    }

    static BloodTransfusionException contextDenied() {
        return new BloodTransfusionException("CONTEXT_NOT_PERMITTED", 403, "The requested transfusion context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record TransfusionHead(long rowVersion, UUID patientId, String reactionType) {}
}
