package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!dev-synthetic & !prod")
final class FailClosedClinicalIdentityProvider implements ClinicalIdentityProvider {

    @Override
    public ClinicalIdentity current(HttpServletRequest request) {
        throw new ClinicalAccessDeniedException(
                "AUTHENTICATION_REQUIRED",
                "OIDC authentication is required; the development identity adapter is disabled");
    }
}
