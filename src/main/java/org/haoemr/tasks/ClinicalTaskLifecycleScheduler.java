package org.haoemr.tasks;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Owns deadline, escalation and temporary-delegation expiry outside a browser session. */
@Component
@ConditionalOnProperty(
        name = "haoemr.task-center.lifecycle-scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
final class ClinicalTaskLifecycleScheduler {
    private final ClinicalTaskLifecycleService lifecycle;

    ClinicalTaskLifecycleScheduler(ClinicalTaskLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(
            fixedDelayString = "${haoemr.task-center.lifecycle-poll-delay-ms:15000}",
            initialDelayString = "${haoemr.task-center.lifecycle-initial-delay-ms:60000}")
    void sweep() {
        lifecycle.sweepDueTransitions();
    }
}
