package org.haoemr.pharmacy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.billing.BillingService;
import org.haoemr.contracts.PharmacyDispensingPrepareRequestWire;
import org.haoemr.contracts.PharmacyDispensingTransitionRequestWire;
import org.haoemr.contracts.PharmacyDispensingUpdateRequestWire;
import org.haoemr.contracts.PharmacyDispensingVoidRequestWire;
import org.haoemr.contracts.PharmacyDispensingWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class PharmacyDispensingService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final BillingService billing;

    @Value("${haoemr.security.require-clinical-operation-roles:false}")
    boolean requireClinicalOperationRoles;

    PharmacyDispensingService(JdbcClient jdbc, TransactionTemplate transactions, BillingService billing) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.billing = billing;
    }

    PharmacyDispensingWire prepare(
            ClinicalIdentity identity, String idempotencyKey, PharmacyDispensingPrepareRequestWire request) {
        requirePharmacist(identity, request.facilityId());
        String drug = requireText(request.drugCode(), 2, "drug_code");
        String batch = requireText(request.batchNumber(), 2, "batch_number");
        String unit = requireText(request.quantityUnit(), 1, "quantity_unit");
        if (request.quantity() == null || request.quantity() <= 0) {
            throw invalid("quantity must be positive");
        }
        if (request.preparedAt() == null) {
            throw invalid("prepared_at is required");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        if (request.orderId() == null || request.orderItemId() == null) {
            throw new PharmacyDispensingException(
                    "PHARMACY_DISPENSING_ORDER_REQUIRED", 409,
                    "Dispensing must reference an active signed medication order item");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "PHARMACY_DISPENSING_PREPARE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.orderItemId()
                            + "|" + drug + "|" + batch
                            + "|" + request.quantity() + "|" + request.preparedAt()));
            requireDispensableMedication(identity.tenantId(), request.patientId(), request.encounterId(),
                    request.facilityId(), request.orderId(), request.orderItemId(), drug, unit,
                    BigDecimal.valueOf(request.quantity()), null);
            UUID dispensingId = UUID.randomUUID();
            jdbc.sql("""
                    insert into pharmacy_dispensing(
                      tenant_id, dispensing_id, patient_id, encounter_id, facility_id,
                      order_id, order_item_id, drug_code, batch_number, quantity, quantity_unit, dispensed_by,
                      status, prepared_at)
                    values (:tenant, :dispensing, :patient, :encounter, :facility,
                      :order_id, :order_item_id, :drug, :batch, :quantity, :unit, :dispensed_by,
                      'PREPARED', :prepared_at)
                    """).param("tenant", identity.tenantId()).param("dispensing", dispensingId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("order_id", request.orderId())
                    .param("order_item_id", request.orderItemId()).param("drug", drug).param("batch", batch)
                    .param("quantity", BigDecimal.valueOf(request.quantity())).param("unit", unit)
                    .param("dispensed_by", identity.userId())
                    .param("prepared_at", request.preparedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), dispensingId, 1,
                    "PHARMACY_DISPENSING_PREPARED", "PharmacyDispensingPrepared");
            completeCommand(identity, "PHARMACY_DISPENSING_PREPARE", idempotencyKey, dispensingId);
            return dispensing(identity.tenantId(), dispensingId, request.patientId());
        });
    }

    PharmacyDispensingWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID dispensingId,
            PharmacyDispensingTransitionRequestWire request) {
        requirePharmacist(identity, request.facilityId());
        if (request.transition() == null) {
            throw invalid("transition is required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "PHARMACY_DISPENSING_TRANSITION", idempotencyKey,
                    sha256(dispensingId + "|" + request.expectedRowVersion() + "|" + request.transition()));
            DispensingHead current = lockDispensing(identity, dispensingId, request.patientId(),
                    request.encounterId(), request.facilityId());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new PharmacyDispensingException(
                        "PHARMACY_DISPENSING_VERSION_CONFLICT", 409, "The dispensing changed; reload before retrying");
            }
            if (current.voidedAt() != null) {
                throw stateInvalid();
            }
            if (request.transition() == PharmacyDispensingTransitionRequestWire.TransitionValue.VERIFY) {
                if (!"PREPARED".equals(current.status())) {
                    throw stateInvalid();
                }
                if (identity.userId().equals(current.dispensedBy())) {
                    throw new PharmacyDispensingException(
                            "PHARMACY_SELF_VERIFICATION_FORBIDDEN", 403,
                            "The same user cannot prepare and verify the dispensing");
                }
                jdbc.sql("""
                        update pharmacy_dispensing set status = 'VERIFIED', verified_by = :verified_by,
                          verified_at = now(), row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and dispensing_id = :dispensing and row_version = :expected
                        """).param("verified_by", identity.userId()).param("tenant", identity.tenantId())
                        .param("dispensing", dispensingId).param("expected", current.rowVersion()).update();
            } else {
                if (!"VERIFIED".equals(current.status())) {
                    throw stateInvalid();
                }
                jdbc.sql("""
                        update pharmacy_dispensing set status = 'DISPENSED', dispensed_at = now(),
                          row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and dispensing_id = :dispensing and row_version = :expected
                        """).param("tenant", identity.tenantId()).param("dispensing", dispensingId)
                        .param("expected", current.rowVersion()).update();
                billing.createAutomaticCharge(identity, request.patientId(), request.encounterId(), request.facilityId(),
                        current.drugCode(), current.quantity(), current.quantityUnit(), "PHARMACY_DISPENSING", dispensingId);
            }
            appendEvidence(identity, request.patientId(), dispensingId, current.rowVersion() + 1,
                    "PHARMACY_DISPENSING_" + request.transition(), "PharmacyDispensing" + request.transition());
            completeCommand(identity, "PHARMACY_DISPENSING_TRANSITION", idempotencyKey, dispensingId);
            return dispensing(identity.tenantId(), dispensingId, request.patientId());
        });
    }

    PharmacyDispensingWire update(
            ClinicalIdentity identity, String idempotencyKey, UUID dispensingId,
            PharmacyDispensingUpdateRequestWire request) {
        requirePharmacist(identity, request.facilityId());
        String drug = requireText(request.drugCode(), 2, "drug_code");
        String batch = requireText(request.batchNumber(), 2, "batch_number");
        String unit = requireText(request.quantityUnit(), 1, "quantity_unit");
        if (request.quantity() == null || request.quantity() <= 0) {
            throw invalid("quantity must be positive");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "PHARMACY_DISPENSING_UPDATE", idempotencyKey,
                    sha256(dispensingId + "|" + request.expectedRowVersion() + "|" + drug + "|" + batch
                            + "|" + request.quantity() + "|" + unit));
            DispensingHead current = lockDispensing(identity, dispensingId, request.patientId(),
                    request.encounterId(), request.facilityId());
            requireVersion(current, request.expectedRowVersion());
            if (!"PREPARED".equals(current.status()) || current.voidedAt() != null) {
                throw new PharmacyDispensingException("PHARMACY_DISPENSING_STATE_INVALID", 409,
                        "Only an active prepared dispensing can be edited");
            }
            if (current.orderItemId() != null) {
                requireDispensableMedication(identity.tenantId(), request.patientId(), request.encounterId(),
                        request.facilityId(), current.orderId(), current.orderItemId(), drug, unit,
                        BigDecimal.valueOf(request.quantity()), dispensingId);
            }
            int updated = jdbc.sql("""
                    update pharmacy_dispensing set drug_code = :drug, batch_number = :batch,
                      quantity = :quantity, quantity_unit = :unit,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and dispensing_id = :dispensing and row_version = :expected
                    """).param("drug", drug).param("batch", batch)
                    .param("quantity", BigDecimal.valueOf(request.quantity())).param("unit", unit)
                    .param("tenant", identity.tenantId()).param("dispensing", dispensingId)
                    .param("expected", current.rowVersion()).update();
            if (updated != 1) throw versionConflict();
            appendEvidence(identity, request.patientId(), dispensingId, current.rowVersion() + 1,
                    "PHARMACY_DISPENSING_UPDATED", "PharmacyDispensingUpdated");
            completeCommand(identity, "PHARMACY_DISPENSING_UPDATE", idempotencyKey, dispensingId);
            return dispensing(identity.tenantId(), dispensingId, request.patientId());
        });
    }

    PharmacyDispensingWire voidDispensing(
            ClinicalIdentity identity, String idempotencyKey, UUID dispensingId,
            PharmacyDispensingVoidRequestWire request) {
        requirePharmacist(identity, request.facilityId());
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "PHARMACY_DISPENSING_VOID", idempotencyKey,
                    sha256(dispensingId + "|" + request.expectedRowVersion() + "|" + reason));
            DispensingHead current = lockDispensing(identity, dispensingId, request.patientId(),
                    request.encounterId(), request.facilityId());
            requireVersion(current, request.expectedRowVersion());
            if (current.voidedAt() != null) {
                throw new PharmacyDispensingException("PHARMACY_DISPENSING_STATE_INVALID", 409,
                        "The pharmacy dispensing is already voided");
            }
            if ("DISPENSED".equals(current.status())) {
                throw new PharmacyDispensingException("PHARMACY_DISPENSING_STATE_INVALID", 409,
                        "A dispensed medication must use the medication return workflow");
            }
            int updated = jdbc.sql("""
                    update pharmacy_dispensing set voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and dispensing_id = :dispensing and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("dispensing", dispensingId).param("expected", current.rowVersion()).update();
            if (updated != 1) throw versionConflict();
            appendEvidence(identity, request.patientId(), dispensingId, current.rowVersion() + 1,
                    "PHARMACY_DISPENSING_VOIDED", "PharmacyDispensingVoided");
            completeCommand(identity, "PHARMACY_DISPENSING_VOID", idempotencyKey, dispensingId);
            return dispensing(identity.tenantId(), dispensingId, request.patientId());
        });
    }

    List<PharmacyDispensingWire> listDispensings(
            ClinicalIdentity identity, UUID patientId, UUID facilityId) {
        requirePharmacist(identity, facilityId);
        return jdbc.sql("""
                select dispensing_id from pharmacy_dispensing
                where tenant_id = :tenant and patient_id = :patient
                order by prepared_at desc, dispensing_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> dispensing(identity.tenantId(), id, patientId)).toList();
    }

    private void requirePharmacist(ClinicalIdentity identity, UUID facilityId) {
        if (!requireClinicalOperationRoles) return;
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new PharmacyDispensingException(
                    "PHARMACIST_ROLE_REQUIRED", 403, "An active pharmacist role is required");
        }
        long authorized = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:assignments) and role_code = 'PHARMACIST'
                  and (facility_id is null or facility_id = :facility)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("facility", facilityId)
                .query(Long.class).single();
        if (authorized == 0) {
            throw new PharmacyDispensingException(
                    "PHARMACIST_ROLE_REQUIRED", 403, "An active pharmacist role is required");
        }
    }

    private PharmacyDispensingWire dispensing(UUID tenantId, UUID dispensingId, UUID patientId) {
        return jdbc.sql("""
                select dispensing_id, patient_id, encounter_id, facility_id, order_id, order_item_id,
                  drug_code, batch_number,
                  quantity, quantity_unit, dispensed_by, verified_by, status,
                  prepared_at, verified_at, dispensed_at, voided_at, void_reason, row_version
                from pharmacy_dispensing
                where tenant_id = :tenant and dispensing_id = :dispensing and patient_id = :patient
                """).param("tenant", tenantId).param("dispensing", dispensingId).param("patient", patientId)
                .query((rs, row) -> new PharmacyDispensingWire(
                        rs.getObject("dispensing_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getObject("order_item_id", UUID.class),
                        rs.getString("drug_code"), rs.getString("batch_number"),
                        rs.getBigDecimal("quantity").doubleValue(), rs.getString("quantity_unit"),
                        rs.getObject("dispensed_by", UUID.class), rs.getObject("verified_by", UUID.class),
                        PharmacyDispensingWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("prepared_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("verified_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("verified_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("dispensed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("dispensed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("voided_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("voided_at", OffsetDateTime.class).toInstant(),
                        rs.getString("void_reason"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(PharmacyDispensingService::contextDenied);
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

    private String encounterType(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select encounter_type from encounter
                where tenant_id = :tenant and encounter_id = :encounter and patient_id = :patient
                  and facility_id = :facility and status in ('ARRIVED', 'IN_PROGRESS', 'SUSPENDED')
                """).param("tenant", tenantId).param("encounter", encounterId).param("patient", patientId)
                .param("facility", facilityId).query(String.class).optional()
                .orElseThrow(PharmacyDispensingService::contextDenied);
    }

    private void requireDispensableMedication(
            UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId,
            UUID orderId, UUID orderItemId, String drugCode, String unit,
            BigDecimal requestedQuantity, UUID excludedDispensingId) {
        PrescribedMedication prescribed = jdbc.sql("""
                select item.catalog_code, item.requested_quantity, item.quantity_unit
                from clinical_order medical_order
                join clinical_order_item item on item.tenant_id = medical_order.tenant_id
                  and item.order_id = medical_order.order_id
                where medical_order.tenant_id = :tenant and medical_order.order_id = :order_id
                  and medical_order.patient_id = :patient and medical_order.encounter_id = :encounter
                  and medical_order.facility_id = :facility
                  and medical_order.status in ('SIGNED', 'ACTIVE', 'IN_PROGRESS')
                  and item.order_item_id = :order_item_id and item.item_type = 'MEDICATION'
                  and item.item_state in ('ACTIVE', 'IN_PROGRESS')
                for update of item
                """).param("tenant", tenantId).param("order_id", orderId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId).param("order_item_id", orderItemId)
                .query((rs, row) -> new PrescribedMedication(
                        rs.getString("catalog_code"), rs.getBigDecimal("requested_quantity"),
                        rs.getString("quantity_unit")))
                .optional().orElseThrow(() -> new PharmacyDispensingException(
                    "PHARMACY_MEDICATION_ORDER_NOT_DISPENSABLE", 409,
                        "The referenced medication order item is missing, unsigned, stopped or not active"));
        if (!prescribed.catalogCode().equals(drugCode) || !prescribed.quantityUnit().equals(unit)) {
            throw new PharmacyDispensingException(
                    "PHARMACY_DISPENSING_ORDER_MISMATCH", 409,
                    "Drug code and quantity unit must match the referenced medication order item");
        }
        BigDecimal alreadyPrepared = excludedDispensingId == null
                ? jdbc.sql("""
                        select coalesce(sum(quantity), 0) from pharmacy_dispensing
                        where tenant_id = :tenant and order_item_id = :order_item_id and voided_at is null
                        """).param("tenant", tenantId).param("order_item_id", orderItemId)
                        .query(BigDecimal.class).single()
                : jdbc.sql("""
                        select coalesce(sum(quantity), 0) from pharmacy_dispensing
                        where tenant_id = :tenant and order_item_id = :order_item_id and voided_at is null
                          and dispensing_id <> :excluded
                        """).param("tenant", tenantId).param("order_item_id", orderItemId)
                        .param("excluded", excludedDispensingId).query(BigDecimal.class).single();
        if (alreadyPrepared.add(requestedQuantity).compareTo(prescribed.quantity()) > 0) {
            throw new PharmacyDispensingException(
                    "PHARMACY_DISPENSING_QUANTITY_EXCEEDED", 409,
                    "Cumulative dispensing quantity exceeds the active medication order quantity");
        }
    }

    private DispensingHead lockDispensing(
            ClinicalIdentity identity, UUID dispensingId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select status, dispensed_by, row_version, voided_at, order_id, order_item_id,
                  drug_code, quantity, quantity_unit
                from pharmacy_dispensing
                where tenant_id = :tenant and dispensing_id = :dispensing
                  and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                for update
                """).param("tenant", identity.tenantId()).param("dispensing", dispensingId)
                .param("patient", patientId).param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new DispensingHead(rs.getString("status"),
                        rs.getObject("dispensed_by", UUID.class), rs.getLong("row_version"),
                        rs.getObject("voided_at", OffsetDateTime.class),
                        rs.getObject("order_id", UUID.class), rs.getObject("order_item_id", UUID.class),
                        rs.getString("drug_code"), rs.getBigDecimal("quantity"), rs.getString("quantity_unit")))
                .optional().orElseThrow(PharmacyDispensingService::contextDenied);
    }

    private static void requireVersion(DispensingHead current, Long expectedRowVersion) {
        if (expectedRowVersion == null || current.rowVersion() != expectedRowVersion) throw versionConflict();
    }

    private static PharmacyDispensingException versionConflict() {
        return new PharmacyDispensingException(
                "PHARMACY_DISPENSING_VERSION_CONFLICT", 409, "The dispensing changed; reload before retrying");
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new PharmacyDispensingException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new PharmacyDispensingException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID dispensingId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", dispensingId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID dispensingId, long version,
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
                + dispensingId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'PHARMACY_DISPENSING', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", dispensingId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'PHARMACY_DISPENSING', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", dispensingId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static PharmacyDispensingException invalid(String message) {
        return new PharmacyDispensingException("PHARMACY_DISPENSING_REQUEST_INVALID", 400, message);
    }

    private static PharmacyDispensingException stateInvalid() {
        return new PharmacyDispensingException("PHARMACY_DISPENSING_STATE_INVALID", 409,
                "The pharmacy dispensing is not in a state that accepts this transition");
    }

    static PharmacyDispensingException contextDenied() {
        return new PharmacyDispensingException("CONTEXT_NOT_PERMITTED", 403,
                "The requested pharmacy dispensing context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record DispensingHead(
            String status, UUID dispensedBy, long rowVersion, OffsetDateTime voidedAt,
            UUID orderId, UUID orderItemId, String drugCode, BigDecimal quantity, String quantityUnit) {}

    private record PrescribedMedication(String catalogCode, BigDecimal quantity, String quantityUnit) {}
}
