package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class ProdEmergencyReauthenticationVerifier implements EmergencyReauthenticationVerifier {
    private final long maxAgeSeconds;

    ProdEmergencyReauthenticationVerifier(
            @Value("${haoemr.security.emergency-reauthentication-max-age-seconds:300}") long maxAgeSeconds) {
        if (maxAgeSeconds < 30 || maxAgeSeconds > 900) {
            throw new IllegalArgumentException("Emergency reauthentication max age must be between 30 and 900 seconds");
        }
        this.maxAgeSeconds = maxAgeSeconds;
    }

    @Override
    public void verify(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            deny();
        }
        Jwt jwt = ((JwtAuthenticationToken) authentication).getToken();
        Instant authenticatedAt = authenticationTime(jwt.getClaim("auth_time"));
        Instant now = Instant.now();
        if (authenticatedAt == null || authenticatedAt.isAfter(now.plusSeconds(30))
                || authenticatedAt.isBefore(now.minusSeconds(maxAgeSeconds))) {
            deny();
        }
    }

    private static Instant authenticationTime(Object claim) {
        if (claim instanceof Instant instant) return instant;
        if (claim instanceof Number epochSeconds) return Instant.ofEpochSecond(epochSeconds.longValue());
        if (claim instanceof String value) {
            try { return Instant.ofEpochSecond(Long.parseLong(value)); }
            catch (NumberFormatException notEpoch) {
                try { return Instant.parse(value); }
                catch (RuntimeException malformed) { return null; }
            }
        }
        return null;
    }

    private static void deny() {
        throw new ClinicalAccessDeniedException(
                "RECENT_REAUTHENTICATION_REQUIRED",
                "Emergency access requires a new high-assurance identity-provider authentication");
    }
}
