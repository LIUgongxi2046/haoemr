package org.haoemr.quality;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs allowlisted, read-only quality scans even when no administrator has an open browser session. */
@Component
@ConditionalOnProperty(
        name = "haoemr.data-quality.scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
final class DataQualityScanScheduler {
    private final DataQualityOperationsService operations;

    DataQualityScanScheduler(DataQualityOperationsService operations) {
        this.operations = operations;
    }

    @Scheduled(
            fixedDelayString = "${haoemr.data-quality.scheduler-poll-delay-ms:900000}",
            initialDelayString = "${haoemr.data-quality.scheduler-initial-delay-ms:60000}")
    void sweep() {
        operations.sweepScheduledScans();
    }
}
