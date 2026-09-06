package org.haoemr.reproductive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ArtCycleRecordCreateRequestWire;
import org.haoemr.contracts.ArtCycleRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ArtCycleService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ArtCycleService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ArtCycleRecordWire createCycle(
            ClinicalIdentity identity, String idempotencyKey, ArtCycleRecordCreateRequestWire request) {
        if (request.cycleType() == null || request.cycleNumber() == null || request.cycleNumber() <= 0
                || request.ethicsConsentDate() == null) {
            throw invalid("cycle_type, positive cycle_number and ethics_consent_date are required");
        }
        if (request.ethicsConsentDate().isAfter(LocalDate.now())) {
            throw invalid("ethics consent date cannot be in the future");
        }
        if (request.partnerPatientId() != null && request.partnerPatientId().equals(request.patientId())) {
            throw invalid("partner cannot be the same patient");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "ART_CYCLE_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.cycleType()
                            + "|" + request.cycleNumber()));
            UUID cycleId = UUID.randomUUID();
            jdbc.sql("""
                    insert into art_cycle_record(
                      tenant_id, cycle_id, patient_id, partner_patient_id, encounter_id, facility_id,
                      cycle_type, cycle_number, ethics_consent_date, consent_document_id, status)
                    values (:tenant, :cycle, :patient, :partner, :encounter, :facility,
                      :cycle_type, :cycle_number, :consent_date, :consent_document, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("cycle", cycleId)
                    .param("patient", request.patientId()).param("partner", request.partnerPatientId())
                    .param("encounter", request.encounterId()).param("facility", request.facilityId())
                    .param("cycle_type", request.cycleType().name()).param("cycle_number", request.cycleNumber())
                    .param("consent_date", request.ethicsConsentDate())
                    .param("consent_document", request.consentDocumentId()).update();
            appendEvidence(identity, request.patientId(), cycleId, 1, "ART_CYCLE_CREATED", "ArtCycleCreated");
            completeCommand(identity, "ART_CYCLE_CREATE", idempotencyKey, cycleId);
            return cycle(identity.tenantId(), cycleId, request.patientId());
        });
    }

    List<ArtCycleRecordWire> listCycles(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select cycle_id from art_cycle_record
                where tenant_id = :tenant and patient_id = :patient
                order by created_at desc, cycle_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> cycle(identity.tenantId(), id, patientId)).toList();
    }

    private ArtCycleRecordWire cycle(UUID tenantId, UUID cycleId, UUID patientId) {
        return jdbc.sql("""
                select cycle_id, patient_id, partner_patient_id, encounter_id, facility_id, cycle_type,
                  cycle_number, ethics_consent_date, consent_document_id, status, row_version
                from art_cycle_record where tenant_id = :tenant and cycle_id = :cycle and patient_id = :patient
                """).param("tenant", tenantId).param("cycle", cycleId).param("patient", patientId)
                .query((rs, row) -> new ArtCycleRecordWire(
                        rs.getObject("cycle_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("partner_patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        ArtCycleRecordWire.CycleTypeValue.valueOf(rs.getString("cycle_type")),
                        rs.getInt("cycle_number"), rs.getObject("ethics_consent_date", LocalDate.class),
                        rs.getObject("consent_document_id", UUID.class),
                        ArtCycleRecordWire.StatusValue.valueOf(rs.getString("status")),
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
            throw new ArtCycleException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ArtCycleException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID cycleId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", cycleId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID cycleId, long version,
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
                + cycleId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ART_CYCLE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", cycleId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ART_CYCLE', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", cycleId).param("version", version).param("event_type", eventType).update();
    }

    private static ArtCycleException invalid(String message) {
        return new ArtCycleException("ART_CYCLE_REQUEST_INVALID", 400, message);
    }

    static ArtCycleException contextDenied() {
        return new ArtCycleException("CONTEXT_NOT_PERMITTED", 403, "The requested ART cycle context is not permitted");
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
