package org.haoemr.security;

import java.util.List;
import java.util.UUID;

public record ClinicalIdentity(UUID tenantId, UUID userId, List<UUID> roleAssignmentIds) {

    public ClinicalIdentity {
        roleAssignmentIds = roleAssignmentIds == null ? List.of() : List.copyOf(roleAssignmentIds);
    }
}
