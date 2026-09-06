package org.haoemr.security;

import java.util.List;
import java.util.UUID;
import org.haoemr.configuration.ConfigurationRuntimeService;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public final class AuthorizationDecisionService {
    private final JdbcClient jdbc;
    private final ConfigurationRuntimeService configurationRuntime;

    AuthorizationDecisionService(JdbcClient jdbc, ConfigurationRuntimeService configurationRuntime) {
        this.jdbc = jdbc;
        this.configurationRuntime = configurationRuntime;
    }

    public AuthorizationDecision evaluate(ClinicalIdentity identity, AuthorizationContext context) {
        return evaluate(identity, context, true);
    }

    /** Administrative simulations must not write runtime authorization evidence. */
    public AuthorizationDecision evaluateForSimulation(ClinicalIdentity identity, AuthorizationContext context) {
        return evaluate(identity, context, false);
    }

    private AuthorizationDecision evaluate(
            ClinicalIdentity identity, AuthorizationContext context, boolean applyConfigurationScope) {
        List<PolicyMatch> policies = jdbc.sql("""
                select policy.policy_id, policy.policy_code, policy.effect,
                  policy.patient_relationship_required, policy.relationship_types
                from authorization_policy policy
                where policy.tenant_id = :tenant and policy.status = 'PUBLISHED'
                  and policy.resource_type = :resource and policy.action_code = :action
                  and policy.valid_from <= now() and (policy.valid_until is null or policy.valid_until > now())
                  and (policy.subject_role_code is null or exists (
                    select 1 from role_assignment role
                    join workforce_assignment workforce on workforce.tenant_id = role.tenant_id
                      and workforce.source_role_assignment_id = role.role_assignment_id
                    where role.tenant_id = policy.tenant_id and role.user_id = :user
                      and role.role_assignment_id = any(cast(:roles as uuid[]))
                      and role.role_code = policy.subject_role_code
                      and role.status = 'ACTIVE' and role.valid_from <= now()
                      and (role.valid_until is null or role.valid_until > now())
                      and workforce.status = 'ACTIVE' and workforce.valid_from <= now()
                      and (workforce.valid_until is null or workforce.valid_until > now())))
                  and (policy.organization_id is null or policy.organization_id = :organization)
                  and (policy.facility_id is null or policy.facility_id = :facility)
                  and (policy.department_id is null or policy.department_id = :department)
                  and (policy.ward_id is null or policy.ward_id = :ward)
                  and (cardinality(policy.resource_statuses) = 0 or :resource_status = any(policy.resource_statuses))
                  and (cardinality(policy.purpose_codes) = 0 or :purpose = any(policy.purpose_codes))
                order by case policy.effect when 'DENY' then 0 else 1 end, policy.priority desc, policy.policy_id
                """)
                .param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", postgresUuidArray(identity.roleAssignmentIds()))
                .param("resource", context.resourceType()).param("action", context.actionCode())
                .param("organization", context.organizationId()).param("facility", context.facilityId())
                .param("department", context.departmentId()).param("ward", context.wardId())
                .param("resource_status", context.resourceStatus()).param("purpose", context.purposeCode())
                .query((rs, row) -> new PolicyMatch(
                        rs.getObject("policy_id", UUID.class), rs.getString("policy_code"),
                        rs.getString("effect"), rs.getBoolean("patient_relationship_required"),
                        List.of((String[]) rs.getArray("relationship_types").getArray())))
                .list();

        for (PolicyMatch policy : policies) {
            if ("DENY".equals(policy.effect()) && relationshipSatisfied(identity, context, policy)) {
                return new AuthorizationDecision(false, "EXPLICIT_DENY", List.of(policy.policyId()), null,
                        "Explicit deny policy " + policy.policyCode() + " matched");
            }
        }
        for (PolicyMatch policy : policies) {
            if ("ALLOW".equals(policy.effect()) && relationshipSatisfied(identity, context, policy)) {
                return applyConfigurationScope(identity, context, applyConfigurationScope,
                        new AuthorizationDecision(true, "POLICY_ALLOW", List.of(policy.policyId()), null,
                                "Allow policy " + policy.policyCode() + " matched"));
            }
        }
        UUID emergencyGrant = activeEmergencyGrant(identity, context);
        if (emergencyGrant != null) {
            return applyConfigurationScope(identity, context, applyConfigurationScope,
                    new AuthorizationDecision(true, "EMERGENCY_ACCESS", List.of(), emergencyGrant,
                            "A current minimum-necessary emergency grant matched"));
        }
        return new AuthorizationDecision(false, policies.isEmpty() ? "NO_PUBLISHED_POLICY" : "CONDITIONS_NOT_MET",
                policies.stream().map(PolicyMatch::policyId).toList(), null,
                policies.isEmpty() ? "No published policy matched the resource and action" : "Policy conditions were not met");
    }

    private AuthorizationDecision applyConfigurationScope(
            ClinicalIdentity identity, AuthorizationContext context, boolean enabled, AuthorizationDecision decision) {
        if (!enabled || context.organizationId() == null || context.facilityId() == null
                || context.resourceType() == null || context.actionCode() == null || context.purposeCode() == null) {
            return decision;
        }
        List<String> roles = activeRoleCodes(identity, context.organizationId(), context.facilityId());
        boolean relationshipVerified = context.patientId() == null
                || hasCurrentPatientRelationship(identity, context.patientId(), context.encounterId());
        var outcome = configurationRuntime.authorizeScopesForAuthorizationIfBound(identity,
                new RuntimeContext(context.organizationId(), context.facilityId(), context.patientId(), context.encounterId()),
                context.resourceType(), context.actionCode(), context.purposeCode(), roles,
                relationshipVerified, false);
        if (outcome.authorized()) return decision;
        return new AuthorizationDecision(false, "CONFIG_SCOPE_DENIED", decision.matchedPolicyIds(), null,
                "A published minimum-necessary scope configuration denied the authorized request");
    }

    private List<String> activeRoleCodes(ClinicalIdentity identity, UUID organizationId, UUID facilityId) {
        return jdbc.sql("""
                select distinct role.role_code
                from role_assignment role
                join workforce_assignment workforce on workforce.tenant_id = role.tenant_id
                  and workforce.source_role_assignment_id = role.role_assignment_id
                where role.tenant_id = :tenant and role.user_id = :user
                  and role.role_assignment_id = any(cast(:roles as uuid[]))
                  and role.organization_id = :organization
                  and (role.facility_id is null or role.facility_id = :facility)
                  and role.status = 'ACTIVE' and role.valid_from <= now()
                  and (role.valid_until is null or role.valid_until > now())
                  and workforce.status = 'ACTIVE' and workforce.valid_from <= now()
                  and (workforce.valid_until is null or workforce.valid_until > now())
                order by role.role_code
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", postgresUuidArray(identity.roleAssignmentIds()))
                .param("organization", organizationId).param("facility", facilityId)
                .query(String.class).list();
    }

    private boolean hasCurrentPatientRelationship(ClinicalIdentity identity, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select exists(
                  select 1 from patient_care_relationship relationship
                  where relationship.tenant_id = :tenant and relationship.patient_id in (
                    select patient_id from patient where tenant_id = :tenant
                      and (patient_id = :patient or merged_into_patient_id = :patient))
                    and relationship.user_id = :user
                    and relationship.role_assignment_id = any(cast(:roles as uuid[]))
                    and relationship.status = 'ACTIVE' and relationship.valid_from <= now()
                    and (relationship.valid_until is null or relationship.valid_until > now())
                    and (:encounter is null or relationship.encounter_id is null or relationship.encounter_id = :encounter))
                """).param("tenant", identity.tenantId()).param("patient", patientId).param("user", identity.userId())
                .param("roles", postgresUuidArray(identity.roleAssignmentIds())).param("encounter", encounterId)
                .query(Boolean.class).single();
    }

    public boolean hasPublishedPolicy(UUID tenantId, String resourceType, String actionCode) {
        return jdbc.sql("""
                select count(*) from authorization_policy
                where tenant_id = :tenant and status = 'PUBLISHED'
                  and resource_type = :resource and action_code = :action
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                """).param("tenant", tenantId).param("resource", resourceType).param("action", actionCode)
                .query(Long.class).single() > 0;
    }

    public DepartmentWardScope resolveScope(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID encounterId) {
        if (encounterId != null) {
            DepartmentWardScope admissionScope = jdbc.sql("""
                    select ward.department_id, admission.ward_id
                    from inpatient_admission admission
                    join clinical_ward ward on ward.tenant_id = admission.tenant_id
                      and ward.ward_id = admission.ward_id
                    where admission.tenant_id = :tenant and admission.encounter_id = :encounter
                      and admission.facility_id = :facility
                      and admission.status in ('ADMITTED', 'TRANSFER_PENDING', 'DISCHARGE_PENDING')
                    """).param("tenant", identity.tenantId()).param("encounter", encounterId)
                    .param("facility", facilityId)
                    .query((rs, row) -> new DepartmentWardScope(
                            rs.getObject("department_id", UUID.class), rs.getObject("ward_id", UUID.class)))
                    .optional().orElse(null);
            if (admissionScope != null) return admissionScope;
        }

        List<DepartmentWardScope> workforceScopes = jdbc.sql("""
                select distinct workforce.department_id, workforce.ward_id
                from workforce_assignment workforce
                where workforce.tenant_id = :tenant and workforce.organization_id = :organization
                  and (workforce.facility_id is null or workforce.facility_id = :facility)
                  and workforce.source_role_assignment_id = any(cast(:roles as uuid[]))
                  and workforce.status = 'ACTIVE' and workforce.valid_from <= now()
                  and (workforce.valid_until is null or workforce.valid_until > now())
                  and (workforce.department_id is not null or workforce.ward_id is not null)
                """).param("tenant", identity.tenantId()).param("organization", organizationId)
                .param("facility", facilityId).param("roles", postgresUuidArray(identity.roleAssignmentIds()))
                .query((rs, row) -> new DepartmentWardScope(
                        rs.getObject("department_id", UUID.class), rs.getObject("ward_id", UUID.class)))
                .list();
        return workforceScopes.size() == 1 ? workforceScopes.getFirst() : new DepartmentWardScope(null, null);
    }

    private boolean relationshipSatisfied(ClinicalIdentity identity, AuthorizationContext context, PolicyMatch policy) {
        if (!policy.relationshipRequired()) return true;
        if (context.patientId() == null) return false;
        return jdbc.sql("""
                select count(*) from patient_care_relationship relationship
                where relationship.tenant_id = :tenant and relationship.patient_id in (
                    select patient_id from patient
                    where tenant_id = :tenant and (patient_id = :patient or merged_into_patient_id = :patient))
                  and relationship.user_id = :user
                  and relationship.role_assignment_id = any(cast(:roles as uuid[]))
                  and relationship.relationship_type = any(cast(:types as text[]))
                  and relationship.status = 'ACTIVE' and relationship.valid_from <= now()
                  and (relationship.valid_until is null or relationship.valid_until > now())
                  and (:encounter is null or relationship.encounter_id is null or relationship.encounter_id = :encounter)
                """).param("tenant", identity.tenantId()).param("patient", context.patientId())
                .param("user", identity.userId()).param("roles", postgresUuidArray(identity.roleAssignmentIds()))
                .param("types", postgresTextArray(policy.relationshipTypes())).param("encounter", context.encounterId())
                .query(Long.class).single() > 0;
    }

    private UUID activeEmergencyGrant(ClinicalIdentity identity, AuthorizationContext context) {
        if (context.patientId() == null) return null;
        return jdbc.sql("""
                select emergency_access_grant_id from emergency_access_grant
                where tenant_id = :tenant and user_id = :user
                  and role_assignment_id = any(cast(:roles as uuid[])) and patient_id in (
                    select patient_id from patient
                    where tenant_id = :tenant and (patient_id = :patient or merged_into_patient_id = :patient))
                  and status = 'ACTIVE' and requested_at <= now() and expires_at > now()
                  and :resource = any(resource_types) and :action = any(action_codes)
                  and (:encounter is null or encounter_id is null or encounter_id = :encounter)
                order by expires_at limit 1
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", postgresUuidArray(identity.roleAssignmentIds())).param("patient", context.patientId())
                .param("resource", context.resourceType()).param("action", context.actionCode())
                .param("encounter", context.encounterId()).query(UUID.class).optional().orElse(null);
    }

    private static String postgresUuidArray(List<UUID> values) {
        return "{" + values.stream().map(UUID::toString).reduce((left, right) -> left + "," + right).orElse("") + "}";
    }

    private static String postgresTextArray(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private record PolicyMatch(UUID policyId, String policyCode, String effect, boolean relationshipRequired,
                               List<String> relationshipTypes) {}

    public record AuthorizationContext(
            String resourceType, String actionCode, UUID organizationId, UUID facilityId,
            UUID departmentId, UUID wardId, UUID patientId, UUID encounterId,
            String purposeCode, String resourceStatus) {}

    public record AuthorizationDecision(
            boolean allowed, String reasonCode, List<UUID> matchedPolicyIds,
            UUID emergencyAccessGrantId, String explanation) {}

    public record DepartmentWardScope(UUID departmentId, UUID wardId) {}
}
