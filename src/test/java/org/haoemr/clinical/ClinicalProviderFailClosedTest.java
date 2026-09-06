package org.haoemr.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.security.ClinicalIdentity;

final class ClinicalProviderFailClosedTest {
    private final ClinicalIdentity identity = new ClinicalIdentity(
            UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()));

    @Test
    void trustedSignatureProviderMustFailClosedWhenProductionAdapterIsUnavailable() {
        var provider = new FailClosedClinicalSignatureProvider();
        assertThatThrownBy(() -> provider.attest(
                identity, UUID.randomUUID(), UUID.randomUUID(), "ATTENDING", "a".repeat(64), Instant.now()))
                .isInstanceOf(ClinicalCommandException.class)
                .hasMessageContaining("trusted CA");
        assertThatThrownBy(() -> provider.verify(
                "credential-ref", UUID.randomUUID(), "b".repeat(64), Instant.now()))
                .isInstanceOf(ClinicalCommandException.class)
                .hasMessageContaining("certificate-chain");
    }

    @Test
    void correctionPropagationMustNeverReportDeliveredWithoutTrustedHieAdapter() {
        var receipt = new FailClosedCorrectionPropagationProvider().deliver(
                identity, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "EXTERNAL_SHARED_RECORD", "c".repeat(64), Instant.now());
        assertThat(receipt.delivered()).isFalse();
        assertThat(receipt.receiptRef()).isNull();
        assertThat(receipt.errorCode()).isEqualTo("TRUSTED_HIE_ADAPTER_UNAVAILABLE");
    }
}
