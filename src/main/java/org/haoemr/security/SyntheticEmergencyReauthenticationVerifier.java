package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev-synthetic")
final class SyntheticEmergencyReauthenticationVerifier implements EmergencyReauthenticationVerifier {
    static final String HEADER = "X-HaoEMR-Synthetic-Reauthenticated-At";
    private final long maxAgeSeconds;

    SyntheticEmergencyReauthenticationVerifier(
            @Value("${haoemr.security.emergency-reauthentication-max-age-seconds:300}") long maxAgeSeconds) {
        this.maxAgeSeconds = maxAgeSeconds;
    }

    @Override
    public void verify(HttpServletRequest request) {
        try {
            Instant authenticatedAt = Instant.parse(request.getHeader(HEADER));
            Instant now = Instant.now();
            if (authenticatedAt.isAfter(now.plusSeconds(30)) || authenticatedAt.isBefore(now.minusSeconds(maxAgeSeconds))) {
                deny();
            }
        } catch (RuntimeException missingOrMalformed) {
            deny();
        }
    }

    private static void deny() {
        throw new ClinicalAccessDeniedException(
                "RECENT_REAUTHENTICATION_REQUIRED",
                "Emergency access requires a recent synthetic step-up marker in development");
    }
}
