package org.haoemr.clinical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class RecordCenterService {
    private static final Set<String> PRIVILEGED_ROLES = Set.of(
            "MEDICAL_RECORDS", "CLINICAL_ADMIN", "QUALITY_MANAGER", "SYSTEM_ADMIN");
    private static final Set<String> DOCUMENT_STATUSES = Set.of(
            "DRAFT", "READY_TO_SIGN", "SIGNED", "CORRECTED", "VOID");
    private static final Set<String> REVIEW_SCOPES = Set.of("RANDOM", "FOCUSED", "TERMINAL", "CORRECTION");
    private static final Set<String> PRIORITIES = Set.of("ROUTINE", "HIGH", "URGENT");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("ASSIGNED", "IN_REVIEW", "VOID"),
            "ASSIGNED", Set.of("IN_REVIEW", "VOID"),
            "IN_REVIEW", Set.of("REMEDIATION", "VERIFIED", "VOID"),
            "REMEDIATION", Set.of("IN_REVIEW", "VERIFIED", "VOID"),
            "VERIFIED", Set.of("CLOSED", "IN_REVIEW", "VOID"));

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    RecordCenterService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    List<WorklistItem> worklist(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, String status, String query) {
        boolean hospitalWide = hasPrivilegedRole(identity, organizationId, facilityId);
        String normalizedStatus = normalizeOptional(status);
        if (normalizedStatus != null && !DOCUMENT_STATUSES.contains(normalizedStatus)) {
            throw invalid("Unsupported document status");
        }
        String statusParameter = normalizedStatus == null ? "" : normalizedStatus;
        String needle = query == null || query.isBlank() ? "" : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.sql("""
                select document.document_id, document.patient_id, document.encounter_id,
                  encounter.encounter_type, encounter.status encounter_status,
                  encounter.department_id, department.display_name department_name,
                  patient.display_name patient_name, document.document_type_code,
                  document.status, document.row_version, document.updated_at,
                  version.document_version_id, version.version_no, version.content_hash,
                  author.display_name author_name,
                  (select count(*) from quality_finding finding
                    where finding.tenant_id = document.tenant_id
                      and finding.document_id = document.document_id
                      and finding.document_version_id = document.current_version_id
                      and finding.state in ('OPEN', 'ACKNOWLEDGED')) open_finding_count,
                  exists(select 1 from quality_finding finding
                    where finding.tenant_id = document.tenant_id
                      and finding.document_id = document.document_id
                      and finding.document_version_id = document.current_version_id
                      and finding.state in ('OPEN', 'ACKNOWLEDGED') and finding.severity = 'BLOCKING') has_blocking_finding,
                  exists(select 1 from signature_evidence signature
                    where signature.tenant_id = document.tenant_id
                      and signature.document_id = document.document_id
                      and signature.document_version_id = document.current_version_id
                      and signature.signature_status = 'VALID') has_valid_signature,
                  review.review_case_id, review.status review_status, review.priority review_priority,
                  review.due_at review_due_at
                from clinical_document document
                join clinical_document_version version on version.tenant_id = document.tenant_id
                  and version.document_id = document.document_id
                  and version.document_version_id = document.current_version_id
                join patient on patient.tenant_id = document.tenant_id and patient.patient_id = document.patient_id
                join encounter on encounter.tenant_id = document.tenant_id and encounter.encounter_id = document.encounter_id
                left join clinical_department department on department.tenant_id = encounter.tenant_id
                  and department.facility_id = encounter.facility_id and department.department_id = encounter.department_id
                join app_user author on author.tenant_id = version.tenant_id and author.user_id = version.author_user_id
                left join lateral (
                  select review_case_id, status, priority, due_at from record_review_case candidate
                  where candidate.tenant_id = document.tenant_id and candidate.document_id = document.document_id
                    and candidate.status not in ('CLOSED', 'VOID')
                  order by candidate.created_at desc limit 1
                ) review on true
                where document.tenant_id = :tenant
                  and encounter.organization_id = :organization and encounter.facility_id = :facility
                  and (:status = '' or document.status = :status)
                  and (:needle = '' or lower(patient.display_name) like :needle
                    or lower(document.document_type_code) like :needle
                    or lower(document.document_id::text) like :needle
                    or lower(encounter.encounter_id::text) like :needle
                    or lower(author.display_name) like :needle)
                  and (:hospital_wide or exists (
                    select 1 from patient_care_relationship relationship
                    where relationship.tenant_id = document.tenant_id
                      and relationship.patient_id = document.patient_id
                      and relationship.user_id = :actor and relationship.status = 'ACTIVE'
                      and relationship.valid_from <= now()
                      and (relationship.valid_until is null or relationship.valid_until > now())
                      and (relationship.encounter_id is null or relationship.encounter_id = document.encounter_id)))
                order by has_blocking_finding desc,
                  case review.priority when 'URGENT' then 1 when 'HIGH' then 2 else 3 end,
                  review.due_at nulls last, document.updated_at desc
                limit 200
                """).param("tenant", identity.tenantId()).param("organization", organizationId)
                .param("facility", facilityId).param("status", statusParameter).param("needle", needle)
                .param("hospital_wide", hospitalWide).param("actor", identity.userId())
                .query((rs, row) -> new WorklistItem(
                        rs.getObject("document_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getString("patient_name"),
                        rs.getObject("encounter_id", UUID.class), rs.getString("encounter_type"),
                        rs.getString("encounter_status"), rs.getObject("department_id", UUID.class),
                        rs.getString("department_name"), rs.getString("document_type_code"),
                        rs.getString("status"), rs.getInt("version_no"), rs.getLong("row_version"),
                        rs.getString("content_hash"), rs.getString("author_name"),
                        rs.getLong("open_finding_count"), rs.getBoolean("has_blocking_finding"),
                        rs.getBoolean("has_valid_signature"), rs.getObject("review_case_id", UUID.class),
                        rs.getString("review_status"), rs.getString("review_priority"),
                        instant(rs.getObject("review_due_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .list();
    }

    List<ReviewCase> reviewCases(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID documentId) {
        boolean hospitalWide = hasPrivilegedRole(identity, organizationId, facilityId);
        return jdbc.sql("""
                select review.review_case_id
                from record_review_case review
                where review.tenant_id = :tenant and review.organization_id = :organization
                  and review.facility_id = :facility
                  and (:all_documents or review.document_id = :document)
                  and (:hospital_wide or exists (
                    select 1 from patient_care_relationship relationship
                    where relationship.tenant_id = review.tenant_id
                      and relationship.patient_id = review.patient_id
                      and relationship.user_id = :actor and relationship.status = 'ACTIVE'
                      and relationship.valid_from <= now()
                      and (relationship.valid_until is null or relationship.valid_until > now())
                      and (relationship.encounter_id is null or relationship.encounter_id = review.encounter_id)))
                order by review.created_at desc limit 200
                """).param("tenant", identity.tenantId()).param("organization", organizationId)
                .param("facility", facilityId).param("all_documents", documentId == null)
                .param("document", documentId == null ? new UUID(0L, 0L) : documentId)
                .param("hospital_wide", hospitalWide).param("actor", identity.userId())
                .query(UUID.class).list().stream().map(id -> reviewCase(identity.tenantId(), id)).toList();
    }

    ReviewCase createReviewCase(
            ClinicalIdentity identity, String idempotencyKey, UUID organizationId, UUID facilityId,
            RecordCenterController.CreateReviewCase command) {
        if (command == null || command.documentId() == null || command.documentVersionId() == null
                || command.dueAt() == null) throw invalid("Document, version and due time are required");
        String scope = normalized(command.reviewScope(), REVIEW_SCOPES, "review scope");
        String priority = normalized(command.priority(), PRIORITIES, "priority");
        String reason = requiredReason(command.reason(), 4);
        if (!command.dueAt().isAfter(Instant.now())) throw invalid("Review due time must be in the future");
        return transactions.execute(tx -> {
            beginCommand(identity, "RECORD_REVIEW_CASE_CREATE", idempotencyKey,
                    sha256(command.documentId() + "|" + command.documentVersionId() + "|" + scope + "|" + reason));
            DocumentHead document = requireVisibleDocument(
                    identity, organizationId, facilityId, command.documentId(), command.documentVersionId(), true);
            long duplicate = jdbc.sql("""
                    select count(*) from record_review_case
                    where tenant_id = :tenant and document_id = :document
                      and review_scope = :scope and status not in ('CLOSED', 'VOID')
                    """).param("tenant", identity.tenantId()).param("document", command.documentId())
                    .param("scope", scope).query(Long.class).single();
            if (duplicate > 0) throw conflict("An active review case already exists for this document and scope");
            if (command.assigneeUserId() != null) requireActiveUser(identity.tenantId(), command.assigneeUserId());
            UUID caseId = UUID.randomUUID();
            String status = command.assigneeUserId() == null ? "OPEN" : "ASSIGNED";
            jdbc.sql("""
                    insert into record_review_case(
                      tenant_id, review_case_id, organization_id, facility_id, patient_id, encounter_id,
                      document_id, document_version_id, review_scope, reason, priority, status,
                      assignee_user_id, due_at, created_by)
                    values (:tenant, :case_id, :organization, :facility, :patient, :encounter,
                      :document, :version, :scope, :reason, :priority, :status, :assignee, :due_at, :actor)
                    """).param("tenant", identity.tenantId()).param("case_id", caseId)
                    .param("organization", organizationId).param("facility", facilityId)
                    .param("patient", document.patientId()).param("encounter", document.encounterId())
                    .param("document", command.documentId()).param("version", command.documentVersionId())
                    .param("scope", scope).param("reason", reason).param("priority", priority)
                    .param("status", status).param("assignee", command.assigneeUserId())
                    .param("due_at", OffsetDateTime.ofInstant(command.dueAt(), ZoneOffset.UTC))
                    .param("actor", identity.userId()).update();
            appendCaseEvent(identity, caseId, 1, "CREATED", null, status, reason, document.patientId());
            completeCommand(identity, "RECORD_REVIEW_CASE_CREATE", idempotencyKey, caseId, 201);
            return reviewCase(identity.tenantId(), caseId);
        });
    }

    ReviewCase transitionReviewCase(
            ClinicalIdentity identity, String idempotencyKey, UUID organizationId, UUID facilityId,
            UUID caseId, RecordCenterController.TransitionReviewCase command) {
        if (command == null || command.targetStatus() == null || command.expectedRowVersion() < 1) {
            throw invalid("Target status and expected row version are required");
        }
        String target = command.targetStatus().trim().toUpperCase(Locale.ROOT);
        String reason = requiredReason(command.reason(), target.equals("ASSIGNED") ? 0 : 4);
        return transactions.execute(tx -> {
            beginCommand(identity, "RECORD_REVIEW_CASE_TRANSITION", idempotencyKey,
                    sha256(caseId + "|" + command.expectedRowVersion() + "|" + target + "|" + reason));
            ReviewHead current = jdbc.sql("""
                    select status, row_version, patient_id, encounter_id, document_id
                    from record_review_case
                    where tenant_id = :tenant and review_case_id = :case_id
                      and organization_id = :organization and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("case_id", caseId)
                    .param("organization", organizationId).param("facility", facilityId)
                    .query((rs, row) -> new ReviewHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                            rs.getObject("document_id", UUID.class)))
                    .optional().orElseThrow(() -> denied());
            requireVisiblePatient(identity, current.patientId(), current.encounterId(), organizationId, facilityId);
            if (current.rowVersion() != command.expectedRowVersion()) {
                throw new RecordCenterException("RECORD_REVIEW_VERSION_CONFLICT", 409,
                        "The review case changed; reload before retrying");
            }
            if (!TRANSITIONS.getOrDefault(current.status(), Set.of()).contains(target)) {
                throw new RecordCenterException("RECORD_REVIEW_STATE_INVALID", 409,
                        "The requested review-case transition is not permitted");
            }
            UUID assignee = command.assigneeUserId();
            if ("ASSIGNED".equals(target) && assignee == null) throw invalid("An assignee is required");
            if (assignee != null) requireActiveUser(identity.tenantId(), assignee);
            boolean voiding = "VOID".equals(target);
            jdbc.sql("""
                    update record_review_case set status = :target,
                      assignee_user_id = coalesce(:assignee, assignee_user_id),
                      voided_by = case when :voiding then :actor else null end,
                      voided_at = case when :voiding then now() else null end,
                      void_reason = case when :voiding then :reason else null end,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and review_case_id = :case_id and row_version = :expected
                    """).param("target", target).param("assignee", assignee).param("voiding", voiding)
                    .param("actor", identity.userId()).param("reason", reason)
                    .param("tenant", identity.tenantId()).param("case_id", caseId)
                    .param("expected", current.rowVersion()).update();
            long sequence = current.rowVersion() + 1;
            appendCaseEvent(identity, caseId, sequence, eventType(target), current.status(), target,
                    reason, current.patientId());
            completeCommand(identity, "RECORD_REVIEW_CASE_TRANSITION", idempotencyKey, caseId, 200);
            return reviewCase(identity.tenantId(), caseId);
        });
    }

    private DocumentHead requireVisibleDocument(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId,
            UUID documentId, UUID versionId, boolean lock) {
        DocumentHead document = jdbc.sql("""
                select document.patient_id, document.encounter_id
                from clinical_document document
                join encounter on encounter.tenant_id = document.tenant_id
                  and encounter.encounter_id = document.encounter_id
                where document.tenant_id = :tenant and document.document_id = :document
                  and document.current_version_id = :version
                  and encounter.organization_id = :organization and encounter.facility_id = :facility
                """ + (lock ? " for update" : ""))
                .param("tenant", identity.tenantId()).param("document", documentId).param("version", versionId)
                .param("organization", organizationId).param("facility", facilityId)
                .query((rs, row) -> new DocumentHead(
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class)))
                .optional().orElseThrow(() -> denied());
        requireVisiblePatient(identity, document.patientId(), document.encounterId(), organizationId, facilityId);
        return document;
    }

    private void requireVisiblePatient(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, UUID organizationId, UUID facilityId) {
        if (hasPrivilegedRole(identity, organizationId, facilityId)) return;
        long visible = jdbc.sql("""
                select count(*) from patient_care_relationship relationship
                join encounter on encounter.tenant_id = relationship.tenant_id and encounter.encounter_id = :encounter
                where relationship.tenant_id = :tenant and relationship.patient_id = :patient
                  and relationship.user_id = :actor and relationship.status = 'ACTIVE'
                  and relationship.valid_from <= now()
                  and (relationship.valid_until is null or relationship.valid_until > now())
                  and (relationship.encounter_id is null or relationship.encounter_id = :encounter)
                  and encounter.organization_id = :organization and encounter.facility_id = :facility
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("actor", identity.userId())
                .param("organization", organizationId).param("facility", facilityId)
                .query(Long.class).single();
        if (visible != 1) throw denied();
    }

    private boolean hasPrivilegedRole(ClinicalIdentity identity, UUID organizationId, UUID facilityId) {
        if (identity.roleAssignmentIds().isEmpty()) return false;
        return jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and role_assignment_id in (:role_ids)
                  and user_id = :actor and organization_id = :organization
                  and (facility_id is null or facility_id = :facility)
                  and role_code in ('MEDICAL_RECORDS', 'CLINICAL_ADMIN', 'QUALITY_MANAGER', 'SYSTEM_ADMIN')
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("role_ids", identity.roleAssignmentIds())
                .param("actor", identity.userId()).param("organization", organizationId)
                .param("facility", facilityId).query(Long.class).single() > 0;
    }

    private ReviewCase reviewCase(UUID tenantId, UUID caseId) {
        return jdbc.sql("""
                select review.review_case_id, review.patient_id, patient.display_name patient_name,
                  review.encounter_id, review.document_id, review.document_version_id,
                  review.review_scope, review.reason, review.priority, review.status,
                  review.assignee_user_id, assignee.display_name assignee_name,
                  review.due_at, review.created_by, creator.display_name created_by_name,
                  review.void_reason, review.row_version, review.created_at, review.updated_at
                from record_review_case review
                join patient on patient.tenant_id = review.tenant_id and patient.patient_id = review.patient_id
                join app_user creator on creator.tenant_id = review.tenant_id and creator.user_id = review.created_by
                left join app_user assignee on assignee.tenant_id = review.tenant_id
                  and assignee.user_id = review.assignee_user_id
                where review.tenant_id = :tenant and review.review_case_id = :case_id
                """).param("tenant", tenantId).param("case_id", caseId)
                .query((rs, row) -> new ReviewCase(
                        rs.getObject("review_case_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("patient_name"), rs.getObject("encounter_id", UUID.class),
                        rs.getObject("document_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                        rs.getString("review_scope"), rs.getString("reason"), rs.getString("priority"),
                        rs.getString("status"), rs.getObject("assignee_user_id", UUID.class),
                        rs.getString("assignee_name"), instant(rs.getObject("due_at", OffsetDateTime.class)),
                        rs.getObject("created_by", UUID.class), rs.getString("created_by_name"),
                        rs.getString("void_reason"), rs.getLong("row_version"),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional().orElseThrow(() -> denied());
    }

    private void appendCaseEvent(
            ClinicalIdentity identity, UUID caseId, long sequence, String eventType,
            String fromStatus, String toStatus, String reason, UUID patientId) {
        jdbc.sql("""
                insert into record_review_case_event(
                  tenant_id, review_case_event_id, review_case_id, sequence_no, event_type,
                  from_status, to_status, actor_user_id, reason)
                values (:tenant, :event, :case_id, :sequence, :event_type,
                  :from_status, :to_status, :actor, :reason)
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("case_id", caseId).param("sequence", sequence).param("event_type", eventType)
                .param("from_status", fromStatus).param("to_status", toStatus)
                .param("actor", identity.userId()).param("reason", reason).update();
        appendAudit(identity, caseId, patientId, "RECORD_REVIEW_" + eventType);
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'RECORD_REVIEW_CASE', :case_id, :version,
                  :event_type, 1, jsonb_build_object('review_case_id', :case_id, 'status', :status))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("case_id", caseId).param("version", sequence)
                .param("event_type", "RecordReviewCase" + eventType).param("status", toStatus).update();
    }

    private void appendAudit(
            ClinicalIdentity identity, UUID resourceId, UUID patientId, String action) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + resourceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'RECORD_REVIEW_CASE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new RecordCenterException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw new RecordCenterException(
                "IDEMPOTENCY_REPLAY", 409, "This command key was already used");
    }

    private void completeCommand(
            ClinicalIdentity identity, String scope, String key, UUID resourceId, int status) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", status).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void requireActiveUser(UUID tenantId, UUID userId) {
        long count = jdbc.sql("select count(*) from app_user where tenant_id = :tenant and user_id = :user and status = 'ACTIVE'")
                .param("tenant", tenantId).param("user", userId).query(Long.class).single();
        if (count != 1) throw invalid("Assignee must be an active user");
    }

    private static String eventType(String target) {
        return switch (target) {
            case "ASSIGNED" -> "ASSIGNED";
            case "IN_REVIEW" -> "REVIEW_STARTED";
            case "REMEDIATION" -> "REMEDIATION_REQUIRED";
            case "VERIFIED" -> "VERIFIED";
            case "CLOSED" -> "CLOSED";
            case "VOID" -> "VOIDED";
            default -> throw invalid("Unsupported target status");
        };
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)
                ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalized(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid("Unsupported " + label);
        return normalized;
    }

    private static String requiredReason(String value, int minimum) {
        String reason = value == null ? "" : value.trim();
        if (reason.length() < minimum || reason.length() > 1000) {
            throw invalid("A reason between " + minimum + " and 1000 characters is required");
        }
        return reason;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static RecordCenterException invalid(String message) {
        return new RecordCenterException("RECORD_CENTER_REQUEST_INVALID", 400, message);
    }

    private static RecordCenterException conflict(String message) {
        return new RecordCenterException("RECORD_REVIEW_CASE_CONFLICT", 409, message);
    }

    private static RecordCenterException denied() {
        return new RecordCenterException("CONTEXT_NOT_PERMITTED", 403,
                "The requested record-center context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record WorklistItem(
            UUID documentId, UUID documentVersionId, UUID patientId, String patientName,
            UUID encounterId, String encounterType, String encounterStatus,
            UUID departmentId, String departmentName, String documentTypeCode, String status,
            int versionNo, long rowVersion, String contentHash, String authorName,
            long openFindingCount, boolean hasBlockingFinding, boolean hasValidSignature,
            UUID reviewCaseId, String reviewStatus, String reviewPriority, Instant reviewDueAt,
            Instant updatedAt) {}

    record ReviewCase(
            UUID reviewCaseId, UUID patientId, String patientName, UUID encounterId,
            UUID documentId, UUID documentVersionId, String reviewScope, String reason,
            String priority, String status, UUID assigneeUserId, String assigneeName,
            Instant dueAt, UUID createdBy, String createdByName, String voidReason,
            long rowVersion, Instant createdAt, Instant updatedAt) {}

    private record DocumentHead(UUID patientId, UUID encounterId) {}
    private record ReviewHead(String status, long rowVersion, UUID patientId, UUID encounterId, UUID documentId) {}
}
