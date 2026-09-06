package org.haoemr.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalTaskNotificationCreateRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationDeliverRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationDispatchRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationDispatchResultWire;
import org.haoemr.contracts.ClinicalTaskNotificationFailRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationRecoverRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationRecoveryResultWire;
import org.haoemr.contracts.ClinicalTaskNotificationWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ClinicalTaskNotificationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ClinicalTaskNotificationService notifications;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成任务通知患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1975, 4, 4)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-NOTIFY', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level, state, business_state, source_route)
                values (cast(:tenant as uuid), :task, :patient, :encounter, cast(:facility as uuid),
                  'DOCUMENT', :source, 'SIGN_DOCUMENT', '待签文书', 'ROUTINE', 'PENDING', 'OPEN', '#/record')
                """).param("tenant", TENANT).param("task", taskId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY)
                .param("source", UUID.randomUUID()).update();
        return new Context(patientId, encounterId, taskId);
    }

    private ClinicalTaskNotificationWire create(Context context) {
        return createScheduled(context, null);
    }

    private ClinicalTaskNotificationWire createScheduled(Context context, Instant scheduledAt) {
        return createScheduled(context, scheduledAt, ClinicalTaskNotificationCreateRequestWire.ChannelValue.IN_APP);
    }

    private ClinicalTaskNotificationWire createScheduled(
            Context context, Instant scheduledAt, ClinicalTaskNotificationCreateRequestWire.ChannelValue channel) {
        return notifications.create(identity(), "notify-" + UUID.randomUUID(),
                new ClinicalTaskNotificationCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), context.taskId(), UUID.fromString(USER),
                        ClinicalTaskNotificationCreateRequestWire.KindValue.CREATED,
                        channel, scheduledAt));
    }

    private ClinicalTaskNotificationDispatchResultWire dispatchDue(Instant scheduledBefore, Integer batchSize) {
        return notifications.dispatchDue(identity(), "dispatch-" + UUID.randomUUID(),
                new ClinicalTaskNotificationDispatchRequestWire(organization, facility, scheduledBefore, batchSize));
    }

    private ClinicalTaskNotificationWire deliver(Context context, UUID notificationId, long expectedRowVersion) {
        return notifications.deliver(identity(), "deliver-" + UUID.randomUUID(), notificationId,
                new ClinicalTaskNotificationDeliverRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), expectedRowVersion));
    }

    private ClinicalTaskNotificationWire fail(Context context, UUID notificationId, long expectedRowVersion, String error) {
        return notifications.fail(identity(), "fail-" + UUID.randomUUID(), notificationId,
                new ClinicalTaskNotificationFailRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), expectedRowVersion, error));
    }

    private ClinicalTaskNotificationRecoveryResultWire recover(Context context) {
        return notifications.recover(identity(), "recover-" + UUID.randomUUID(),
                new ClinicalTaskNotificationRecoverRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), context.taskId()));
    }

    @Test
    void givenNotification_whenCreatingAndListing_thenPending() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = create(context);
        assertThat(created.status()).isEqualTo(ClinicalTaskNotificationWire.StatusValue.PENDING);
        assertThat(created.attemptCount()).isZero();
        assertThat(created.recipientUserId()).isEqualTo(UUID.fromString(USER));

        List<ClinicalTaskNotificationWire> listed = notifications.list(
                identity(), context.patientId(), context.encounterId(), facility, context.taskId());
        assertThat(listed).extracting(ClinicalTaskNotificationWire::notificationId).contains(created.notificationId());
    }

    @Test
    void givenPendingNotification_whenDelivering_thenDelivered() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = create(context);
        ClinicalTaskNotificationWire delivered = deliver(context, created.notificationId(), 1L);
        assertThat(delivered.status()).isEqualTo(ClinicalTaskNotificationWire.StatusValue.DELIVERED);
        assertThat(delivered.deliveredAt()).isNotNull();
        assertThat(jdbc.sql("""
                select count(*) from clinical_task_in_app_delivery
                where tenant_id = cast(:tenant as uuid) and notification_id = :notification
                  and recipient_user_id = cast(:recipient as uuid)
                """).param("tenant", TENANT).param("notification", created.notificationId())
                .param("recipient", USER).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenStaleVersion_whenDelivering_thenRejected() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = create(context);
        assertThatThrownBy(() -> deliver(context, created.notificationId(), 99L))
                .isInstanceOf(ClinicalTaskNotificationException.class)
                .satisfies(e -> assertThat(((ClinicalTaskNotificationException) e).code())
                        .isEqualTo("TASK_NOTIFICATION_VERSION_CONFLICT"));
    }

    @Test
    void givenPendingNotification_whenFailing_thenFailedWithError() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = create(context);
        ClinicalTaskNotificationWire failed = fail(context, created.notificationId(), 1L, "channel timeout");
        assertThat(failed.status()).isEqualTo(ClinicalTaskNotificationWire.StatusValue.FAILED);
        assertThat(failed.lastError()).isEqualTo("channel timeout");
    }

    @Test
    void givenFailedNotification_whenRecovering_thenRequeuedWithAttemptIncrement() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = create(context);
        fail(context, created.notificationId(), 1L, "channel timeout");

        ClinicalTaskNotificationRecoveryResultWire result = recover(context);
        assertThat(result.recoveredCount()).isEqualTo(1);

        ClinicalTaskNotificationWire requeued = notifications.list(
                        identity(), context.patientId(), context.encounterId(), facility, context.taskId())
                .stream().filter(n -> n.notificationId().equals(created.notificationId())).findFirst().orElseThrow();
        assertThat(requeued.status()).isEqualTo(ClinicalTaskNotificationWire.StatusValue.PENDING);
        assertThat(requeued.attemptCount()).isEqualTo(1);
        assertThat(requeued.lastError()).isNull();

        ClinicalTaskNotificationWire delivered = deliver(context, created.notificationId(), requeued.rowVersion());
        assertThat(delivered.status()).isEqualTo(ClinicalTaskNotificationWire.StatusValue.DELIVERED);
    }

    @Test
    void givenDeliveredNotification_whenRecovering_thenUntouched() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = create(context);
        deliver(context, created.notificationId(), 1L);

        ClinicalTaskNotificationRecoveryResultWire result = recover(context);
        assertThat(result.recoveredCount()).isZero();
    }

    @Test
    void givenDuePendingNotification_whenDispatching_thenDelivered() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = create(context);
        ClinicalTaskNotificationDispatchResultWire result =
                dispatchDue(Instant.now().plusSeconds(60), 1000);
        assertThat(result.dispatchedCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.notificationIds()).contains(created.notificationId());

        ClinicalTaskNotificationWire dispatched = notifications.list(
                        identity(), context.patientId(), context.encounterId(), facility, context.taskId())
                .stream().filter(n -> n.notificationId().equals(created.notificationId())).findFirst().orElseThrow();
        assertThat(dispatched.status()).isEqualTo(ClinicalTaskNotificationWire.StatusValue.DELIVERED);
        assertThat(jdbc.sql("""
                select count(*) from clinical_task_in_app_delivery
                where tenant_id = cast(:tenant as uuid) and notification_id = :notification
                """).param("tenant", TENANT).param("notification", created.notificationId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenExternalOutboxNotification_whenDispatching_thenRemainsPendingForHospitalAdapter() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = createScheduled(
                context, null, ClinicalTaskNotificationCreateRequestWire.ChannelValue.OUTBOX);

        ClinicalTaskNotificationDispatchResultWire result = dispatchDue(Instant.now().plusSeconds(60), 1000);
        assertThat(result.notificationIds()).doesNotContain(created.notificationId());
        assertThat(notifications.list(identity(), context.patientId(), context.encounterId(), facility, context.taskId())
                .stream().filter(item -> item.notificationId().equals(created.notificationId()))
                .findFirst().orElseThrow().status())
                .isEqualTo(ClinicalTaskNotificationWire.StatusValue.PENDING);
        assertThatThrownBy(() -> deliver(context, created.notificationId(), created.rowVersion()))
                .isInstanceOf(ClinicalTaskNotificationException.class)
                .satisfies(e -> assertThat(((ClinicalTaskNotificationException) e).code())
                        .isEqualTo("TASK_NOTIFICATION_EXTERNAL_ADAPTER_REQUIRED"));
    }

    @Test
    void givenFutureScheduledNotification_whenDispatching_thenNotDelivered() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = createScheduled(context, Instant.now().plusSeconds(3600));
        ClinicalTaskNotificationDispatchResultWire result = dispatchDue(Instant.now(), 1000);
        assertThat(result.notificationIds()).doesNotContain(created.notificationId());

        ClinicalTaskNotificationWire stillPending = notifications.list(
                        identity(), context.patientId(), context.encounterId(), facility, context.taskId())
                .stream().filter(n -> n.notificationId().equals(created.notificationId())).findFirst().orElseThrow();
        assertThat(stillPending.status()).isEqualTo(ClinicalTaskNotificationWire.StatusValue.PENDING);
    }

    @Test
    void givenNotificationIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ClinicalTaskNotificationWire created = create(context);
        assertThatThrownBy(() -> jdbc.sql("""
                update clinical_task_notification set kind = 'ESCALATED'
                where tenant_id = cast(:tenant as uuid) and notification_id = :notification
                """).param("tenant", TENANT).param("notification", created.notificationId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId, UUID taskId) {}
}
