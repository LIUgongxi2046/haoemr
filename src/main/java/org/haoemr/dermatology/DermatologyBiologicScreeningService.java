package org.haoemr.dermatology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DermatologyBiologicScreeningCreateRequestWire;
import org.haoemr.contracts.DermatologyBiologicScreeningWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DermatologyBiologicScreeningService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DermatologyBiologicScreeningService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DermatologyBiologicScreeningWire record(
            ClinicalIdentity identity, String idempotencyKey, DermatologyBiologicScreeningCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.tbScreeningResult() == null
                || request.hepatitisScreeningResult() == null || request.screenedAt() == null) {
            throw invalid("patient_id, encounter_id, tb/hepatitis screening result and screened_at are required");
        }
        String biologicName = requireText(request.biologicName(), 2, "biologic_name");
        boolean cleared = request.tbScreeningResult() == DermatologyBiologicScreeningCreateRequestWire.TbScreeningResultValue.NEGATIVE
                && request.hepatitisScreeningResult() == DermatologyBiologicScreeningCreateRequestWire.HepatitisScreeningResultValue.NEGATIVE;
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "DERMATOLOGY_BIOLOGIC_SCREENING", idempotencyKey,
                    sha256(request.patientId() + "|" + biologicName + "|" + request.tbScreeningResult()
                            + "|" + request.hepatitisScreeningResult()));
            UUID screeningId = UUID.randomUUID();
            jdbc.sql("""
                    insert into dermatology_biologic_screening(
                      tenant_id, screening_id, patient_id, encounter_id, facility_id, biologic_name,
                      tb_screening_result, hepatitis_screening_result, cleared_for_biologic, screened_at, screened_by)
                    values (:tenant, :screening, :patient, :encounter, :facility, :biologic,
                      :tb, :hepatitis, :cleared, :screened_at, :screened_by)
                    """).param("tenant", identity.tenantId()).param("screening", screeningId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("biologic", biologicName)
                    .param("tb", request.tbScreeningResult().name())
                    .param("hepatitis", request.hepatitisScreeningResult().name())
                    .param("cleared", cleared)
                    .param("screened_at", request.screenedAt().atOffset(ZoneOffset.UTC))
                    .param("screened_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), screeningId, 1, "DERMATOLOGY_BIOLOGIC_SCREENED",
                    "DermatologyBiologicScreened");
            completeCommand(identity, "DERMATOLOGY_BIOLOGIC_SCREENING", idempotencyKey, screeningId);
            return screening(identity.tenantId(), screeningId);
        });
    }

    List<DermatologyBiologicScreeningWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select screening_id from dermatology_biologic_screening
                where tenant_id = :tenant and patient_id = :patient
                order by screened_at desc, screening_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> screening(identity.tenantId(), id)).toList();
    }

    private DermatologyBiologicScreeningWire screening(UUID tenantId, UUID screeningId) {
        return jdbc.sql("""
                select screening_id, patient_id, encounter_id, facility_id, biologic_name,
                  tb_screening_result, hepatitis_screening_result, cleared_for_biologic, screened_at, screened_by, row_version
                from dermatology_biologic_screening
                where tenant_id = :tenant and screening_id = :screening
                """).param("tenant", tenantId).param("screening", screeningId)
                .query((rs, row) -> new DermatologyBiologicScreeningWire(
                        rs.getObject("screening_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getString("biologic_name"),
                        DermatologyBiologicScreeningWire.TbScreeningResultValue.valueOf(rs.getString("tb_screening_result")),
                        DermatologyBiologicScreeningWire.HepatitisScreeningResultValue.valueOf(rs.getString("hepatitis_screening_result")),
                        rs.getBoolean("cleared_for_biologic"),
                        rs.getObject("screened_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("screened_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(DermatologyBiologicScreeningService::contextDenied);
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
            throw new DermatologyBiologicScreeningException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new DermatologyBiologicScreeningException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID screeningId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", screeningId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID screeningId, long version,
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
                + screeningId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'DERMATOLOGY_BIOLOGIC', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", screeningId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DERMATOLOGY_BIOLOGIC', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", screeningId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static DermatologyBiologicScreeningException invalid(String message) {
        return new DermatologyBiologicScreeningException(
                "DERMATOLOGY_BIOLOGIC_REQUEST_INVALID", 400, message);
    }

    static DermatologyBiologicScreeningException contextDenied() {
        return new DermatologyBiologicScreeningException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested dermatology biologic screening context is not permitted");
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
