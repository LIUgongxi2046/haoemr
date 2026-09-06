package org.haoemr.administration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
final class MasterDataRecordService {
    private static final Set<String> ADMIN_ROLES = Set.of("SYSTEM_ADMIN", "CLINICAL_ADMIN", "CONFIG_AUTHOR", "CONFIG_APPROVER");
    private static final Set<String> MAPPING_STATES = Set.of("MATCHED", "UNMATCHED", "CONFLICT", "LOCAL_ONLY");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    MasterDataRecordService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    List<MasterDataRecordWire> list(ClinicalIdentity identity, UUID configId, String keyword, String status) {
        requireAdministrator(identity);
        StringBuilder sql = new StringBuilder("""
                select record_id, config_id, code_system, national_code, local_code, display_name,
                  category_path, national_version, authoritative_source, mapping_status, status,
                  effective_from, effective_until, attributes::text, row_version, created_by,
                  created_at, updated_at
                from master_data_record where tenant_id = :tenant
                """);
        if (configId != null) sql.append(" and config_id = :config");
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (local_code ilike :keyword or national_code ilike :keyword or display_name ilike :keyword or category_path ilike :keyword)");
        }
        if (status != null && !status.isBlank()) sql.append(" and status = :status");
        sql.append(" order by category_path, local_code, record_id limit 1000");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (configId != null) statement = statement.param("config", configId);
        if (keyword != null && !keyword.isBlank()) statement = statement.param("keyword", "%" + keyword.trim() + "%");
        if (status != null && !status.isBlank()) statement = statement.param("status", status.trim().toUpperCase());
        return statement.query((rs, row) -> wire(
                rs.getObject("record_id", UUID.class), rs.getObject("config_id", UUID.class),
                rs.getString("code_system"), rs.getString("national_code"), rs.getString("local_code"),
                rs.getString("display_name"), rs.getString("category_path"), rs.getString("national_version"),
                rs.getString("authoritative_source"), rs.getString("mapping_status"), rs.getString("status"),
                instant(rs.getObject("effective_from", OffsetDateTime.class)),
                instant(rs.getObject("effective_until", OffsetDateTime.class)), jsonMap(rs.getString("attributes")),
                rs.getLong("row_version"), rs.getObject("created_by", UUID.class),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)))).list();
    }

    MasterDataRecordWire create(ClinicalIdentity identity, String idempotencyKey, MasterDataCreateRequest request) {
        requireAdministrator(identity);
        validate(request);
        return transactions.execute(status -> {
            begin(identity, "MASTER_DATA_CREATE", idempotencyKey, sha256(json(request)));
            requireActiveCatalog(identity.tenantId(), request.configId(), request.codeSystem());
            UUID recordId = UUID.randomUUID();
            try {
                jdbc.sql("""
                        insert into master_data_record(
                          tenant_id, record_id, config_id, code_system, national_code, local_code,
                          display_name, category_path, national_version, authoritative_source,
                          mapping_status, status, effective_from, effective_until, attributes, created_by)
                        values (:tenant, :record, :config, :code_system, :national_code, :local_code,
                          :name, :category, :national_version, :source, :mapping, 'ACTIVE',
                          :effective_from, :effective_until, cast(:attributes as jsonb), :actor)
                        """).param("tenant", identity.tenantId()).param("record", recordId)
                        .param("config", request.configId()).param("code_system", request.codeSystem().trim())
                        .param("national_code", blankToNull(request.nationalCode()))
                        .param("local_code", request.localCode().trim()).param("name", request.displayName().trim())
                        .param("category", request.categoryPath().trim())
                        .param("national_version", blankToNull(request.nationalVersion()))
                        .param("source", request.authoritativeSource().trim())
                        .param("mapping", request.mappingStatus().trim().toUpperCase())
                        .param("effective_from", utc(request.effectiveFrom()))
                        .param("effective_until", utc(request.effectiveUntil()))
                        .param("attributes", json(request.attributes() == null ? Map.of() : request.attributes()))
                        .param("actor", identity.userId()).update();
            } catch (DataIntegrityViolationException conflict) {
                throw new AdministrationRuntimeException(
                        "MASTER_DATA_CODE_CONFLICT", 409, "同一编码体系下本地编码必须唯一，且目录与人员必须有效");
            }
            appendEvidence(identity, "MASTER_DATA_RECORD_CREATED", recordId, 1);
            complete(identity, "MASTER_DATA_CREATE", idempotencyKey, recordId, 201);
            return find(identity.tenantId(), recordId);
        });
    }

    MasterDataRecordWire update(
            ClinicalIdentity identity, UUID recordId, String idempotencyKey, MasterDataUpdateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedVersion() < 1) invalid("必须提供当前数据库版本");
        validate(new MasterDataCreateRequest(request.configId(), request.codeSystem(), request.nationalCode(),
                request.localCode(), request.displayName(), request.categoryPath(), request.nationalVersion(),
                request.authoritativeSource(), request.mappingStatus(), request.effectiveFrom(),
                request.effectiveUntil(), request.attributes()));
        return transactions.execute(status -> {
            begin(identity, "MASTER_DATA_UPDATE", idempotencyKey, sha256(recordId + "|" + json(request)));
            requireActiveCatalog(identity.tenantId(), request.configId(), request.codeSystem());
            int updated;
            try {
                updated = jdbc.sql("""
                        update master_data_record set config_id = :config, code_system = :code_system,
                          national_code = :national_code, local_code = :local_code, display_name = :name,
                          category_path = :category, national_version = :national_version,
                          authoritative_source = :source, mapping_status = :mapping,
                          effective_from = :effective_from, effective_until = :effective_until,
                          attributes = cast(:attributes as jsonb), row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and record_id = :record and status = 'ACTIVE'
                          and row_version = :version
                        """).param("config", request.configId()).param("code_system", request.codeSystem().trim())
                        .param("national_code", blankToNull(request.nationalCode()))
                        .param("local_code", request.localCode().trim()).param("name", request.displayName().trim())
                        .param("category", request.categoryPath().trim())
                        .param("national_version", blankToNull(request.nationalVersion()))
                        .param("source", request.authoritativeSource().trim())
                        .param("mapping", request.mappingStatus().trim().toUpperCase())
                        .param("effective_from", utc(request.effectiveFrom()))
                        .param("effective_until", utc(request.effectiveUntil()))
                        .param("attributes", json(request.attributes() == null ? Map.of() : request.attributes()))
                        .param("tenant", identity.tenantId()).param("record", recordId)
                        .param("version", request.expectedVersion()).update();
            } catch (DataIntegrityViolationException conflict) {
                throw new AdministrationRuntimeException("MASTER_DATA_CODE_CONFLICT", 409, "主数据编码与当前目录冲突");
            }
            if (updated != 1) conflict("主数据已停用或数据库版本发生变化");
            appendEvidence(identity, "MASTER_DATA_RECORD_UPDATED", recordId, request.expectedVersion() + 1);
            complete(identity, "MASTER_DATA_UPDATE", idempotencyKey, recordId, 200);
            return find(identity.tenantId(), recordId);
        });
    }

    MasterDataRecordWire deactivate(
            ClinicalIdentity identity, UUID recordId, String idempotencyKey, DeactivateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedVersion() < 1 || request.reason() == null
                || request.reason().trim().length() < 8) invalid("必须提供当前版本和至少 8 个字符的停用原因");
        return transactions.execute(status -> {
            begin(identity, "MASTER_DATA_DEACTIVATE", idempotencyKey,
                    sha256(recordId + "|" + request.expectedVersion() + "|" + request.reason().trim()));
            int updated = jdbc.sql("""
                    update master_data_record set status = 'INACTIVE',
                      effective_until = coalesce(effective_until, greatest(now(), effective_from + interval '1 second')),
                      attributes = attributes || jsonb_build_object('deactivation_reason', :reason),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and record_id = :record and status = 'ACTIVE'
                      and row_version = :version
                    """).param("reason", request.reason().trim()).param("tenant", identity.tenantId())
                    .param("record", recordId).param("version", request.expectedVersion()).update();
            if (updated != 1) conflict("主数据已停用或数据库版本发生变化");
            appendEvidence(identity, "MASTER_DATA_RECORD_DEACTIVATED", recordId, request.expectedVersion() + 1);
            complete(identity, "MASTER_DATA_DEACTIVATE", idempotencyKey, recordId, 200);
            return find(identity.tenantId(), recordId);
        });
    }

    private void requireActiveCatalog(UUID tenantId, UUID configId, String codeSystem) {
        long count = jdbc.sql("""
                select count(*) from config_item where tenant_id = :tenant and config_id = :config
                  and config_type = 'MASTER_DATA' and status = 'ACTIVE'
                  and payload ->> 'code_system' = :code_system
                """).param("tenant", tenantId).param("config", configId)
                .param("code_system", codeSystem.trim()).query(Long.class).single();
        if (count != 1) throw new AdministrationRuntimeException(
                "MASTER_DATA_CATALOG_NOT_ACTIVE", 409, "主数据记录只能归属已发布且编码体系一致的目录");
    }

    private MasterDataRecordWire find(UUID tenantId, UUID recordId) {
        return jdbc.sql("""
                select record_id, config_id, code_system, national_code, local_code, display_name,
                  category_path, national_version, authoritative_source, mapping_status, status,
                  effective_from, effective_until, attributes::text, row_version, created_by,
                  created_at, updated_at from master_data_record
                where tenant_id = :tenant and record_id = :record
                """).param("tenant", tenantId).param("record", recordId).query((rs, row) -> wire(
                        rs.getObject("record_id", UUID.class), rs.getObject("config_id", UUID.class),
                        rs.getString("code_system"), rs.getString("national_code"), rs.getString("local_code"),
                        rs.getString("display_name"), rs.getString("category_path"), rs.getString("national_version"),
                        rs.getString("authoritative_source"), rs.getString("mapping_status"), rs.getString("status"),
                        instant(rs.getObject("effective_from", OffsetDateTime.class)),
                        instant(rs.getObject("effective_until", OffsetDateTime.class)), jsonMap(rs.getString("attributes")),
                        rs.getLong("row_version"), rs.getObject("created_by", UUID.class),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional().orElseThrow(() -> new AdministrationRuntimeException(
                        "MASTER_DATA_RECORD_NOT_FOUND", 404, "主数据记录不存在"));
    }

    private MasterDataRecordWire wire(UUID recordId, UUID configId, String codeSystem, String nationalCode,
            String localCode, String displayName, String categoryPath, String nationalVersion,
            String authoritativeSource, String mappingStatus, String status, Instant effectiveFrom,
            Instant effectiveUntil, Map<String, Object> attributes, long rowVersion, UUID createdBy,
            Instant createdAt, Instant updatedAt) {
        return new MasterDataRecordWire(recordId, configId, codeSystem, nationalCode, localCode, displayName,
                categoryPath, nationalVersion, authoritativeSource, mappingStatus, status, effectiveFrom,
                effectiveUntil, attributes, rowVersion, createdBy, createdAt, updatedAt);
    }

    private void validate(MasterDataCreateRequest request) {
        if (request == null || request.configId() == null || blank(request.codeSystem()) || blank(request.localCode())
                || blank(request.displayName()) || blank(request.categoryPath()) || blank(request.authoritativeSource())
                || blank(request.mappingStatus()) || !MAPPING_STATES.contains(request.mappingStatus().trim().toUpperCase())
                || request.effectiveFrom() == null
                || (request.effectiveUntil() != null && !request.effectiveUntil().isAfter(request.effectiveFrom()))) {
            invalid("目录、编码体系、本地编码、名称、分类、权威来源、映射状态和有效期必须完整有效");
        }
        if (request.mappingStatus().equalsIgnoreCase("MATCHED") && blank(request.nationalCode())) {
            invalid("映射状态为已匹配时必须填写国家或行业标准编码");
        }
    }

    private void requireAdministrator(ClinicalIdentity identity) {
        if (identity.roleAssignmentIds().isEmpty()) denied();
        long count = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:roles) and role_code in (:codes) and status = 'ACTIVE'
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", identity.roleAssignmentIds()).param("codes", ADMIN_ROLES).query(Long.class).single();
        if (count == 0) denied();
    }

    private void begin(ClinicalIdentity identity, String scope, String key, String hash) {
        if (key == null || key.isBlank()) invalid("Idempotency-Key 不能为空");
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", hash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) conflict("该主数据操作已提交，请勿重复执行");
    }

    private void complete(ClinicalIdentity identity, String scope, String key, UUID resourceId, int status) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", status).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, String action, UUID resourceId, long version) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID audit = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String hash = sha256(identity.tenantId() + "|" + audit + "|" + action + "|" + resourceId + "|"
                + trace + "|" + (previous == null ? "GENESIS" : previous));
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'MASTER_DATA_RECORD',
                  :resource, :trace, :previous, :hash)
                """).param("tenant", identity.tenantId()).param("audit", audit)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("trace", trace).param("previous", previous).param("hash", hash).update();
        jdbc.sql("""
                insert into outbox_event(tenant_id, event_id, aggregate_type, aggregate_id,
                  aggregate_version, event_type, schema_version, payload)
                values (:tenant, :event, 'MASTER_DATA_RECORD', :resource, :version, :action, 1,
                  jsonb_build_object('record_id', :resource))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("resource", resourceId).param("version", version).param("action", action).update();
    }

    private Map<String, Object> jsonMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception invalid) { throw new IllegalStateException("Stored master data attributes are invalid", invalid); }
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Master data JSON serialization failed", invalid); }
    }
    private static OffsetDateTime utc(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void invalid(String message) { throw new AdministrationRuntimeException("MASTER_DATA_REQUEST_INVALID", 400, message); }
    private static void conflict(String message) { throw new AdministrationRuntimeException("MASTER_DATA_VERSION_CONFLICT", 409, message); }
    private static void denied() { throw new AdministrationRuntimeException("ADMIN_SCOPE_DENIED", 403, "没有主数据管理权限"); }

    record MasterDataCreateRequest(UUID configId, String codeSystem, String nationalCode, String localCode,
            String displayName, String categoryPath, String nationalVersion, String authoritativeSource,
            String mappingStatus, Instant effectiveFrom, Instant effectiveUntil, Map<String, Object> attributes) {}
    record MasterDataUpdateRequest(long expectedVersion, UUID configId, String codeSystem, String nationalCode,
            String localCode, String displayName, String categoryPath, String nationalVersion,
            String authoritativeSource, String mappingStatus, Instant effectiveFrom, Instant effectiveUntil,
            Map<String, Object> attributes) {}
    record DeactivateRequest(long expectedVersion, String reason) {}
    record MasterDataRecordWire(UUID recordId, UUID configId, String codeSystem, String nationalCode,
            String localCode, String displayName, String categoryPath, String nationalVersion,
            String authoritativeSource, String mappingStatus, String status, Instant effectiveFrom,
            Instant effectiveUntil, Map<String, Object> attributes, long rowVersion, UUID createdBy,
            Instant createdAt, Instant updatedAt) {}
}
