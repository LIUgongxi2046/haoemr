package org.haoemr.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("dev-synthetic")
@Transactional
final class ProdOidcClinicalIdentityProviderTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final String SUBJECT = "william.lin";
    private static final String REQUIRED_ACR = "urn:haoemr:acr:mfa";

    @Autowired
    private JdbcClient jdbc;

    private ProdOidcClinicalIdentityProvider provider;

    @BeforeEach
    void restoreActiveIdentityFixture() {
        provider = new ProdOidcClinicalIdentityProvider(jdbc, REQUIRED_ACR);
        jdbc.sql("update tenant set status = 'ACTIVE' where tenant_id = :tenant")
                .param("tenant", TENANT).update();
        jdbc.sql("update app_user set status = 'ACTIVE' where tenant_id = :tenant and user_id = :user")
                .param("tenant", TENANT).param("user", USER).update();
        jdbc.sql("update role_assignment set status = 'SUSPENDED' where tenant_id = :tenant and user_id = :user and role_assignment_id <> :role")
                .param("tenant", TENANT).param("user", USER).param("role", ROLE).update();
        jdbc.sql("""
                update role_assignment
                set status = 'ACTIVE', valid_from = now() - interval '1 minute', valid_until = null
                where tenant_id = :tenant and role_assignment_id = :role
                """).param("tenant", TENANT).param("role", ROLE).update();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void givenVerifiedMfaJwt_whenMapped_thenIdentityComesFromActiveDatabaseAssignments() {
        authenticate(jwt(SUBJECT, TENANT.toString(), REQUIRED_ACR, Map.of(
                "roles", List.of("SUPER_ADMIN"),
                "role_assignment_ids", List.of(UUID.randomUUID().toString()))));

        ClinicalIdentity identity = provider.current(null);

        assertThat(identity.tenantId()).isEqualTo(TENANT);
        assertThat(identity.userId()).isEqualTo(USER);
        assertThat(identity.roleAssignmentIds()).containsExactly(ROLE);
    }

    @Test
    void givenUnknownSubject_whenMapped_thenExistenceIsNotDisclosed() {
        authenticate(jwt("unknown-subject", TENANT.toString(), REQUIRED_ACR, Map.of()));

        assertDenied("AUTHENTICATION_REQUIRED");
    }

    @Test
    void givenLockedAccount_whenMapped_thenAuthenticationFailsClosed() {
        jdbc.sql("update app_user set status = 'LOCKED' where tenant_id = :tenant and user_id = :user")
                .param("tenant", TENANT).param("user", USER).update();
        authenticate(jwt(SUBJECT, TENANT.toString(), REQUIRED_ACR, Map.of()));

        assertDenied("AUTHENTICATION_REQUIRED");
    }

    @Test
    void givenSuspendedTenant_whenMapped_thenAuthenticationFailsClosed() {
        jdbc.sql("update tenant set status = 'SUSPENDED' where tenant_id = :tenant")
                .param("tenant", TENANT).update();
        authenticate(jwt(SUBJECT, TENANT.toString(), REQUIRED_ACR, Map.of()));

        assertDenied("AUTHENTICATION_REQUIRED");
    }

    @Test
    void givenExpiredOrRevokedAssignment_whenMapped_thenClinicalRoleIsDenied() {
        jdbc.sql("""
                update role_assignment
                set status = 'EXPIRED', valid_until = now()
                where tenant_id = :tenant and role_assignment_id = :role
                """).param("tenant", TENANT).param("role", ROLE).update();
        authenticate(jwt(SUBJECT, TENANT.toString(), REQUIRED_ACR, Map.of()));

        assertDenied("CLINICAL_ROLE_REQUIRED");
    }

    @Test
    void givenMissingMfaOrInvalidTenantClaim_whenMapped_thenAuthenticationFailsClosed() {
        authenticate(jwt(SUBJECT, TENANT.toString(), "urn:haoemr:acr:password", Map.of()));
        assertDenied("AUTHENTICATION_REQUIRED");

        authenticate(jwt(SUBJECT, "not-a-uuid", REQUIRED_ACR, Map.of()));
        assertDenied("AUTHENTICATION_REQUIRED");
    }

    @Test
    void givenNoJwtAuthentication_whenMapped_thenAuthenticationFailsClosed() {
        SecurityContextHolder.clearContext();

        assertDenied("AUTHENTICATION_REQUIRED");
    }

    private void assertDenied(String expectedCode) {
        assertThatThrownBy(() -> provider.current(null))
                .isInstanceOfSatisfying(ClinicalAccessDeniedException.class,
                        denied -> assertThat(denied.code()).isEqualTo(expectedCode))
                .hasMessageNotContaining(SUBJECT)
                .hasMessageNotContaining(TENANT.toString());
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static Jwt jwt(
            String subject,
            String tenantId,
            String acr,
            Map<String, Object> additionalClaims) {
        var builder = Jwt.withTokenValue("verified-by-resource-server")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tenant_id", tenantId)
                .claim("acr", acr);
        additionalClaims.forEach(builder::claim);
        return builder.build();
    }
}
