package org.haoemr.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.haoemr.contracts.ReleaseDownloadEventCreateRequestWire;
import org.haoemr.contracts.ReleaseDownloadEventWire;
import org.haoemr.contracts.ReleaseDownloadValidCountWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ReleaseDownloadEventService {
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BOT_AGENT = Pattern.compile(
            "(?i)(bot|crawl|spider|slurp|curl|wget|python|headless|phantom|scan|monitor|uptime|go-http-client|node)");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ReleaseDownloadEventService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ReleaseDownloadEventWire record(
            ClinicalIdentity identity, String idempotencyKey, ReleaseDownloadEventCreateRequestWire request) {
        if (request.channel() == null || request.fingerprintHash() == null || request.downloadedAt() == null) {
            throw invalid("channel, fingerprint_hash and downloaded_at are required");
        }
        if (!FINGERPRINT.matcher(request.fingerprintHash()).matches()) {
            throw new ReleaseDownloadEventException(
                    "INVALID_FINGERPRINT_HASH", 400, "fingerprint_hash must be a lowercase 64 hex SHA-256");
        }
        boolean robot = classifyRobot(request.userAgent());
        return transactions.execute(status -> {
            beginCommand(identity, "RELEASE_DOWNLOAD_EVENT_RECORD", idempotencyKey,
                    sha256(request.channel() + "|" + request.fingerprintHash()));
            UUID eventId = UUID.randomUUID();
            jdbc.sql("""
                    insert into release_download_event(
                      tenant_id, download_event_id, channel, source_ip, user_agent,
                      fingerprint_hash, is_robot, downloaded_at)
                    values (:tenant, :event, :channel, :ip, :agent, :fingerprint, :robot, :downloaded_at)
                    """).param("tenant", identity.tenantId()).param("event", eventId)
                    .param("channel", request.channel().name()).param("ip", blankToNull(request.sourceIp()))
                    .param("agent", blankToNull(request.userAgent())).param("fingerprint", request.fingerprintHash())
                    .param("robot", robot).param("downloaded_at", request.downloadedAt().atOffset(ZoneOffset.UTC)).update();
            completeCommand(identity, "RELEASE_DOWNLOAD_EVENT_RECORD", idempotencyKey, eventId);
            return event(identity.tenantId(), eventId);
        });
    }

    List<ReleaseDownloadEventWire> list(ClinicalIdentity identity, String channel) {
        List<UUID> ids = channel == null || channel.isBlank()
                ? jdbc.sql("""
                        select download_event_id from release_download_event
                        where tenant_id = :tenant order by downloaded_at desc, download_event_id desc limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select download_event_id from release_download_event
                        where tenant_id = :tenant and channel = :channel
                        order by downloaded_at desc, download_event_id desc limit 500
                        """).param("tenant", identity.tenantId()).param("channel", channel).query(UUID.class).list();
        return ids.stream().map(id -> event(identity.tenantId(), id)).toList();
    }

    ReleaseDownloadValidCountWire validCount(ClinicalIdentity identity, String channel) {
        Long count = channel == null || channel.isBlank()
                ? jdbc.sql("""
                        select count(*) from release_download_event
                        where tenant_id = :tenant and not is_robot
                        """).param("tenant", identity.tenantId()).query(Long.class).single()
                : jdbc.sql("""
                        select count(*) from release_download_event
                        where tenant_id = :tenant and channel = :channel and not is_robot
                        """).param("tenant", identity.tenantId()).param("channel", channel).query(Long.class).single();
        return new ReleaseDownloadValidCountWire(blankToNull(channel), count.intValue());
    }

    private ReleaseDownloadEventWire event(UUID tenantId, UUID eventId) {
        return jdbc.sql("""
                select download_event_id, channel, source_ip, user_agent, fingerprint_hash, is_robot, downloaded_at
                from release_download_event where tenant_id = :tenant and download_event_id = :event
                """).param("tenant", tenantId).param("event", eventId)
                .query((rs, row) -> new ReleaseDownloadEventWire(
                        rs.getObject("download_event_id", UUID.class),
                        ReleaseDownloadEventWire.ChannelValue.valueOf(rs.getString("channel")),
                        rs.getString("source_ip"),
                        rs.getString("user_agent"),
                        rs.getString("fingerprint_hash"),
                        rs.getBoolean("is_robot"),
                        rs.getObject("downloaded_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(ReleaseDownloadEventService::contextDenied);
    }

    private static boolean classifyRobot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        return BOT_AGENT.matcher(userAgent).find();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ReleaseDownloadEventException("INVALID_IDEMPOTENCY_KEY", 400,
                    "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ReleaseDownloadEventException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID eventId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", eventId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ReleaseDownloadEventException invalid(String message) {
        return new ReleaseDownloadEventException("RELEASE_DOWNLOAD_EVENT_REQUEST_INVALID", 400, message);
    }

    static ReleaseDownloadEventException contextDenied() {
        return new ReleaseDownloadEventException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested release download context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
