package org.haoemr.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ContextLeasePolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-14T05:00:00Z");
    private final ContextLeasePolicy policy = new ContextLeasePolicy(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void givenAValidIdentity_whenIssuingALease_thenItExpiresWithinFifteenMinutes() {
        var identity = new ClinicalIdentity(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()));

        var lease = policy.issue(identity, UUID.randomUUID(), UUID.randomUUID(), null, null, null, "DOCUMENT_DRAFT");

        assertThat(lease.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(lease.authorizationWatermark()).hasSize(64);
    }

    @Test
    void givenNoActiveRoleOrPurpose_whenIssuingALease_thenItIsRejected() {
        var identityWithoutRole = new ClinicalIdentity(UUID.randomUUID(), UUID.randomUUID(), List.of());

        assertThatIllegalArgumentException().isThrownBy(() ->
                policy.issue(identityWithoutRole, UUID.randomUUID(), UUID.randomUUID(), null, null, null, "DOCUMENT_DRAFT"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy.issue(new ClinicalIdentity(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID())),
                        UUID.randomUUID(), UUID.randomUUID(), null, null, null, " "));
    }

    @Test
    void medicalAgentLeaseAllowsAnApprovedExternalModelOnlyWhenTheEnvironmentEnablesIt() {
        var identity = new ClinicalIdentity(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()));
        var externalPolicy = new ContextLeasePolicy(Clock.fixed(NOW, ZoneOffset.UTC), true);

        var externalLease = externalPolicy.issue(identity, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, "MEDICAL_AGENT_COLLABORATION");
        var ordinaryLease = externalPolicy.issue(identity, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, "DOCUMENT_DRAFT");

        assertThat(externalLease.modelResidencyPolicy()).isEqualTo("APPROVED_EXTERNAL");
        assertThat(ordinaryLease.modelResidencyPolicy()).isEqualTo("ON_PREM_ONLY");
    }
}
