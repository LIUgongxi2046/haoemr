package org.haoemr.clinical;

import java.time.Instant;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!dev-synthetic")
final class FailClosedClinicalSignatureProvider implements ClinicalSignatureProvider {
    @Override
    public SignatureAttestation attest(
            ClinicalIdentity identity, UUID documentId, UUID documentVersionId,
            String signatureRole, String contentHash, Instant requestedAt) {
        throw new ClinicalCommandException(
                "TRUSTED_SIGNATURE_PROVIDER_UNAVAILABLE", 503,
                "A trusted CA and timestamp signature provider must attest this exact content hash before final signature");
    }

    @Override
    public VerificationAttestation verify(
            String credentialRef, UUID documentVersionId, String contentHash, Instant signedAt) {
        throw new ClinicalCommandException(
                "TRUSTED_SIGNATURE_PROVIDER_UNAVAILABLE", 503,
                "Trusted certificate-chain, revocation and timestamp verification is unavailable");
    }
}
