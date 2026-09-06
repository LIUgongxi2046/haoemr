package org.haoemr.clinical;

import java.time.Instant;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev-synthetic")
final class SyntheticClinicalSignatureProvider implements ClinicalSignatureProvider {
    @Override
    public SignatureAttestation attest(
            ClinicalIdentity identity, UUID documentId, UUID documentVersionId,
            String signatureRole, String contentHash, Instant requestedAt) {
        return new SignatureAttestation(
                "VALID",
                "SYNTHETIC-CA://" + documentVersionId + "/" + identity.userId() + "/" + contentHash.substring(0, 16));
    }

    @Override
    public VerificationAttestation verify(
            String credentialRef, UUID documentVersionId, String contentHash, Instant signedAt) {
        String expectedPrefix = "SYNTHETIC-CA://" + documentVersionId + "/";
        boolean valid = credentialRef != null && credentialRef.startsWith(expectedPrefix)
                && credentialRef.endsWith("/" + contentHash.substring(0, 16));
        return new VerificationAttestation(
                valid, "SYNTHETIC_CA", valid ? "SYNTHETIC_SIGNATURE_MATCH" : "SYNTHETIC_SIGNATURE_MISMATCH");
    }
}
