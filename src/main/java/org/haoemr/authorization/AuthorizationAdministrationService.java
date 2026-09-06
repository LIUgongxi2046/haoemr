package org.haoemr.authorization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.AuthorizationDecisionService;
import org.haoemr.security.AuthorizationDecisionService.AuthorizationContext;
import org.haoemr.security.AuthorizationDecisionService.AuthorizationDecision;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AuthorizationAdministrationService {
    private static final List<String> ADMIN_ROLES = List.of("SYSTEM_ADMIN", "CLINICAL_ADMIN", "SECURITY_ADMIN");
    private static final Set<String> EMERGENCY_RESOURCES = Set.of("CLINICAL_CONTEXT", "PATIENT", "ENCOUNTER", "DOCUMENT", "ORDER", "RESULT");
    private static final Set<String> EMERGENCY_ACTIONS = Set.of("LEASE_ISSUE", "READ", "CREATE", "UPDATE", "SIGN");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AuthorizationDecisionService decisions;

    AuthorizationAdministrationService(JdbcClient jdbc, TransactionTemplate transactions, AuthorizationDecisionService decisions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.decisions = decisions;
    }

    List<AuthorizationPolicyWire> listPolicies(ClinicalIdentity identity) {
        requireAdministrator(identity);
        return jdbc.sql("""
                select policy_id, policy_code, policy_name, version_no, effect, status, subject_role_code,
                  resource_type, action_code, organization_id, facility_id, department_id, ward_id,
                  patient_relationship_required, relationship_types, resource_statuses, purpose_codes,
                  emergency_override_allowed, priority, valid_from, valid_until, created_by, approved_by,
                  published_at, row_version
                from authorization_policy where tenant_id = :tenant
                order by policy_code, version_no desc
                """).param("tenant", identity.tenantId()).query((rs, row) -> policy(rs)).list();
    }

    AuthorizationPolicyWire createPolicy(ClinicalIdentity identity, String idempotencyKey, PolicyCreateRequest request) {
        requireAdministrator(identity);
        validatePolicy(request);
        return transactions.execute(status -> {
            String hash = sha256(request.toString());
            begin(identity, "AUTHORIZATION_POLICY_CREATE", idempotencyKey, hash);
            UUID policyId = request.policyId() == null ? UUID.randomUUID() : request.policyId();
            try {
                jdbc.sql("""
                        insert into authorization_policy(
                          tenant_id, policy_id, policy_code, policy_name, version_no, effect, status,
                          subject_role_code, resource_type, action_code, organization_id, facility_id,
                          department_id, ward_id, patient_relationship_required, relationship_types,
                          resource_statuses, purpose_codes, emergency_override_allowed, priority,
                          valid_from, valid_until, created_by)
                        values (:tenant, :policy, :code, :name, :version, :effect, 'DRAFT', :role,
                          :resource, :action, :organization, :facility, :department, :ward,
                          :relationship_required, cast(:relationship_types as text[]),
                          cast(:resource_statuses as text[]), cast(:purpose_codes as text[]),
                          :emergency_override, :priority, :valid_from, :valid_until, :actor)
                        """).param("tenant", identity.tenantId()).param("policy", policyId)
                        .param("code", request.policyCode().trim()).param("version", request.versionNo())
                        .param("name", request.policyName().trim())
                        .param("effect", request.effect().trim().toUpperCase()).param("role", blankToNull(request.subjectRoleCode()))
                        .param("resource", request.resourceType().trim().toUpperCase())
                        .param("action", request.actionCode().trim().toUpperCase())
                        .param("organization", request.organizationId()).param("facility", request.facilityId())
                        .param("department", request.departmentId()).param("ward", request.wardId())
                        .param("relationship_required", request.patientRelationshipRequired())
                        .param("relationship_types", textArray(request.relationshipTypes()))
                        .param("resource_statuses", textArray(request.resourceStatuses()))
                        .param("purpose_codes", textArray(request.purposeCodes()))
                        .param("emergency_override", request.emergencyOverrideAllowed())
                        .param("priority", request.priority()).param("valid_from", utc(request.validFrom()))
                        .param("valid_until", utc(request.validUntil())).param("actor", identity.userId()).update();
            } catch (DataIntegrityViolationException conflict) {
                throw conflict("The policy version, scope or protected-duty constraint conflicts with current data");
            }
            evidence(identity, "AUTHORIZATION_POLICY_DRAFTED", "AUTHORIZATION_POLICY", policyId, 1,
                    "policy_code", request.policyCode());
            complete(identity, "AUTHORIZATION_POLICY_CREATE", idempotencyKey, policyId);
            return findPolicy(identity.tenantId(), policyId);
        });
    }

    AuthorizationPolicyWire publishPolicy(ClinicalIdentity identity, String idempotencyKey, UUID policyId, PolicyPublishRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedRowVersion() < 1) throw invalid("Expected row version is required");
        return transactions.execute(status -> {
            begin(identity, "AUTHORIZATION_POLICY_PUBLISH", idempotencyKey,
                    sha256(policyId + "|" + request.expectedRowVersion()));
            int updated;
            try {
                updated = jdbc.sql("""
                        update authorization_policy set status = 'PUBLISHED', approved_by = :actor,
                          published_at = now(), row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and policy_id = :policy and status = 'DRAFT'
                          and row_version = :expected and created_by <> :actor
                        """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                        .param("policy", policyId).param("expected", request.expectedRowVersion()).update();
            } catch (DataIntegrityViolationException conflict) {
                throw conflict("Only one published version is allowed for the policy code");
            }
            if (updated != 1) throw conflict("The policy changed, is not a draft, or the creator attempted self-approval");
            evidence(identity, "AUTHORIZATION_POLICY_PUBLISHED", "AUTHORIZATION_POLICY", policyId,
                    request.expectedRowVersion() + 1, "approval", "INDEPENDENT");
            complete(identity, "AUTHORIZATION_POLICY_PUBLISH", idempotencyKey, policyId);
            return findPolicy(identity.tenantId(), policyId);
        });
    }

    AuthorizationDecision simulate(ClinicalIdentity identity, AuthorizationSimulationRequest request) {
        requireAdministrator(identity);
        if (request == null || request.targetUserId() == null || request.targetRoleAssignmentIds() == null
                || request.targetRoleAssignmentIds().isEmpty() || request.resourceType() == null || request.actionCode() == null) {
            throw invalid("Target identity, resource and action are required");
        }
        long target = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id = any(cast(:roles as uuid[])) and status = 'ACTIVE'
                """).param("tenant", identity.tenantId()).param("user", request.targetUserId())
                .param("roles", uuidArray(request.targetRoleAssignmentIds())).query(Long.class).single();
        if (target != request.targetRoleAssignmentIds().size()) throw invalid("Target role assignments are not active");
        AuthorizationDecision decision = decisions.evaluateForSimulation(
                new ClinicalIdentity(identity.tenantId(), request.targetUserId(), request.targetRoleAssignmentIds()),
                request.context());
        evidence(identity, "AUTHORIZATION_SIMULATED", "APP_USER", request.targetUserId(), 1,
                "decision", decision.reasonCode());
        return decision;
    }

    EmergencyAccessGrantWire requestEmergency(ClinicalIdentity identity, String idempotencyKey, EmergencyAccessRequest request) {
        validateEmergency(request);
        return transactions.execute(status -> {
            begin(identity, "EMERGENCY_ACCESS_REQUEST", idempotencyKey, sha256(request.toString()));
            long activeRole = jdbc.sql("""
                    select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                      and role_assignment_id = :role and role_assignment_id = any(cast(:roles as uuid[]))
                      and status = 'ACTIVE' and valid_from <= now() and (valid_until is null or valid_until > now())
                    """).param("tenant", identity.tenantId()).param("user", identity.userId())
                    .param("role", request.roleAssignmentId()).param("roles", uuidArray(identity.roleAssignmentIds()))
                    .query(Long.class).single();
            long patient = jdbc.sql("select count(*) from patient where tenant_id = :tenant and patient_id = :patient and status = 'ACTIVE'")
                    .param("tenant", identity.tenantId()).param("patient", request.patientId()).query(Long.class).single();
            if (activeRole != 1 || patient != 1) throw denied("Emergency access identity or patient is not permitted");
            UUID grantId = UUID.randomUUID();
            Instant now = Instant.now();
            jdbc.sql("""
                    insert into emergency_access_grant(
                      tenant_id, emergency_access_grant_id, user_id, role_assignment_id, patient_id,
                      encounter_id, resource_types, action_codes, reason, status, requested_at, expires_at)
                    values (:tenant, :grant, :user, :role, :patient, :encounter,
                      cast(:resources as text[]), cast(:actions as text[]), :reason, 'ACTIVE', :requested, :expires)
                    """).param("tenant", identity.tenantId()).param("grant", grantId).param("user", identity.userId())
                    .param("role", request.roleAssignmentId()).param("patient", request.patientId())
                    .param("encounter", request.encounterId()).param("resources", textArray(request.resourceTypes()))
                    .param("actions", textArray(request.actionCodes())).param("reason", request.reason().trim())
                    .param("requested", utc(now)).param("expires", utc(now.plusSeconds(request.durationMinutes() * 60L))).update();
            evidence(identity, "EMERGENCY_ACCESS_GRANTED", "EMERGENCY_ACCESS_GRANT", grantId, 1,
                    "patient_ref_hash", sha256(request.patientId().toString()));
            complete(identity, "EMERGENCY_ACCESS_REQUEST", idempotencyKey, grantId);
            return findGrant(identity.tenantId(), grantId);
        });
    }

    List<EmergencyAccessGrantWire> listOwnEmergency(ClinicalIdentity identity) {
        return listGrants(identity.tenantId(), "and user_id = :user", identity.userId());
    }

    List<EmergencyAccessGrantWire> listEmergencyForReview(ClinicalIdentity identity) {
        requireAdministrator(identity);
        return listGrants(identity.tenantId(), "", null);
    }

    EmergencyAccessGrantWire reviewEmergency(ClinicalIdentity identity, String idempotencyKey, UUID grantId, EmergencyReviewRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedRowVersion() < 1 || request.outcome() == null
                || !Set.of("APPROPRIATE", "INAPPROPRIATE", "ESCALATED").contains(request.outcome().toUpperCase())) {
            throw invalid("Expected version and a valid review outcome are required");
        }
        return transactions.execute(status -> {
            begin(identity, "EMERGENCY_ACCESS_REVIEW", idempotencyKey, sha256(grantId + "|" + request));
            int updated = jdbc.sql("""
                    update emergency_access_grant set status = 'REVIEWED', reviewed_by = :reviewer,
                      reviewed_at = now(), review_outcome = :outcome, review_note = :note,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and emergency_access_grant_id = :grant
                      and user_id <> :reviewer and status in ('ACTIVE', 'EXPIRED', 'REVOKED')
                      and row_version = :expected
                    """).param("reviewer", identity.userId()).param("outcome", request.outcome().toUpperCase())
                    .param("note", request.note()).param("tenant", identity.tenantId()).param("grant", grantId)
                    .param("expected", request.expectedRowVersion()).update();
            if (updated != 1) throw conflict("The emergency grant changed or cannot be reviewed by this user");
            evidence(identity, "EMERGENCY_ACCESS_REVIEWED", "EMERGENCY_ACCESS_GRANT", grantId,
                    request.expectedRowVersion() + 1, "outcome", request.outcome().toUpperCase());
            complete(identity, "EMERGENCY_ACCESS_REVIEW", idempotencyKey, grantId);
            return findGrant(identity.tenantId(), grantId);
        });
    }

    private void requireAdministrator(ClinicalIdentity identity) {
        long allowed = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id = any(cast(:roles as uuid[])) and role_code in (:admin_roles)
                  and status = 'ACTIVE' and valid_from <= now() and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", uuidArray(identity.roleAssignmentIds())).param("admin_roles", ADMIN_ROLES)
                .query(Long.class).single();
        if (allowed == 0) throw denied("Authorization administration scope is not permitted");
    }

    private void validatePolicy(PolicyCreateRequest request) {
        if (request == null || request.policyCode() == null || request.policyCode().isBlank()
                || request.policyName() == null || request.policyName().trim().length() < 2
                || request.policyName().trim().length() > 256
                || request.versionNo() < 1 || request.effect() == null
                || !Set.of("ALLOW", "DENY").contains(request.effect().toUpperCase())
                || request.resourceType() == null || request.resourceType().isBlank()
                || request.actionCode() == null || request.actionCode().isBlank()
                || request.validFrom() == null || request.priority() < 0 || request.priority() > 10000
                || (request.validUntil() != null && !request.validUntil().isAfter(request.validFrom()))
                || (request.patientRelationshipRequired() && safe(request.relationshipTypes()).isEmpty())) {
            throw invalid("A Chinese policy name, valid policy code, effect, resource, action, conditions and effective period are required");
        }
    }

    private void validateEmergency(EmergencyAccessRequest request) {
        if (request == null || request.roleAssignmentId() == null || request.patientId() == null
                || request.reason() == null || request.reason().trim().length() < 10
                || !request.riskAcknowledged() || request.durationMinutes() < 1 || request.durationMinutes() > 60
                || safe(request.resourceTypes()).isEmpty() || safe(request.actionCodes()).isEmpty()
                || !EMERGENCY_RESOURCES.containsAll(request.resourceTypes())
                || !EMERGENCY_ACTIONS.containsAll(request.actionCodes())) {
            throw invalid("Emergency access requires acknowledged risk, a detailed reason, minimum scope and a duration up to 60 minutes");
        }
    }

    private AuthorizationPolicyWire findPolicy(UUID tenantId, UUID policyId) {
        return jdbc.sql("""
                select policy_id, policy_code, policy_name, version_no, effect, status, subject_role_code,
                  resource_type, action_code, organization_id, facility_id, department_id, ward_id,
                  patient_relationship_required, relationship_types, resource_statuses, purpose_codes,
                  emergency_override_allowed, priority, valid_from, valid_until, created_by, approved_by,
                  published_at, row_version from authorization_policy
                where tenant_id = :tenant and policy_id = :policy
                """).param("tenant", tenantId).param("policy", policyId).query((rs, row) -> policy(rs)).single();
    }

    private AuthorizationPolicyWire policy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuthorizationPolicyWire(rs.getObject("policy_id", UUID.class), rs.getString("policy_code"),
                rs.getString("policy_name"),
                rs.getInt("version_no"), rs.getString("effect"), rs.getString("status"),
                rs.getString("subject_role_code"), rs.getString("resource_type"), rs.getString("action_code"),
                rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                rs.getObject("department_id", UUID.class), rs.getObject("ward_id", UUID.class),
                rs.getBoolean("patient_relationship_required"), List.of((String[]) rs.getArray("relationship_types").getArray()),
                List.of((String[]) rs.getArray("resource_statuses").getArray()),
                List.of((String[]) rs.getArray("purpose_codes").getArray()), rs.getBoolean("emergency_override_allowed"),
                rs.getInt("priority"), instant(rs.getObject("valid_from", OffsetDateTime.class)),
                instant(rs.getObject("valid_until", OffsetDateTime.class)), rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class), instant(rs.getObject("published_at", OffsetDateTime.class)),
                rs.getLong("row_version"));
    }

    private EmergencyAccessGrantWire findGrant(UUID tenantId, UUID grantId) {
        return jdbc.sql("""
                select emergency_access_grant_id, user_id, role_assignment_id, patient_id, encounter_id,
                  resource_types, action_codes, reason, status, requested_at, expires_at,
                  reviewed_by, reviewed_at, review_outcome, review_note, row_version
                from emergency_access_grant where tenant_id = :tenant and emergency_access_grant_id = :grant
                """).param("tenant", tenantId).param("grant", grantId).query((rs, row) -> new EmergencyAccessGrantWire(
                        rs.getObject("emergency_access_grant_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("role_assignment_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), List.of((String[]) rs.getArray("resource_types").getArray()),
                        List.of((String[]) rs.getArray("action_codes").getArray()), rs.getString("reason"), rs.getString("status"),
                        instant(rs.getObject("requested_at", OffsetDateTime.class)), instant(rs.getObject("expires_at", OffsetDateTime.class)),
                        rs.getObject("reviewed_by", UUID.class), instant(rs.getObject("reviewed_at", OffsetDateTime.class)),
                        rs.getString("review_outcome"), rs.getString("review_note"), rs.getLong("row_version"))).single();
    }

    private List<EmergencyAccessGrantWire> listGrants(UUID tenantId, String predicate, UUID userId) {
        String sql = """
                select emergency_access_grant_id, user_id, role_assignment_id, patient_id, encounter_id,
                  resource_types, action_codes, reason,
                  case when status = 'ACTIVE' and expires_at <= now() then 'EXPIRED' else status end as status,
                  requested_at, expires_at, reviewed_by, reviewed_at, review_outcome, review_note, row_version
                from emergency_access_grant where tenant_id = :tenant
                """ + predicate + " order by requested_at desc, emergency_access_grant_id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("tenant", tenantId);
        if (userId != null) statement = statement.param("user", userId);
        return statement.query((rs, row) -> new EmergencyAccessGrantWire(
                rs.getObject("emergency_access_grant_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("role_assignment_id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getObject("encounter_id", UUID.class), List.of((String[]) rs.getArray("resource_types").getArray()),
                List.of((String[]) rs.getArray("action_codes").getArray()), rs.getString("reason"), rs.getString("status"),
                instant(rs.getObject("requested_at", OffsetDateTime.class)), instant(rs.getObject("expires_at", OffsetDateTime.class)),
                rs.getObject("reviewed_by", UUID.class), instant(rs.getObject("reviewed_at", OffsetDateTime.class)),
                rs.getString("review_outcome"), rs.getString("review_note"), rs.getLong("row_version"))).list();
    }

    private void begin(ClinicalIdentity identity, String scope, String key, String hash) {
        if (key == null || key.isBlank()) throw invalid("Idempotency-Key is required");
        int inserted = jdbc.sql("""
                insert into idempotency_record(tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", hash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw conflict("This authorization command was already submitted");
    }

    private void complete(ClinicalIdentity identity, String scope, String key, UUID resource) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resource).param("tenant", identity.tenantId()).param("scope", scope).param("key", key).update();
    }

    private void evidence(ClinicalIdentity identity, String action, String resourceType, UUID resourceId,
                          long version, String detailKey, String detailValue) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID audit = UUID.randomUUID(); String trace = UUID.randomUUID().toString();
        String hash = sha256(identity.tenantId() + "|" + audit + "|" + action + "|" + resourceId + "|" + trace + "|" + previous);
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, :resource_type, :resource,
                  :trace, :previous, :hash, jsonb_build_object(:detail_key, :detail_value))
                """).param("tenant", identity.tenantId()).param("audit", audit).param("actor", identity.userId())
                .param("action", action).param("resource_type", resourceType).param("resource", resourceId)
                .param("trace", trace).param("previous", previous).param("hash", hash)
                .param("detail_key", detailKey).param("detail_value", detailValue).update();
        if (!"AUTHORIZATION_SIMULATED".equals(action)) {
            jdbc.sql("""
                    insert into outbox_event(tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                      event_type, schema_version, payload)
                    values (:tenant, :event, :type, :resource, :version, :event_type, 1,
                      jsonb_build_object('resource_id', :resource))
                    """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID()).param("type", resourceType)
                    .param("resource", resourceId).param("version", version).param("event_type", action).update();
        }
    }

    private static AuthorizationAdministrationException invalid(String message) {
        return new AuthorizationAdministrationException("AUTHORIZATION_REQUEST_INVALID", 400, message);
    }
    private static AuthorizationAdministrationException denied(String message) {
        return new AuthorizationAdministrationException("AUTHORIZATION_SCOPE_DENIED", 403, message);
    }
    private static AuthorizationAdministrationException conflict(String message) {
        return new AuthorizationAdministrationException("AUTHORIZATION_VERSION_CONFLICT", 409, message);
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(); }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private static String uuidArray(List<UUID> values) { return "{" + safe(values).stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("") + "}"; }
    private static String textArray(List<String> values) { return "{" + String.join(",", safe(values).stream().map(String::toUpperCase).toList()) + "}"; }
    private static OffsetDateTime utc(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record PolicyCreateRequest(UUID policyId, String policyCode, String policyName, int versionNo, String effect, String subjectRoleCode,
            String resourceType, String actionCode, UUID organizationId, UUID facilityId, UUID departmentId,
            UUID wardId, boolean patientRelationshipRequired, List<String> relationshipTypes,
            List<String> resourceStatuses, List<String> purposeCodes, boolean emergencyOverrideAllowed,
            int priority, Instant validFrom, Instant validUntil) {}
    record PolicyPublishRequest(long expectedRowVersion) {}
    record AuthorizationSimulationRequest(UUID targetUserId, List<UUID> targetRoleAssignmentIds,
            String resourceType, String actionCode, UUID organizationId, UUID facilityId, UUID departmentId,
            UUID wardId, UUID patientId, UUID encounterId, String purposeCode, String resourceStatus) {
        AuthorizationContext context() { return new AuthorizationContext(resourceType.toUpperCase(), actionCode.toUpperCase(),
                organizationId, facilityId, departmentId, wardId, patientId, encounterId,
                purposeCode == null ? null : purposeCode.toUpperCase(), resourceStatus == null ? null : resourceStatus.toUpperCase()); }
    }
    record EmergencyAccessRequest(UUID roleAssignmentId, UUID patientId, UUID encounterId,
            List<String> resourceTypes, List<String> actionCodes, String reason, int durationMinutes,
            boolean riskAcknowledged) {}
    record EmergencyReviewRequest(long expectedRowVersion, String outcome, String note) {}
    record AuthorizationPolicyWire(UUID policyId, String policyCode, String policyName, int versionNo, String effect, String status,
            String subjectRoleCode, String resourceType, String actionCode, UUID organizationId, UUID facilityId,
            UUID departmentId, UUID wardId, boolean patientRelationshipRequired, List<String> relationshipTypes,
            List<String> resourceStatuses, List<String> purposeCodes, boolean emergencyOverrideAllowed,
            int priority, Instant validFrom, Instant validUntil, UUID createdBy, UUID approvedBy,
            Instant publishedAt, long rowVersion) {}
    record EmergencyAccessGrantWire(UUID emergencyAccessGrantId, UUID userId, UUID roleAssignmentId,
            UUID patientId, UUID encounterId, List<String> resourceTypes, List<String> actionCodes,
            String reason, String status, Instant requestedAt, Instant expiresAt, UUID reviewedBy,
            Instant reviewedAt, String reviewOutcome, String reviewNote, long rowVersion) {}
}
