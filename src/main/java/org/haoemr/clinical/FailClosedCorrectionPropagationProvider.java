package org.haoemr.clinical;

import java.time.Instant;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!dev-synthetic")
final class FailClosedCorrectionPropagationProvider implements CorrectionPropagationProvider {
    @Override
    public DeliveryReceipt deliver(
            ClinicalIdentity identity, UUID documentId, UUID correctionId, UUID propagationId,
            String destinationCode, String contentHash, Instant requestedAt) {
        return DeliveryReceipt.failed("UNCONFIGURED_HIE", "TRUSTED_HIE_ADAPTER_UNAVAILABLE");
    }
}
