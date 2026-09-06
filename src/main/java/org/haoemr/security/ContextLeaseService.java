package org.haoemr.security;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.haoemr.security.AuthorizationDecisionService.AuthorizationContext;
import org.haoemr.security.AuthorizationDecisionService.DepartmentWardScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ContextLeaseService {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ContextLeasePolicy policy;
    private final AuthorizationDecisionService authorization;
    private final boolean requirePublishedAuthorization;

    ContextLeaseService(JdbcClient jdbc, TransactionTemplate transactions, ContextLeasePolicy policy,
            AuthorizationDecisionService authorization,
            @Value("${haoemr.security.require-published-authorization:false}")
            boolean requirePublishedAuthorization) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.policy = policy;
        this.authorization = authorization;
        this.requirePublishedAuthorization = requirePublishedAuthorization;
    }

    ContextLease issue(
            ClinicalIdentity identity,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            UUID taskId,
            String purposeCode) {
        return transactions.execute(status -> {
            validateScope(identity, organizationId, facilityId, patientId, encounterId);
            requirePublishedAuthorization(identity, organizationId, facilityId, patientId, encounterId, purposeCode);
            ContextLease lease = policy.issue(
                    identity, organizationId, facilityId, patientId, encounterId, taskId, purposeCode);
            persist(lease);
            recordEvidence(lease);
            return lease;
        });
    }

    void requirePublishedAuthorization(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId,
            UUID patientId, UUID encounterId, String purposeCode) {
        if (!authorization.hasPublishedPolicy(identity.tenantId(), "CLINICAL_CONTEXT", "LEASE_ISSUE")) {
            if (requirePublishedAuthorization) {
                throw new ClinicalAccessDeniedException(
                        "AUTHORIZATION_POLICY_MISSING",
                        "No published clinical-context authorization policy is available");
            }
            return;
        }
        DepartmentWardScope scope = authorization.resolveScope(identity, organizationId, facilityId, encounterId);
        var decision = authorization.evaluate(identity, new AuthorizationContext(
                "CLINICAL_CONTEXT", "LEASE_ISSUE", organizationId, facilityId,
                scope.departmentId(), scope.wardId(), patientId, encounterId, purposeCode, "ACTIVE"));
        if (!decision.allowed()) {
            throw new ClinicalAccessDeniedException(
                    "CONFIG_SCOPE_DENIED".equals(decision.reasonCode())
                            ? "CONFIG_SCOPE_DENIED" : "AUTHORIZATION_POLICY_DENIED",
                    "The requested clinical context is not permitted");
        }
    }

    private void validateScope(
            ClinicalIdentity identity,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId) {
        if (identity.roleAssignmentIds().isEmpty()) {
            denyContext();
        }
        long activeRoles = jdbc.sql("""
                select count(*) from role_assignment assignment
                join app_user account on account.tenant_id = assignment.tenant_id
                  and account.user_id = assignment.user_id and account.person_id = assignment.person_id
                join workforce_person person on person.tenant_id = account.tenant_id
                  and person.person_id = account.person_id
                join workforce_assignment workforce on workforce.tenant_id = assignment.tenant_id
                  and workforce.source_role_assignment_id = assignment.role_assignment_id
                where assignment.tenant_id = :tenant and assignment.user_id = :user
                  and assignment.organization_id = :organization
                  and (assignment.facility_id is null or assignment.facility_id = :facility)
                  and assignment.role_assignment_id = any(cast(:roles as uuid[]))
                  and account.status = 'ACTIVE' and person.status = 'ACTIVE'
                  and person.effective_from <= now()
                  and (person.effective_until is null or person.effective_until > now())
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= now()
                  and (assignment.valid_until is null or assignment.valid_until > now())
                  and workforce.status = 'ACTIVE' and workforce.valid_from <= now()
                  and (workforce.valid_until is null or workforce.valid_until > now())
                """)
                .param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("organization", organizationId).param("facility", facilityId)
                .param("roles", postgresUuidArray(identity.roleAssignmentIds()))
                .query(Long.class).single();
        if (activeRoles != identity.roleAssignmentIds().size()) {
            denyContext();
        }

        long facilityScope = jdbc.sql("""
                select count(*) from facility site
                join organization parent on parent.tenant_id = site.tenant_id
                  and parent.organization_id = site.organization_id
                where site.tenant_id = :tenant and site.organization_id = :organization
                  and site.facility_id = :facility and site.status = 'ACTIVE'
                  and site.effective_from <= now()
                  and (site.effective_until is null or site.effective_until > now())
                  and parent.status = 'ACTIVE' and parent.effective_from <= now()
                  and (parent.effective_until is null or parent.effective_until > now())
                """)
                .param("tenant", identity.tenantId()).param("organization", organizationId)
                .param("facility", facilityId).query(Long.class).single();
        if (facilityScope != 1) {
            denyContext();
        }

        if (patientId != null) {
            long patientScope = jdbc.sql("""
                    select count(*) from patient
                    where tenant_id = :tenant and patient_id = :patient and status = 'ACTIVE'
                    """)
                    .param("tenant", identity.tenantId()).param("patient", patientId)
                    .query(Long.class).single();
            if (patientScope != 1) {
                denyContext();
            }
        }

        if (encounterId != null) {
            if (patientId == null) {
                denyContext();
            }
            long encounterScope = jdbc.sql("""
                    select count(*) from encounter
                    where tenant_id = :tenant and encounter_id = :encounter and patient_id = :patient
                      and organization_id = :organization and facility_id = :facility
                      and status in ('PLANNED', 'ARRIVED', 'IN_PROGRESS', 'SUSPENDED', 'FINISHED')
                    """)
                    .param("tenant", identity.tenantId()).param("encounter", encounterId)
                    .param("patient", patientId).param("organization", organizationId)
                    .param("facility", facilityId).query(Long.class).single();
            if (encounterScope != 1) {
                denyContext();
            }
        }
    }

    private void persist(ContextLease lease) {
        jdbc.sql("""
                insert into context_lease(
                  tenant_id, lease_id, organization_id, facility_id, user_id,
                  role_assignment_ids, patient_id, encounter_id, task_id, purpose_code,
                  allowed_source_types, authorization_watermark, data_classification_ceiling,
                  model_residency_policy, issued_at, expires_at)
                values (
                  :tenant, :lease, :organization, :facility, :user,
                  cast(:roles as uuid[]), :patient, :encounter, :task, :purpose,
                  cast(:sources as text[]), :watermark, :classification,
                  :residency, :issued, :expires)
                """)
                .param("tenant", lease.tenantId()).param("lease", lease.leaseId())
                .param("organization", lease.organizationId()).param("facility", lease.facilityId())
                .param("user", lease.userId()).param("roles", postgresUuidArray(lease.roleAssignmentIds()))
                .param("patient", lease.patientId()).param("encounter", lease.encounterId())
                .param("task", lease.taskId()).param("purpose", lease.purposeCode())
                .param("sources", postgresTextArray(lease.allowedSourceTypes()))
                .param("watermark", lease.authorizationWatermark())
                .param("classification", lease.dataClassificationCeiling())
                .param("residency", lease.modelResidencyPolicy())
                .param("issued", OffsetDateTime.ofInstant(lease.issuedAt(), ZoneOffset.UTC))
                .param("expires", OffsetDateTime.ofInstant(lease.expiresAt(), ZoneOffset.UTC))
                .update();
    }

    private void recordEvidence(ContextLease lease) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", lease.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """)
                .param("tenant", lease.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String traceId = lease.leaseId().toString();
        String eventHash = ContextLeasePolicy.sha256(String.join("|",
                lease.tenantId().toString(), auditId.toString(), "CONTEXT_LEASE_ISSUED",
                lease.leaseId().toString(), lease.authorizationWatermark(),
                previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, :occurred, :actor, 'CONTEXT_LEASE_ISSUED',
                  'CONTEXT_LEASE', :lease, :trace, :previous_hash, :hash,
                  jsonb_build_object('purpose_code', :purpose, 'authorization_watermark', :watermark))
                """)
                .param("tenant", lease.tenantId()).param("audit", auditId)
                .param("occurred", OffsetDateTime.ofInstant(lease.issuedAt(), ZoneOffset.UTC))
                .param("actor", lease.userId()).param("lease", lease.leaseId()).param("trace", traceId)
                .param("previous_hash", previousHash).param("hash", eventHash).param("purpose", lease.purposeCode())
                .param("watermark", lease.authorizationWatermark()).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CONTEXT_LEASE', :lease, 1,
                  'ContextLeaseIssued', 1,
                  jsonb_build_object('lease_id', :lease, 'expires_at', :expires,
                    'authorization_watermark', :watermark))
                """)
                .param("tenant", lease.tenantId()).param("event", UUID.randomUUID())
                .param("lease", lease.leaseId())
                .param("expires", OffsetDateTime.ofInstant(lease.expiresAt(), ZoneOffset.UTC))
                .param("watermark", lease.authorizationWatermark()).update();
    }

    private static String postgresUuidArray(List<UUID> values) {
        return "{" + values.stream().map(UUID::toString).reduce((left, right) -> left + "," + right).orElse("") + "}";
    }

    private static String postgresTextArray(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static void denyContext() {
        throw new ClinicalAccessDeniedException("CONTEXT_NOT_PERMITTED", "The requested clinical context is not permitted");
    }
}
