package org.haoemr.tasks;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalTaskNotificationDispatchRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dispatches due in-app notifications using a tenant-scoped service account. */
@Component
@ConditionalOnProperty(
        name = "haoemr.task-center.notification-scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
final class ClinicalTaskNotificationScheduler {
    static final String AUTOMATION_SUBJECT = "system:clinical-task-automation";

    private final JdbcClient jdbc;
    private final ClinicalTaskNotificationService notifications;

    ClinicalTaskNotificationScheduler(JdbcClient jdbc, ClinicalTaskNotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${haoemr.task-center.notification-poll-delay-ms:5000}")
    void dispatch() {
        for (AutomationContext context : contexts()) {
            Instant cutoff = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            try {
                notifications.dispatchDue(
                        new ClinicalIdentity(context.tenantId(), context.userId(), context.roleIds()),
                        "task-notification-scheduler-" + cutoff.truncatedTo(ChronoUnit.MINUTES),
                        new ClinicalTaskNotificationDispatchRequestWire(
                                context.organizationId(), context.facilityId(), cutoff, 200));
            } catch (ClinicalTaskNotificationException replayOrConcurrentRun) {
                if (!"IDEMPOTENCY_REPLAY".equals(replayOrConcurrentRun.code())) throw replayOrConcurrentRun;
            }
        }
    }

    private List<AutomationContext> contexts() {
        return jdbc.sql("""
                select account.tenant_id, account.user_id, role.organization_id, role.facility_id,
                  array_agg(role.role_assignment_id order by role.role_assignment_id)::text as roles
                from app_user account
                join role_assignment role on role.tenant_id = account.tenant_id
                  and role.user_id = account.user_id
                where account.external_subject = :subject and account.status = 'ACTIVE'
                  and role.status = 'ACTIVE' and role.facility_id is not null
                  and role.valid_from <= now() and (role.valid_until is null or role.valid_until > now())
                  and exists (
                    select 1 from clinical_task_notification notification
                    where notification.tenant_id = account.tenant_id
                      and notification.status = 'PENDING' and notification.channel = 'IN_APP'
                      and notification.scheduled_at <= now())
                group by account.tenant_id, account.user_id, role.organization_id, role.facility_id
                """).param("subject", AUTOMATION_SUBJECT)
                .query((rs, row) -> new AutomationContext(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        parseRoles(rs.getString("roles")))).list();
    }

    private static List<UUID> parseRoles(String postgresArray) {
        if (postgresArray == null || postgresArray.length() < 3) return List.of();
        return java.util.Arrays.stream(postgresArray.substring(1, postgresArray.length() - 1).split(","))
                .map(value -> UUID.fromString(value.replace("\"", ""))).toList();
    }

    private record AutomationContext(
            UUID tenantId, UUID userId, UUID organizationId, UUID facilityId, List<UUID> roleIds) {}
}
