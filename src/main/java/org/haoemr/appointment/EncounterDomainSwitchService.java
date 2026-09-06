package org.haoemr.appointment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EncounterDomainSwitchRecordRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchCorrectionRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchVoidRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class EncounterDomainSwitchService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EncounterDomainSwitchService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    EncounterDomainSwitchWire record(
            ClinicalIdentity identity, String idempotencyKey, EncounterDomainSwitchRecordRequestWire request) {
        if (request.patientId() == null || request.fromEncounterId() == null || request.toEncounterId() == null
                || request.fromDomain() == null || request.toDomain() == null || request.switchedAt() == null) {
            throw invalid("patient, from/to encounter, from/to domain and switched_at are required");
        }
        String reason = requireText(request.reason(), 2, "reason");
        if (request.fromDomain().name().equals(request.toDomain().name())) {
            throw new EncounterDomainSwitchException(
                    "ENCOUNTER_DOMAIN_SWITCH_SAME_DOMAIN", 400,
                    "A domain switch must move between different care domains");
        }
        if (request.fromEncounterId().equals(request.toEncounterId())) {
            throw new EncounterDomainSwitchException(
                    "ENCOUNTER_DOMAIN_SWITCH_SAME_ENCOUNTER", 400,
                    "A domain switch must move between different encounters");
        }
        requireBothEncountersSamePatient(identity.tenantId(), request.patientId(),
                request.fromEncounterId(), request.toEncounterId(),
                request.fromDomain().name(), request.toDomain().name());
        return transactions.execute(status -> {
            beginCommand(identity, "ENCOUNTER_DOMAIN_SWITCH_RECORD", idempotencyKey,
                    sha256(request.patientId() + "|" + request.fromEncounterId() + "|" + request.toEncounterId()
                            + "|" + request.fromDomain() + "|" + request.toDomain()));
            UUID switchId = UUID.randomUUID();
            jdbc.sql("""
                    insert into encounter_domain_switch(
                      tenant_id, domain_switch_id, patient_id, from_encounter_id, to_encounter_id,
                      from_domain, to_domain, reason, switched_at, switched_by)
                    values (:tenant, :switch, :patient, :from_encounter, :to_encounter,
                      :from_domain, :to_domain, :reason, :switched_at, :switched_by)
                    """).param("tenant", identity.tenantId()).param("switch", switchId)
                    .param("patient", request.patientId()).param("from_encounter", request.fromEncounterId())
                    .param("to_encounter", request.toEncounterId())
                    .param("from_domain", request.fromDomain().name()).param("to_domain", request.toDomain().name())
                    .param("reason", reason)
                    .param("switched_at", request.switchedAt().atOffset(ZoneOffset.UTC))
                    .param("switched_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), switchId, "ENCOUNTER_DOMAIN_SWITCH_RECORDED",
                    "EncounterDomainSwitchRecorded");
            completeCommand(identity, "ENCOUNTER_DOMAIN_SWITCH_RECORD", idempotencyKey, switchId);
            return domainSwitch(identity.tenantId(), switchId, request.patientId());
        });
    }

    List<EncounterDomainSwitchWire> listSwitches(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select domain_switch_id from encounter_domain_switch
                where tenant_id = :tenant and patient_id = :patient
                  and voided_at is null
                order by switched_at desc, domain_switch_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> domainSwitch(identity.tenantId(), id, patientId)).toList();
    }

    EncounterDomainSwitchWire correct(
            ClinicalIdentity identity, String idempotencyKey, UUID switchId,
            EncounterDomainSwitchCorrectionRequestWire request) {
        String reason = requireText(request.reason(), 2, "reason");
        String correctionReason = requireText(request.correctionReason(), 4, "correction_reason");
        if (request.fromEncounterId() == null || request.toEncounterId() == null || request.fromDomain() == null
                || request.toDomain() == null || request.switchedAt() == null
                || request.fromDomain().name().equals(request.toDomain().name())
                || request.fromEncounterId().equals(request.toEncounterId())) {
            throw invalid("valid, different from/to encounters and domains are required");
        }
        requireBothEncountersSamePatient(identity.tenantId(), request.patientId(),
                request.fromEncounterId(), request.toEncounterId(),
                request.fromDomain().name(), request.toDomain().name());
        return transactions.execute(status -> {
            beginCommand(identity, "ENCOUNTER_DOMAIN_SWITCH_CORRECT", idempotencyKey,
                    sha256(switchId + "|" + request.expectedRowVersion() + "|" + reason));
            DomainSwitchHead current = lockActive(identity.tenantId(), switchId, request.patientId());
            requireCurrentVersion(current, request.expectedRowVersion());
            jdbc.sql("""
                    update encounter_domain_switch set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1
                    where tenant_id = :tenant and domain_switch_id = :switch and row_version = :expected
                    """).param("reason", correctionReason).param("tenant", identity.tenantId())
                    .param("switch", switchId).param("expected", current.rowVersion()).update();
            UUID replacementId = UUID.randomUUID();
            jdbc.sql("""
                    insert into encounter_domain_switch(
                      tenant_id, domain_switch_id, patient_id, from_encounter_id, to_encounter_id,
                      from_domain, to_domain, reason, switched_at, switched_by, supersedes_domain_switch_id)
                    values (:tenant, :switch, :patient, :from_encounter, :to_encounter,
                      :from_domain, :to_domain, :reason, :switched_at, :actor, :supersedes)
                    """).param("tenant", identity.tenantId()).param("switch", replacementId)
                    .param("patient", request.patientId()).param("from_encounter", request.fromEncounterId())
                    .param("to_encounter", request.toEncounterId()).param("from_domain", request.fromDomain().name())
                    .param("to_domain", request.toDomain().name()).param("reason", reason)
                    .param("switched_at", request.switchedAt().atOffset(ZoneOffset.UTC))
                    .param("actor", identity.userId()).param("supersedes", switchId).update();
            appendEvidence(identity, request.patientId(), switchId,
                    "ENCOUNTER_DOMAIN_SWITCH_SUPERSEDED", "EncounterDomainSwitchSuperseded");
            appendEvidence(identity, request.patientId(), replacementId,
                    "ENCOUNTER_DOMAIN_SWITCH_CORRECTED", "EncounterDomainSwitchCorrected");
            completeCommand(identity, "ENCOUNTER_DOMAIN_SWITCH_CORRECT", idempotencyKey, replacementId);
            return domainSwitch(identity.tenantId(), replacementId, request.patientId());
        });
    }

    EncounterDomainSwitchWire voidSwitch(
            ClinicalIdentity identity, String idempotencyKey, UUID switchId,
            EncounterDomainSwitchVoidRequestWire request) {
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "ENCOUNTER_DOMAIN_SWITCH_VOID", idempotencyKey,
                    sha256(switchId + "|" + request.expectedRowVersion() + "|" + reason));
            DomainSwitchHead current = lockActive(identity.tenantId(), switchId, request.patientId());
            requireCurrentVersion(current, request.expectedRowVersion());
            jdbc.sql("""
                    update encounter_domain_switch set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1
                    where tenant_id = :tenant and domain_switch_id = :switch and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("switch", switchId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), switchId,
                    "ENCOUNTER_DOMAIN_SWITCH_VOIDED", "EncounterDomainSwitchVoided");
            completeCommand(identity, "ENCOUNTER_DOMAIN_SWITCH_VOID", idempotencyKey, switchId);
            return domainSwitch(identity.tenantId(), switchId, request.patientId());
        });
    }

    private DomainSwitchHead lockActive(UUID tenantId, UUID switchId, UUID patientId) {
        return jdbc.sql("""
                select row_version, voided_at from encounter_domain_switch
                where tenant_id = :tenant and domain_switch_id = :switch
                  and patient_id = :patient for update
                """).param("tenant", tenantId).param("switch", switchId).param("patient", patientId)
                .query((rs, row) -> new DomainSwitchHead(rs.getLong("row_version"),
                        rs.getObject("voided_at", OffsetDateTime.class)))
                .optional().orElseThrow(EncounterDomainSwitchService::contextDenied);
    }

    private static void requireCurrentVersion(DomainSwitchHead current, Long expectedRowVersion) {
        if (expectedRowVersion == null || current.rowVersion() != expectedRowVersion) {
            throw new EncounterDomainSwitchException("ENCOUNTER_DOMAIN_SWITCH_VERSION_CONFLICT", 409,
                    "The domain switch changed; reload before retrying");
        }
        if (current.voidedAt() != null) {
            throw new EncounterDomainSwitchException("ENCOUNTER_DOMAIN_SWITCH_STATE_INVALID", 409,
                    "A voided domain switch cannot be changed");
        }
    }

    private EncounterDomainSwitchWire domainSwitch(UUID tenantId, UUID switchId, UUID patientId) {
        return jdbc.sql("""
                select domain_switch_id, patient_id, from_encounter_id, to_encounter_id,
                  from_domain, to_domain, reason, switched_at, switched_by, row_version
                from encounter_domain_switch
                where tenant_id = :tenant and domain_switch_id = :switch and patient_id = :patient
                """).param("tenant", tenantId).param("switch", switchId).param("patient", patientId)
                .query((rs, row) -> new EncounterDomainSwitchWire(
                        rs.getObject("domain_switch_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("from_encounter_id", UUID.class), rs.getObject("to_encounter_id", UUID.class),
                        EncounterDomainSwitchWire.FromDomainValue.valueOf(rs.getString("from_domain")),
                        EncounterDomainSwitchWire.ToDomainValue.valueOf(rs.getString("to_domain")),
                        rs.getString("reason"),
                        rs.getObject("switched_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("switched_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(EncounterDomainSwitchService::contextDenied);
    }

    private void requireBothEncountersSamePatient(
            UUID tenantId, UUID patientId, UUID fromEncounterId, UUID toEncounterId,
            String fromDomain, String toDomain) {
        long count = jdbc.sql("""
                select count(*) from encounter
                where tenant_id = :tenant and patient_id = :patient
                  and ((encounter_id = :from and encounter_type = :from_domain)
                    or (encounter_id = :to and encounter_type = :to_domain))
                """).param("tenant", tenantId).param("patient", patientId)
                .param("from", fromEncounterId).param("to", toEncounterId)
                .param("from_domain", fromDomain).param("to_domain", toDomain)
                .query(Long.class).single();
        if (count != 2) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new EncounterDomainSwitchException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new EncounterDomainSwitchException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID switchId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", switchId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID switchId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + switchId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ENCOUNTER_DOMAIN_SWITCH', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", switchId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ENCOUNTER_DOMAIN_SWITCH', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", switchId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static EncounterDomainSwitchException invalid(String message) {
        return new EncounterDomainSwitchException(
                "ENCOUNTER_DOMAIN_SWITCH_REQUEST_INVALID", 400, message);
    }

    static EncounterDomainSwitchException contextDenied() {
        return new EncounterDomainSwitchException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested encounter domain switch context is not permitted");
    }

    private record DomainSwitchHead(long rowVersion, OffsetDateTime voidedAt) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
