package org.haoemr;

import java.time.Instant;

public record SystemReadiness(
        String product,
        String component,
        String version,
        String status,
        Instant checkedAt) {

    public static SystemReadiness ready(String version) {
        return new SystemReadiness(
                "haoemr",
                "clinical-core",
                version,
                "READY",
                Instant.now());
    }
}

