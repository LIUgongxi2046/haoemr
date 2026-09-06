package org.haoemr.executioncenter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ExecutionWorklistItem(
        String domain,
        UUID patientId,
        UUID encounterId,
        UUID admissionId,
        String patientDisplayName,
        String sexCode,
        LocalDate birthDate,
        String visitType,
        String location,
        String taskLabel,
        String status,
        int pendingCount,
        int overdueCount,
        int criticalCount,
        Instant latestActivityAt) {}
