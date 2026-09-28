package org.haoemr.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SessionLoginRequestWire;
import org.haoemr.contracts.SessionLoginResponseWire;
import org.haoemr.contracts.SessionUserWire;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("dev-synthetic")
final class DevelopmentSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(12);
    private static final int DEFAULT_MAX_FAILURES = 5;
    private static final int DEFAULT_LOCKOUT_MINUTES = 15;
    private static final int DEFAULT_SESSION_ABSOLUTE_MINUTES = 480;
    private static final int DEFAULT_SESSION_IDLE_MINUTES = 15;
    private static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 3;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DevelopmentSessionService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    SessionLoginResponseWire login(SessionLoginRequestWire request) {
        String username = request.username() == null ? "" : request.username().trim().toLowerCase();
        LoginAttempt attempt = transactions.execute(status -> {
            Credential credential = jdbc.sql("""
                    select credential.tenant_id, credential.user_id, credential.password_hash,
                      credential.failed_attempts, credential.locked_until, account.status
                    from dev_user_credential credential
                    join app_user account on account.tenant_id = credential.tenant_id
                      and account.user_id = credential.user_id
                    where lower(credential.username) = :username
                    for update of credential
                    """).param("username", username)
                    .query((rs, row) -> new Credential(
                            rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class),
                            rs.getString("password_hash"), rs.getInt("failed_attempts"),
                            rs.getObject("locked_until", OffsetDateTime.class), rs.getString("status")))
                    .optional().orElse(null);
            if (credential == null || !"ACTIVE".equals(credential.accountStatus())) {
                return LoginAttempt.invalid();
            }
            if (credential.lockedUntil() != null && credential.lockedUntil().isAfter(OffsetDateTime.now())) {
                return LoginAttempt.lockedAttempt();
            }
            int maximumFailures = integerParameter(
                    credential.tenantId(), "auth-max-failed-attempts", DEFAULT_MAX_FAILURES, 3, 20);
            int lockoutMinutes = integerParameter(
                    credential.tenantId(), "auth-lockout-minutes", DEFAULT_LOCKOUT_MINUTES, 1, 1440);
            if (request.password() == null || !PASSWORDS.matches(request.password(), credential.passwordHash())) {
                int failures = credential.failedAttempts() + 1;
                jdbc.sql("""
                        update dev_user_credential
                        set failed_attempts = :failures,
                          locked_until = case when :failures >= :maximum
                            then now() + make_interval(mins => :lockout_minutes) else null end,
                          updated_at = now()
                        where tenant_id = :tenant and user_id = :user
                        """).param("failures", failures).param("maximum", maximumFailures)
                        .param("lockout_minutes", lockoutMinutes)
                        .param("tenant", credential.tenantId()).param("user", credential.userId()).update();
                appendAudit(credential.tenantId(), credential.userId(), UUID.randomUUID(), "LOGIN_FAILED");
                return failures >= maximumFailures ? LoginAttempt.lockedAttempt() : LoginAttempt.invalid();
            }

            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            UUID sessionId = UUID.randomUUID();
            int sessionMinutes = integerParameter(
                    credential.tenantId(), "auth-session-absolute-minutes", DEFAULT_SESSION_ABSOLUTE_MINUTES, 5, 1440);
            int maximumSessions = integerParameter(
                    credential.tenantId(), "auth-max-concurrent-sessions", DEFAULT_MAX_CONCURRENT_SESSIONS, 1, 10);
            revokeExcessSessions(credential.tenantId(), credential.userId(), maximumSessions - 1);
            Instant expiresAt = Instant.now().plusSeconds(sessionMinutes * 60L);
            jdbc.sql("""
                    insert into user_session(
                      tenant_id, session_id, user_id, token_hash, issued_at, expires_at, last_seen_at)
                    values (:tenant, :session, :user, :hash, now(), :expires, now())
                    """).param("tenant", credential.tenantId()).param("session", sessionId)
                    .param("user", credential.userId()).param("hash", sha256Bytes(token))
                    .param("expires", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)).update();
            jdbc.sql("""
                    update dev_user_credential set failed_attempts = 0, locked_until = null,
                      last_login_at = now(), updated_at = now()
                    where tenant_id = :tenant and user_id = :user
                    """).param("tenant", credential.tenantId()).param("user", credential.userId()).update();
            appendAudit(credential.tenantId(), credential.userId(), sessionId, "LOGIN_SUCCEEDED");
            return LoginAttempt.succeeded(new SessionLoginResponseWire(
                    token, sessionUser(credential.tenantId(), credential.userId(), expiresAt)));
        });
        if (attempt.locked()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "账户暂时锁定，请稍后重试");
        }
        if (attempt.response() == null) {
            throw invalidCredentials();
        }
        return attempt.response();
    }

    SessionUserWire current(String authorization) {
        SessionHead session = requireSession(authorization, true);
        return sessionUser(session.tenantId(), session.userId(), session.expiresAt());
    }

    ClinicalIdentity currentIdentity(String authorization) {
        SessionHead session = requireSession(authorization, true);
        List<UUID> roles = activeRoles(session.tenantId(), session.userId());
        if (roles.isEmpty()) {
            throw new ClinicalAccessDeniedException("CLINICAL_ROLE_REQUIRED", "No active clinical role assignment is available");
        }
        return new ClinicalIdentity(session.tenantId(), session.userId(), roles);
    }

    void logout(String authorization) {
        SessionHead session = requireSession(authorization, false);
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                    update user_session set revoked_at = coalesce(revoked_at, now()),
                      revoke_reason = coalesce(revoke_reason, 'USER_LOGOUT')
                    where tenant_id = :tenant and session_id = :session
                    """).param("tenant", session.tenantId()).param("session", session.sessionId()).update();
            appendAudit(session.tenantId(), session.userId(), session.sessionId(), "LOGOUT_SUCCEEDED");
        });
    }

    private SessionHead requireSession(String authorization, boolean touch) {
        String token = bearer(authorization);
        SessionHead result = jdbc.sql("""
                select tenant_id, session_id, user_id, expires_at, last_seen_at
                from user_session
                where token_hash = :hash and revoked_at is null and expires_at > now()
                """).param("hash", sha256Bytes(token))
                .query((rs, row) -> new SessionHead(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("session_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("last_seen_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> new ClinicalAccessDeniedException(
                        "AUTHENTICATION_REQUIRED", "登录会话无效或已过期"));
        int idleMinutes = integerParameter(
                result.tenantId(), "auth-session-idle-minutes", DEFAULT_SESSION_IDLE_MINUTES, 1, 240);
        if (result.lastSeenAt().isBefore(Instant.now().minusSeconds(idleMinutes * 60L))) {
            jdbc.sql("""
                    update user_session set revoked_at = now(), revoke_reason = 'IDLE_TIMEOUT'
                    where tenant_id = :tenant and session_id = :session and revoked_at is null
                    """).param("tenant", result.tenantId()).param("session", result.sessionId()).update();
            throw new ClinicalAccessDeniedException("SESSION_IDLE_TIMEOUT", "登录会话因长时间未操作已失效");
        }
        if (touch) {
            jdbc.sql("update user_session set last_seen_at = now() where tenant_id = :tenant and session_id = :session")
                    .param("tenant", result.tenantId()).param("session", result.sessionId()).update();
        }
        return result;
    }

    private int integerParameter(UUID tenantId, String key, int fallback, int minimum, int maximum) {
        String raw = jdbc.sql("""
                select payload ->> 'configured_value'
                from config_item
                where tenant_id = :tenant and config_type = 'PARAMETER' and config_key = :key
                  and status = 'ACTIVE'
                  and case
                    when payload ->> 'effective_at' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
                      then (payload ->> 'effective_at')::timestamptz
                    else 'infinity'::timestamptz
                  end <= now()
                order by published_at desc nulls last, row_version desc
                limit 1
                """).param("tenant", tenantId).param("key", key)
                .query(String.class).optional().orElse(null);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= minimum && value <= maximum ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private void revokeExcessSessions(UUID tenantId, UUID userId, int sessionsToKeep) {
        jdbc.sql("""
                update user_session set revoked_at = now(), revoke_reason = 'CONCURRENT_SESSION_LIMIT'
                where tenant_id = :tenant and user_id = :user and revoked_at is null and expires_at > now()
                  and session_id not in (
                    select session_id from user_session
                    where tenant_id = :tenant and user_id = :user and revoked_at is null and expires_at > now()
                    order by last_seen_at desc, issued_at desc limit :keep
                  )
                """).param("tenant", tenantId).param("user", userId).param("keep", sessionsToKeep).update();
    }

    private SessionUserWire sessionUser(UUID tenantId, UUID userId, Instant expiresAt) {
        Account account = jdbc.sql("""
                select account.display_name, assignment.organization_id, organization.display_name organization_name,
                  assignment.facility_id, facility.display_name facility_name
                from app_user account
                join role_assignment assignment on assignment.tenant_id = account.tenant_id
                  and assignment.user_id = account.user_id and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= now()
                  and (assignment.valid_until is null or assignment.valid_until > now())
                join organization on organization.tenant_id = assignment.tenant_id
                  and organization.organization_id = assignment.organization_id
                join facility on facility.tenant_id = assignment.tenant_id
                  and facility.facility_id = assignment.facility_id
                where account.tenant_id = :tenant and account.user_id = :user and account.status = 'ACTIVE'
                order by case when assignment.role_code = 'CLINICIAN' then 0 else 1 end
                limit 1
                """).param("tenant", tenantId).param("user", userId)
                .query((rs, row) -> new Account(
                        rs.getString("display_name"), rs.getObject("organization_id", UUID.class),
                        rs.getString("organization_name"), rs.getObject("facility_id", UUID.class),
                        rs.getString("facility_name")))
                .optional().orElseThrow(() -> new ClinicalAccessDeniedException(
                        "CLINICAL_ROLE_REQUIRED", "当前用户没有有效的机构岗位"));
        List<Role> roles = jdbc.sql("""
                select role_assignment_id, role_code from role_assignment
                where tenant_id = :tenant and user_id = :user and status = 'ACTIVE'
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                order by role_code, role_assignment_id
                """).param("tenant", tenantId).param("user", userId)
                .query((rs, row) -> new Role(rs.getObject("role_assignment_id", UUID.class), rs.getString("role_code")))
                .list();
        String shift = "今日 08:00–17:00 · " + DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.ofHours(8)).format(Instant.now());
        List<UUID> authorizedWards = jdbc.sql("""
                select distinct scope.ward_id from ward_role_scope scope
                join clinical_ward ward on ward.tenant_id=scope.tenant_id and ward.ward_id=scope.ward_id
                join role_assignment assignment on assignment.tenant_id=scope.tenant_id
                  and assignment.role_assignment_id=scope.role_assignment_id
                where scope.tenant_id=:tenant and ward.facility_id=:facility
                  and assignment.user_id=:user and assignment.status='ACTIVE'
                  and assignment.valid_from <= now() and (assignment.valid_until is null or assignment.valid_until > now())
                  and scope.valid_from <= now() and (scope.valid_until is null or scope.valid_until > now())
                order by scope.ward_id
                """).param("tenant",tenantId).param("facility",account.facilityId()).param("user",userId).query(UUID.class).list();
        return new SessionUserWire(tenantId, userId, account.displayName(), account.organizationId(),
                account.organizationName(), account.facilityId(), account.facilityName(),
                roles.stream().map(Role::id).toList(), roles.stream().map(Role::code).toList(), shift, expiresAt, authorizedWards);
    }

    private List<UUID> activeRoles(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                select role_assignment_id from role_assignment
                where tenant_id = :tenant and user_id = :user and status = 'ACTIVE'
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                order by role_assignment_id
                """).param("tenant", tenantId).param("user", userId).query(UUID.class).list();
    }

    private void appendAudit(UUID tenantId, UUID userId, UUID resourceId, String action) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", tenantId).query(UUID.class).single();
        String previous = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", tenantId).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String hash = sha256Hex(tenantId + "|" + auditId + "|" + action + "|" + resourceId + "|" + trace
                + "|" + (previous == null ? "GENESIS" : previous));
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'USER_SESSION', :resource, :trace, :previous, :hash)
                """).param("tenant", tenantId).param("audit", auditId).param("actor", userId)
                .param("action", action).param("resource", resourceId).param("trace", trace)
                .param("previous", previous).param("hash", hash).update();
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ClinicalAccessDeniedException("AUTHENTICATION_REQUIRED", "需要登录后访问");
        }
        return authorization.substring(7).trim();
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256Hex(String value) { return HexFormat.of().formatHex(sha256Bytes(value)); }
    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }

    private record Credential(UUID tenantId, UUID userId, String passwordHash, int failedAttempts,
                              OffsetDateTime lockedUntil, String accountStatus) {}
    private record LoginAttempt(SessionLoginResponseWire response, boolean locked) {
        private static LoginAttempt succeeded(SessionLoginResponseWire response) {
            return new LoginAttempt(response, false);
        }

        private static LoginAttempt invalid() {
            return new LoginAttempt(null, false);
        }

        private static LoginAttempt lockedAttempt() {
            return new LoginAttempt(null, true);
        }
    }
    private record SessionHead(UUID tenantId, UUID sessionId, UUID userId, Instant expiresAt, Instant lastSeenAt) {}
    private record Account(String displayName, UUID organizationId, String organizationName,
                           UUID facilityId, String facilityName) {}
    private record Role(UUID id, String code) {}
}
