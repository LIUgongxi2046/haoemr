package org.haoemr.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.haoemr.contracts.AppointmentBookRequestWire;
import org.haoemr.contracts.AppointmentCancelRequestWire;
import org.haoemr.contracts.AppointmentCheckInRequestWire;
import org.haoemr.contracts.AppointmentConsultRequestWire;
import org.haoemr.contracts.AppointmentRescheduleRequestWire;
import org.haoemr.contracts.AppointmentWire;
import org.haoemr.contracts.ScheduleSlotCreateRequestWire;
import org.haoemr.contracts.ScheduleSlotWire;
import org.haoemr.contracts.WaitingQueueCallRequestWire;
import org.haoemr.contracts.WaitingQueueEntryWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class AppointmentSchedulingApiTest {
    private static final AtomicInteger SLOT_SEQUENCE = new AtomicInteger();

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String PATIENT = "018f0000-0000-7000-8000-000000000001";
    private static final String DEPARTMENT = "018f0000-0000-7000-8000-00000000aa08";

    @Autowired
    private AppointmentService appointments;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);
    private final UUID patient = UUID.fromString(PATIENT);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ScheduleSlotWire createSlot(int capacity) {
        int runOffset = Math.floorMod(UUID.randomUUID().hashCode(), 20_000) + 365;
        return appointments.createScheduleSlot(identity(), "slot-" + UUID.randomUUID(),
                new ScheduleSlotCreateRequestWire(organization, facility, UUID.fromString(DEPARTMENT), UUID.fromString(USER),
                        ScheduleSlotCreateRequestWire.VisitTypeValue.OUTPATIENT,
                        LocalDate.now().plusDays(runOffset + SLOT_SEQUENCE.incrementAndGet()),
                        "09:00:00", "10:00:00", capacity));
    }

    @Test
    void givenSyntheticWaitingQueue_whenListed_thenEveryEntryHasEncounterContext() {
        List<WaitingQueueEntryWire> queue = appointments.listWaitingQueue(identity(), facility, LocalDate.now());

        assertThat(queue).isNotEmpty();
        assertThat(queue).allSatisfy(entry -> {
            assertThat(entry.encounterId()).isNotNull();
            assertThat(entry.patientDisplayName()).matches("\\p{IsHan}{2,4}")
                    .doesNotContain("合成", "测试", "患者");
            assertThat(entry.patientSexCode()).isIn("M", "F");
            assertThat(entry.patientBirthDate()).isNotNull();
        });
    }

    @Test
    void givenSingleCapacitySlot_whenTwoBookings_thenSecondIsRejectedWithoutOversell() {
        ScheduleSlotWire slot = createSlot(1);
        assertThat(slot.bookedCount()).isZero();

        AppointmentWire first = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, slot.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));
        assertThat(first.status()).isEqualTo(AppointmentWire.StatusValue.BOOKED);
        assertThat(first.patientDisplayName()).isEqualTo("张慧敏");
        assertThat(jdbc.sql("""
                select booked_count from schedule_slot
                where tenant_id = cast(:tenant as uuid) and schedule_slot_id = :slot
                """).param("tenant", TENANT).param("slot", slot.scheduleSlotId())
                .query(Integer.class).single()).isEqualTo(1);

        assertThatThrownBy(() -> appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, slot.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT)))
                .isInstanceOf(AppointmentException.class)
                .satisfies(e -> assertThat(((AppointmentException) e).code())
                        .isEqualTo("SCHEDULE_SLOT_UNAVAILABLE"));
        assertThat(jdbc.sql("""
                select booked_count from schedule_slot
                where tenant_id = cast(:tenant as uuid) and schedule_slot_id = :slot
                """).param("tenant", TENANT).param("slot", slot.scheduleSlotId())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void givenBookedAppointment_whenCancelled_thenCapacityReleasedAndRebookable() {
        ScheduleSlotWire slot = createSlot(1);
        AppointmentWire booked = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, slot.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));

        AppointmentWire cancelled = appointments.cancelAppointment(identity(), "cancel-" + UUID.randomUUID(),
                booked.appointmentId(), new AppointmentCancelRequestWire(
                        organization, facility, patient, booked.rowVersion(), "患者行程变更无法按时就诊"));
        assertThat(cancelled.status()).isEqualTo(AppointmentWire.StatusValue.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(jdbc.sql("""
                select booked_count from schedule_slot
                where tenant_id = cast(:tenant as uuid) and schedule_slot_id = :slot
                """).param("tenant", TENANT).param("slot", slot.scheduleSlotId())
                .query(Integer.class).single()).isZero();

        AppointmentWire rebooked = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, slot.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));
        assertThat(rebooked.status()).isEqualTo(AppointmentWire.StatusValue.BOOKED);
    }

    @Test
    void givenBookedAppointment_whenRescheduled_thenCapacityMovesAtomicallyAndEventIsPreserved() {
        ScheduleSlotWire original = createSlot(1);
        ScheduleSlotWire replacement = createSlot(1);
        AppointmentWire booked = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, original.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));

        AppointmentWire moved = appointments.rescheduleAppointment(identity(), "reschedule-" + UUID.randomUUID(),
                booked.appointmentId(), new AppointmentRescheduleRequestWire(
                        organization, facility, patient, replacement.scheduleSlotId(), booked.rowVersion(),
                        "患者时间冲突，改约下一时段"));

        assertThat(moved.scheduleSlotId()).isEqualTo(replacement.scheduleSlotId());
        assertThat(moved.status()).isEqualTo(AppointmentWire.StatusValue.BOOKED);
        assertThat(moved.rowVersion()).isEqualTo(booked.rowVersion() + 1);
        assertThat(jdbc.sql("""
                select booked_count from schedule_slot
                where tenant_id = cast(:tenant as uuid) and schedule_slot_id = :slot
                """).param("tenant", TENANT).param("slot", original.scheduleSlotId())
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                select booked_count from schedule_slot
                where tenant_id = cast(:tenant as uuid) and schedule_slot_id = :slot
                """).param("tenant", TENANT).param("slot", replacement.scheduleSlotId())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from appointment_event
                where tenant_id = cast(:tenant as uuid) and appointment_id = :appointment
                  and event_type = 'RESCHEDULED'
                """).param("tenant", TENANT).param("appointment", booked.appointmentId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenCheckedInAppointment_whenRescheduled_thenWorkflowRejectsTheEdit() {
        ScheduleSlotWire original = createSlot(1);
        ScheduleSlotWire replacement = createSlot(1);
        AppointmentWire booked = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, original.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));
        appointments.checkIn(identity(), "checkin-" + UUID.randomUUID(), booked.appointmentId(),
                new AppointmentCheckInRequestWire(organization, facility, patient, booked.rowVersion()));

        assertThatThrownBy(() -> appointments.rescheduleAppointment(identity(), "reschedule-" + UUID.randomUUID(),
                booked.appointmentId(), new AppointmentRescheduleRequestWire(
                        organization, facility, patient, replacement.scheduleSlotId(), booked.rowVersion() + 1,
                        "尝试跨过报到状态改约")))
                .isInstanceOf(AppointmentException.class)
                .satisfies(error -> assertThat(((AppointmentException) error).code())
                        .isEqualTo("APPOINTMENT_STATE_INVALID"));
        assertThat(jdbc.sql("""
                select booked_count from schedule_slot
                where tenant_id = cast(:tenant as uuid) and schedule_slot_id = :slot
                """).param("tenant", TENANT).param("slot", replacement.scheduleSlotId())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void givenReplayedBookingKey_thenIdempotencyReplayWithoutDoubleBooking() {
        ScheduleSlotWire slot = createSlot(2);
        String key = "book-replay-" + UUID.randomUUID();
        AppointmentBookRequestWire request = new AppointmentBookRequestWire(
                organization, facility, patient, slot.scheduleSlotId(), AppointmentBookRequestWire.SourceValue.APPOINTMENT);
        appointments.bookAppointment(identity(), key, request);
        assertThatThrownBy(() -> appointments.bookAppointment(identity(), key, request))
                .isInstanceOf(AppointmentException.class)
                .satisfies(e -> assertThat(((AppointmentException) e).code()).isEqualTo("IDEMPOTENCY_REPLAY"));
        assertThat(jdbc.sql("""
                select count(*) from appointment
                where tenant_id = cast(:tenant as uuid) and schedule_slot_id = :slot and status <> 'CANCELLED'
                """).param("tenant", TENANT).param("slot", slot.scheduleSlotId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenBookedAppointment_whenCheckedInAndCalled_thenEntersAndAdvancesTheWaitingQueue() {
        ScheduleSlotWire slot = createSlot(1);
        AppointmentWire booked = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, slot.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));

        WaitingQueueEntryWire entered = appointments.checkIn(identity(), "checkin-" + UUID.randomUUID(),
                booked.appointmentId(), new AppointmentCheckInRequestWire(
                        organization, facility, patient, booked.rowVersion()));
        assertThat(entered.status()).isEqualTo(WaitingQueueEntryWire.StatusValue.WAITING);
        assertThat(entered.sequenceNo()).isPositive();
        assertThat(entered.appointmentId()).isEqualTo(booked.appointmentId());
        assertThat(jdbc.sql("""
                select status from appointment
                where tenant_id = cast(:tenant as uuid) and appointment_id = :appointment
                """).param("tenant", TENANT).param("appointment", booked.appointmentId())
                .query(String.class).single()).isEqualTo("CHECKED_IN");

        UUID encounterId = jdbc.sql("""
                select encounter_id from appointment
                where tenant_id = cast(:tenant as uuid) and appointment_id = :appointment
                """).param("tenant", TENANT).param("appointment", booked.appointmentId())
                .query(UUID.class).single();
        assertThat(encounterId).isNotNull();
        assertThat(jdbc.sql("""
                select status from encounter where tenant_id = cast(:tenant as uuid) and encounter_id = :encounter
                """).param("tenant", TENANT).param("encounter", encounterId)
                .query(String.class).single()).isEqualTo("ARRIVED");

        WaitingQueueEntryWire called = appointments.callWaitingQueue(identity(), "call-" + UUID.randomUUID(),
                entered.waitingQueueEntryId(), new WaitingQueueCallRequestWire(
                        organization, facility, entered.rowVersion()));
        assertThat(called.status()).isEqualTo(WaitingQueueEntryWire.StatusValue.CALLED);
        assertThat(called.calledAt()).isNotNull();
        assertThat(called.calledBy()).isEqualTo(UUID.fromString(USER));

        List<WaitingQueueEntryWire> queue = appointments.listWaitingQueue(identity(), facility, LocalDate.now());
        assertThat(queue).extracting(WaitingQueueEntryWire::waitingQueueEntryId)
                .contains(entered.waitingQueueEntryId());
    }

    @Test
    void givenCheckedInAppointment_whenConsulted_thenEncounterAdvancedToInProgress() {
        ScheduleSlotWire slot = createSlot(1);
        AppointmentWire booked = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, slot.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));
        appointments.checkIn(identity(), "checkin-" + UUID.randomUUID(), booked.appointmentId(),
                new AppointmentCheckInRequestWire(organization, facility, patient, booked.rowVersion()));

        AppointmentWire consulted = appointments.consult(identity(), "consult-" + UUID.randomUUID(),
                booked.appointmentId(), new AppointmentConsultRequestWire(
                        organization, facility, patient, booked.rowVersion() + 1));
        assertThat(consulted.encounterId()).isNotNull();
        assertThat(jdbc.sql("""
                select status from encounter
                where tenant_id = cast(:tenant as uuid) and encounter_id = :encounter
                """).param("tenant", TENANT).param("encounter", consulted.encounterId())
                .query(String.class).single()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.sql("""
                select status from waiting_queue_entry
                where tenant_id = cast(:tenant as uuid) and appointment_id = :appointment
                """).param("tenant", TENANT).param("appointment", booked.appointmentId())
                .query(String.class).single()).isEqualTo("IN_CONSULTATION");
    }

    @Test
    void givenCheckedInAppointment_whenCheckedInAgain_thenStateInvalid() {
        ScheduleSlotWire slot = createSlot(1);
        AppointmentWire booked = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, slot.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));
        WaitingQueueEntryWire entered = appointments.checkIn(identity(), "checkin-" + UUID.randomUUID(),
                booked.appointmentId(), new AppointmentCheckInRequestWire(
                        organization, facility, patient, booked.rowVersion()));
        assertThat(entered.status()).isEqualTo(WaitingQueueEntryWire.StatusValue.WAITING);

        assertThatThrownBy(() -> appointments.checkIn(identity(), "checkin-" + UUID.randomUUID(),
                booked.appointmentId(), new AppointmentCheckInRequestWire(
                        organization, facility, patient, 2L)))
                .isInstanceOf(AppointmentException.class)
                .satisfies(e -> assertThat(((AppointmentException) e).code())
                        .isEqualTo("APPOINTMENT_STATE_INVALID"));
    }

    @Test
    void givenAppointmentEvents_whenTampered_thenDatabaseRejectsMutation() {
        ScheduleSlotWire slot = createSlot(1);
        AppointmentWire booked = appointments.bookAppointment(identity(), "book-" + UUID.randomUUID(),
                new AppointmentBookRequestWire(organization, facility, patient, slot.scheduleSlotId(),
                        AppointmentBookRequestWire.SourceValue.APPOINTMENT));
        UUID eventId = jdbc.sql("""
                select appointment_event_id from appointment_event
                where tenant_id = cast(:tenant as uuid) and appointment_id = :appointment
                """).param("tenant", TENANT).param("appointment", booked.appointmentId())
                .query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("""
                update appointment_event set reason = 'tamper'
                where tenant_id = cast(:tenant as uuid) and appointment_event_id = :event
                """).param("tenant", TENANT).param("event", eventId).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
