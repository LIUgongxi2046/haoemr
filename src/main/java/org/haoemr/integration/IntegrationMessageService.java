package org.haoemr.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.IntegrationMessageCollectRequestWire;
import org.haoemr.contracts.IntegrationMessageCollectResultWire;
import org.haoemr.contracts.IntegrationMessageReconcileRequestWire;
import org.haoemr.contracts.IntegrationMessageWire;
import org.haoemr.contracts.IntegrationReconciliationWire;
import org.haoemr.contracts.MockInvocationResultWire;
import org.haoemr.mock.MockInterfaceService;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class IntegrationMessageService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final MockInterfaceService mocks;

    IntegrationMessageService(
            JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper,
            MockInterfaceService mocks) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.mocks = mocks;
    }

    List<IntegrationMessageWire> listMessages(ClinicalIdentity identity, String connectorCode, String status) {
        StringBuilder sql = new StringBuilder("""
                select message_id from integration_message where tenant_id = :tenant
                """);
        if (connectorCode != null && !connectorCode.isBlank()) sql.append(" and connector_code = :connector");
        if (status != null && !status.isBlank()) sql.append(" and message_status = :status");
        sql.append(" order by occurred_at desc, message_id desc limit 500");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (connectorCode != null && !connectorCode.isBlank()) spec = spec.param("connector", connectorCode.trim());
        if (status != null && !status.isBlank()) spec = spec.param("status", status.trim());
        List<UUID> ids = spec.query(UUID.class).list();
        return ids.stream().map(id -> message(identity.tenantId(), id)).toList();
    }

    List<IntegrationReconciliationWire> listReconciliations(ClinicalIdentity identity, String connectorCode) {
        StringBuilder sql = new StringBuilder("""
                select reconciliation_id from integration_reconciliation where tenant_id = :tenant
                """);
        if (connectorCode != null && !connectorCode.isBlank()) sql.append(" and connector_code = :connector");
        sql.append(" order by window_end desc, reconciliation_id desc limit 200");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (connectorCode != null && !connectorCode.isBlank()) spec = spec.param("connector", connectorCode.trim());
        List<UUID> ids = spec.query(UUID.class).list();
        return ids.stream().map(id -> reconciliation(identity.tenantId(), id)).toList();
    }

    IntegrationMessageCollectResultWire collect(
            ClinicalIdentity identity, String idempotencyKey, IntegrationMessageCollectRequestWire request) {
        String connectorCode = requireText(request.connectorCode(), 2, "connector_code");
        String interfaceCode = resolveInterfaceCode(identity.tenantId(), connectorCode);
        String direction = request.direction() == null
                ? IntegrationMessageWire.DirectionValue.OUTBOUND.name()
                : request.direction().name();
        String scenario = request.simulationScenario() == null ? "SUCCESS" : request.simulationScenario().name();
        int recordCount = request.recordCount() == null ? 36 : request.recordCount();
        if (recordCount < 12 || recordCount > 200) {
            throw invalid("record_count must be between 12 and 200");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "INTEGRATION_COLLECT", idempotencyKey, sha256(connectorCode + "|" + interfaceCode + "|" + scenario));
            if ("UNAVAILABLE".equals(scenario)) {
                UUID messageId = UUID.randomUUID();
                Instant occurredAt = Instant.now();
                jdbc.sql("""
                        insert into integration_message(
                          tenant_id, message_id, trace_id, connector_code, interface_code,
                          direction, business_object, business_key, message_status, error_detail,
                          payload, occurred_at)
                        values (:tenant, :message, :trace, :connector, :interface, :direction,
                          '受控仿真依赖不可用', :business_key, 'FAILED', :error,
                          cast(:payload as jsonb), :occurred)
                        """).param("tenant", identity.tenantId()).param("message", messageId)
                        .param("trace", "SIMULATED-UNAVAILABLE-" + messageId.toString().substring(0, 8))
                        .param("connector", connectorCode).param("interface", interfaceCode)
                        .param("direction", direction).param("business_key", idempotencyKey)
                        .param("error", "受控仿真外部依赖不可用；未创建或修改任何临床事实")
                        .param("payload", json(Map.of("simulation_scenario", scenario,
                                "connector_code", connectorCode, "clinical_fact_written", false)))
                        .param("occurred", occurredAt.atOffset(ZoneOffset.UTC)).update();
                appendEvidence(identity, messageId, "INTEGRATION_MESSAGE_FAILED", "IntegrationMessageFailed");
                IntegrationReconciliationWire reconciliation = recomputeReconciliation(identity, connectorCode, occurredAt);
                completeCommand(identity, "INTEGRATION_COLLECT", idempotencyKey, connectorCode);
                return new IntegrationMessageCollectResultWire(
                        List.of(message(identity.tenantId(), messageId)), reconciliation);
            }
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("simulation_scenario", scenario);
            payload.put("record_count", recordCount);
            payload.put("connector_code", connectorCode);
            MockInvocationResultWire generated = mocks.invoke(interfaceCode, payload);
            List<Map<String, Object>> records = businessRecords(generated.payload());
            Instant traceTime = generated.producedAt();
            List<IntegrationMessageWire> messages = new ArrayList<>(records.size());
            for (Map<String, Object> record : records) {
                UUID messageId = UUID.randomUUID();
                IntegrationMessageWire.MessageStatusValue messageStatus =
                        "DEGRADED".equals(scenario)
                                ? IntegrationMessageWire.MessageStatusValue.PENDING
                                : IntegrationMessageWire.MessageStatusValue.DELIVERED;
                jdbc.sql("""
                        insert into integration_message(
                          tenant_id, message_id, trace_id, connector_code, interface_code,
                          direction, business_object, business_key, message_status, error_detail,
                          payload, occurred_at)
                        values (:tenant, :message, :trace, :connector, :interface, :direction,
                          :object, :business_key, :status, :error, cast(:payload as jsonb), :occurred)
                        """).param("tenant", identity.tenantId()).param("message", messageId)
                        .param("trace", generated.requestId().toString()).param("connector", connectorCode)
                        .param("interface", interfaceCode).param("direction", direction)
                        .param("object", businessObject(interfaceCode, record))
                        .param("business_key", text(record, "business_id"))
                        .param("status", messageStatus.name())
                        .param("error", "DEGRADED".equals(scenario) ? "合成上游延迟，结果待对账" : null)
                        .param("payload", json(record))
                        .param("occurred", occurredAt(record, traceTime).atOffset(ZoneOffset.UTC)).update();
                appendEvidence(identity, messageId, "INTEGRATION_MESSAGE_RECORDED", "IntegrationMessageRecorded");
                messages.add(message(identity.tenantId(), messageId));
            }
            Instant anchor = messages.stream().map(IntegrationMessageWire::occurredAt)
                    .max(Instant::compareTo).orElse(Instant.now());
            IntegrationReconciliationWire reconciliation = recomputeReconciliation(identity, connectorCode, anchor);
            completeCommand(identity, "INTEGRATION_COLLECT", idempotencyKey, connectorCode);
            return new IntegrationMessageCollectResultWire(messages, reconciliation);
        });
    }

    IntegrationMessageWire reconcile(
            ClinicalIdentity identity, String idempotencyKey, UUID messageId,
            IntegrationMessageReconcileRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "INTEGRATION_RECONCILE", idempotencyKey, sha256(messageId.toString()));
            IntegrationMessageWire current = message(identity.tenantId(), messageId);
            if (current.messageStatus() == IntegrationMessageWire.MessageStatusValue.RECONCILED
                    || current.messageStatus() == IntegrationMessageWire.MessageStatusValue.FAILED) {
                throw new IntegrationException(
                        "INTEGRATION_MESSAGE_STATE_INVALID", 409, "已对账或失败的消息不能人工改写为已对账");
            }
            jdbc.sql("""
                    update integration_message
                    set message_status = 'RECONCILED', error_detail = null, row_version = row_version + 1
                    where tenant_id = :tenant and message_id = :message
                    """).param("tenant", identity.tenantId()).param("message", messageId).update();
            appendEvidence(identity, messageId, "INTEGRATION_MESSAGE_RECONCILED", "IntegrationMessageReconciled");
            recomputeReconciliation(identity, current.connectorCode(), current.occurredAt());
            completeCommand(identity, "INTEGRATION_RECONCILE", idempotencyKey, messageId);
            return message(identity.tenantId(), messageId);
        });
    }

    private IntegrationReconciliationWire recomputeReconciliation(ClinicalIdentity identity, String connectorCode, Instant anchor) {
        Instant windowStart = anchor.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant windowEnd = windowStart.plusSeconds(86_400);
        long sent = jdbc.sql("""
                select count(*) from integration_message
                where tenant_id = :tenant and connector_code = :connector
                  and occurred_at >= :start and occurred_at < :end
                """).param("tenant", identity.tenantId()).param("connector", connectorCode)
                .param("start", windowStart.atOffset(ZoneOffset.UTC))
                .param("end", windowEnd.atOffset(ZoneOffset.UTC)).query(Long.class).single();
        long delivered = countByStatus(identity, connectorCode, windowStart, windowEnd, "DELIVERED");
        long error = countByStatus(identity, connectorCode, windowStart, windowEnd, "FAILED");
        long pending = countByStatus(identity, connectorCode, windowStart, windowEnd, "PENDING");
        String status = pending == 0 && error == 0 ? "RECONCILED" : "OPEN";
        UUID reconciliationId = jdbc.sql("""
                select reconciliation_id from integration_reconciliation
                where tenant_id = :tenant and connector_code = :connector
                  and window_start = :start and window_end = :end
                """).param("tenant", identity.tenantId()).param("connector", connectorCode)
                .param("start", windowStart.atOffset(ZoneOffset.UTC))
                .param("end", windowEnd.atOffset(ZoneOffset.UTC)).query(UUID.class).optional().orElse(null);
        if (reconciliationId == null) {
            reconciliationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into integration_reconciliation(
                      tenant_id, reconciliation_id, connector_code, window_start, window_end,
                      sent_count, delivered_count, error_count, pending_count, status, reconciled_at)
                    values (:tenant, :reconciliation, :connector, :start, :end,
                      :sent, :delivered, :error, :pending, :status, :reconciled_at)
                    """).param("tenant", identity.tenantId()).param("reconciliation", reconciliationId)
                    .param("connector", connectorCode)
                    .param("start", windowStart.atOffset(ZoneOffset.UTC))
                    .param("end", windowEnd.atOffset(ZoneOffset.UTC))
                    .param("sent", sent).param("delivered", delivered).param("error", error)
                    .param("pending", pending).param("status", status)
                    .param("reconciled_at", "RECONCILED".equals(status) ? OffsetDateTime.now(ZoneOffset.UTC) : null).update();
        } else {
            jdbc.sql("""
                    update integration_reconciliation
                    set sent_count = :sent, delivered_count = :delivered, error_count = :error,
                        pending_count = :pending, status = :status,
                        reconciled_at = case when :status = 'RECONCILED' then now() else null end,
                        row_version = row_version + 1
                    where tenant_id = :tenant and reconciliation_id = :reconciliation
                    """).param("sent", sent).param("delivered", delivered).param("error", error)
                    .param("pending", pending).param("status", status)
                    .param("tenant", identity.tenantId()).param("reconciliation", reconciliationId).update();
        }
        return reconciliation(identity.tenantId(), reconciliationId);
    }

    private long countByStatus(ClinicalIdentity identity, String connectorCode, Instant start, Instant end, String status) {
        return jdbc.sql("""
                select count(*) from integration_message
                where tenant_id = :tenant and connector_code = :connector
                  and occurred_at >= :start and occurred_at < :end and message_status = :status
                """).param("tenant", identity.tenantId()).param("connector", connectorCode)
                .param("start", start.atOffset(ZoneOffset.UTC))
                .param("end", end.atOffset(ZoneOffset.UTC)).param("status", status).query(Long.class).single();
    }

    private String resolveInterfaceCode(UUID tenantId, String connectorCode) {
        var row = jdbc.sql("""
                select payload->>'system_type' as system_type from config_item
                where tenant_id = :tenant and config_type = 'INTEGRATION_CONNECTOR'
                  and config_key = :connector and status = 'ACTIVE'
                """).param("tenant", tenantId).param("connector", connectorCode)
                .query((rs, ignored) -> rs.getString("system_type")).optional().orElse(null);
        if (row == null) {
            throw new IntegrationException(
                    "INTEGRATION_CONNECTOR_NOT_ACTIVE", 409, "连接器不存在或未处于 ACTIVE 状态");
        }
        return switch (row) {
            case "LIS" -> "LIS_RESULTS";
            case "PACS" -> "PACS_IMAGES";
            case "HIS" -> "HIS_INSURANCE";
            case "CA" -> "CA_TIMESTAMP";
            case "HIE" -> "HIE_DOCUMENT_EXCHANGE";
            default -> throw new IntegrationException(
                    "INTEGRATION_INTERFACE_UNSUPPORTED", 422, "该连接器系统类型暂不支持消息采集");
        };
    }

    private String businessObject(String interfaceCode, Map<String, Object> record) {
        return switch (interfaceCode) {
            case "LIS_RESULTS" -> "检验报告 " + text(record, "specimen_id");
            case "PACS_IMAGES" -> "影像检查 " + text(record, "study_uid");
            case "HIS_INSURANCE" -> "医保结算 " + text(record, "claim_id");
            case "CA_TIMESTAMP" -> "签名时间戳 " + text(record, "certificate_serial");
            case "HIE_DOCUMENT_EXCHANGE" -> "区域文档 " + text(record, "exchange_id");
            default -> text(record, "business_id");
        };
    }

    private Instant occurredAt(Map<String, Object> record, Instant fallback) {
        String value = text(record, "business_time");
        if (value.isEmpty()) return fallback;
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> businessRecords(Map<String, Object> payload) {
        Object records = payload.get("business_records");
        if (!(records instanceof List<?> list)) {
            throw new IntegrationException("INTEGRATION_MOCK_PAYLOAD_INVALID", 502, "模拟接口未返回业务记录");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return result;
    }

    private IntegrationMessageWire message(UUID tenantId, UUID messageId) {
        return jdbc.sql("""
                select message_id, trace_id, connector_code, interface_code, direction,
                  business_object, business_key, message_status, error_detail, occurred_at, row_version, created_at
                from integration_message where tenant_id = :tenant and message_id = :message
                """).param("tenant", tenantId).param("message", messageId)
                .query((rs, row) -> new IntegrationMessageWire(
                        rs.getObject("message_id", UUID.class), rs.getString("trace_id"),
                        rs.getString("connector_code"), rs.getString("interface_code"),
                        IntegrationMessageWire.DirectionValue.valueOf(rs.getString("direction")),
                        rs.getString("business_object"), rs.getString("business_key"),
                        IntegrationMessageWire.MessageStatusValue.valueOf(rs.getString("message_status")),
                        rs.getString("error_detail"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(IntegrationMessageService::contextDenied);
    }

    private IntegrationReconciliationWire reconciliation(UUID tenantId, UUID reconciliationId) {
        return jdbc.sql("""
                select reconciliation_id, connector_code, window_start, window_end, sent_count,
                  delivered_count, error_count, pending_count, status, reconciled_at, row_version, created_at
                from integration_reconciliation where tenant_id = :tenant and reconciliation_id = :reconciliation
                """).param("tenant", tenantId).param("reconciliation", reconciliationId)
                .query((rs, row) -> new IntegrationReconciliationWire(
                        rs.getObject("reconciliation_id", UUID.class), rs.getString("connector_code"),
                        rs.getObject("window_start", OffsetDateTime.class).toInstant(),
                        rs.getObject("window_end", OffsetDateTime.class).toInstant(),
                        rs.getLong("sent_count"), rs.getLong("delivered_count"),
                        rs.getLong("error_count"), rs.getLong("pending_count"),
                        IntegrationReconciliationWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("reconciled_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("reconciled_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(IntegrationMessageService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw invalid("A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new IntegrationException("IDEMPOTENCY_REPLAY", 409, "该命令键已使用");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, Object resource) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', cast(:resource as text))
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", String.valueOf(resource)).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID messageId, String action, String eventType) {
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + messageId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'INTEGRATION_MESSAGE', :resource,
                  :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", messageId)
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'INTEGRATION_MESSAGE', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", messageId).param("event_type", eventType).update();
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception invalid) {
            throw new IntegrationException("INTEGRATION_PAYLOAD_INVALID", 400, "载荷不可序列化");
        }
    }

    private String text(Map<String, Object> record, String key) {
        Object value = record.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " is required");
        }
        return value.trim();
    }

    private static IntegrationException invalid(String message) {
        return new IntegrationException("INTEGRATION_REQUEST_INVALID", 400, message);
    }

    static IntegrationException contextDenied() {
        return new IntegrationException("CONTEXT_NOT_PERMITTED", 403, "请求的集成上下文不允许访问");
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
