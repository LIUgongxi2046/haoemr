package org.haoemr.imaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ImagingOrderCreateRequestWire;
import org.haoemr.contracts.ImagingOrderTransitionRequestWire;
import org.haoemr.contracts.ImagingOrderWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ImagingOrderService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ImagingOrderService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ImagingOrderWire createOrder(
            ClinicalIdentity identity, String idempotencyKey, ImagingOrderCreateRequestWire request) {
        if (request.modality() == null || request.bodyPart() == null || request.laterality() == null
                || request.contrastRequired() == null || request.orderedAt() == null) {
            throw invalid("modality, body_part, laterality, contrast_required and ordered_at are required");
        }
        boolean paired = request.bodyPart() == ImagingOrderCreateRequestWire.BodyPartValue.UPPER_EXTREMITY
                || request.bodyPart() == ImagingOrderCreateRequestWire.BodyPartValue.LOWER_EXTREMITY;
        if (paired && request.laterality() == ImagingOrderCreateRequestWire.LateralityValue.NONE) {
            throw invalid("laterality is required for paired body parts");
        }
        requireRole(identity, request.facilityId(),
                List.of("CLINICIAN", "ATTENDING_PHYSICIAN", "CHIEF_PHYSICIAN"),
                "IMAGING_ORDER_PRESCRIBER_ROLE_REQUIRED");
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "IMAGING_ORDER_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.modality()
                            + "|" + request.bodyPart() + "|" + request.laterality() + "|" + request.orderedAt()));
            UUID orderId = UUID.randomUUID();
            jdbc.sql("""
                    insert into imaging_order(
                      tenant_id, imaging_order_id, patient_id, encounter_id, facility_id,
                      modality, body_part, laterality, contrast_required, status, ordered_at)
                    values (:tenant, :order, :patient, :encounter, :facility,
                      :modality, :body_part, :laterality, :contrast, 'ORDERED', :ordered_at)
                    """).param("tenant", identity.tenantId()).param("order", orderId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("modality", request.modality().name())
                    .param("body_part", request.bodyPart().name()).param("laterality", request.laterality().name())
                    .param("contrast", request.contrastRequired())
                    .param("ordered_at", request.orderedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), orderId, 1, "IMAGING_ORDER_CREATED", "ImagingOrderCreated");
            completeCommand(identity, "IMAGING_ORDER_CREATE", idempotencyKey, orderId);
            return order(identity.tenantId(), orderId, request.patientId());
        });
    }

    ImagingOrderWire transitionOrder(
            ClinicalIdentity identity, String idempotencyKey, UUID orderId,
            ImagingOrderTransitionRequestWire request) {
        if (request.transition() == null) {
            throw invalid("transition is required");
        }
        if (request.transition() == ImagingOrderTransitionRequestWire.TransitionValue.CANCEL) {
            requireRole(identity, request.facilityId(),
                    List.of("CLINICIAN", "ATTENDING_PHYSICIAN", "CHIEF_PHYSICIAN"),
                    "IMAGING_ORDER_PRESCRIBER_ROLE_REQUIRED");
        } else {
            requireRole(identity, request.facilityId(), List.of("RADIOLOGIST"),
                    "IMAGING_ORDER_RADIOLOGIST_ROLE_REQUIRED");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "IMAGING_ORDER_TRANSITION", idempotencyKey,
                    sha256(orderId + "|" + request.expectedRowVersion() + "|" + request.transition()));
            ImagingOrderHead current = jdbc.sql("""
                    select status, row_version from imaging_order
                    where tenant_id = :tenant and imaging_order_id = :order
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                      for update
                    """).param("tenant", identity.tenantId()).param("order", orderId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new ImagingOrderHead(rs.getString("status"), rs.getLong("row_version")))
                    .optional().orElseThrow(ImagingOrderService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new ImagingOrderException(
                        "IMAGING_ORDER_VERSION_CONFLICT", 409, "The imaging order changed; reload before retrying");
            }
            String target = switch (request.transition()) {
                case PERFORM -> {
                    if (!"ORDERED".equals(current.status())) throw stateInvalid();
                    yield "PERFORMED";
                }
                case REPORT -> {
                    if (!"PERFORMED".equals(current.status())) throw stateInvalid();
                    yield "REPORTED";
                }
                case CANCEL -> {
                    if (!"ORDERED".equals(current.status())) throw stateInvalid();
                    yield "CANCELLED";
                }
            };
            if ("PERFORMED".equals(target)) {
                jdbc.sql("""
                        update imaging_order set status = 'PERFORMED', performed_at = now(),
                          row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and imaging_order_id = :order and row_version = :expected
                        """).param("tenant", identity.tenantId()).param("order", orderId)
                        .param("expected", current.rowVersion()).update();
            } else if ("REPORTED".equals(target)) {
                jdbc.sql("""
                        update imaging_order set status = 'REPORTED', reported_at = now(),
                          row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and imaging_order_id = :order and row_version = :expected
                        """).param("tenant", identity.tenantId()).param("order", orderId)
                        .param("expected", current.rowVersion()).update();
            } else {
                jdbc.sql("""
                        update imaging_order set status = 'CANCELLED', row_version = row_version + 1,
                          updated_at = now()
                        where tenant_id = :tenant and imaging_order_id = :order and row_version = :expected
                        """).param("tenant", identity.tenantId()).param("order", orderId)
                        .param("expected", current.rowVersion()).update();
            }
            appendEvidence(identity, request.patientId(), orderId, current.rowVersion() + 1,
                    "IMAGING_ORDER_" + target, "ImagingOrder" + target);
            completeCommand(identity, "IMAGING_ORDER_TRANSITION", idempotencyKey, orderId);
            return order(identity.tenantId(), orderId, request.patientId());
        });
    }

    List<ImagingOrderWire> listOrders(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select imaging_order_id from imaging_order
                where tenant_id = :tenant and patient_id = :patient
                order by ordered_at desc, imaging_order_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> order(identity.tenantId(), id, patientId)).toList();
    }

    private ImagingOrderWire order(UUID tenantId, UUID orderId, UUID patientId) {
        return jdbc.sql("""
                select imaging_order_id, patient_id, encounter_id, facility_id, modality, body_part,
                  laterality, contrast_required, status, ordered_at, performed_at, reported_at, row_version
                from imaging_order
                where tenant_id = :tenant and imaging_order_id = :order and patient_id = :patient
                """).param("tenant", tenantId).param("order", orderId).param("patient", patientId)
                .query((rs, row) -> new ImagingOrderWire(
                        rs.getObject("imaging_order_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        ImagingOrderWire.ModalityValue.valueOf(rs.getString("modality")),
                        ImagingOrderWire.BodyPartValue.valueOf(rs.getString("body_part")),
                        ImagingOrderWire.LateralityValue.valueOf(rs.getString("laterality")),
                        rs.getBoolean("contrast_required"),
                        ImagingOrderWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("ordered_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("performed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("performed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("reported_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("reported_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ImagingOrderService::contextDenied);
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

    private void requireRole(
            ClinicalIdentity identity, UUID facilityId, List<String> allowedRoles, String failureCode) {
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new ImagingOrderException(failureCode, 403,
                    "The active role assignment is not permitted to perform this imaging workflow action");
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
            throw new ImagingOrderException(failureCode, 403,
                    "The active role assignment is not permitted to perform this imaging workflow action");
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ImagingOrderException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ImagingOrderException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID orderId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", orderId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID orderId, long version,
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
                + orderId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'IMAGING_ORDER', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", orderId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'IMAGING_ORDER', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", orderId).param("version", version).param("event_type", eventType).update();
    }

    private static ImagingOrderException invalid(String message) {
        return new ImagingOrderException("IMAGING_ORDER_REQUEST_INVALID", 400, message);
    }

    private static ImagingOrderException stateInvalid() {
        return new ImagingOrderException("IMAGING_ORDER_STATE_INVALID", 409,
                "The imaging order is not in a state that accepts this transition");
    }

    static ImagingOrderException contextDenied() {
        return new ImagingOrderException("CONTEXT_NOT_PERMITTED", 403,
                "The requested imaging order context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ImagingOrderHead(String status, long rowVersion) {}
}
