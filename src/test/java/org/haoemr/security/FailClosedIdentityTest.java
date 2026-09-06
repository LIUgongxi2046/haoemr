package org.haoemr.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class FailClosedIdentityTest {

    @Test
    void givenTheProductionIdentityAdapter_whenDevHeadersArePresented_thenAuthenticationStillFailsClosed() {
        var provider = new FailClosedClinicalIdentityProvider();

        assertThatThrownBy(() -> provider.current(null))
                .isInstanceOf(ClinicalAccessDeniedException.class)
                .hasMessageContaining("OIDC authentication is required");
    }
}
