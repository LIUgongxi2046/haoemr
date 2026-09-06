package org.haoemr.clinical;

import java.time.Instant;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;

interface ClinicalSignatureProvider {
    SignatureAttestation attest(
            ClinicalIdentity identity,
            UUID documentId,
            UUID documentVersionId,
            String signatureRole,
            String contentHash,
            Instant requestedAt);

    VerificationAttestation verify(
            String credentialRef,
            UUID documentVersionId,
            String contentHash,
            Instant signedAt);

    record SignatureAttestation(String status, String credentialRef) {
        public SignatureAttestation {
            if (!"VALID".equals(status) && !"PENDING_CA_EVIDENCE".equals(status)) {
                throw new IllegalArgumentException("Unsupported signature attestation status");
            }
        }

        boolean valid() {
            return "VALID".equals(status);
        }
    }

    record VerificationAttestation(boolean valid, String providerCode, String evidenceCode) {}
}
