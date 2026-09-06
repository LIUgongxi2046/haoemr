package org.haoemr.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.billing.BillingService;
import org.haoemr.configuration.ConfigurationRuntimeService;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeCommandRequest;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeContext;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeExecutionWire;
import org.haoemr.contracts.ClinicalOrderCreateRequestWire;
import org.haoemr.contracts.ClinicalOrderControlRequestWire;
import org.haoemr.contracts.ClinicalOrderItemCreateRequestWire;
import org.haoemr.contracts.ClinicalOrderItemWire;
import org.haoemr.contracts.ClinicalOrderSafetyCheckRequestWire;
import org.haoemr.contracts.ClinicalOrderSignRequestWire;
import org.haoemr.contracts.ClinicalOrderWire;
import org.haoemr.contracts.MedicationSafetyEvaluationWire;
import org.haoemr.contracts.MedicationSafetyFindingWire;
import org.haoemr.contracts.OrderExecutionEventCreateRequestWire;
import org.haoemr.contracts.OrderExecutionTaskWire;
import org.haoemr.security.ClinicalIdentity;
import org.haoemr.tasks.ClinicalTaskRuleResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class OrderService {
    private static final String ACTIVE_RULE_WATERMARK = "RULESET-MEDICATION-6";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ClinicalTaskRuleResolver taskRules;
    private final BillingService billing;
    private final ConfigurationRuntimeService configurationRuntime;

    OrderService(
            JdbcClient jdbc, TransactionTemplate transactions, ClinicalTaskRuleResolver taskRules,
            BillingService billing, ConfigurationRuntimeService configurationRuntime) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.taskRules = taskRules;
        this.billing = billing;
        this.configurationRuntime = configurationRuntime;
    }

    ClinicalOrderWire create(
            ClinicalIdentity identity, String idempotencyKey, ClinicalOrderCreateRequestWire request) {
        validateCreate(request);
        requirePrescriberRole(identity, request.facilityId());
        return transactions.execute(status -> {
            requireEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
            String requestHash = sha256(request.patientId() + "|" + request.encounterId() + "|"
                    + request.orderScope() + "|" + request.clinicalIndication().trim() + "|" + request.items());
            beginCommand(identity, "ORDER_CREATE", idempotencyKey, requestHash);
            UUID orderId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_order(
                      tenant_id, order_id, patient_id, encounter_id, facility_id,
                      order_scope, status, clinical_indication, author_user_id)
                    values (:tenant, :order_id, :patient, :encounter, :facility,
                      :order_scope, 'DRAFT', :indication, :author)
                    """).param("tenant", identity.tenantId()).param("order_id", orderId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("order_scope", request.orderScope().name())
                    .param("indication", request.clinicalIndication().trim()).param("author", identity.userId()).update();
            for (ClinicalOrderItemCreateRequestWire item : request.items()) {
                MedicationCatalog medication = "MEDICATION".equals(item.itemType().name())
                        ? requireActiveMedication(identity.tenantId(), item.catalogCode().trim()) : null;
                jdbc.sql("""
                        insert into clinical_order_item(
                          tenant_id, order_item_id, order_id, item_type, catalog_code,
                          display_name, requested_quantity, quantity_unit, instructions, item_state,
                          medication_catalog_version_id, drug_code, ingredient_code,
                          dose_value, dose_unit, route_code, frequency_code)
                        values (:tenant, :item_id, :order_id, :item_type, :catalog_code,
                          :display_name, :quantity, :unit, :instructions, 'DRAFT',
                          :medication_version, :drug_code, :ingredient_code,
                          :dose_value, :dose_unit, :route_code, :frequency_code)
                        """).param("tenant", identity.tenantId()).param("item_id", UUID.randomUUID())
                        .param("order_id", orderId).param("item_type", item.itemType().name())
                        .param("catalog_code", item.catalogCode().trim())
                        .param("display_name", item.displayName().trim())
                        .param("quantity", decimal(item.requestedQuantity()))
                        .param("unit", item.quantityUnit().trim())
                        .param("instructions", blankToNull(item.instructions()))
                        .param("medication_version", medication == null ? null : medication.versionId())
                        .param("drug_code", medication == null ? null : medication.drugCode())
                        .param("ingredient_code", medication == null ? null : medication.ingredientCode())
                        .param("dose_value", item.doseValue() == null ? null : decimal(item.doseValue()))
                        .param("dose_unit", blankToNull(item.doseUnit()))
                        .param("route_code", blankToNull(item.routeCode()))
                        .param("frequency_code", blankToNull(item.frequencyCode())).update();
            }
            appendEvidence(identity, request.patientId(), orderId, 1, "ORDER_DRAFT_CREATED", "ClinicalOrderDraftCreated");
            completeCommand(identity, "ORDER_CREATE", idempotencyKey, 201, orderId);
            return snapshot(identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
        });
    }

    ClinicalOrderWire update(
            ClinicalIdentity identity, String idempotencyKey, UUID orderId, long expectedVersion,
            ClinicalOrderCreateRequestWire request) {
        validateCreate(request);
        requirePrescriberRole(identity, request.facilityId());
        return transactions.execute(status -> {
            LockedOrder current = lockOrder(
                    identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
            if (current.rowVersion() != expectedVersion) {
                throw new OrderException("ORDER_VERSION_CONFLICT", 409, "The draft order changed; reload before editing");
            }
            if (!"DRAFT".equals(current.status())) {
                throw new OrderException("ORDER_STATE_INVALID", 409, "Only a draft order can be edited");
            }
            beginCommand(identity, "ORDER_UPDATE", idempotencyKey,
                    sha256(orderId + "|" + expectedVersion + "|" + request.orderScope() + "|"
                            + request.clinicalIndication().trim() + "|" + request.items()));
            int updated = jdbc.sql("""
                    update clinical_order set order_scope = :scope, clinical_indication = :indication,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and order_id = :order and status = 'DRAFT'
                      and row_version = :expected
                    """).param("scope", request.orderScope().name())
                    .param("indication", request.clinicalIndication().trim())
                    .param("tenant", identity.tenantId()).param("order", orderId)
                    .param("expected", expectedVersion).update();
            if (updated != 1) throw new OrderException("ORDER_VERSION_CONFLICT", 409, "The draft order changed");
            jdbc.sql("delete from clinical_order_item where tenant_id = :tenant and order_id = :order")
                    .param("tenant", identity.tenantId()).param("order", orderId).update();
            for (ClinicalOrderItemCreateRequestWire item : request.items()) {
                MedicationCatalog medication = "MEDICATION".equals(item.itemType().name())
                        ? requireActiveMedication(identity.tenantId(), item.catalogCode().trim()) : null;
                jdbc.sql("""
                        insert into clinical_order_item(
                          tenant_id, order_item_id, order_id, item_type, catalog_code,
                          display_name, requested_quantity, quantity_unit, instructions, item_state,
                          medication_catalog_version_id, drug_code, ingredient_code,
                          dose_value, dose_unit, route_code, frequency_code)
                        values (:tenant, :item_id, :order_id, :item_type, :catalog_code,
                          :display_name, :quantity, :unit, :instructions, 'DRAFT',
                          :medication_version, :drug_code, :ingredient_code,
                          :dose_value, :dose_unit, :route_code, :frequency_code)
                        """).param("tenant", identity.tenantId()).param("item_id", UUID.randomUUID())
                        .param("order_id", orderId).param("item_type", item.itemType().name())
                        .param("catalog_code", item.catalogCode().trim()).param("display_name", item.displayName().trim())
                        .param("quantity", decimal(item.requestedQuantity())).param("unit", item.quantityUnit().trim())
                        .param("instructions", blankToNull(item.instructions()))
                        .param("medication_version", medication == null ? null : medication.versionId())
                        .param("drug_code", medication == null ? null : medication.drugCode())
                        .param("ingredient_code", medication == null ? null : medication.ingredientCode())
                        .param("dose_value", item.doseValue() == null ? null : decimal(item.doseValue()))
                        .param("dose_unit", blankToNull(item.doseUnit())).param("route_code", blankToNull(item.routeCode()))
                        .param("frequency_code", blankToNull(item.frequencyCode())).update();
            }
            appendEvidence(identity, request.patientId(), orderId, expectedVersion + 1,
                    "ORDER_DRAFT_UPDATED", "ClinicalOrderDraftUpdated");
            completeCommand(identity, "ORDER_UPDATE", idempotencyKey, 200, orderId);
            return snapshot(identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
        });
    }

    ClinicalOrderWire get(
            ClinicalIdentity identity, UUID orderId, UUID patientId, UUID encounterId, UUID facilityId) {
        return snapshot(identity.tenantId(), orderId, patientId, encounterId, facilityId);
    }

    List<ClinicalOrderWire> list(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, UUID facilityId) {
        requireReadableEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select order_id from clinical_order
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by updated_at desc, order_id
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(orderId -> snapshot(identity.tenantId(), orderId, patientId, encounterId, facilityId))
                .toList();
    }

    MedicationSafetyEvaluationWire checkSafety(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID orderId,
            ClinicalOrderSafetyCheckRequestWire request) {
        requirePrescriberRole(identity, request.facilityId());
        if (request.expectedRowVersion() == null || request.ruleWatermark() == null
                || request.ruleWatermark().isBlank()) {
            throw new OrderException(
                    "ORDER_SAFETY_REQUEST_INVALID", 400,
                    "Expected version and rule watermark are required for medication safety evaluation");
        }
        return transactions.execute(status -> {
            LockedOrder order = lockOrder(
                    identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
            validateSafetyRequest(order, request.expectedRowVersion(), request.ruleWatermark());
            String requestHash = sha256(orderId + "|" + request.expectedRowVersion() + "|" + request.ruleWatermark());
            beginCommand(identity, "ORDER_SAFETY_CHECK", idempotencyKey, requestHash);
            MedicationSafetyEvaluationWire evaluation = persistSafetyEvaluation(
                    identity, idempotencyKey, orderId, request.patientId(), request.encounterId(),
                    request.facilityId(), order.rowVersion());
            completeCommand(identity, "ORDER_SAFETY_CHECK", idempotencyKey, 200, evaluation.evaluationId());
            return evaluation;
        });
    }

    ClinicalOrderWire sign(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID orderId,
            ClinicalOrderSignRequestWire request) {
        requirePrescriberRole(identity, request.facilityId());
        if (request.expectedRowVersion() == null || request.ruleWatermark() == null
                || request.ruleWatermark().isBlank()) {
            throw new OrderException("ORDER_SIGN_REQUEST_INVALID", 400, "Expected version and rule watermark are required");
        }
        MedicationSafetyEvaluationWire safety = transactions.execute(status -> {
            LockedOrder order = lockOrder(
                    identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
            validateSafetyRequest(order, request.expectedRowVersion(), request.ruleWatermark());
            return persistSafetyEvaluation(
                    identity, idempotencyKey, orderId, request.patientId(), request.encounterId(),
                    request.facilityId(), order.rowVersion());
        });
        if (!safety.passed()) {
            throw new OrderException(
                    "MEDICATION_SAFETY_BLOCKED", 409,
                    "Deterministic medication safety findings must be resolved before signing");
        }
        return transactions.execute(status -> {
            LockedOrder order = lockOrder(
                    identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
            if (order.rowVersion() != request.expectedRowVersion()) {
                throw new OrderException("ORDER_VERSION_CONFLICT", 409, "The order changed before signing");
            }
            if (!"DRAFT".equals(order.status())) {
                throw new OrderException("ORDER_STATE_INVALID", 409, "Only a draft order can be signed");
            }
            if (!ACTIVE_RULE_WATERMARK.equals(request.ruleWatermark())) {
                throw new OrderException("RULE_WATERMARK_STALE", 409, "Reload the active deterministic order rules");
            }
            if (evaluateSafety(identity.tenantId(), orderId, request.patientId(), request.encounterId()).stream()
                    .anyMatch(d -> "BLOCKING".equals(d.severity()))) {
                throw new OrderException(
                        "MEDICATION_SAFETY_BLOCKED", 409,
                        "Medication safety facts changed before signing; reload and evaluate again");
            }
            String requestHash = sha256(orderId + "|" + request.expectedRowVersion() + "|" + request.ruleWatermark());
            beginCommand(identity, "ORDER_SIGN", idempotencyKey, requestHash);
            rejectActiveDuplicates(identity.tenantId(), orderId, request.encounterId());
            OffsetDateTime signedAt = OffsetDateTime.now(ZoneOffset.UTC);
            int updated = jdbc.sql("""
                    update clinical_order
                    set status = 'ACTIVE', signed_by = :signer, signed_at = :signed_at,
                      rule_watermark = :watermark, row_version = row_version + 1, updated_at = :signed_at
                    where tenant_id = :tenant and order_id = :order_id and status = 'DRAFT'
                      and row_version = :expected
                    """).param("signer", identity.userId()).param("signed_at", signedAt)
                    .param("watermark", ACTIVE_RULE_WATERMARK).param("tenant", identity.tenantId())
                    .param("order_id", orderId).param("expected", request.expectedRowVersion()).update();
            if (updated != 1) throw new OrderException("ORDER_VERSION_CONFLICT", 409, "The order changed before signing");
            jdbc.sql("""
                    update clinical_order_item set item_state = 'ACTIVE', row_version = row_version + 1,
                      updated_at = :updated where tenant_id = :tenant and order_id = :order_id
                      and item_state = 'DRAFT'
                    """).param("updated", signedAt).param("tenant", identity.tenantId())
                    .param("order_id", orderId).update();
            jdbc.sql("""
                    insert into order_execution_task(
                      tenant_id, execution_task_id, order_id, order_item_id, patient_id,
                      encounter_id, task_state, requested_quantity, quantity_unit)
                    select tenant_id, gen_random_uuid(), order_id, order_item_id, :patient,
                      :encounter, 'PENDING', requested_quantity, quantity_unit
                    from clinical_order_item where tenant_id = :tenant and order_id = :order_id
                    """).param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("tenant", identity.tenantId()).param("order_id", orderId).update();
            createClinicalTasks(identity, orderId, request.patientId(), request.encounterId(), request.facilityId());
            appendEvidence(identity, request.patientId(), orderId, 2, "ORDER_SIGNED_ACTIVE", "ClinicalOrderActivated");
            completeCommand(identity, "ORDER_SIGN", idempotencyKey, 200, orderId);
            return snapshot(identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
        });
    }

    ClinicalOrderWire stop(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID orderId,
            ClinicalOrderControlRequestWire request) {
        return control(identity, idempotencyKey, orderId, request, ControlAction.STOP);
    }

    ClinicalOrderWire cancel(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID orderId,
            ClinicalOrderControlRequestWire request) {
        return control(identity, idempotencyKey, orderId, request, ControlAction.CANCEL);
    }

    private ClinicalOrderWire control(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID orderId,
            ClinicalOrderControlRequestWire request,
            ControlAction action) {
        requirePrescriberRole(identity, request.facilityId());
        if (request.expectedRowVersion() == null || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 1000) {
            throw new OrderException(
                    "ORDER_CONTROL_REQUEST_INVALID", 400,
                    "Expected version and a reason of no more than 1000 characters are required");
        }
        return transactions.execute(status -> {
            LockedOrder order = lockOrder(
                    identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
            if (order.rowVersion() != request.expectedRowVersion()) {
                throw new OrderException("ORDER_VERSION_CONFLICT", 409, "The order changed before control was applied");
            }
            if (!List.of("ACTIVE", "IN_PROGRESS").contains(order.status())) {
                throw new OrderException(
                        "ORDER_STATE_INVALID", 409, "Only active or in-progress orders can be stopped or cancelled");
            }
            String scope = action == ControlAction.STOP ? "ORDER_STOP" : "ORDER_CANCEL";
            String requestHash = sha256(orderId + "|" + request.expectedRowVersion() + "|"
                    + request.reason().trim() + "|" + action);
            beginCommand(identity, scope, idempotencyKey, requestHash);

            long inFlight = jdbc.sql("""
                    select count(*) from order_execution_task
                    where tenant_id = :tenant and order_id = :order_id
                      and task_state in ('ACCEPTED', 'IN_PROGRESS', 'PARTIAL')
                    """).param("tenant", identity.tenantId()).param("order_id", orderId)
                    .query(Long.class).single();
            if (action == ControlAction.CANCEL && inFlight > 0) {
                throw new OrderException(
                        "ORDER_CANCEL_EXECUTION_EXISTS", 409,
                        "An order with execution facts must be stopped instead of cancelled");
            }
            String resultingStatus = action == ControlAction.CANCEL
                    ? "CANCELLED" : inFlight == 0 ? "STOPPED" : "STOPPING";
            String terminalItemState = action == ControlAction.CANCEL ? "CANCELLED" : "STOPPED";
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            jdbc.sql("""
                    update order_execution_task set task_state = 'CANCELLED',
                      row_version = row_version + 1, updated_at = :occurred_at
                    where tenant_id = :tenant and order_id = :order_id and task_state = 'PENDING'
                    """).param("occurred_at", occurredAt).param("tenant", identity.tenantId())
                    .param("order_id", orderId).update();
            withdrawCancelledClinicalTasks(identity, orderId);
            jdbc.sql("""
                    update clinical_order_item item set item_state = :item_state,
                      row_version = row_version + 1, updated_at = :occurred_at
                    where item.tenant_id = :tenant and item.order_id = :order_id
                      and exists (
                        select 1 from order_execution_task task
                        where task.tenant_id = item.tenant_id
                          and task.order_item_id = item.order_item_id
                          and task.task_state = 'CANCELLED')
                    """).param("item_state", terminalItemState).param("occurred_at", occurredAt)
                    .param("tenant", identity.tenantId()).param("order_id", orderId).update();
            long orderVersion = jdbc.sql("""
                    update clinical_order set status = :resulting_status,
                      row_version = row_version + 1, updated_at = :occurred_at
                    where tenant_id = :tenant and order_id = :order_id
                      and row_version = :expected and status in ('ACTIVE', 'IN_PROGRESS')
                    returning row_version
                    """).param("resulting_status", resultingStatus).param("occurred_at", occurredAt)
                    .param("tenant", identity.tenantId()).param("order_id", orderId)
                    .param("expected", request.expectedRowVersion()).query(Long.class)
                    .optional().orElseThrow(() -> new OrderException(
                            "ORDER_VERSION_CONFLICT", 409, "The order changed before control was applied"));
            jdbc.sql("""
                    insert into order_control_event(
                      tenant_id, order_control_event_id, order_id, action_type,
                      previous_status, resulting_status, reason, actor_user_id, occurred_at)
                    values (:tenant, :event_id, :order_id, :action_type,
                      :previous_status, :resulting_status, :reason, :actor, :occurred_at)
                    """).param("tenant", identity.tenantId()).param("event_id", UUID.randomUUID())
                    .param("order_id", orderId)
                    .param("action_type", action == ControlAction.STOP ? "STOP_REQUESTED" : "CANCELLED")
                    .param("previous_status", order.status()).param("resulting_status", resultingStatus)
                    .param("reason", request.reason().trim()).param("actor", identity.userId())
                    .param("occurred_at", occurredAt).update();
            appendEvidence(
                    identity, request.patientId(), orderId, orderVersion,
                    action == ControlAction.STOP ? "ORDER_STOP_REQUESTED" : "ORDER_CANCELLED",
                    action == ControlAction.STOP ? "ClinicalOrderStopRequested" : "ClinicalOrderCancelled");
            completeCommand(identity, scope, idempotencyKey, 200, orderId);
            return snapshot(identity.tenantId(), orderId, request.patientId(), request.encounterId(), request.facilityId());
        });
    }

    public OrderExecutionTaskWire recordExecution(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID executionTaskId,
            OrderExecutionEventCreateRequestWire request) {
        if (request.expectedTaskRowVersion() == null || request.performedQuantity() == null
                || request.performedQuantity() <= 0 || request.quantityUnit() == null
                || request.quantityUnit().isBlank()) {
            throw new OrderException("EXECUTION_EVENT_INVALID", 400, "Positive quantity, unit and task version are required");
        }
        return transactions.execute(status -> {
            LockedTask task = lockTask(
                    identity.tenantId(), executionTaskId, request.patientId(), request.encounterId(), request.facilityId());
            requireExecutionRole(identity, request.facilityId(), task.itemType());
            if (task.rowVersion() != request.expectedTaskRowVersion()) {
                throw new OrderException("EXECUTION_VERSION_CONFLICT", 409, "The execution task changed; reload before recording");
            }
            if (List.of("COMPLETED", "REFUSED", "CANCELLED").contains(task.taskState())) {
                throw new OrderException("EXECUTION_STATE_INVALID", 409, "The execution task is already terminal");
            }
            if ("STOPPING".equals(task.orderStatus()) && "PARTIAL".equals(request.eventType().name())) {
                throw new OrderException(
                        "EXECUTION_STOPPING_REQUIRES_TERMINAL_EVENT", 409,
                        "A stopping order only accepts completion of an in-flight execution");
            }
            if (!task.quantityUnit().equals(request.quantityUnit().trim())) {
                throw new OrderException("EXECUTION_UNIT_MISMATCH", 409, "Execution quantity unit does not match the order item");
            }
            BigDecimal delta = decimal(request.performedQuantity());
            BigDecimal cumulative = task.performedQuantity().add(delta);
            if (cumulative.compareTo(task.requestedQuantity()) > 0) {
                throw new OrderException("EXECUTION_QUANTITY_EXCEEDED", 409, "Performed quantity exceeds the signed order");
            }
            String eventType = request.eventType().name();
            if ("PARTIAL".equals(eventType) && cumulative.compareTo(task.requestedQuantity()) >= 0) {
                throw new OrderException("EXECUTION_EVENT_STATE_MISMATCH", 409, "A partial event must leave quantity outstanding");
            }
            if ("COMPLETED".equals(eventType) && cumulative.compareTo(task.requestedQuantity()) != 0) {
                throw new OrderException("EXECUTION_EVENT_STATE_MISMATCH", 409, "Completion requires the full signed quantity");
            }
            String requestHash = sha256(executionTaskId + "|" + request.expectedTaskRowVersion() + "|"
                    + eventType + "|" + delta + "|" + request.quantityUnit().trim());
            beginCommand(identity, "ORDER_EXECUTION_EVENT", idempotencyKey, requestHash);
            UUID eventId = UUID.randomUUID();
            jdbc.sql("""
                    insert into order_execution_event(
                      tenant_id, execution_event_id, execution_task_id, order_id, order_item_id,
                      patient_id, encounter_id, event_type, performed_quantity, quantity_unit,
                      note, actor_user_id)
                    values (:tenant, :event_id, :task_id, :order_id, :item_id,
                      :patient, :encounter, :event_type, :quantity, :unit, :note, :actor)
                    """).param("tenant", identity.tenantId()).param("event_id", eventId)
                    .param("task_id", executionTaskId).param("order_id", task.orderId())
                    .param("item_id", task.orderItemId()).param("patient", request.patientId())
                    .param("encounter", request.encounterId()).param("event_type", eventType)
                    .param("quantity", delta).param("unit", request.quantityUnit().trim())
                    .param("note", blankToNull(request.note())).param("actor", identity.userId()).update();
            int taskUpdated = jdbc.sql("""
                    update order_execution_task
                    set task_state = :task_state, performed_quantity = :performed,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and execution_task_id = :task_id
                      and row_version = :expected
                    """).param("task_state", eventType).param("performed", cumulative)
                    .param("tenant", identity.tenantId()).param("task_id", executionTaskId)
                    .param("expected", request.expectedTaskRowVersion()).update();
            if (taskUpdated != 1) {
                throw new OrderException("EXECUTION_VERSION_CONFLICT", 409, "The execution task changed; reload before recording");
            }
            syncClinicalTaskFromExecution(identity, executionTaskId, eventType);
            jdbc.sql("""
                    update clinical_order_item set item_state = :item_state,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and order_item_id = :item_id
                    """).param("item_state", "COMPLETED".equals(eventType) ? "COMPLETED" : "IN_PROGRESS")
                    .param("tenant", identity.tenantId()).param("item_id", task.orderItemId()).update();
            long remaining = jdbc.sql("""
                    select count(*) from order_execution_task
                    where tenant_id = :tenant and order_id = :order_id
                      and task_state not in ('COMPLETED', 'CANCELLED', 'REFUSED')
                    """).param("tenant", identity.tenantId()).param("order_id", task.orderId())
                    .query(Long.class).single();
            String orderState = "STOPPING".equals(task.orderStatus())
                    ? remaining == 0 ? "STOPPED" : "STOPPING"
                    : remaining == 0 ? "COMPLETED" : "IN_PROGRESS";
            long orderVersion = jdbc.sql("""
                    update clinical_order set status = :order_state,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and order_id = :order_id
                      and status in ('ACTIVE', 'IN_PROGRESS', 'STOPPING')
                    returning row_version
                    """).param("order_state", orderState).param("tenant", identity.tenantId())
                    .param("order_id", task.orderId()).query(Long.class).single();
            if (!"MEDICATION".equals(task.itemType())) {
                billing.createAutomaticCharge(identity, request.patientId(), request.encounterId(), request.facilityId(),
                        task.catalogCode(), delta, task.quantityUnit(), "ORDER_EXECUTION", eventId);
            }
            appendEvidence(
                    identity, request.patientId(), task.orderId(), orderVersion,
                    "ORDER_EXECUTION_" + eventType, "OrderExecution" + title(eventType));
            completeCommand(identity, "ORDER_EXECUTION_EVENT", idempotencyKey, 200, eventId);
            return taskSnapshot(identity.tenantId(), executionTaskId);
        });
    }

    private void createClinicalTasks(
            ClinicalIdentity identity, UUID orderId, UUID patientId, UUID encounterId, UUID facilityId) {
        List<UUID> taskIds = jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level,
                  state, business_state, due_at, source_route)
                select execution.tenant_id, gen_random_uuid(), :patient, :encounter, :facility,
                  'ORDER_EXECUTION', execution.execution_task_id, item.item_type || '_EXECUTION',
                  item.display_name,
                  case when item.item_type = 'MEDICATION' then 'HIGH' else 'ROUTINE' end,
                  'PENDING', execution.task_state,
                  now() + case when item.item_type = 'MEDICATION' then interval '30 minutes'
                    else interval '2 hours' end,
                  case encounter.encounter_type
                    when 'INPATIENT' then '#/ip-orders'
                    when 'EMERGENCY' then '#/emergency-orders'
                    else '#/opd-orders' end
                from order_execution_task execution
                join clinical_order_item item on item.tenant_id = execution.tenant_id
                  and item.order_item_id = execution.order_item_id
                join encounter encounter on encounter.tenant_id = execution.tenant_id
                  and encounter.encounter_id = execution.encounter_id
                where execution.tenant_id = :tenant and execution.order_id = :order_id
                on conflict (tenant_id, source_type, source_id, task_type) do nothing
                returning task_id
                """).param("patient", patientId).param("encounter", encounterId).param("facility", facilityId)
                .param("tenant", identity.tenantId()).param("order_id", orderId).query(UUID.class).list();
        String careDomain = jdbc.sql("""
                select encounter_type from encounter where tenant_id = :tenant and encounter_id = :encounter
                """).param("tenant", identity.tenantId()).param("encounter", encounterId)
                .query(String.class).single();
        for (UUID taskId : taskIds) {
            TaskRuleBasis basis = jdbc.sql("""
                    select task_type, risk_level from clinical_task
                    where tenant_id = :tenant and task_id = :task
                    """).param("tenant", identity.tenantId()).param("task", taskId)
                    .query((rs, row) -> new TaskRuleBasis(
                            rs.getString("task_type"), rs.getString("risk_level"))).single();
            Duration defaultDue = "HIGH".equals(basis.riskLevel())
                    ? Duration.ofMinutes(30) : Duration.ofHours(2);
            ClinicalTaskRuleResolver.ResolvedTaskRule rule = taskRules.resolve(
                    identity.tenantId(), basis.taskType(), careDomain, basis.riskLevel(), defaultDue);
            jdbc.sql("""
                    update clinical_task set risk_level = :risk, due_at = :due_at,
                      task_rule_config_id = :rule_config, task_rule_version = :rule_version,
                      rule_snapshot = cast(:snapshot as jsonb), escalation_at = :escalation_at,
                      updated_at = now()
                    where tenant_id = :tenant and task_id = :task
                    """).param("risk", rule.riskLevel()).param("due_at", rule.dueAt())
                    .param("rule_config", rule.configId()).param("rule_version", rule.configVersion())
                    .param("snapshot", rule.snapshotJson()).param("escalation_at", rule.escalationAt())
                    .param("tenant", identity.tenantId()).param("task", taskId).update();
            jdbc.sql("""
                    insert into clinical_task_event(
                      tenant_id, task_event_id, task_id, event_type,
                      previous_state, resulting_state, actor_user_id)
                    values (:tenant, gen_random_uuid(), :task_id, 'CREATED', null, 'PENDING', :actor)
                    """).param("tenant", identity.tenantId()).param("task_id", taskId)
                    .param("actor", identity.userId()).update();
            appendTaskOutbox(identity.tenantId(), taskId, 1, "ClinicalTaskCreated", orderId);
        }
    }

    private record TaskRuleBasis(String taskType, String riskLevel) {}

    private void syncClinicalTaskFromExecution(
            ClinicalIdentity identity, UUID executionTaskId, String businessState) {
        TaskProjection task = jdbc.sql("""
                select task_id, state, row_version from clinical_task
                where tenant_id = :tenant and source_type = 'ORDER_EXECUTION'
                  and source_id = :source_id and task_type like '%_EXECUTION'
                for update
                """).param("tenant", identity.tenantId()).param("source_id", executionTaskId)
                .query((rs, row) -> new TaskProjection(
                        rs.getObject("task_id", UUID.class), rs.getString("state"), rs.getLong("row_version")))
                .optional().orElse(null);
        if (task == null) return;
        String nextState = "COMPLETED".equals(businessState) ? "COMPLETED" : "IN_PROGRESS";
        long nextVersion = jdbc.sql("""
                update clinical_task set state = :state, business_state = :business_state,
                  claimed_by = coalesce(claimed_by, :actor), row_version = row_version + 1,
                  updated_at = now()
                where tenant_id = :tenant and task_id = :task_id and row_version = :expected
                returning row_version
                """).param("state", nextState).param("business_state", businessState)
                .param("actor", identity.userId()).param("tenant", identity.tenantId())
                .param("task_id", task.taskId()).param("expected", task.rowVersion())
                .query(Long.class).single();
        String eventType = "COMPLETED".equals(businessState) ? "SOURCE_COMPLETED" : "STARTED";
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type,
                  previous_state, resulting_state, actor_user_id)
                values (:tenant, gen_random_uuid(), :task_id, :event_type,
                  :previous, :resulting, :actor)
                """).param("tenant", identity.tenantId()).param("task_id", task.taskId())
                .param("event_type", eventType).param("previous", task.state())
                .param("resulting", nextState).param("actor", identity.userId()).update();
        appendTaskOutbox(
                identity.tenantId(), task.taskId(), nextVersion,
                "COMPLETED".equals(businessState) ? "ClinicalTaskSourceCompleted" : "ClinicalTaskSourceStarted",
                executionTaskId);
    }

    private void withdrawCancelledClinicalTasks(ClinicalIdentity identity, UUID orderId) {
        List<TaskProjection> tasks = jdbc.sql("""
                select task.task_id, task.state, task.row_version
                from clinical_task task
                join order_execution_task execution on execution.tenant_id = task.tenant_id
                  and execution.execution_task_id = task.source_id
                where task.tenant_id = :tenant and task.source_type = 'ORDER_EXECUTION'
                  and execution.order_id = :order_id and execution.task_state = 'CANCELLED'
                  and task.state not in ('COMPLETED', 'WITHDRAWN', 'EXPIRED')
                for update of task
                """).param("tenant", identity.tenantId()).param("order_id", orderId)
                .query((rs, row) -> new TaskProjection(
                        rs.getObject("task_id", UUID.class), rs.getString("state"), rs.getLong("row_version")))
                .list();
        for (TaskProjection task : tasks) {
            long nextVersion = jdbc.sql("""
                    update clinical_task set state = 'WITHDRAWN', business_state = 'CANCELLED',
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and task_id = :task_id and row_version = :expected
                    returning row_version
                    """).param("tenant", identity.tenantId()).param("task_id", task.taskId())
                    .param("expected", task.rowVersion()).query(Long.class).single();
            jdbc.sql("""
                    insert into clinical_task_event(
                      tenant_id, task_event_id, task_id, event_type,
                      previous_state, resulting_state, actor_user_id)
                    values (:tenant, gen_random_uuid(), :task_id, 'SOURCE_WITHDRAWN',
                      :previous, 'WITHDRAWN', :actor)
                    """).param("tenant", identity.tenantId()).param("task_id", task.taskId())
                    .param("previous", task.state()).param("actor", identity.userId()).update();
            appendTaskOutbox(
                    identity.tenantId(), task.taskId(), nextVersion,
                    "ClinicalTaskSourceWithdrawn", orderId);
        }
    }

    private void appendTaskOutbox(
            UUID tenantId, UUID taskId, long taskVersion, String eventType, UUID sourceId) {
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, gen_random_uuid(), 'CLINICAL_TASK', :task_id, :task_version,
                  :event_type, 1, jsonb_build_object('task_id', :task_id, 'source_id', :source_id))
                """).param("tenant", tenantId).param("task_id", taskId).param("task_version", taskVersion)
                .param("event_type", eventType).param("source_id", sourceId).update();
    }

    private void validateCreate(ClinicalOrderCreateRequestWire request) {
        if (request.clinicalIndication() == null || request.clinicalIndication().isBlank()
                || request.clinicalIndication().length() > 1000
                || request.items() == null || request.items().isEmpty() || request.items().size() > 100) {
            throw new OrderException("ORDER_REQUEST_INVALID", 400, "Indication and one to one hundred order items are required");
        }
        Set<String> codes = new HashSet<>();
        for (ClinicalOrderItemCreateRequestWire item : request.items()) {
            if (item.itemType() == null
                    || item.catalogCode() == null || item.catalogCode().isBlank() || item.catalogCode().length() > 128
                    || item.displayName() == null || item.displayName().isBlank() || item.displayName().length() > 256
                    || item.requestedQuantity() == null || item.requestedQuantity() <= 0
                    || item.quantityUnit() == null || item.quantityUnit().isBlank()
                    || !codes.add(item.catalogCode().trim())) {
                throw new OrderException("ORDER_ITEM_INVALID", 400, "Order items require unique codes and positive quantities");
            }
            if ("MEDICATION".equals(item.itemType().name())
                    && (item.doseValue() == null || item.doseValue() <= 0
                    || item.doseUnit() == null || item.doseUnit().isBlank() || item.doseUnit().length() > 64
                    || item.routeCode() == null || item.routeCode().isBlank() || item.routeCode().length() > 64
                    || item.frequencyCode() == null || item.frequencyCode().isBlank()
                    || item.frequencyCode().length() > 64)) {
                throw new OrderException(
                        "MEDICATION_DIRECTIONS_REQUIRED", 400,
                        "Medication orders require a positive single dose, dose unit, route and frequency");
            }
        }
    }

    private void validateSafetyRequest(LockedOrder order, long expectedRowVersion, String ruleWatermark) {
        if (order.rowVersion() != expectedRowVersion) {
            throw new OrderException("ORDER_VERSION_CONFLICT", 409, "The order changed before safety evaluation");
        }
        if (!"DRAFT".equals(order.status())) {
            throw new OrderException("ORDER_STATE_INVALID", 409, "Only a draft order can be evaluated for signing");
        }
        if (!ACTIVE_RULE_WATERMARK.equals(ruleWatermark)) {
            throw new OrderException("RULE_WATERMARK_STALE", 409, "Reload the active deterministic order rules");
        }
    }

    private MedicationCatalog requireActiveMedication(UUID tenantId, String catalogCode) {
        return jdbc.sql("""
                select medication_catalog_version_id, drug_code, ingredient_code,
                  minimum_single_dose, maximum_single_dose, dose_unit, release_version
                from medication_catalog_version
                where tenant_id = :tenant and catalog_code = :catalog and status = 'ACTIVE'
                  and effective_from <= current_date
                  and (effective_to is null or effective_to >= current_date)
                """).param("tenant", tenantId).param("catalog", catalogCode)
                .query((rs, row) -> new MedicationCatalog(
                        rs.getObject("medication_catalog_version_id", UUID.class),
                        rs.getString("drug_code"), rs.getString("ingredient_code"),
                        rs.getBigDecimal("minimum_single_dose"), rs.getBigDecimal("maximum_single_dose"),
                        rs.getString("dose_unit"), rs.getString("release_version")))
                .optional().orElseThrow(() -> new OrderException(
                        "MEDICATION_CATALOG_NOT_ACTIVE", 409,
                        "The medication catalog entry is missing, retired or outside its effective period"));
    }

    private MedicationSafetyEvaluationWire persistSafetyEvaluation(
            ClinicalIdentity identity, String idempotencyKey, UUID orderId, UUID patientId, UUID encounterId,
            UUID facilityId, long orderRowVersion) {
        List<SafetyFindingDraft> drafts = evaluateSafety(identity.tenantId(), orderId, patientId, encounterId);
        appendPublishedMedicationRuleFindings(
                identity, idempotencyKey, orderId, patientId, encounterId, facilityId, drafts);
        long blockingCount = drafts.stream().filter(d -> "BLOCKING".equals(d.severity())).count();
        boolean passed = blockingCount == 0;
        UUID evaluationId = UUID.randomUUID();
        OffsetDateTime evaluatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                insert into medication_safety_evaluation(
                  tenant_id, evaluation_id, order_id, patient_id, encounter_id,
                  evaluated_order_row_version, rule_watermark, passed, blocking_count,
                  evaluated_by, evaluated_at)
                values (:tenant, :evaluation, :order_id, :patient, :encounter,
                  :order_version, :watermark, :passed, :blocking_count, :actor, :evaluated_at)
                """).param("tenant", identity.tenantId()).param("evaluation", evaluationId)
                .param("order_id", orderId).param("patient", patientId).param("encounter", encounterId)
                .param("order_version", orderRowVersion).param("watermark", ACTIVE_RULE_WATERMARK)
                .param("passed", passed).param("blocking_count", blockingCount)
                .param("actor", identity.userId()).param("evaluated_at", evaluatedAt).update();
        List<MedicationSafetyFindingWire> findings = new ArrayList<>();
        for (SafetyFindingDraft draft : drafts) {
            UUID findingId = UUID.randomUUID();
            jdbc.sql("""
                    insert into medication_safety_finding(
                      tenant_id, finding_id, evaluation_id, order_item_id, code, severity,
                      title, detail, evidence_source, override_allowed)
                    values (:tenant, :finding, :evaluation, :item, :code, :severity,
                      :title, :detail, :evidence, false)
                    """).param("tenant", identity.tenantId()).param("finding", findingId)
                    .param("evaluation", evaluationId).param("item", draft.orderItemId())
                    .param("code", draft.code()).param("severity", draft.severity())
                    .param("title", draft.title())
                    .param("detail", draft.detail()).param("evidence", draft.evidenceSource()).update();
            findings.add(new MedicationSafetyFindingWire(
                    findingId, draft.orderItemId(), draft.code(),
                    MedicationSafetyFindingWire.SeverityValue.valueOf(draft.severity()),
                    draft.title(), draft.detail(), draft.evidenceSource(), false));
        }
        appendSafetyEvidence(identity, patientId, evaluationId, orderId, orderRowVersion, passed);
        return new MedicationSafetyEvaluationWire(
                evaluationId, orderId, orderRowVersion, ACTIVE_RULE_WATERMARK,
                passed, (int) blockingCount, evaluatedAt.toInstant(), findings);
    }

    /**
     * Published RULE configuration is an additive clinical gate.  The catalog, allergy and dose
     * rules above remain authoritative baseline protections; a local configuration can only add
     * a blocking decision backed by the same immutable evaluation evidence.
     */
    private void appendPublishedMedicationRuleFindings(
            ClinicalIdentity identity, String idempotencyKey, UUID orderId, UUID patientId, UUID encounterId,
            UUID facilityId, List<SafetyFindingDraft> drafts) {
        UUID orderItemId = firstMedicationItem(identity.tenantId(), orderId);
        if (orderItemId == null) return;
        Map<String, Object> facts = medicationRuleFacts(drafts, identity.tenantId(), orderId, patientId, encounterId);
        String runtimeKey = "medication-rule-" + sha256(orderId + "|" + idempotencyKey).substring(0, 32);
        var evaluation = configurationRuntime.evaluateRulesIfActive(identity,
                new RuntimeContext(organizationForFacility(identity.tenantId(), facilityId), facilityId, patientId, encounterId),
                runtimeKey, "runtime-rule-medication-safety-v1",
                new RuntimeCommandRequest("ENCOUNTER", encounterId, facts));
        if (evaluation.isEmpty()) return;
        RuntimeExecutionWire execution = evaluation.get();
        Object rawDecisions = execution.outputPayload().get("decisions");
        if (!(rawDecisions instanceof List<?> decisions)) return;
        for (Object raw : decisions) {
            if (!(raw instanceof Map<?, ?> decision) || !"BLOCK".equals(String.valueOf(decision.get("decision")))) continue;
            String ruleId = String.valueOf(decision.get("rule_id"));
            String name = String.valueOf(decision.get("name"));
            String action = String.valueOf(decision.get("action"));
            drafts.add(new SafetyFindingDraft(orderItemId, "CONFIG_RULE_"
                    + ruleId.toUpperCase().replaceAll("[^A-Z0-9_]", "_"), "BLOCKING",
                    name, action, "CONFIG_RUNTIME:" + execution.executionId()));
        }
    }

    private Map<String, Object> medicationRuleFacts(
            List<SafetyFindingDraft> drafts, UUID tenantId, UUID orderId, UUID patientId, UUID encounterId) {
        Set<String> codes = new HashSet<>();
        for (SafetyFindingDraft draft : drafts) codes.add(draft.code());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("med-allergy", codes.contains("ACTIVE_INGREDIENT_ALLERGY"));
        facts.put("med-duplicate", codes.contains("ACTIVE_INGREDIENT_DUPLICATE"));
        facts.put("med-renal", codes.contains("RENAL_IMPAIRMENT_CONTRAINDICATION"));
        facts.put("med-pediatric", codes.stream().anyMatch(code -> code.startsWith("PEDIATRIC_")
                || code.startsWith("DOSE_PER_KG_")));
        facts.put("med-high-alert", codes.contains("RESTRICTED_MEDICATION_AUTHORIZATION_REQUIRED"));
        facts.put("order", Map.of("medication_count", medicationItemCount(tenantId, orderId)));
        facts.put("patient", Map.of("id", patientId.toString()));
        facts.put("encounter", Map.of("id", encounterId.toString()));
        return facts;
    }

    private UUID firstMedicationItem(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                select order_item_id from clinical_order_item
                where tenant_id = :tenant and order_id = :order and item_type = 'MEDICATION'
                order by created_at, order_item_id limit 1
                """).param("tenant", tenantId).param("order", orderId).query(UUID.class).optional().orElse(null);
    }

    private long medicationItemCount(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                select count(*) from clinical_order_item
                where tenant_id = :tenant and order_id = :order and item_type = 'MEDICATION'
                """).param("tenant", tenantId).param("order", orderId).query(Long.class).single();
    }

    private UUID organizationForFacility(UUID tenantId, UUID facilityId) {
        return jdbc.sql("""
                select organization_id from facility where tenant_id = :tenant and facility_id = :facility
                """).param("tenant", tenantId).param("facility", facilityId).query(UUID.class).optional()
                .orElseThrow(OrderService::contextDenied);
    }

    private List<SafetyFindingDraft> evaluateSafety(
            UUID tenantId, UUID orderId, UUID patientId, UUID encounterId) {
        List<MedicationItem> medications = jdbc.sql("""
                select item.order_item_id, item.ingredient_code, item.dose_value, item.dose_unit,
                  catalog.drug_code, catalog.minimum_single_dose, catalog.maximum_single_dose,
                  catalog.dose_unit as catalog_dose_unit, catalog.release_version,
                  catalog.medication_catalog_version_id, catalog.prescribing_restriction_code,
                  catalog.weight_based, catalog.min_dose_per_kg, catalog.max_dose_per_kg,
                  catalog.renal_contraindication_stage, catalog.hepatic_contraindication_class
                from clinical_order_item item
                join medication_catalog_version catalog
                  on catalog.tenant_id = item.tenant_id
                  and catalog.medication_catalog_version_id = item.medication_catalog_version_id
                where item.tenant_id = :tenant and item.order_id = :order_id
                  and item.item_type = 'MEDICATION'
                order by item.created_at, item.order_item_id
                """).param("tenant", tenantId).param("order_id", orderId)
                .query((rs, row) -> new MedicationItem(
                        rs.getObject("order_item_id", UUID.class), rs.getString("ingredient_code"),
                        rs.getString("drug_code"), rs.getBigDecimal("dose_value"), rs.getString("dose_unit"),
                        rs.getBigDecimal("minimum_single_dose"), rs.getBigDecimal("maximum_single_dose"),
                        rs.getString("catalog_dose_unit"), rs.getString("release_version"),
                        rs.getObject("medication_catalog_version_id", UUID.class),
                        rs.getString("prescribing_restriction_code"), rs.getBoolean("weight_based"),
                        rs.getBigDecimal("min_dose_per_kg"), rs.getBigDecimal("max_dose_per_kg"),
                        rs.getString("renal_contraindication_stage"),
                        rs.getString("hepatic_contraindication_class"))).list();
        PatientSafetyProfile profile = jdbc.sql("""
                select weight_kg, renal_impairment_stage, hepatic_impairment_class
                from patient where tenant_id = :tenant and patient_id = :patient
                """).param("tenant", tenantId).param("patient", patientId)
                .query((rs, row) -> new PatientSafetyProfile(
                        rs.getBigDecimal("weight_kg"), rs.getString("renal_impairment_stage"),
                        rs.getString("hepatic_impairment_class"))).optional().orElse(null);
        BigDecimal patientWeightKg = profile == null ? null : profile.weightKg();
        List<SafetyFindingDraft> findings = new ArrayList<>();
        for (MedicationItem medication : medications) {
            UUID allergyId = jdbc.sql("""
                    select allergy_id from patient_allergy
                    where tenant_id = :tenant and patient_id = :patient
                      and substance_code = :ingredient and clinical_status = 'ACTIVE'
                      and verification_status <> 'REFUTED'
                    order by created_at desc, allergy_id limit 1
                    """).param("tenant", tenantId).param("patient", patientId)
                    .param("ingredient", medication.ingredientCode()).query(UUID.class).optional().orElse(null);
            if (allergyId != null) {
                findings.add(new SafetyFindingDraft(
                        medication.orderItemId(), "ACTIVE_INGREDIENT_ALLERGY", "BLOCKING", "活动过敏硬阻断",
                        "处方成分与患者当前活动过敏物质一致，必须先核实并处理过敏事实。",
                        "PATIENT_ALLERGY:" + allergyId));
            }
            if (!medication.catalogDoseUnit().equals(medication.doseUnit())) {
                findings.add(new SafetyFindingDraft(
                        medication.orderItemId(), "DOSE_UNIT_MISMATCH", "BLOCKING", "剂量单位不匹配",
                        "处方单次剂量单位与已发布药品目录单位不一致。",
                        "MEDICATION_CATALOG:" + medication.catalogVersionId()));
            } else if (medication.doseValue().compareTo(medication.minimumDose()) < 0) {
                findings.add(new SafetyFindingDraft(
                        medication.orderItemId(), "SINGLE_DOSE_BELOW_MINIMUM", "BLOCKING", "单次剂量低于下限",
                        "处方单次剂量低于当前规则版本允许的最小值。",
                        "MEDICATION_CATALOG:" + medication.catalogVersionId()));
            } else if (medication.doseValue().compareTo(medication.maximumDose()) > 0) {
                findings.add(new SafetyFindingDraft(
                        medication.orderItemId(), "SINGLE_DOSE_ABOVE_MAXIMUM", "BLOCKING", "单次剂量超过上限",
                        "处方单次剂量超过当前规则版本允许的最大值。",
                        "MEDICATION_CATALOG:" + medication.catalogVersionId()));
            }
            if (Boolean.TRUE.equals(medication.weightBased())) {
                if (patientWeightKg == null) {
                    findings.add(new SafetyFindingDraft(
                            medication.orderItemId(), "PEDIATRIC_WEIGHT_REQUIRED", "BLOCKING", "按体重给药缺少体重",
                            "该药品按体重给药，但患者当前无有效体重记录，无法计算每公斤剂量。",
                            "PATIENT:" + patientId));
                } else {
                    BigDecimal perKg = medication.doseValue()
                            .divide(patientWeightKg, 6, RoundingMode.HALF_UP);
                    if (perKg.compareTo(medication.minDosePerKg()) < 0) {
                        findings.add(new SafetyFindingDraft(
                                medication.orderItemId(), "DOSE_PER_KG_BELOW_MINIMUM", "BLOCKING",
                                "每公斤剂量低于下限",
                                "按体重计算的每公斤剂量低于该药品允许的最小每公斤剂量。",
                                "MEDICATION_CATALOG:" + medication.catalogVersionId()));
                    } else if (perKg.compareTo(medication.maxDosePerKg()) > 0) {
                        findings.add(new SafetyFindingDraft(
                                medication.orderItemId(), "DOSE_PER_KG_ABOVE_MAXIMUM", "BLOCKING",
                                "每公斤剂量超过上限",
                                "按体重计算的每公斤剂量超过该药品允许的最大每公斤剂量。",
                                "MEDICATION_CATALOG:" + medication.catalogVersionId()));
                    }
                }
            }
            if (medication.renalContraindicationStage() != null && profile != null
                    && profile.renalStage() != null
                    && renalRank(profile.renalStage()) >= renalRank(medication.renalContraindicationStage())) {
                findings.add(new SafetyFindingDraft(
                        medication.orderItemId(), "RENAL_IMPAIRMENT_CONTRAINDICATION", "BLOCKING",
                        "肾功能不全禁忌",
                        "该药品在患者当前肾功能分期下禁忌使用。",
                        "MEDICATION_CATALOG:" + medication.catalogVersionId()));
            }
            if (medication.hepaticContraindicationClass() != null && profile != null
                    && profile.hepaticClass() != null
                    && hepaticRank(profile.hepaticClass()) >= hepaticRank(medication.hepaticContraindicationClass())) {
                findings.add(new SafetyFindingDraft(
                        medication.orderItemId(), "HEPATIC_IMPAIRMENT_CONTRAINDICATION", "BLOCKING",
                        "肝功能不全禁忌",
                        "该药品在患者当前肝功能分级下禁忌使用。",
                        "MEDICATION_CATALOG:" + medication.catalogVersionId()));
            }
            UUID duplicateOrderId = jdbc.sql("""
                    select existing.order_id
                    from clinical_order_item existing_item
                    join clinical_order existing on existing.tenant_id = existing_item.tenant_id
                      and existing.order_id = existing_item.order_id
                    where existing_item.tenant_id = :tenant and existing.encounter_id = :encounter
                      and existing.order_id <> :order_id
                      and existing.status in ('ACTIVE', 'IN_PROGRESS')
                      and existing_item.item_type = 'MEDICATION'
                      and existing_item.ingredient_code = :ingredient
                    order by existing.updated_at desc, existing.order_id limit 1
                    """).param("tenant", tenantId).param("encounter", encounterId)
                    .param("order_id", orderId).param("ingredient", medication.ingredientCode())
                    .query(UUID.class).optional().orElse(null);
            if (duplicateOrderId != null) {
                findings.add(new SafetyFindingDraft(
                        medication.orderItemId(), "ACTIVE_INGREDIENT_DUPLICATE", "BLOCKING", "活动成分重复",
                        "同一次就诊已有包含相同活动成分且仍在执行的医嘱。",
                        "CLINICAL_ORDER:" + duplicateOrderId));
            }
            if (medication.prescribingRestrictionCode() != null) {
                UUID authorizationId = jdbc.sql("""
                        select authorization_id from medication_prescribing_authorization
                        where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter
                          and drug_code = :drug and status = 'ACTIVE'
                          and (valid_until is null or valid_until > now())
                        order by approved_at desc, authorization_id limit 1
                        """).param("tenant", tenantId).param("patient", patientId)
                        .param("encounter", encounterId).param("drug", medication.drugCode())
                        .query(UUID.class).optional().orElse(null);
                if (authorizationId == null) {
                    findings.add(new SafetyFindingDraft(
                            medication.orderItemId(), "RESTRICTED_MEDICATION_AUTHORIZATION_REQUIRED", "BLOCKING",
                            "受限药品需要处方授权",
                            "该药品属于受限类别（" + medication.prescribingRestrictionCode()
                                    + "），签署前必须取得有效处方授权。",
                            "MEDICATION_CATALOG:" + medication.catalogVersionId()));
                }
            }
        }
        findings.addAll(interactionFindings(tenantId, orderId, encounterId, medications));
        return findings;
    }

    private List<SafetyFindingDraft> interactionFindings(
            UUID tenantId, UUID orderId, UUID encounterId, List<MedicationItem> medications) {
        List<SafetyFindingDraft> findings = new ArrayList<>();
        List<OtherIngredient> otherIngredients = jdbc.sql("""
                select item.ingredient_code
                from clinical_order_item item
                join clinical_order existing on existing.tenant_id = item.tenant_id
                  and existing.order_id = item.order_id
                where item.tenant_id = :tenant and existing.encounter_id = :encounter
                  and existing.order_id <> :order_id
                  and existing.status in ('ACTIVE', 'IN_PROGRESS')
                  and item.item_type = 'MEDICATION'
                  and item.ingredient_code is not null
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("order_id", orderId)
                .query((rs, row) -> new OtherIngredient(rs.getString("ingredient_code"))).list();
        for (MedicationItem medication : medications) {
            for (OtherIngredient other : otherIngredients) {
                if (!medication.ingredientCode().equals(other.ingredientCode())) {
                    appendInteractionFinding(tenantId, findings, medication, medication.ingredientCode(), other.ingredientCode());
                }
            }
        }
        for (int i = 0; i < medications.size(); i++) {
            for (int j = i + 1; j < medications.size(); j++) {
                MedicationItem a = medications.get(i);
                MedicationItem b = medications.get(j);
                if (!a.ingredientCode().equals(b.ingredientCode())) {
                    appendInteractionFinding(tenantId, findings, a, a.ingredientCode(), b.ingredientCode());
                }
            }
        }
        return findings;
    }

    private void appendInteractionFinding(
            UUID tenantId, List<SafetyFindingDraft> findings, MedicationItem medication,
            String ingredientA, String ingredientB) {
        InteractionRule rule = findInteraction(tenantId, ingredientA, ingredientB);
        if (rule != null) {
            String severity = "CONTRAINDICATED".equals(rule.severity()) ? "BLOCKING" : "WARNING";
            findings.add(new SafetyFindingDraft(
                    medication.orderItemId(), "DRUG_INTERACTION", severity, rule.title(),
                    rule.detail(), "MEDICATION_INTERACTION:" + rule.interactionId()));
        }
    }

    private InteractionRule findInteraction(UUID tenantId, String ingredientA, String ingredientB) {
        return jdbc.sql("""
                select interaction_id, severity, title, detail
                from medication_interaction
                where tenant_id = :tenant
                  and status = 'ACTIVE'
                  and effective_from <= current_date
                  and (effective_to is null or effective_to >= current_date)
                  and ((ingredient_a_code = :a and ingredient_b_code = :b)
                       or (ingredient_a_code = :b and ingredient_b_code = :a))
                order by case severity when 'CONTRAINDICATED' then 0 else 1 end, created_at desc
                limit 1
                """).param("tenant", tenantId).param("a", ingredientA).param("b", ingredientB)
                .query((rs, row) -> new InteractionRule(
                        rs.getObject("interaction_id", UUID.class), rs.getString("severity"),
                        rs.getString("title"), rs.getString("detail")))
                .optional().orElse(null);
    }

    private void requireEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter where tenant_id = :tenant and encounter_id = :encounter
                  and patient_id = :patient and facility_id = :facility and status = 'IN_PROGRESS'
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void requireReadableEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter where tenant_id = :tenant and encounter_id = :encounter
                  and patient_id = :patient and facility_id = :facility
                  and status in ('PLANNED', 'ARRIVED', 'IN_PROGRESS', 'SUSPENDED', 'FINISHED')
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private LockedOrder lockOrder(UUID tenantId, UUID orderId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select status, row_version from clinical_order
                where tenant_id = :tenant and order_id = :order_id and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility for update
                """).param("tenant", tenantId).param("order_id", orderId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new LockedOrder(rs.getString("status"), rs.getLong("row_version")))
                .optional().orElseThrow(OrderService::contextDenied);
    }

    private LockedTask lockTask(
            UUID tenantId, UUID taskId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select task.order_id, task.order_item_id, item.item_type, item.catalog_code, task.task_state,
                  clinical_order.status as order_status,
                  task.requested_quantity, task.performed_quantity, task.quantity_unit, task.row_version
                from order_execution_task task
                join clinical_order clinical_order on clinical_order.tenant_id = task.tenant_id
                  and clinical_order.order_id = task.order_id
                join clinical_order_item item on item.tenant_id = task.tenant_id
                  and item.order_item_id = task.order_item_id
                where task.tenant_id = :tenant and task.execution_task_id = :task_id
                  and task.patient_id = :patient and task.encounter_id = :encounter
                  and clinical_order.facility_id = :facility
                for update of task, clinical_order
                """).param("tenant", tenantId).param("task_id", taskId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new LockedTask(
                        rs.getObject("order_id", UUID.class), rs.getObject("order_item_id", UUID.class),
                        rs.getString("item_type"), rs.getString("catalog_code"), rs.getString("task_state"), rs.getString("order_status"),
                        rs.getBigDecimal("requested_quantity"),
                        rs.getBigDecimal("performed_quantity"), rs.getString("quantity_unit"),
                        rs.getLong("row_version"))).optional().orElseThrow(OrderService::contextDenied);
    }

    private void requirePrescriberRole(ClinicalIdentity identity, UUID facilityId) {
        requireRole(identity, facilityId,
                List.of("CLINICIAN", "ATTENDING_PHYSICIAN", "CHIEF_PHYSICIAN"),
                "ORDER_PRESCRIBER_ROLE_REQUIRED");
    }

    private void requireExecutionRole(ClinicalIdentity identity, UUID facilityId, String itemType) {
        List<String> allowedRoles = switch (itemType) {
            case "LAB" -> List.of("LAB_TECHNICIAN");
            case "IMAGING" -> List.of("RADIOLOGIST");
            case "MEDICATION", "NURSING" -> List.of("REGISTERED_NURSE", "NURSE_MANAGER");
            case "TREATMENT", "DIET", "OTHER" ->
                    List.of("CLINICIAN", "ATTENDING_PHYSICIAN", "CHIEF_PHYSICIAN",
                            "REGISTERED_NURSE", "NURSE_MANAGER");
            default -> List.of();
        };
        requireRole(identity, facilityId, allowedRoles, "ORDER_EXECUTION_ROLE_REQUIRED");
    }

    private void requireRole(
            ClinicalIdentity identity, UUID facilityId, List<String> allowedRoles, String failureCode) {
        if (identity.roleAssignmentIds().isEmpty() || allowedRoles.isEmpty()) {
            throw new OrderException(failureCode, 403,
                    "The active role assignment is not permitted to perform this order workflow action");
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
            throw new OrderException(failureCode, 403,
                    "The active role assignment is not permitted to perform this order workflow action");
        }
    }

    private void rejectActiveDuplicates(UUID tenantId, UUID orderId, UUID encounterId) {
        long duplicate = jdbc.sql("""
                select count(*) from clinical_order_item candidate
                join clinical_order existing on existing.tenant_id = candidate.tenant_id
                  and existing.order_id = candidate.order_id
                where candidate.tenant_id = :tenant and existing.encounter_id = :encounter
                  and existing.order_id <> :order_id
                  and existing.status in ('ACTIVE', 'IN_PROGRESS')
                  and exists (
                    select 1 from clinical_order_item incoming
                    where incoming.tenant_id = candidate.tenant_id and incoming.order_id = :order_id
                      and incoming.catalog_code = candidate.catalog_code)
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("order_id", orderId).query(Long.class).single();
        if (duplicate > 0) {
            throw new OrderException("ORDER_DUPLICATE", 409, "An active order already contains the same catalog item");
        }
    }

    private ClinicalOrderWire snapshot(
            UUID tenantId, UUID orderId, UUID patientId, UUID encounterId, UUID facilityId) {
        OrderHead head = jdbc.sql("""
                select patient_id, encounter_id, order_scope, status, clinical_indication, row_version
                from clinical_order where tenant_id = :tenant and order_id = :order_id
                  and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                """).param("tenant", tenantId).param("order_id", orderId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new OrderHead(
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getString("order_scope"), rs.getString("status"),
                        rs.getString("clinical_indication"), rs.getLong("row_version")))
                .optional().orElseThrow(OrderService::contextDenied);
        List<ClinicalOrderItemWire> items = jdbc.sql("""
                select order_item_id, item_type, catalog_code, display_name, requested_quantity,
                  quantity_unit, medication_catalog_version_id, drug_code, ingredient_code,
                  dose_value, dose_unit, route_code, frequency_code,
                  instructions, item_state, row_version
                from clinical_order_item where tenant_id = :tenant and order_id = :order_id
                order by created_at, order_item_id
                """).param("tenant", tenantId).param("order_id", orderId)
                .query((rs, row) -> new ClinicalOrderItemWire(
                        rs.getObject("order_item_id", UUID.class),
                        ClinicalOrderItemWire.ItemTypeValue.valueOf(rs.getString("item_type")),
                        rs.getString("catalog_code"), rs.getString("display_name"),
                        rs.getBigDecimal("requested_quantity").doubleValue(), rs.getString("quantity_unit"),
                        rs.getObject("medication_catalog_version_id", UUID.class),
                        rs.getString("drug_code"), rs.getString("ingredient_code"),
                        nullableDouble(rs.getBigDecimal("dose_value")), rs.getString("dose_unit"),
                        rs.getString("route_code"), rs.getString("frequency_code"),
                        rs.getString("instructions"),
                        ClinicalOrderItemWire.ItemStateValue.valueOf(rs.getString("item_state")),
                        rs.getLong("row_version"))).list();
        List<OrderExecutionTaskWire> tasks = jdbc.sql("""
                select execution_task_id, order_id, order_item_id, task_state,
                  requested_quantity, performed_quantity, quantity_unit, row_version
                from order_execution_task where tenant_id = :tenant and order_id = :order_id
                order by created_at, execution_task_id
                """).param("tenant", tenantId).param("order_id", orderId)
                .query((rs, row) -> toWire(rs.getObject("execution_task_id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getObject("order_item_id", UUID.class),
                        rs.getString("task_state"), rs.getBigDecimal("requested_quantity"),
                        rs.getBigDecimal("performed_quantity"), rs.getString("quantity_unit"),
                        rs.getLong("row_version"))).list();
        String watermark = sha256(orderId + "|" + head.status() + "|" + head.rowVersion() + "|"
                + items.size() + "|" + tasks.stream().map(task -> task.taskState() + ":" + task.rowVersion()).toList());
        return new ClinicalOrderWire(
                orderId, head.patientId(), head.encounterId(),
                ClinicalOrderWire.OrderScopeValue.valueOf(head.orderScope()),
                ClinicalOrderWire.StatusValue.valueOf(head.status()), head.clinicalIndication(),
                items, tasks, head.rowVersion(), watermark);
    }

    private OrderExecutionTaskWire taskSnapshot(UUID tenantId, UUID taskId) {
        return jdbc.sql("""
                select execution_task_id, order_id, order_item_id, task_state,
                  requested_quantity, performed_quantity, quantity_unit, row_version
                from order_execution_task where tenant_id = :tenant and execution_task_id = :task_id
                """).param("tenant", tenantId).param("task_id", taskId)
                .query((rs, row) -> toWire(
                        rs.getObject("execution_task_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getObject("order_item_id", UUID.class), rs.getString("task_state"),
                        rs.getBigDecimal("requested_quantity"), rs.getBigDecimal("performed_quantity"),
                        rs.getString("quantity_unit"), rs.getLong("row_version"))).single();
    }

    private static OrderExecutionTaskWire toWire(
            UUID taskId, UUID orderId, UUID itemId, String state,
            BigDecimal requested, BigDecimal performed, String unit, long rowVersion) {
        return new OrderExecutionTaskWire(
                taskId, orderId, itemId, OrderExecutionTaskWire.TaskStateValue.valueOf(state),
                requested.doubleValue(), performed.doubleValue(), unit, rowVersion);
    }

    private void beginCommand(
            ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new OrderException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw new OrderException("IDEMPOTENCY_REPLAY", 409, "This order command key was already used");
    }

    private void completeCommand(
            ClinicalIdentity identity, String scope, String key, int responseStatus, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :response_status,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("response_status", responseStatus).param("resource", resourceId)
                .param("tenant", identity.tenantId()).param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity,
            UUID patientId,
            UUID orderId,
            long aggregateVersion,
            String actionCode,
            String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + actionCode + "|"
                + orderId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CLINICAL_ORDER', :order_id,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", actionCode).param("order_id", orderId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId)).param("trace", trace)
                .param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event_id, 'CLINICAL_ORDER', :order_id, :aggregate_version,
                  :event_type, 1, jsonb_build_object('order_id', :order_id))
                """).param("tenant", identity.tenantId()).param("event_id", UUID.randomUUID())
                .param("order_id", orderId).param("aggregate_version", aggregateVersion)
                .param("event_type", eventType).update();
    }

    private void appendSafetyEvidence(
            ClinicalIdentity identity,
            UUID patientId,
            UUID evaluationId,
            UUID orderId,
            long orderRowVersion,
            boolean passed) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String action = passed ? "MEDICATION_SAFETY_PASSED" : "MEDICATION_SAFETY_BLOCKED";
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + evaluationId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'MEDICATION_SAFETY_EVALUATION', :evaluation,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("evaluation", evaluationId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId)).param("trace", trace)
                .param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event_id, 'MEDICATION_SAFETY_EVALUATION', :evaluation, 1,
                  :event_type, 1, jsonb_build_object(
                    'evaluation_id', :evaluation, 'order_id', :order_id,
                    'evaluated_order_row_version', :order_version, 'passed', :passed))
                """).param("tenant", identity.tenantId()).param("event_id", UUID.randomUUID())
                .param("evaluation", evaluationId).param("event_type", passed
                        ? "MedicationSafetyPassed" : "MedicationSafetyBlocked")
                .param("order_id", orderId).param("order_version", orderRowVersion)
                .param("passed", passed).update();
    }

    private static BigDecimal decimal(Double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros();
    }

    private static Double nullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String title(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static OrderException contextDenied() {
        return new OrderException("CONTEXT_NOT_PERMITTED", 403, "The requested order context is not permitted");
    }

    private static int renalRank(String stage) {
        return switch (stage) {
            case "MILD" -> 1;
            case "MODERATE" -> 2;
            case "SEVERE" -> 3;
            default -> 0;
        };
    }

    private static int hepaticRank(String childPughClass) {
        return switch (childPughClass) {
            case "A" -> 1;
            case "B" -> 2;
            case "C" -> 3;
            default -> 0;
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record LockedOrder(String status, long rowVersion) {}
    private record LockedTask(
            UUID orderId, UUID orderItemId, String itemType, String catalogCode, String taskState, String orderStatus,
            BigDecimal requestedQuantity, BigDecimal performedQuantity,
            String quantityUnit, long rowVersion) {}
    private record OrderHead(
            UUID patientId, UUID encounterId, String orderScope,
            String status, String clinicalIndication, long rowVersion) {}
    private record MedicationCatalog(
            UUID versionId, String drugCode, String ingredientCode,
            BigDecimal minimumDose, BigDecimal maximumDose, String doseUnit, String releaseVersion) {}
    private record MedicationItem(
            UUID orderItemId, String ingredientCode, String drugCode, BigDecimal doseValue, String doseUnit,
            BigDecimal minimumDose, BigDecimal maximumDose, String catalogDoseUnit,
            String releaseVersion, UUID catalogVersionId, String prescribingRestrictionCode,
            Boolean weightBased, BigDecimal minDosePerKg, BigDecimal maxDosePerKg,
            String renalContraindicationStage, String hepaticContraindicationClass) {}
    private record PatientSafetyProfile(BigDecimal weightKg, String renalStage, String hepaticClass) {}
    private record SafetyFindingDraft(
            UUID orderItemId, String code, String severity, String title, String detail, String evidenceSource) {}
    private record OtherIngredient(String ingredientCode) {}
    private record InteractionRule(UUID interactionId, String severity, String title, String detail) {}
    private record TaskProjection(UUID taskId, String state, long rowVersion) {}
    private enum ControlAction { STOP, CANCEL }
}
