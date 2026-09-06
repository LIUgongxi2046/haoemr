package org.haoemr.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ContextLeasePolicy {

    static final Duration LEASE_DURATION = Duration.ofMinutes(15);
    private static final List<String> DEFAULT_SOURCES =
            List.of("DOCUMENT_VERSION", "OBSERVATION", "ORDER", "RULE");

    private final Clock clock;
    private final boolean externalMedicalAgentModelsEnabled;

    @Autowired
    public ContextLeasePolicy(
            @Value("${haoemr.medical-agent.external-models-enabled:false}")
            boolean externalMedicalAgentModelsEnabled) {
        this(Clock.systemUTC(), externalMedicalAgentModelsEnabled);
    }

    ContextLeasePolicy(Clock clock) {
        this(clock, false);
    }

    ContextLeasePolicy(Clock clock, boolean externalMedicalAgentModelsEnabled) {
        this.clock = Objects.requireNonNull(clock);
        this.externalMedicalAgentModelsEnabled = externalMedicalAgentModelsEnabled;
    }

    public ContextLease issue(
            ClinicalIdentity identity,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            UUID taskId,
            String purposeCode) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(identity.tenantId(), "tenantId");
        Objects.requireNonNull(identity.userId(), "userId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(facilityId, "facilityId");
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new IllegalArgumentException("At least one active role assignment is required");
        }
        if (Set.copyOf(identity.roleAssignmentIds()).size() != identity.roleAssignmentIds().size()) {
            throw new IllegalArgumentException("Role assignments must be unique");
        }
        if (purposeCode == null || purposeCode.isBlank()) {
            throw new IllegalArgumentException("A non-blank purpose code is required");
        }
        if (encounterId != null && patientId == null) {
            throw new IllegalArgumentException("An encounter lease requires a patient");
        }

        String normalizedPurpose = purposeCode.trim();
        String modelResidencyPolicy = externalMedicalAgentModelsEnabled
                && "MEDICAL_AGENT_COLLABORATION".equals(normalizedPurpose)
                ? "APPROVED_EXTERNAL" : "ON_PREM_ONLY";
        Instant issuedAt = clock.instant();
        UUID leaseId = uuidV7(issuedAt);
        String watermark = sha256(String.join("|",
                leaseId.toString(), identity.tenantId().toString(), identity.userId().toString(),
                organizationId.toString(), facilityId.toString(), String.valueOf(patientId),
                String.valueOf(encounterId), normalizedPurpose, modelResidencyPolicy, issuedAt.toString()));

        return new ContextLease(
                leaseId,
                identity.tenantId(),
                organizationId,
                facilityId,
                identity.userId(),
                identity.roleAssignmentIds(),
                patientId,
                encounterId,
                taskId,
                normalizedPurpose,
                DEFAULT_SOURCES,
                watermark,
                "SENSITIVE",
                modelResidencyPolicy,
                issuedAt,
                issuedAt.plus(LEASE_DURATION));
    }

    private static UUID uuidV7(Instant instant) {
        long unixMillis = instant.toEpochMilli();
        UUID random = UUID.randomUUID();
        long mostSignificant = (unixMillis & 0xffffffffffffL) << 16;
        mostSignificant |= 0x7000L;
        mostSignificant |= random.getMostSignificantBits() & 0x0fffL;
        long leastSignificant = random.getLeastSignificantBits();
        leastSignificant &= 0x3fffffffffffffffL;
        leastSignificant |= 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK is missing SHA-256", impossible);
        }
    }
}
