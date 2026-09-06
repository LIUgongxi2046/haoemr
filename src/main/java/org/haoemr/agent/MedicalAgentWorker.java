package org.haoemr.agent;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class MedicalAgentWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(MedicalAgentWorker.class);

    private final MedicalAgentHarnessService harness;
    private final boolean enabled;
    private final int leaseSeconds;

    MedicalAgentWorker(
            MedicalAgentHarnessService harness,
            @Value("${haoemr.medical-agent.worker.enabled:true}") boolean enabled,
            @Value("${haoemr.medical-agent.worker.lease-seconds:180}") int leaseSeconds) {
        this.harness = harness;
        this.enabled = enabled;
        this.leaseSeconds = Math.clamp(leaseSeconds, 60, 600);
    }

    @Scheduled(fixedDelayString = "${haoemr.medical-agent.worker.poll-delay-ms:500}")
    void scheduledDispatch() {
        if (enabled) dispatchOne();
    }

    boolean dispatchOne() {
        harness.reclaimExpiredWorkerLeases();
        UUID workerId = UUID.randomUUID();
        return harness.claimNext(workerId, leaseSeconds).map(claim -> {
            try {
                harness.executeClaimed(claim.tenantId(), claim.runId(), workerId, leaseSeconds);
            } catch (RuntimeException failure) {
                harness.recordWorkerFailure(claim, workerId, failure);
                LOGGER.warn("Medical agent run failed run_id={} attempt={} code={}",
                        claim.runId(), claim.attempt(), failure.getClass().getSimpleName());
            }
            return true;
        }).orElse(false);
    }
}
