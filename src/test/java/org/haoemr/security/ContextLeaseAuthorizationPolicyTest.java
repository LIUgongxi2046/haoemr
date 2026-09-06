package org.haoemr.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

final class ContextLeaseAuthorizationPolicyTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000e101");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000e102");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000e103");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000e104");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000e105");

    @Test
    void productionModeFailsClosedWhenNoPublishedClinicalAuthorizationPolicyExists() {
        AuthorizationDecisionService authorization = mock(AuthorizationDecisionService.class);
        when(authorization.hasPublishedPolicy(TENANT, "CLINICAL_CONTEXT", "LEASE_ISSUE")).thenReturn(false);
        ContextLeaseService service = service(authorization, true);

        assertThatThrownBy(() -> service.requirePublishedAuthorization(
                identity(), ORGANIZATION, FACILITY, null, null, "INPATIENT_WORKLIST"))
                .isInstanceOf(ClinicalAccessDeniedException.class)
                .satisfies(error -> assertThatCode(() -> {
                    if (!"AUTHORIZATION_POLICY_MISSING".equals(((ClinicalAccessDeniedException) error).code())) {
                        throw new AssertionError("unexpected access-denied code");
                    }
                }).doesNotThrowAnyException());
    }

    @Test
    void syntheticDevelopmentModeMayRunWithoutPublishedPolicyForIsolatedAcceptanceData() {
        AuthorizationDecisionService authorization = mock(AuthorizationDecisionService.class);
        when(authorization.hasPublishedPolicy(TENANT, "CLINICAL_CONTEXT", "LEASE_ISSUE")).thenReturn(false);
        ContextLeaseService service = service(authorization, false);

        assertThatCode(() -> service.requirePublishedAuthorization(
                identity(), ORGANIZATION, FACILITY, null, null, "INPATIENT_WORKLIST"))
                .doesNotThrowAnyException();
    }

    private static ContextLeaseService service(AuthorizationDecisionService authorization, boolean required) {
        return new ContextLeaseService(
                mock(JdbcClient.class), mock(TransactionTemplate.class),
                mock(ContextLeasePolicy.class), authorization, required);
    }

    private static ClinicalIdentity identity() {
        return new ClinicalIdentity(TENANT, USER, List.of(ROLE));
    }
}
