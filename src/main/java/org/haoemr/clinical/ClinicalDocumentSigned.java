package org.haoemr.clinical;

import java.time.Instant;
import java.util.UUID;

/** Immutable application event published inside the successful signature transaction. */
public record ClinicalDocumentSigned(
        UUID tenantId,
        UUID actorUserId,
        UUID patientId,
        UUID encounterId,
        UUID documentId,
        UUID documentVersionId,
        Instant signedAt) {}
