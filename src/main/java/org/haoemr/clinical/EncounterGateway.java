package org.haoemr.clinical;

import java.time.Instant;
import java.util.UUID;
import org.haoemr.contracts.EncounterWire;
import org.haoemr.security.ClinicalIdentity;

/** Public clinical encounter application boundary used by other bounded contexts. */
public interface EncounterGateway {

    EncounterWire createEncounter(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            String encounterType,
            String initialStatus,
            UUID departmentId,
            UUID responsibleUserId,
            Instant startedAt,
            String sourceSystem,
            String sourceKey);

    EncounterWire transitionEncounter(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            long expectedRowVersion,
            String targetStatus,
            Instant occurredAt,
            String reason);
}
