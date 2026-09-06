package org.haoemr.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.Instant;
import org.haoemr.contracts.ClinicalReminderConversionCreateRequestWire;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalReminderAcknowledgeRequestWire;
import org.haoemr.contracts.ClinicalReminderCreateRequestWire;
import org.haoemr.contracts.ClinicalReminderSilenceRequestWire;
import org.haoemr.contracts.ClinicalReminderWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ClinicalReminderApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ClinicalReminderService reminders;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ClinicalReminderConversionService conversions;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成提醒患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1978, 9, 9)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-REM', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    @Test
    void givenActiveEncounter_whenCreatingAndAcknowledgingReminder_thenLifecycleRecorded() {
        Context context = seedContext();
        ClinicalReminderWire created = reminders.create(identity(), "rem-" + UUID.randomUUID(),
                new ClinicalReminderCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), ClinicalReminderCreateRequestWire.ReminderTypeValue.CRITICAL_VALUE,
                        "危急值未处置，请尽快查看", ClinicalReminderCreateRequestWire.SeverityValue.CRITICAL, null, null));
        assertThat(created.status()).isEqualTo(ClinicalReminderWire.StatusValue.PENDING);
        assertThat(created.severity()).isEqualTo(ClinicalReminderWire.SeverityValue.CRITICAL);

        ClinicalReminderWire acknowledged = reminders.acknowledge(identity(), "ack-" + UUID.randomUUID(),
                created.reminderId(), new ClinicalReminderAcknowledgeRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), created.rowVersion()));
        assertThat(acknowledged.status()).isEqualTo(ClinicalReminderWire.StatusValue.ACKNOWLEDGED);
        assertThat(acknowledged.acknowledgedAt()).isNotNull();

        List<ClinicalReminderWire> listed = reminders.list(
                identity(), organization, facility, context.patientId(), context.encounterId());
        assertThat(listed).extracting(ClinicalReminderWire::reminderId).contains(created.reminderId());
    }

    @Test
    void givenPendingReminder_whenSilenced_thenStatusSilenced() {
        Context context = seedContext();
        ClinicalReminderWire created = reminders.create(identity(), "rem-" + UUID.randomUUID(),
                new ClinicalReminderCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), ClinicalReminderCreateRequestWire.ReminderTypeValue.OVERDUE_TASK,
                        "有任务已超时", ClinicalReminderCreateRequestWire.SeverityValue.WARNING, null, null));
        ClinicalReminderWire silenced = reminders.silence(identity(), "sil-" + UUID.randomUUID(),
                created.reminderId(), new ClinicalReminderSilenceRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), created.rowVersion()));
        assertThat(silenced.status()).isEqualTo(ClinicalReminderWire.StatusValue.SILENCED);
        assertThat(silenced.silencedAt()).isNotNull();
    }

    @Test
    void givenReminderContent_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ClinicalReminderWire created = reminders.create(identity(), "rem-" + UUID.randomUUID(),
                new ClinicalReminderCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), ClinicalReminderCreateRequestWire.ReminderTypeValue.OTHER,
                        "随访提醒", ClinicalReminderCreateRequestWire.SeverityValue.INFO, null, null));
        assertThatThrownBy(() -> jdbc.sql("""
                update clinical_reminder set message = 'TAMPERED'
                where tenant_id = cast(:tenant as uuid) and reminder_id = :reminder
                """).param("tenant", TENANT).param("reminder", created.reminderId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void longCandidateReminderCanBecomeATaskAndItsSourceClosesIt() {
        Context context = seedContext();
        for (boolean acknowledge : List.of(true, false)) {
            var reminder = reminders.create(identity(), UUID.randomUUID().toString(),
                    new ClinicalReminderCreateRequestWire(organization, facility, context.patientId(), context.encounterId(),
                            ClinicalReminderCreateRequestWire.ReminderTypeValue.OTHER, "完整待办内容".repeat(100),
                            ClinicalReminderCreateRequestWire.SeverityValue.INFO, null, null));
            var conversion = conversions.convert(identity(), UUID.randomUUID().toString(),
                    new ClinicalReminderConversionCreateRequestWire(organization, facility, context.patientId(),
                            context.encounterId(), reminder.reminderId(), Instant.now()));
            if (acknowledge) reminders.acknowledge(identity(), UUID.randomUUID().toString(), reminder.reminderId(),
                    new ClinicalReminderAcknowledgeRequestWire(organization, facility, context.patientId(), context.encounterId(), reminder.rowVersion()));
            else reminders.silence(identity(), UUID.randomUUID().toString(), reminder.reminderId(),
                    new ClinicalReminderSilenceRequestWire(organization, facility, context.patientId(), context.encounterId(), reminder.rowVersion()));
            String state = jdbc.sql("select state from clinical_task where tenant_id=:tenant and task_id=:task")
                    .param("tenant",tenant).param("task",conversion.clinicalTaskId()).query(String.class).single();
            assertThat(state).isEqualTo(acknowledge ? "COMPLETED" : "WITHDRAWN");
            assertThat(jdbc.sql("select count(*) from clinical_task_event where tenant_id=:tenant and task_id=:task and event_type=:event")
                    .param("tenant",tenant).param("task",conversion.clinicalTaskId())
                    .param("event",acknowledge ? "SOURCE_COMPLETED" : "SOURCE_WITHDRAWN").query(Long.class).single()).isEqualTo(1L);
        }
    }

    @Test
    void unverifiedAgentSourceCannotCreateReminder() {
        Context context = seedContext();
        assertThatThrownBy(() -> reminders.create(identity(),UUID.randomUUID().toString(),
                new ClinicalReminderCreateRequestWire(organization,facility,context.patientId(),context.encounterId(),
                        ClinicalReminderCreateRequestWire.ReminderTypeValue.OTHER,"无有效来源的候选提醒",
                        ClinicalReminderCreateRequestWire.SeverityValue.INFO,null,UUID.randomUUID())))
                .isInstanceOf(ClinicalReminderException.class);
        assertThat(reminders.list(identity(),organization,facility,context.patientId(),context.encounterId())).isEmpty();
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
