package org.haoemr.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.AuditEventWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
final class AuditEventService {
    private static final List<String> AUDIT_ROLES = List.of(
            "SYSTEM_ADMIN", "SECURITY_AUDITOR", "CLINICAL_ADMIN");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    AuditEventService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<AuditEventWire> list(
            ClinicalIdentity identity, String actionCode, String resourceType,
            UUID resourceId, Instant from, Instant to) {
        requireAuditRole(identity);
        StringBuilder sql = new StringBuilder("""
                select audit_event_id, occurred_at, actor_user_id, action_code, resource_type,
                       resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details
                from audit_event
                where tenant_id = :tenant
                """);
        if (actionCode != null && !actionCode.isBlank()) sql.append(" and action_code = :action");
        if (resourceType != null && !resourceType.isBlank()) sql.append(" and resource_type = :resource_type");
        if (resourceId != null) sql.append(" and resource_id = :resource");
        if (from != null) sql.append(" and occurred_at >= :from");
        if (to != null) sql.append(" and occurred_at <= :to");
        sql.append(" order by occurred_at desc, audit_event_id desc limit 500");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (actionCode != null && !actionCode.isBlank()) spec = spec.param("action", actionCode);
        if (resourceType != null && !resourceType.isBlank()) spec = spec.param("resource_type", resourceType);
        if (resourceId != null) spec = spec.param("resource", resourceId);
        if (from != null) spec = spec.param("from", from);
        if (to != null) spec = spec.param("to", to);
        return spec.query((rs, row) -> new AuditEventWire(
                rs.getObject("audit_event_id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("action_code"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                rs.getString("patient_ref_hash"),
                rs.getString("trace_id"),
                rs.getString("previous_hash"),
                rs.getString("event_hash"),
                details(rs.getString("details"))))
                .list();
    }

    private void requireAuditRole(ClinicalIdentity identity) {
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new AuditEventException("AUDIT_ROLE_REQUIRED", 403, "当前岗位无权读取审计记录");
        }
        long allowed = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:assignments) and role_code in (:roles)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("roles", AUDIT_ROLES)
                .query(Long.class).single();
        if (allowed == 0) {
            throw new AuditEventException("AUDIT_ROLE_REQUIRED", 403, "当前岗位无权读取审计记录");
        }
    }

    private Map<String, Object> details(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new AuditEventException(
                    "AUDIT_DETAILS_INVALID", 500, "Stored audit details JSON is invalid");
        }
    }
}
