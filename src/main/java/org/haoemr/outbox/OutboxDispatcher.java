package org.haoemr.outbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class OutboxDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final List<OutboxConsumer> consumers;
    private final boolean hasExternalDeliveryConsumer;
    private final boolean scheduledEnabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxAttempts;

    OutboxDispatcher(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            List<OutboxConsumer> consumers,
            @Value("${haoemr.outbox.enabled:false}") boolean scheduledEnabled,
            @Value("${haoemr.outbox.batch-size:50}") int batchSize,
            @Value("${haoemr.outbox.lease-seconds:30}") int leaseSeconds,
            @Value("${haoemr.outbox.max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.consumers = consumers.stream().sorted(Comparator.comparing(OutboxConsumer::consumerName)).toList();
        this.hasExternalDeliveryConsumer = this.consumers.stream()
                .anyMatch(consumer -> consumer.deliveryKind() == OutboxConsumer.DeliveryKind.EXTERNAL_HOSPITAL_DELIVERY);
        this.scheduledEnabled = scheduledEnabled;
        this.batchSize = Math.clamp(batchSize, 1, 500);
        this.leaseSeconds = Math.clamp(leaseSeconds, 5, 300);
        this.maxAttempts = Math.clamp(maxAttempts, 1, 20);
    }

    @Scheduled(fixedDelayString = "${haoemr.outbox.poll-delay-ms:1000}")
    void scheduledDispatch() {
        if (scheduledEnabled) {
            DispatchResult result = dispatchBatch();
            if (result.failed() > 0 || result.deadLettered() > 0) {
                LOGGER.warn("Outbox batch completed with failed={} dead_lettered={}",
                        result.failed(), result.deadLettered());
            }
        }
    }

    public DispatchResult dispatchBatch() {
        if (!hasExternalDeliveryConsumer) {
            return new DispatchResult(0, 0, 0, 0, true);
        }
        reclaimExpiredLeases();
        UUID workerId = UUID.randomUUID();
        List<OutboxMessage> claimed = claim(workerId);
        int published = 0;
        int failed = 0;
        int deadLettered = 0;
        for (OutboxMessage message : claimed) {
            try {
                for (OutboxConsumer consumer : consumers) {
                    deliver(workerId, message, consumer);
                }
                markPublished(workerId, message);
                published++;
            } catch (FencingLostException staleWorker) {
                failed++;
            } catch (RuntimeException consumerFailure) {
                boolean dead = markFailed(workerId, message);
                failed++;
                if (dead) {
                    deadLettered++;
                }
                LOGGER.warn("Outbox event failed event_id={} event_type={} attempt={} code=OUTBOX_CONSUMER_FAILURE",
                        message.eventId(), message.eventType(), message.attempt());
            }
        }
        return new DispatchResult(claimed.size(), published, failed, deadLettered, false);
    }

    public int reclaimExpiredLeases() {
        return Objects.requireNonNull(transactions.execute(status -> jdbc.sql("""
                update outbox_event
                set dispatch_state = 'PENDING', lease_owner = null, lease_until = null,
                  available_at = now(), last_error_code = 'LEASE_EXPIRED'
                where dispatch_state = 'IN_FLIGHT' and lease_until < now() and published_at is null
                """).update()));
    }

    void replayDeadLetter(UUID tenantId, UUID eventId, UUID actorUserId, String reason) {
        if (reason == null || reason.trim().length() < 8 || reason.trim().length() > 500) {
            throw new IllegalArgumentException("Replay reason must contain 8 to 500 characters");
        }
        transactions.executeWithoutResult(status -> {
            Integer priorAttempt = jdbc.sql("""
                    select attempt from outbox_event
                    where tenant_id = :tenant and event_id = :event and dispatch_state = 'DEAD_LETTER'
                    for update
                    """).param("tenant", tenantId).param("event", eventId)
                    .query(Integer.class).optional()
                    .orElseThrow(() -> new IllegalStateException("Only a dead-letter event can be replayed"));
            long activeActor = jdbc.sql("""
                    select count(*) from app_user
                    where tenant_id = :tenant and user_id = :actor and status = 'ACTIVE'
                    """).param("tenant", tenantId).param("actor", actorUserId).query(Long.class).single();
            if (activeActor != 1) {
                throw new IllegalStateException("Replay actor is not an active tenant user");
            }
            jdbc.sql("""
                    insert into outbox_replay_audit(
                      replay_audit_id, tenant_id, event_id, actor_user_id, prior_attempt, reason)
                    values (:audit, :tenant, :event, :actor, :attempt, :reason)
                    """).param("audit", UUID.randomUUID()).param("tenant", tenantId).param("event", eventId)
                    .param("actor", actorUserId).param("attempt", priorAttempt).param("reason", reason.trim()).update();
            jdbc.sql("""
                    update outbox_event
                    set dispatch_state = 'PENDING', attempt = 0, available_at = now(),
                      lease_owner = null, lease_until = null, dead_lettered_at = null,
                      last_error_code = null
                    where tenant_id = :tenant and event_id = :event and dispatch_state = 'DEAD_LETTER'
                    """).param("tenant", tenantId).param("event", eventId).update();
        });
    }

    public ReconciliationStatus reconciliationStatus(UUID tenantId) {
        return jdbc.sql("""
                select
                  count(*) filter (where dispatch_state = 'PENDING') as pending,
                  count(*) filter (where dispatch_state = 'IN_FLIGHT') as in_flight,
                  count(*) filter (where dispatch_state = 'PUBLISHED') as published,
                  count(*) filter (where dispatch_state = 'PUBLISHED' and exists (
                    select 1 from outbox_consumer_receipt receipt
                    where receipt.tenant_id = outbox_event.tenant_id and receipt.event_id = outbox_event.event_id
                      and receipt.delivery_kind = 'EXTERNAL_HOSPITAL_DELIVERY')) as external_delivery_accepted,
                  count(*) filter (where dispatch_state = 'PUBLISHED' and not exists (
                    select 1 from outbox_consumer_receipt receipt
                    where receipt.tenant_id = outbox_event.tenant_id and receipt.event_id = outbox_event.event_id
                      and receipt.delivery_kind = 'EXTERNAL_HOSPITAL_DELIVERY')) as closed_without_external_evidence,
                  count(*) filter (where dispatch_state = 'DEAD_LETTER') as dead_letter,
                  coalesce(extract(epoch from (now() - min(created_at)
                    filter (where dispatch_state = 'PENDING'))), 0)::bigint as oldest_pending_seconds
                from outbox_event
                where tenant_id = :tenant
                """).param("tenant", tenantId).query((rs, row) -> new ReconciliationStatus(
                        hasExternalDeliveryConsumer, scheduledEnabled, rs.getLong("pending"),
                        rs.getLong("in_flight"), rs.getLong("published"),
                        rs.getLong("external_delivery_accepted"), rs.getLong("closed_without_external_evidence"),
                        rs.getLong("dead_letter"),
                        rs.getLong("oldest_pending_seconds"))).single();
    }

    private List<OutboxMessage> claim(UUID workerId) {
        return Objects.requireNonNull(transactions.execute(status -> jdbc.sql("""
                with candidates as (
                  select candidate.tenant_id, candidate.event_id
                  from outbox_event candidate
                  where candidate.dispatch_state = 'PENDING'
                    and candidate.published_at is null and candidate.available_at <= now()
                    and not exists (
                      select 1 from outbox_event earlier
                      where earlier.tenant_id = candidate.tenant_id
                        and earlier.aggregate_type = candidate.aggregate_type
                        and earlier.aggregate_id = candidate.aggregate_id
                        and earlier.aggregate_version < candidate.aggregate_version
                        and earlier.published_at is null)
                  order by candidate.available_at, candidate.event_id
                  for update skip locked
                  limit :batch_size
                )
                update outbox_event event
                set dispatch_state = 'IN_FLIGHT', lease_owner = :worker,
                  lease_until = now() + (:lease_seconds * interval '1 second'),
                  fencing_token = event.fencing_token + 1, attempt = event.attempt + 1,
                  last_error_code = null
                from candidates
                where event.tenant_id = candidates.tenant_id and event.event_id = candidates.event_id
                returning event.tenant_id, event.event_id, event.aggregate_type, event.aggregate_id,
                  event.aggregate_version, event.event_type, event.schema_version,
                  event.payload::text, event.attempt, event.fencing_token
                """).param("batch_size", batchSize).param("worker", workerId).param("lease_seconds", leaseSeconds)
                .query((rs, row) -> new OutboxMessage(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                        rs.getLong("aggregate_version"), rs.getString("event_type"),
                        rs.getInt("schema_version"), rs.getString("payload"),
                        rs.getInt("attempt"), rs.getLong("fencing_token"))).list()));
    }

    private void deliver(UUID workerId, OutboxMessage message, OutboxConsumer consumer) {
        transactions.executeWithoutResult(status -> {
            assertFence(workerId, message);
            long completed = jdbc.sql("""
                    select count(*) from outbox_consumer_receipt
                    where tenant_id = :tenant and event_id = :event and consumer_name = :consumer
                    """).param("tenant", message.tenantId()).param("event", message.eventId())
                    .param("consumer", consumer.consumerName()).query(Long.class).single();
            if (completed == 1) {
                return;
            }
            consumer.consume(message);
            jdbc.sql("""
                    insert into outbox_consumer_receipt(
                      tenant_id, event_id, consumer_name, payload_hash, delivery_kind)
                    values (:tenant, :event, :consumer, :payload_hash, :delivery_kind)
                    """).param("tenant", message.tenantId()).param("event", message.eventId())
                    .param("consumer", consumer.consumerName()).param("payload_hash", sha256(message.payload()))
                    .param("delivery_kind", consumer.deliveryKind().name()).update();
        });
    }

    private void assertFence(UUID workerId, OutboxMessage message) {
        Long ownedFence = jdbc.sql("""
                select fencing_token from outbox_event
                where tenant_id = :tenant and event_id = :event and dispatch_state = 'IN_FLIGHT'
                  and lease_owner = :worker and fencing_token = :fence and lease_until > now()
                for update
                """).param("tenant", message.tenantId()).param("event", message.eventId())
                .param("worker", workerId).param("fence", message.fencingToken()).query(Long.class).optional()
                .orElse(null);
        if (ownedFence == null || ownedFence != message.fencingToken()) {
            throw new FencingLostException();
        }
    }

    private void markPublished(UUID workerId, OutboxMessage message) {
        int updated = Objects.requireNonNull(transactions.execute(status -> jdbc.sql("""
                update outbox_event
                set dispatch_state = 'PUBLISHED', published_at = now(), lease_owner = null, lease_until = null
                where tenant_id = :tenant and event_id = :event and dispatch_state = 'IN_FLIGHT'
                  and lease_owner = :worker and fencing_token = :fence and lease_until > now()
                """).param("tenant", message.tenantId()).param("event", message.eventId())
                .param("worker", workerId).param("fence", message.fencingToken()).update()));
        if (updated != 1) {
            throw new FencingLostException();
        }
    }

    private boolean markFailed(UUID workerId, OutboxMessage message) {
        boolean dead = message.attempt() >= maxAttempts;
        int updated = Objects.requireNonNull(transactions.execute(status -> jdbc.sql("""
                update outbox_event
                set dispatch_state = :next_state, lease_owner = null, lease_until = null,
                  available_at = now() + (:delay_seconds * interval '1 second'),
                  last_error_code = 'OUTBOX_CONSUMER_FAILURE',
                  dead_lettered_at = case when :dead then now() else null end
                where tenant_id = :tenant and event_id = :event and dispatch_state = 'IN_FLIGHT'
                  and lease_owner = :worker and fencing_token = :fence
                """).param("next_state", dead ? "DEAD_LETTER" : "PENDING")
                .param("delay_seconds", dead ? 0 : Math.min(message.attempt(), 60)).param("dead", dead)
                .param("tenant", message.tenantId()).param("event", message.eventId())
                .param("worker", workerId).param("fence", message.fencingToken()).update()));
        if (updated != 1) {
            throw new FencingLostException();
        }
        return dead;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record DispatchResult(int claimed, int published, int failed, int deadLettered,
            boolean skippedMissingExternalDeliveryConsumer) {
    }

    public record ReconciliationStatus(
            boolean externalDeliveryConfigured, boolean scheduledDispatchEnabled,
            long pending, long inFlight, long published, long externalDeliveryAccepted,
            long closedWithoutExternalEvidence, long deadLetter, long oldestPendingSeconds) {
    }

    private static final class FencingLostException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
