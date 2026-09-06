package org.haoemr.clinical;

import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.DocumentVersionWire;
import org.haoemr.security.ClinicalIdentity;

/** Public clinical document application boundary used by other bounded contexts. */
public interface ClinicalDocumentGateway {

    DocumentVersionWire createDocument(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID patientId,
            UUID encounterId,
            String documentTypeCode,
            Map<String, Object> sections);

    void configureSignaturePolicy(
            ClinicalIdentity identity,
            UUID documentId,
            UUID documentVersionId,
            String requiredSignatureLevel);
}
