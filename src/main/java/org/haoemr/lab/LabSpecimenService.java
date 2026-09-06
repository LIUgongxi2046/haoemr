package org.haoemr.lab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.LabSpecimenCollectRequestWire;
import org.haoemr.contracts.LabSpecimenCreateRequestWire;
import org.haoemr.contracts.LabSpecimenReceiveRequestWire;
import org.haoemr.contracts.LabSpecimenRejectRequestWire;
import org.haoemr.contracts.LabSpecimenWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class LabSpecimenService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    LabSpecimenService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    LabSpecimenWire createSpecimen(
            ClinicalIdentity identity, String idempotencyKey, LabSpecimenCreateRequestWire request) {
        if (request.orderItemId() == null || request.specimenType() == null) {
            throw invalid("order_item_id and specimen_type are required");
        }
        requireRole(identity, request.facilityId(), List.of("LAB_TECHNICIAN"));
        return transactions.execute(status -> {
            beginCommand(identity, "LAB_SPECIMEN_CREATE", idempotencyKey,
                    sha256(request.orderItemId() + "|" + request.specimenType()));
            LabOrderItem order = jdbc.sql("""
                    select item.order_id, item.item_type, ord.patient_id, ord.encounter_id, ord.facility_id
                    from clinical_order_item item
                    join clinical_order ord on ord.tenant_id = item.tenant_id and ord.order_id = item.order_id
                    where item.tenant_id = :tenant and item.order_item_id = :order_item_id
                      and ord.patient_id = :patient and ord.encounter_id = :encounter
                    """).param("tenant", identity.tenantId()).param("order_item_id", request.orderItemId())
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .query((rs, row) -> new LabOrderItem(
                            rs.getObject("order_id", UUID.class), rs.getString("item_type"),
                            rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                            rs.getObject("facility_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (!"LAB".equals(order.itemType())) {
                throw new LabSpecimenException(
                        "LAB_SPECIMEN_ORDER_TYPE_INVALID", 409, "Only a lab order item can have a specimen");
            }
            UUID specimenId = UUID.randomUUID();
            jdbc.sql("""
                    insert into lab_specimen(
                      tenant_id, specimen_id, order_id, order_item_id, patient_id, encounter_id,
                      facility_id, specimen_type, collection_status)
                    values (:tenant, :specimen, :order_id, :order_item_id, :patient, :encounter,
                      :facility, :specimen_type, 'ORDERED')
                    """).param("tenant", identity.tenantId()).param("specimen", specimenId)
                    .param("order_id", order.orderId()).param("order_item_id", request.orderItemId())
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("specimen_type", request.specimenType().name()).update();
            appendEvidence(identity, request.patientId(), specimenId, 1, "LAB_SPECIMEN_ORDERED", "LabSpecimenOrdered");
            completeCommand(identity, "LAB_SPECIMEN_CREATE", idempotencyKey, specimenId);
            return specimen(identity.tenantId(), specimenId, request.patientId(), request.encounterId());
        });
    }

    LabSpecimenWire collectSpecimen(
            ClinicalIdentity identity, String idempotencyKey, UUID specimenId, LabSpecimenCollectRequestWire request) {
        requireRole(identity, request.facilityId(), List.of("LAB_TECHNICIAN", "REGISTERED_NURSE", "NURSE_MANAGER"));
        return transactions.execute(status -> {
            beginCommand(identity, "LAB_SPECIMEN_COLLECT", idempotencyKey,
                    sha256(specimenId + "|" + request.expectedRowVersion()));
            SpecimenHead current = lock(identity.tenantId(), specimenId, request.patientId(),
                    request.encounterId(), request.facilityId());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new LabSpecimenException("LAB_SPECIMEN_VERSION_CONFLICT", 409, "The specimen changed; reload before retrying");
            }
            if (!"ORDERED".equals(current.status())) {
                throw new LabSpecimenException("LAB_SPECIMEN_STATE_INVALID", 409, "Only an ordered specimen can be collected");
            }
            jdbc.sql("""
                    update lab_specimen set collection_status = 'COLLECTED', collected_at = now(),
                      collected_by = :actor, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and specimen_id = :specimen and row_version = :expected
                    """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                    .param("specimen", specimenId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), specimenId, current.rowVersion() + 1,
                    "LAB_SPECIMEN_COLLECTED", "LabSpecimenCollected");
            completeCommand(identity, "LAB_SPECIMEN_COLLECT", idempotencyKey, specimenId);
            return specimen(identity.tenantId(), specimenId, request.patientId(), request.encounterId());
        });
    }

    LabSpecimenWire receiveSpecimen(
            ClinicalIdentity identity, String idempotencyKey, UUID specimenId, LabSpecimenReceiveRequestWire request) {
        requireRole(identity, request.facilityId(), List.of("LAB_TECHNICIAN"));
        return transactions.execute(status -> {
            beginCommand(identity, "LAB_SPECIMEN_RECEIVE", idempotencyKey,
                    sha256(specimenId + "|" + request.expectedRowVersion()));
            SpecimenHead current = lock(identity.tenantId(), specimenId, request.patientId(),
                    request.encounterId(), request.facilityId());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new LabSpecimenException("LAB_SPECIMEN_VERSION_CONFLICT", 409, "The specimen changed; reload before retrying");
            }
            if (!"COLLECTED".equals(current.status())) {
                throw new LabSpecimenException("LAB_SPECIMEN_STATE_INVALID", 409, "Only a collected specimen can be received");
            }
            jdbc.sql("""
                    update lab_specimen set collection_status = 'RECEIVED', received_at = now(),
                      received_by = :actor, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and specimen_id = :specimen and row_version = :expected
                    """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                    .param("specimen", specimenId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), specimenId, current.rowVersion() + 1,
                    "LAB_SPECIMEN_RECEIVED", "LabSpecimenReceived");
            completeCommand(identity, "LAB_SPECIMEN_RECEIVE", idempotencyKey, specimenId);
            return specimen(identity.tenantId(), specimenId, request.patientId(), request.encounterId());
        });
    }

    LabSpecimenWire rejectSpecimen(
            ClinicalIdentity identity, String idempotencyKey, UUID specimenId, LabSpecimenRejectRequestWire request) {
        String reason = request.rejectionReason() == null ? "" : request.rejectionReason().trim();
        if (reason.length() < 2 || reason.length() > 1000) {
            throw invalid("rejection_reason must contain 2 to 1000 characters");
        }
        requireRole(identity, request.facilityId(), List.of("LAB_TECHNICIAN"));
        return transactions.execute(status -> {
            beginCommand(identity, "LAB_SPECIMEN_REJECT", idempotencyKey,
                    sha256(specimenId + "|" + request.expectedRowVersion() + "|" + reason));
            SpecimenHead current = lock(identity.tenantId(), specimenId, request.patientId(),
                    request.encounterId(), request.facilityId());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new LabSpecimenException("LAB_SPECIMEN_VERSION_CONFLICT", 409, "The specimen changed; reload before retrying");
            }
            if (!"ORDERED".equals(current.status()) && !"COLLECTED".equals(current.status())) {
                throw new LabSpecimenException("LAB_SPECIMEN_STATE_INVALID", 409, "Only an ordered or collected specimen can be rejected");
            }
            jdbc.sql("""
                    update lab_specimen set collection_status = 'REJECTED', rejection_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and specimen_id = :specimen and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("specimen", specimenId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), specimenId, current.rowVersion() + 1,
                    "LAB_SPECIMEN_REJECTED", "LabSpecimenRejected");
            completeCommand(identity, "LAB_SPECIMEN_REJECT", idempotencyKey, specimenId);
            return specimen(identity.tenantId(), specimenId, request.patientId(), request.encounterId());
        });
    }

    List<LabSpecimenWire> listSpecimens(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select specimen_id from lab_specimen
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by created_at desc, specimen_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> specimen(identity.tenantId(), id, patientId, encounterId)).toList();
    }

    private void requireRole(ClinicalIdentity identity, UUID facilityId, List<String> allowedRoles) {
        if (identity.roleAssignmentIds().isEmpty()) {
            throw roleDenied();
        }
        long count = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id=:tenant and user_id=:user and role_assignment_id in (:assignments)
                  and role_code in (:roles) and status='ACTIVE' and valid_from<=now()
                  and (valid_until is null or valid_until>now())
                  and (facility_id is null or facility_id=:facility)
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("roles", allowedRoles)
                .param("facility", facilityId).query(Long.class).single();
        if (count < 1) {
            throw roleDenied();
        }
    }

    private static LabSpecimenException roleDenied() {
        return new LabSpecimenException("LAB_SPECIMEN_ROLE_REQUIRED", 403,
                "The active role assignment is not permitted to perform this specimen workflow action");
    }

    private SpecimenHead lock(UUID tenantId, UUID specimenId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select collection_status, row_version, patient_id from lab_specimen
                where tenant_id = :tenant and specimen_id = :specimen
                  and patient_id = :patient and encounter_id = :encounter and facility_id = :facility for update
                """).param("tenant", tenantId).param("specimen", specimenId)
                .param("patient", patientId).param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new SpecimenHead(
                        rs.getString("collection_status"), rs.getLong("row_version"), rs.getObject("patient_id", UUID.class)))
                .optional().orElseThrow(() -> contextDenied());
    }

    private LabSpecimenWire specimen(UUID tenantId, UUID specimenId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select specimen_id, order_id, order_item_id, patient_id, encounter_id, facility_id,
                  specimen_type, collection_status, collected_at, collected_by, received_at,
                  received_by, rejection_reason, row_version
                from lab_specimen
                where tenant_id = :tenant and specimen_id = :specimen
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", tenantId).param("specimen", specimenId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new LabSpecimenWire(
                        rs.getObject("specimen_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getObject("order_item_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        LabSpecimenWire.SpecimenTypeValue.valueOf(rs.getString("specimen_type")),
                        LabSpecimenWire.CollectionStatusValue.valueOf(rs.getString("collection_status")),
                        rs.getObject("collected_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("collected_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("collected_by", UUID.class),
                        rs.getObject("received_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("received_by", UUID.class), rs.getString("rejection_reason"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new LabSpecimenException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new LabSpecimenException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID specimenId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", specimenId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID specimenId, long version,
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
                + specimenId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'LAB_SPECIMEN', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", specimenId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'LAB_SPECIMEN', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", specimenId).param("version", version).param("event_type", eventType).update();
    }

    private static LabSpecimenException invalid(String message) {
        return new LabSpecimenException("LAB_SPECIMEN_REQUEST_INVALID", 400, message);
    }

    static LabSpecimenException contextDenied() {
        return new LabSpecimenException("CONTEXT_NOT_PERMITTED", 403, "The requested lab specimen context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record LabOrderItem(
            UUID orderId, String itemType, UUID patientId, UUID encounterId, UUID facilityId) {}
    private record SpecimenHead(String status, long rowVersion, UUID patientId) {}
}
