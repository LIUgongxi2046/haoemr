package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!dev-synthetic & !prod")
final class FailClosedEmergencyReauthenticationVerifier implements EmergencyReauthenticationVerifier {
    @Override
    public void verify(HttpServletRequest request) {
        throw new ClinicalAccessDeniedException(
                "RECENT_REAUTHENTICATION_REQUIRED", "Emergency reauthentication is not configured");
    }
}
