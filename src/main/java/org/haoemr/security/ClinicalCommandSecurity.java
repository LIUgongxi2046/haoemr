package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.AuthorizationDecisionService.AuthorizationContext;
import org.haoemr.security.AuthorizationDecisionService.DepartmentWardScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class ClinicalCommandSecurity {

    private final ClinicalIdentityProvider identities;
    private final JdbcClient jdbc;
    private final AuthorizationDecisionService authorization;
    private final boolean requirePublishedAuthorization;

    ClinicalCommandSecurity(ClinicalIdentityProvider identities, JdbcClient jdbc,
            AuthorizationDecisionService authorization,
            @Value("${haoemr.security.require-published-authorization:false}")
            boolean requirePublishedAuthorization) {
        this.identities = identities;
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.requirePublishedAuthorization = requirePublishedAuthorization;
    }

    public ClinicalIdentity authenticate(HttpServletRequest request) {
        return identities.current(request);
    }

    public ClinicalIdentity authorize(
            HttpServletRequest request,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId) {
        return authorizeForPurposes(request, organizationId, facilityId, patientId, encounterId, Set.of());
    }

    public ClinicalIdentity authorizeForPurposes(
            HttpServletRequest request,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            Set<String> allowedPurposeCodes) {
        ClinicalIdentity identity = identities.current(request);
        UUID leaseId = requiredUuidHeader(request, "X-Context-Lease-Id");
        String watermark = requiredHeader(request, "X-Authorization-Watermark");
        requireHeaderContext(request, "X-Organization-Context", organizationId);
        requireHeaderContext(request, "X-Facility-Context", facilityId);
        requireHeaderContext(request, "X-Patient-Context", patientId);
        requireHeaderContext(request, "X-Encounter-Context", encounterId);

        String roles = "{" + identity.roleAssignmentIds().stream()
                .map(UUID::toString).reduce((left, right) -> left + "," + right).orElse("") + "}";
        String purposeCode = jdbc.sql("""
                select lease.purpose_code from context_lease lease
                where lease.tenant_id = :tenant and lease.lease_id = :lease
                  and lease.user_id = :user and lease.organization_id = :organization
                  and lease.facility_id = :facility
                  and lease.patient_id is not distinct from :patient
                  and lease.encounter_id is not distinct from :encounter
                  and lease.authorization_watermark = :watermark
                  and lease.role_assignment_ids @> cast(:roles as uuid[])
                  and lease.role_assignment_ids <@ cast(:roles as uuid[])
                  and lease.revoked_at is null and lease.expires_at > now()
                  and not exists (
                    select 1 from unnest(lease.role_assignment_ids) role_id
                    where not exists (
                      select 1 from role_assignment assignment
                      join app_user account on account.tenant_id = assignment.tenant_id
                        and account.user_id = assignment.user_id and account.person_id = assignment.person_id
                      join workforce_person person on person.tenant_id = account.tenant_id
                        and person.person_id = account.person_id
                      join workforce_assignment workforce on workforce.tenant_id = assignment.tenant_id
                        and workforce.source_role_assignment_id = assignment.role_assignment_id
                      where assignment.tenant_id = lease.tenant_id
                        and assignment.role_assignment_id = role_id
                        and assignment.user_id = lease.user_id
                        and assignment.organization_id = lease.organization_id
                        and (assignment.facility_id is null or assignment.facility_id = lease.facility_id)
                        and assignment.status = 'ACTIVE' and assignment.valid_from <= now()
                        and (assignment.valid_until is null or assignment.valid_until > now())
                        and account.status = 'ACTIVE' and person.status = 'ACTIVE'
                        and person.effective_from <= now()
                        and (person.effective_until is null or person.effective_until > now())
                        and workforce.status = 'ACTIVE' and workforce.valid_from <= now()
                        and (workforce.valid_until is null or workforce.valid_until > now())
                    )
                  )
                """)
                .param("tenant", identity.tenantId()).param("lease", leaseId).param("user", identity.userId())
                .param("organization", organizationId).param("facility", facilityId)
                .param("patient", patientId).param("encounter", encounterId)
                .param("watermark", watermark).param("roles", roles)
                .query(String.class).optional().orElse(null);
        if (purposeCode == null) {
            throw new ClinicalAccessDeniedException(
                    "CONTEXT_NOT_PERMITTED", "The requested clinical context is not permitted");
        }
        if (allowedPurposeCodes != null && !allowedPurposeCodes.isEmpty()
                && !allowedPurposeCodes.contains(purposeCode)) {
            throw new ClinicalAccessDeniedException(
                    "PURPOSE_NOT_PERMITTED", "The context lease purpose is not permitted for this operation");
        }
        requireCurrentAuthorization(identity, organizationId, facilityId, patientId, encounterId, purposeCode);
        return identity;
    }

    private void requireCurrentAuthorization(
            ClinicalIdentity identity,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            String purposeCode) {
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
                    "The current clinical authorization is no longer permitted");
        }
    }

    private static void requireHeaderContext(HttpServletRequest request, String name, UUID expected) {
        String actual = request.getHeader(name);
        if (expected == null) {
            if (actual != null && !actual.isBlank()) {
                throw new ClinicalAccessDeniedException(
                        "CONTEXT_NOT_PERMITTED", "The requested clinical context is not permitted");
            }
            return;
        }
        if (!expected.toString().equals(actual)) {
            throw new ClinicalAccessDeniedException(
                    "CONTEXT_NOT_PERMITTED", "The requested clinical context is not permitted");
        }
    }

    private static UUID requiredUuidHeader(HttpServletRequest request, String name) {
        try {
            return UUID.fromString(requiredHeader(request, name));
        } catch (IllegalArgumentException invalid) {
            throw new ClinicalAccessDeniedException(
                    "CONTEXT_NOT_PERMITTED", "The requested clinical context is not permitted");
        }
    }

    private static String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new ClinicalAccessDeniedException(
                    "CONTEXT_NOT_PERMITTED", "The requested clinical context is not permitted");
        }
        return value;
    }
}
