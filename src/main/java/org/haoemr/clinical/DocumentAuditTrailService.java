package org.haoemr.clinical;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
final class DocumentAuditTrailService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    DocumentAuditTrailService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<DocumentAuditEvent> list(
            ClinicalIdentity identity, UUID documentId, UUID patientId, UUID encounterId) {
        long permitted = jdbc.sql("""
                select count(*) from clinical_document
                where tenant_id = :tenant and document_id = :document
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("patient", patientId).param("encounter", encounterId)
                .query(Long.class).single();
        if (permitted != 1) throw new ClinicalCommandException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested document audit context is not permitted");
        return jdbc.sql("""
                select audit_event_id, occurred_at, actor_user_id, action_code, resource_type,
                  resource_id, trace_id, previous_hash, event_hash, details
                from audit_event where tenant_id = :tenant
                  and (resource_id = :document or details ->> 'document_id' = cast(:document as text))
                order by occurred_at desc, audit_event_id desc limit 500
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .query((rs, row) -> new DocumentAuditEvent(
                        rs.getObject("audit_event_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("action_code"),
                        rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                        rs.getString("trace_id"), rs.getString("previous_hash"),
                        rs.getString("event_hash"), details(rs.getString("details"))))
                .list();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> details(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored document audit details are invalid", invalid);
        }
    }

    record DocumentAuditEvent(
            UUID auditEventId, Instant occurredAt, UUID actorUserId, String actionCode,
            String resourceType, UUID resourceId, String traceId, String previousHash,
            String eventHash, Map<String, Object> details) {}
}
