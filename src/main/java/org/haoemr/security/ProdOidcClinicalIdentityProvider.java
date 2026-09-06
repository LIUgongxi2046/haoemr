package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class ProdOidcClinicalIdentityProvider implements ClinicalIdentityProvider {

    private final JdbcClient jdbc;
    private final String requiredAcr;

    ProdOidcClinicalIdentityProvider(
            JdbcClient jdbc,
            @Value("${haoemr.production.identity.required-acr}") String requiredAcr) {
        this.jdbc = jdbc;
        this.requiredAcr = requiredAcr;
    }

    @Override
    public ClinicalIdentity current(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            denyAuthentication();
        }

        Jwt jwt = ((JwtAuthenticationToken) authentication).getToken();
        String subject;
        String tenantClaim;
        String acr;
        try {
            subject = jwt.getSubject();
            tenantClaim = jwt.getClaimAsString("tenant_id");
            acr = jwt.getClaimAsString("acr");
        } catch (RuntimeException malformedClaim) {
            denyAuthentication();
            throw new IllegalStateException("unreachable");
        }
        if (subject == null || subject.isBlank() || tenantClaim == null || tenantClaim.isBlank()
                || !requiredAcr.equals(acr)) {
            denyAuthentication();
        }

        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantClaim);
        } catch (IllegalArgumentException invalidTenantClaim) {
            denyAuthentication();
            throw new IllegalStateException("unreachable");
        }

        Account account = jdbc.sql("""
                select account.tenant_id, account.user_id, account.person_id
                from app_user account
                join tenant on tenant.tenant_id = account.tenant_id
                join workforce_person person on person.tenant_id = account.tenant_id
                  and person.person_id = account.person_id
                where account.tenant_id = :tenant and account.external_subject = :subject
                  and account.status = 'ACTIVE' and tenant.status = 'ACTIVE'
                  and person.status = 'ACTIVE' and person.effective_from <= now()
                  and (person.effective_until is null or person.effective_until > now())
                """)
                .param("tenant", tenantId).param("subject", subject)
                .query((rs, row) -> new Account(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("person_id", UUID.class)))
                .optional().orElseThrow(ProdOidcClinicalIdentityProvider::authenticationDenied);

        List<UUID> activeRoles = jdbc.sql("""
                select assignment.role_assignment_id
                from role_assignment assignment
                join workforce_assignment workforce
                  on workforce.tenant_id = assignment.tenant_id
                  and workforce.source_role_assignment_id = assignment.role_assignment_id
                where assignment.tenant_id = :tenant and assignment.user_id = :user
                  and assignment.person_id = :person and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= now()
                  and (assignment.valid_until is null or assignment.valid_until > now())
                  and workforce.status = 'ACTIVE' and workforce.valid_from <= now()
                  and (workforce.valid_until is null or workforce.valid_until > now())
                order by assignment.role_assignment_id
                """)
                .param("tenant", account.tenantId()).param("user", account.userId())
                .param("person", account.personId())
                .query(UUID.class).list();
        if (activeRoles.isEmpty()) {
            throw new ClinicalAccessDeniedException(
                    "CLINICAL_ROLE_REQUIRED", "No active clinical role assignment is available");
        }
        return new ClinicalIdentity(account.tenantId(), account.userId(), activeRoles);
    }

    private static void denyAuthentication() {
        throw authenticationDenied();
    }

    private static ClinicalAccessDeniedException authenticationDenied() {
        return new ClinicalAccessDeniedException(
                "AUTHENTICATION_REQUIRED", "A verified and mapped OIDC clinical identity is required");
    }

    private record Account(UUID tenantId, UUID userId, UUID personId) {}
}
