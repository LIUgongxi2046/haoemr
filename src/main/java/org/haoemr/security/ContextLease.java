package org.haoemr.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContextLease(
        UUID leaseId,
        UUID tenantId,
        UUID organizationId,
        UUID facilityId,
        UUID userId,
        List<UUID> roleAssignmentIds,
        UUID patientId,
        UUID encounterId,
        UUID taskId,
        String purposeCode,
        List<String> allowedSourceTypes,
        String authorizationWatermark,
        String dataClassificationCeiling,
        String modelResidencyPolicy,
        Instant issuedAt,
        Instant expiresAt) {

    public ContextLease {
        roleAssignmentIds = List.copyOf(roleAssignmentIds);
        allowedSourceTypes = List.copyOf(allowedSourceTypes);
    }
}
