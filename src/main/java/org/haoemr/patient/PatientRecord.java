package org.haoemr.patient;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record PatientRecord(
        UUID tenantId,
        UUID patientId,
        String displayName,
        String sexCode,
        LocalDate birthDate,
        long rowVersion) {

    public PatientRecord {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(birthDate, "birthDate");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (sexCode == null || sexCode.isBlank()) {
            throw new IllegalArgumentException("sexCode must not be blank");
        }
        if (rowVersion < 1) {
            throw new IllegalArgumentException("rowVersion must be positive");
        }
    }
}

