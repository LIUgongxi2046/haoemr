package org.haoemr.authorization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class EmergencyAccessExpirySweeper {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EmergencyAccessExpirySweeper(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            fixedDelayString = "${haoemr.security.emergency-expiry-sweep-delay-ms:15000}",
            initialDelayString = "${haoemr.security.emergency-expiry-sweep-initial-delay-ms:60000}")
    void scheduledSweep() {
        sweepExpired();
    }

    int sweepExpired() {
        return transactions.execute(status -> {
            List<ExpiredGrant> grants = jdbc.sql("""
                    select tenant_id, emergency_access_grant_id, row_version
                    from emergency_access_grant
                    where status = 'ACTIVE' and expires_at <= now()
                    order by expires_at, emergency_access_grant_id
                    for update skip locked limit 100
                    """).query((rs, row) -> new ExpiredGrant(
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("emergency_access_grant_id", UUID.class),
                            rs.getLong("row_version"))).list();
            for (ExpiredGrant grant : grants) expire(grant);
            return grants.size();
        });
    }

    private void expire(ExpiredGrant grant) {
        long nextVersion = grant.rowVersion() + 1;
        int updated = jdbc.sql("""
                update emergency_access_grant
                set status = 'EXPIRED', row_version = :next_version, updated_at = now()
                where tenant_id = :tenant and emergency_access_grant_id = :grant
                  and status = 'ACTIVE' and row_version = :expected and expires_at <= now()
                """).param("next_version", nextVersion).param("tenant", grant.tenantId())
                .param("grant", grant.grantId()).param("expected", grant.rowVersion()).update();
        if (updated != 1) return;

        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", grant.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", grant.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString();
        String hash = sha256(grant.tenantId() + "|" + auditId + "|EMERGENCY_ACCESS_EXPIRED|"
                + grant.grantId() + "|" + nextVersion + "|" + traceId + "|" + previous);
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id,
                  action_code, resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), null, 'EMERGENCY_ACCESS_EXPIRED',
                  'EMERGENCY_ACCESS_GRANT', :grant, :trace, :previous, :hash,
                  jsonb_build_object('reason_code', 'TIME_LIMIT_REACHED'))
                """).param("tenant", grant.tenantId()).param("audit", auditId)
                .param("grant", grant.grantId()).param("trace", traceId)
                .param("previous", previous).param("hash", hash).update();
        jdbc.sql("""
                insert into outbox_event(tenant_id, event_id, aggregate_type, aggregate_id,
                  aggregate_version, event_type, schema_version, payload)
                values (:tenant, :event, 'EMERGENCY_ACCESS_GRANT', :grant,
                  :version, 'EMERGENCY_ACCESS_EXPIRED', 1,
                  jsonb_build_object('emergency_access_grant_id', :grant,
                    'reason_code', 'TIME_LIMIT_REACHED'))
                """).param("tenant", grant.tenantId()).param("event", UUID.randomUUID())
                .param("grant", grant.grantId()).param("version", nextVersion).update();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ExpiredGrant(UUID tenantId, UUID grantId, long rowVersion) {}
}
