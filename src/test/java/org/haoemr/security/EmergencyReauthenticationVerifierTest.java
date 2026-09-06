package org.haoemr.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class EmergencyReauthenticationVerifierTest {
    private final ProdEmergencyReauthenticationVerifier verifier = new ProdEmergencyReauthenticationVerifier(300);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void givenRecentIdpAuthentication_whenRequestingEmergencyAccess_thenStepUpIsAccepted() {
        authenticate(Instant.now().minusSeconds(60));
        assertThatCode(() -> verifier.verify(null)).doesNotThrowAnyException();
    }

    @Test
    void givenMissingOrStaleIdpAuthentication_whenRequestingEmergencyAccess_thenFailsClosed() {
        authenticate(Instant.now().minusSeconds(301));
        assertDenied();

        authenticate(null);
        assertDenied();

        SecurityContextHolder.clearContext();
        assertDenied();
    }

    private void assertDenied() {
        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOfSatisfying(ClinicalAccessDeniedException.class,
                        denied -> assertThat(denied.code()).isEqualTo("RECENT_REAUTHENTICATION_REQUIRED"));
    }

    private static void authenticate(Instant authenticationTime) {
        var builder = Jwt.withTokenValue("verified-emergency-step-up")
                .header("alg", "RS256")
                .subject("clinician")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(300));
        if (authenticationTime != null) builder.claim("auth_time", authenticationTime);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(builder.build(), List.of()));
    }
}
