package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev-synthetic")
final class DevOidcClinicalIdentityProvider implements ClinicalIdentityProvider {

    private static final String DEVELOPMENT_TOKEN = "Bearer dev-synthetic-token";

    private final DevelopmentSessionService sessions;

    DevOidcClinicalIdentityProvider(DevelopmentSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public ClinicalIdentity current(HttpServletRequest request) {
        if (!DEVELOPMENT_TOKEN.equals(request.getHeader("Authorization"))) {
            return sessions.currentIdentity(request.getHeader("Authorization"));
        }
        try {
            UUID tenantId = UUID.fromString(requiredHeader(request, "X-HaoEMR-Tenant-Id"));
            UUID userId = UUID.fromString(requiredHeader(request, "X-HaoEMR-User-Id"));
            java.util.List<UUID> roles = java.util.Arrays.stream(requiredHeader(request, "X-HaoEMR-Role-Assignment-Ids").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(UUID::fromString)
                    .toList();
            return new ClinicalIdentity(tenantId, userId, roles);
        } catch (IllegalArgumentException invalidIdentity) {
            throw new ClinicalAccessDeniedException("AUTHENTICATION_REQUIRED", "A valid clinical identity is required");
        }
    }

    private static String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing identity header");
        }
        return value;
    }
}
