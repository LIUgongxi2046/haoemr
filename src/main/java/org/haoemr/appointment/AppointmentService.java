package org.haoemr.appointment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AppointmentBookRequestWire;
import org.haoemr.contracts.AppointmentCancelRequestWire;
import org.haoemr.contracts.AppointmentCheckInRequestWire;
import org.haoemr.contracts.AppointmentConsultRequestWire;
import org.haoemr.contracts.AppointmentRescheduleRequestWire;
import org.haoemr.contracts.AppointmentWire;
import org.haoemr.contracts.ScheduleSlotCreateRequestWire;
import org.haoemr.contracts.ScheduleSlotWire;
import org.haoemr.clinical.EncounterGateway;
import org.haoemr.contracts.WaitingQueueCallRequestWire;
import org.haoemr.contracts.WaitingQueueEntryWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AppointmentService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final EncounterGateway encounters;

    AppointmentService(JdbcClient jdbc, TransactionTemplate transactions, EncounterGateway encounters) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.encounters = encounters;
    }

    ScheduleSlotWire createScheduleSlot(
            ClinicalIdentity identity, String idempotencyKey, ScheduleSlotCreateRequestWire request) {
        if (request.visitType() == null || request.slotDate() == null || request.totalCapacity() == null
                || request.totalCapacity() <= 0) {
            throw invalid("visit_type, slot_date and a positive total_capacity are required");
        }
        if (request.startTime() == null || request.endTime() == null
                || request.startTime().isBlank() || request.endTime().isBlank()) {
            throw invalid("start_time and end_time are required");
        }
        UUID departmentId = request.departmentId() == null
                ? defaultDepartment(identity.tenantId(), request.facilityId()) : request.departmentId();
        UUID doctorUserId = request.doctorUserId() == null ? identity.userId() : request.doctorUserId();
        requireSlotScope(identity.tenantId(), request.facilityId(), departmentId, doctorUserId);
        return transactions.execute(status -> {
            String requestHash = sha256(request.organizationId() + "|" + request.facilityId() + "|"
                    + departmentId + "|" + doctorUserId + "|" + request.visitType() + "|" + request.slotDate() + "|"
                    + request.startTime() + "|" + request.endTime() + "|" + request.totalCapacity());
            beginCommand(identity, "SCHEDULE_SLOT_CREATE", idempotencyKey, requestHash);
            UUID slotId = UUID.randomUUID();
            jdbc.sql("""
                    insert into schedule_slot(
                      tenant_id, schedule_slot_id, organization_id, facility_id, department_id,
                      doctor_user_id, visit_type, slot_date, start_time, end_time, total_capacity, booked_count, status)
                    values (:tenant, :slot, :organization, :facility, :department, :doctor,
                      :visit_type, :slot_date, cast(:start_time as time), cast(:end_time as time),
                      :capacity, 0, 'OPEN')
                    """).param("tenant", identity.tenantId()).param("slot", slotId)
                    .param("organization", request.organizationId()).param("facility", request.facilityId())
                    .param("department", departmentId).param("doctor", doctorUserId)
                    .param("visit_type", request.visitType().name())
                    .param("slot_date", request.slotDate()).param("start_time", request.startTime())
                    .param("end_time", request.endTime()).param("capacity", request.totalCapacity()).update();
            appendEvidence(identity, null, slotId, 1, "SCHEDULE_SLOT_CREATED", "ScheduleSlotCreated");
            completeCommand(identity, "SCHEDULE_SLOT_CREATE", idempotencyKey, slotId);
            return slot(identity.tenantId(), slotId, request.facilityId());
        });
    }

    List<ScheduleSlotWire> listScheduleSlots(
            ClinicalIdentity identity, UUID facilityId, LocalDate fromDate, UUID departmentId, UUID doctorUserId) {
        return jdbc.sql("""
                select schedule_slot_id from schedule_slot
                where tenant_id = :tenant and facility_id = :facility and slot_date >= :from_date
                  and department_id is not null and doctor_user_id is not null
                  and (cast(:department as uuid) is null or department_id = cast(:department as uuid))
                  and (cast(:doctor as uuid) is null or doctor_user_id = cast(:doctor as uuid))
                order by slot_date, start_time, department_id, doctor_user_id
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .param("from_date", fromDate).param("department", departmentId).param("doctor", doctorUserId)
                .query(UUID.class).list().stream().map(id -> slot(identity.tenantId(), id, facilityId)).toList();
    }

    AppointmentWire bookAppointment(
            ClinicalIdentity identity, String idempotencyKey, AppointmentBookRequestWire request) {
        if (request.source() == null) throw invalid("source is required");
        return transactions.execute(status -> {
            String requestHash = sha256(request.organizationId() + "|" + request.facilityId() + "|"
                    + request.patientId() + "|" + request.scheduleSlotId() + "|" + request.source());
            beginCommand(identity, "APPOINTMENT_BOOK", idempotencyKey, requestHash);
            requireActivePatient(identity.tenantId(), request.patientId());
            int booked = jdbc.sql("""
                    update schedule_slot
                    set booked_count = booked_count + 1, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and schedule_slot_id = :slot
                      and status = 'OPEN' and booked_count < total_capacity and slot_date >= current_date
                    """).param("tenant", identity.tenantId()).param("slot", request.scheduleSlotId()).update();
            if (booked != 1) {
                throw new AppointmentException(
                        "SCHEDULE_SLOT_UNAVAILABLE", 409, "The slot is full, closed or in the past");
            }
            String visitType = jdbc.sql("""
                    select visit_type from schedule_slot
                    where tenant_id = :tenant and schedule_slot_id = :slot
                    """).param("tenant", identity.tenantId()).param("slot", request.scheduleSlotId())
                    .query(String.class).single();
            UUID appointmentId = UUID.randomUUID();
            jdbc.sql("""
                    insert into appointment(
                      tenant_id, appointment_id, schedule_slot_id, patient_id, organization_id,
                      facility_id, visit_type, source, status, booked_at)
                    values (:tenant, :appointment, :slot, :patient, :organization,
                      :facility, :visit_type, :source, 'BOOKED', now())
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("slot", request.scheduleSlotId()).param("patient", request.patientId())
                    .param("organization", request.organizationId()).param("facility", request.facilityId())
                    .param("visit_type", visitType).param("source", request.source().name()).update();
            jdbc.sql("""
                    insert into appointment_event(
                      tenant_id, appointment_event_id, appointment_id, event_type,
                      previous_status, resulting_status, actor_user_id)
                    values (:tenant, gen_random_uuid(), :appointment, 'BOOKED', null, 'BOOKED', :actor)
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("actor", identity.userId()).update();
            appendEvidence(identity, request.patientId(), appointmentId, 1, "APPOINTMENT_BOOKED", "AppointmentBooked");
            completeCommand(identity, "APPOINTMENT_BOOK", idempotencyKey, appointmentId);
            return appointment(identity.tenantId(), appointmentId, request.patientId(), request.facilityId());
        });
    }

    AppointmentWire cancelAppointment(
            ClinicalIdentity identity, String idempotencyKey, UUID appointmentId,
            AppointmentCancelRequestWire request) {
        String reason = request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.length() < 2) throw invalid("a cancellation reason is required");
        return transactions.execute(status -> {
            beginCommand(identity, "APPOINTMENT_CANCEL", idempotencyKey,
                    sha256(appointmentId + "|" + request.expectedRowVersion() + "|" + reason));
            AppointmentHead current = jdbc.sql("""
                    select status, row_version, schedule_slot_id, patient_id from appointment
                    where tenant_id = :tenant and appointment_id = :appointment
                      and patient_id = :patient and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("patient", request.patientId()).param("facility", request.facilityId())
                    .query((rs, row) -> new AppointmentHead(
                            rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("schedule_slot_id", UUID.class), rs.getObject("patient_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new AppointmentException(
                        "APPOINTMENT_VERSION_CONFLICT", 409, "The appointment changed; reload before retrying");
            }
            if (!"BOOKED".equals(current.status())) {
                throw new AppointmentException(
                        "APPOINTMENT_STATE_INVALID", 409, "Only a booked appointment can be cancelled");
            }
            jdbc.sql("""
                    update appointment set status = 'CANCELLED', cancelled_at = now(),
                      cancel_reason = :reason, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and appointment_id = :appointment and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("appointment", appointmentId).param("expected", current.rowVersion()).update();
            jdbc.sql("""
                    update schedule_slot set booked_count = greatest(booked_count - 1, 0),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and schedule_slot_id = :slot
                    """).param("tenant", identity.tenantId()).param("slot", current.scheduleSlotId()).update();
            jdbc.sql("""
                    insert into appointment_event(
                      tenant_id, appointment_event_id, appointment_id, event_type,
                      previous_status, resulting_status, actor_user_id, reason)
                    values (:tenant, gen_random_uuid(), :appointment, 'CANCELLED',
                      :previous, 'CANCELLED', :actor, :reason)
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("previous", current.status()).param("actor", identity.userId())
                    .param("reason", reason).update();
            appendEvidence(identity, current.patientId(), appointmentId, current.rowVersion() + 1,
                    "APPOINTMENT_CANCELLED", "AppointmentCancelled");
            completeCommand(identity, "APPOINTMENT_CANCEL", idempotencyKey, appointmentId);
            return appointment(identity.tenantId(), appointmentId, request.patientId(), request.facilityId());
        });
    }

    AppointmentWire rescheduleAppointment(
            ClinicalIdentity identity, String idempotencyKey, UUID appointmentId,
            AppointmentRescheduleRequestWire request) {
        String reason = request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.length() < 2) throw invalid("a reschedule reason is required");
        return transactions.execute(status -> {
            beginCommand(identity, "APPOINTMENT_RESCHEDULE", idempotencyKey,
                    sha256(appointmentId + "|" + request.expectedRowVersion() + "|"
                            + request.scheduleSlotId() + "|" + reason));
            AppointmentHead current = jdbc.sql("""
                    select status, row_version, schedule_slot_id, patient_id from appointment
                    where tenant_id = :tenant and appointment_id = :appointment
                      and patient_id = :patient and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("patient", request.patientId()).param("facility", request.facilityId())
                    .query((rs, row) -> new AppointmentHead(
                            rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("schedule_slot_id", UUID.class), rs.getObject("patient_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new AppointmentException(
                        "APPOINTMENT_VERSION_CONFLICT", 409, "The appointment changed; reload before retrying");
            }
            if (!"BOOKED".equals(current.status())) {
                throw new AppointmentException(
                        "APPOINTMENT_STATE_INVALID", 409, "Only a booked appointment can be rescheduled");
            }
            if (current.scheduleSlotId().equals(request.scheduleSlotId())) {
                throw invalid("the new schedule slot must differ from the current slot");
            }
            int reserved = jdbc.sql("""
                    update schedule_slot set booked_count = booked_count + 1,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and schedule_slot_id = :slot
                      and organization_id = :organization and facility_id = :facility
                      and status = 'OPEN' and booked_count < total_capacity and slot_date >= current_date
                    """).param("tenant", identity.tenantId()).param("slot", request.scheduleSlotId())
                    .param("organization", request.organizationId()).param("facility", request.facilityId()).update();
            if (reserved != 1) {
                throw new AppointmentException(
                        "SCHEDULE_SLOT_UNAVAILABLE", 409, "The new slot is full, closed, outside the facility or in the past");
            }
            int moved = jdbc.sql("""
                    update appointment set schedule_slot_id = :new_slot,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and appointment_id = :appointment and row_version = :expected
                    """).param("new_slot", request.scheduleSlotId()).param("tenant", identity.tenantId())
                    .param("appointment", appointmentId).param("expected", current.rowVersion()).update();
            if (moved != 1) {
                throw new AppointmentException(
                        "APPOINTMENT_VERSION_CONFLICT", 409, "The appointment changed; reload before retrying");
            }
            jdbc.sql("""
                    update schedule_slot set booked_count = greatest(booked_count - 1, 0),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and schedule_slot_id = :slot
                    """).param("tenant", identity.tenantId()).param("slot", current.scheduleSlotId()).update();
            jdbc.sql("""
                    insert into appointment_event(
                      tenant_id, appointment_event_id, appointment_id, event_type,
                      previous_status, resulting_status, actor_user_id, reason)
                    values (:tenant, gen_random_uuid(), :appointment, 'RESCHEDULED',
                      'BOOKED', 'BOOKED', :actor, :reason)
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("actor", identity.userId()).param("reason", reason).update();
            appendEvidence(identity, current.patientId(), appointmentId, current.rowVersion() + 1,
                    "APPOINTMENT_RESCHEDULED", "AppointmentRescheduled");
            completeCommand(identity, "APPOINTMENT_RESCHEDULE", idempotencyKey, appointmentId);
            return appointment(identity.tenantId(), appointmentId, request.patientId(), request.facilityId());
        });
    }

    List<AppointmentWire> listAppointments(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId) {
        requireActivePatient(identity.tenantId(), patientId);
        return jdbc.sql("""
                select appointment.appointment_id, appointment.schedule_slot_id, appointment.patient_id,
                  patient.display_name as patient_display_name, patient.sex_code as patient_sex_code,
                  patient.birth_date as patient_birth_date, appointment.organization_id,
                  appointment.facility_id, facility.display_name facility_name,
                  coalesce(slot.department_id, fallback_department.department_id) department_id,
                  coalesce(department.display_name, fallback_department.display_name) department_name,
                  coalesce(slot.doctor_user_id, fallback_doctor.user_id) doctor_user_id,
                  coalesce(doctor.display_name, fallback_doctor.display_name) doctor_display_name,
                  slot.slot_date, slot.start_time, slot.end_time,
                  appointment.visit_type, appointment.source, appointment.status,
                  appointment.booked_at, appointment.cancelled_at, appointment.encounter_id,
                  appointment.row_version
                from appointment
                join patient on patient.tenant_id = appointment.tenant_id
                  and patient.patient_id = appointment.patient_id
                join facility on facility.tenant_id = appointment.tenant_id
                  and facility.facility_id = appointment.facility_id
                join schedule_slot slot on slot.tenant_id = appointment.tenant_id
                  and slot.schedule_slot_id = appointment.schedule_slot_id
                left join clinical_department department on department.tenant_id = slot.tenant_id
                  and department.facility_id = slot.facility_id and department.department_id = slot.department_id
                left join app_user doctor on doctor.tenant_id = slot.tenant_id and doctor.user_id = slot.doctor_user_id
                left join lateral (
                  select configured.department_id, configured.display_name
                  from clinical_department configured
                  where configured.tenant_id = slot.tenant_id
                    and configured.facility_id = slot.facility_id and configured.status = 'ACTIVE'
                  order by configured.department_code, configured.department_id limit 1
                ) fallback_department on true
                left join lateral (
                  select account.user_id, account.display_name
                  from role_assignment assignment
                  join app_user account on account.tenant_id = assignment.tenant_id
                    and account.user_id = assignment.user_id and account.status = 'ACTIVE'
                  where assignment.tenant_id = slot.tenant_id
                    and assignment.organization_id = slot.organization_id
                    and (assignment.facility_id is null or assignment.facility_id = slot.facility_id)
                    and assignment.status = 'ACTIVE'
                  order by case when assignment.facility_id = slot.facility_id then 0 else 1 end,
                    account.user_id limit 1
                ) fallback_doctor on true
                where appointment.tenant_id = :tenant and appointment.patient_id = :patient
                  and appointment.organization_id = :organization and appointment.facility_id = :facility
                order by appointment.booked_at desc, appointment.appointment_id limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("organization", organizationId).param("facility", facilityId)
                .query((rs, row) -> mapAppointment(rs)).list();
    }

    WaitingQueueEntryWire checkIn(
            ClinicalIdentity identity, String idempotencyKey, UUID appointmentId,
            AppointmentCheckInRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "APPOINTMENT_CHECK_IN", idempotencyKey,
                    sha256(appointmentId + "|" + request.expectedRowVersion()));
            AppointmentHead current = jdbc.sql("""
                    select status, row_version, schedule_slot_id, patient_id from appointment
                    where tenant_id = :tenant and appointment_id = :appointment
                      and patient_id = :patient and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("patient", request.patientId()).param("facility", request.facilityId())
                    .query((rs, row) -> new AppointmentHead(
                            rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("schedule_slot_id", UUID.class), rs.getObject("patient_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new AppointmentException(
                        "APPOINTMENT_VERSION_CONFLICT", 409, "The appointment changed; reload before retrying");
            }
            if (!"BOOKED".equals(current.status())) {
                throw new AppointmentException(
                        "APPOINTMENT_STATE_INVALID", 409, "Only a booked appointment can check in");
            }
            jdbc.sql("""
                    update appointment set status = 'CHECKED_IN', check_in_at = now(),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and appointment_id = :appointment and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("expected", current.rowVersion()).update();
            jdbc.sql("""
                    select facility_id from facility
                    where tenant_id = :tenant and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("facility", request.facilityId())
                    .query(UUID.class).single();
            int sequence = jdbc.sql("""
                    select coalesce(max(sequence_no), 0) + 1 from waiting_queue_entry
                    where tenant_id = :tenant and facility_id = :facility and queue_date = current_date
                    """).param("tenant", identity.tenantId()).param("facility", request.facilityId())
                    .query(Integer.class).single();
            UUID entryId = UUID.randomUUID();
            jdbc.sql("""
                    insert into waiting_queue_entry(
                      tenant_id, waiting_queue_entry_id, appointment_id, facility_id,
                      queue_date, sequence_no, status)
                    values (:tenant, :entry, :appointment, :facility, current_date, :sequence, 'WAITING')
                    """).param("tenant", identity.tenantId()).param("entry", entryId)
                    .param("appointment", appointmentId).param("facility", request.facilityId())
                    .param("sequence", sequence).update();
            jdbc.sql("""
                    insert into appointment_event(
                      tenant_id, appointment_event_id, appointment_id, event_type,
                      previous_status, resulting_status, actor_user_id)
                    values (:tenant, gen_random_uuid(), :appointment, 'CHECKED_IN',
                      :previous, 'CHECKED_IN', :actor)
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("previous", current.status()).param("actor", identity.userId()).update();
            UUID encounterId = encounters.createEncounter(
                    identity, "checkin-enc-" + UUID.randomUUID(),
                    request.organizationId(), request.facilityId(), current.patientId(),
                    "OUTPATIENT", "ARRIVED", null, null, Instant.now(),
                    "HAOEMR-APPOINTMENT", appointmentId.toString()).encounterId();
            jdbc.sql("""
                    update appointment set encounter_id = :encounter
                    where tenant_id = :tenant and appointment_id = :appointment
                    """).param("encounter", encounterId).param("tenant", identity.tenantId())
                    .param("appointment", appointmentId).update();
            appendEvidence(identity, current.patientId(), appointmentId, current.rowVersion() + 1,
                    "APPOINTMENT_CHECKED_IN", "AppointmentCheckedIn");
            completeCommand(identity, "APPOINTMENT_CHECK_IN", idempotencyKey, appointmentId);
            return queueEntry(identity.tenantId(), entryId, request.facilityId());
        });
    }

    AppointmentWire consult(
            ClinicalIdentity identity, String idempotencyKey, UUID appointmentId,
            AppointmentConsultRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "APPOINTMENT_CONSULT", idempotencyKey,
                    sha256(appointmentId + "|" + request.expectedRowVersion()));
            AppointmentHead current = jdbc.sql("""
                    select status, row_version, schedule_slot_id, patient_id from appointment
                    where tenant_id = :tenant and appointment_id = :appointment
                      and patient_id = :patient and facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .param("patient", request.patientId()).param("facility", request.facilityId())
                    .query((rs, row) -> new AppointmentHead(
                            rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("schedule_slot_id", UUID.class), rs.getObject("patient_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new AppointmentException(
                        "APPOINTMENT_VERSION_CONFLICT", 409, "The appointment changed; reload before retrying");
            }
            if (!"CHECKED_IN".equals(current.status())) {
                throw new AppointmentException(
                        "APPOINTMENT_STATE_INVALID", 409, "Only a checked-in appointment can start consultation");
            }
            EncounterLink encounter = jdbc.sql("""
                    select e.encounter_id, e.status, e.row_version
                    from appointment appt
                    join encounter e on e.tenant_id = appt.tenant_id and e.encounter_id = appt.encounter_id
                    where appt.tenant_id = :tenant and appt.appointment_id = :appointment
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId)
                    .query((rs, row) -> new EncounterLink(
                            rs.getObject("encounter_id", UUID.class), rs.getString("status"),
                            rs.getLong("row_version"))).optional().orElse(null);
            if (encounter == null || !"ARRIVED".equals(encounter.status())) {
                throw new AppointmentException(
                        "APPOINTMENT_STATE_INVALID", 409, "The linked encounter is not ready to start consultation");
            }
            encounters.transitionEncounter(identity, "consult-enc-" + UUID.randomUUID(),
                    request.organizationId(), request.facilityId(), current.patientId(),
                    encounter.encounterId(), encounter.rowVersion(), "IN_PROGRESS",
                    Instant.now(), "CONSULTATION_STARTED");
            jdbc.sql("""
                    update waiting_queue_entry set status = 'IN_CONSULTATION',
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and appointment_id = :appointment
                      and status in ('WAITING', 'CALLED')
                    """).param("tenant", identity.tenantId()).param("appointment", appointmentId).update();
            appendEvidence(identity, current.patientId(), appointmentId, current.rowVersion() + 1,
                    "APPOINTMENT_CONSULTED", "AppointmentConsulted");
            completeCommand(identity, "APPOINTMENT_CONSULT", idempotencyKey, appointmentId);
            return appointment(identity.tenantId(), appointmentId, request.patientId(), request.facilityId());
        });
    }

    WaitingQueueEntryWire callWaitingQueue(
            ClinicalIdentity identity, String idempotencyKey, UUID entryId,
            WaitingQueueCallRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "WAITING_QUEUE_CALL", idempotencyKey,
                    sha256(entryId + "|" + request.expectedRowVersion()));
            QueueEntryHead current = jdbc.sql("""
                    select entry.status, entry.row_version, entry.appointment_id, appt.patient_id
                    from waiting_queue_entry entry
                    join appointment appt on appt.tenant_id = entry.tenant_id
                      and appt.appointment_id = entry.appointment_id
                    where entry.tenant_id = :tenant and entry.waiting_queue_entry_id = :entry
                      and entry.facility_id = :facility for update
                    """).param("tenant", identity.tenantId()).param("entry", entryId)
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new QueueEntryHead(
                            rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("appointment_id", UUID.class), rs.getObject("patient_id", UUID.class)))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new AppointmentException(
                        "WAITING_QUEUE_VERSION_CONFLICT", 409, "The queue entry changed; reload before retrying");
            }
            if (!"WAITING".equals(current.status())) {
                throw new AppointmentException(
                        "WAITING_QUEUE_STATE_INVALID", 409, "Only a waiting entry can be called");
            }
            jdbc.sql("""
                    update waiting_queue_entry set status = 'CALLED', called_at = now(),
                      called_by = :actor, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and waiting_queue_entry_id = :entry and row_version = :expected
                    """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                    .param("entry", entryId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, current.patientId(), entryId, current.rowVersion() + 1,
                    "WAITING_QUEUE_CALLED", "WaitingQueueCalled");
            completeCommand(identity, "WAITING_QUEUE_CALL", idempotencyKey, entryId);
            return queueEntry(identity.tenantId(), entryId, request.facilityId());
        });
    }

    List<WaitingQueueEntryWire> listWaitingQueue(
            ClinicalIdentity identity, UUID facilityId, LocalDate date) {
        return jdbc.sql("""
                select waiting_queue_entry_id from waiting_queue_entry
                where tenant_id = :tenant and facility_id = :facility and queue_date = :date
                order by sequence_no, created_at
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .param("date", date).query(UUID.class).list().stream()
                .map(id -> queueEntry(identity.tenantId(), id, facilityId)).toList();
    }

    private WaitingQueueEntryWire queueEntry(UUID tenantId, UUID entryId, UUID facilityId) {
        return jdbc.sql("""
                select q.waiting_queue_entry_id, q.appointment_id, a.patient_id,
                  patient.display_name as patient_display_name, patient.sex_code as patient_sex_code,
                  patient.birth_date as patient_birth_date, a.encounter_id,
                  q.facility_id, q.queue_date, q.sequence_no, q.status, q.called_at,
                  q.called_by, q.row_version
                from waiting_queue_entry q
                join appointment a on a.tenant_id = q.tenant_id and a.appointment_id = q.appointment_id
                join patient on patient.tenant_id = a.tenant_id and patient.patient_id = a.patient_id
                where q.tenant_id = :tenant and q.waiting_queue_entry_id = :entry and q.facility_id = :facility
                """).param("tenant", tenantId).param("entry", entryId).param("facility", facilityId)
                .query((rs, row) -> new WaitingQueueEntryWire(
                        rs.getObject("waiting_queue_entry_id", UUID.class),
                        rs.getObject("appointment_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getString("patient_display_name"),
                        rs.getString("patient_sex_code"), rs.getObject("patient_birth_date", LocalDate.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getObject("queue_date", LocalDate.class), rs.getInt("sequence_no"),
                        WaitingQueueEntryWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("called_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("called_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("called_by", UUID.class), rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private ScheduleSlotWire slot(UUID tenantId, UUID slotId, UUID facilityId) {
        return jdbc.sql("""
                select slot.schedule_slot_id, slot.organization_id, slot.facility_id, slot.department_id,
                  slot.doctor_user_id, facility.display_name facility_name,
                  department.display_name department_name, doctor.display_name doctor_display_name,
                  slot.visit_type, slot.slot_date, slot.start_time, slot.end_time, slot.total_capacity,
                  slot.booked_count, slot.status, slot.row_version
                from schedule_slot slot
                join facility on facility.tenant_id = slot.tenant_id and facility.facility_id = slot.facility_id
                join clinical_department department on department.tenant_id = slot.tenant_id
                  and department.facility_id = slot.facility_id and department.department_id = slot.department_id
                join app_user doctor on doctor.tenant_id = slot.tenant_id and doctor.user_id = slot.doctor_user_id
                where slot.tenant_id = :tenant and slot.schedule_slot_id = :slot and slot.facility_id = :facility
                """).param("tenant", tenantId).param("slot", slotId).param("facility", facilityId)
                .query((rs, row) -> new ScheduleSlotWire(
                        rs.getObject("schedule_slot_id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getObject("facility_id", UUID.class), rs.getObject("department_id", UUID.class),
                        rs.getObject("doctor_user_id", UUID.class), rs.getString("facility_name"),
                        rs.getString("department_name"), rs.getString("doctor_display_name"),
                        ScheduleSlotWire.VisitTypeValue.valueOf(rs.getString("visit_type")),
                        rs.getObject("slot_date", LocalDate.class), rs.getObject("start_time", java.sql.Time.class).toString(),
                        rs.getObject("end_time", java.sql.Time.class).toString(), rs.getInt("total_capacity"),
                        rs.getInt("booked_count"), ScheduleSlotWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> new AppointmentException("SCHEDULE_SLOT_NOT_FOUND", 404, "Slot not found"));
    }

    private AppointmentWire appointment(UUID tenantId, UUID appointmentId, UUID patientId, UUID facilityId) {
        return jdbc.sql("""
                select appointment.appointment_id, appointment.schedule_slot_id, appointment.patient_id,
                  patient.display_name as patient_display_name, patient.sex_code as patient_sex_code,
                  patient.birth_date as patient_birth_date, appointment.organization_id,
                  appointment.facility_id, facility.display_name facility_name,
                  coalesce(slot.department_id, fallback_department.department_id) department_id,
                  coalesce(department.display_name, fallback_department.display_name) department_name,
                  coalesce(slot.doctor_user_id, fallback_doctor.user_id) doctor_user_id,
                  coalesce(doctor.display_name, fallback_doctor.display_name) doctor_display_name,
                  slot.slot_date, slot.start_time, slot.end_time,
                  appointment.visit_type, appointment.source,
                  appointment.status, appointment.booked_at, appointment.cancelled_at,
                  appointment.encounter_id, appointment.row_version
                from appointment
                join patient on patient.tenant_id = appointment.tenant_id
                  and patient.patient_id = appointment.patient_id
                join facility on facility.tenant_id = appointment.tenant_id
                  and facility.facility_id = appointment.facility_id
                join schedule_slot slot on slot.tenant_id = appointment.tenant_id
                  and slot.schedule_slot_id = appointment.schedule_slot_id
                left join clinical_department department on department.tenant_id = slot.tenant_id
                  and department.facility_id = slot.facility_id and department.department_id = slot.department_id
                left join app_user doctor on doctor.tenant_id = slot.tenant_id and doctor.user_id = slot.doctor_user_id
                left join lateral (
                  select configured.department_id, configured.display_name
                  from clinical_department configured
                  where configured.tenant_id = slot.tenant_id
                    and configured.facility_id = slot.facility_id
                    and configured.status = 'ACTIVE'
                  order by configured.department_code, configured.department_id
                  limit 1
                ) fallback_department on true
                left join lateral (
                  select account.user_id, account.display_name
                  from role_assignment assignment
                  join app_user account on account.tenant_id = assignment.tenant_id
                    and account.user_id = assignment.user_id and account.status = 'ACTIVE'
                  where assignment.tenant_id = slot.tenant_id
                    and assignment.organization_id = slot.organization_id
                    and (assignment.facility_id is null or assignment.facility_id = slot.facility_id)
                    and assignment.status = 'ACTIVE'
                  order by case when assignment.facility_id = slot.facility_id then 0 else 1 end,
                    account.user_id
                  limit 1
                ) fallback_doctor on true
                where appointment.tenant_id = :tenant and appointment.appointment_id = :appointment
                  and appointment.patient_id = :patient and appointment.facility_id = :facility
                """).param("tenant", tenantId).param("appointment", appointmentId)
                .param("patient", patientId).param("facility", facilityId)
                .query((rs, row) -> mapAppointment(rs)).optional().orElseThrow(() -> contextDenied());
    }

    private AppointmentWire mapAppointment(ResultSet rs) throws SQLException {
        UUID appointmentId = rs.getObject("appointment_id", UUID.class);
        long version = rs.getLong("row_version");
        OffsetDateTime cancelledAt = rs.getObject("cancelled_at", OffsetDateTime.class);
        return new AppointmentWire(
                appointmentId, rs.getObject("schedule_slot_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("patient_display_name"),
                rs.getString("patient_sex_code"), rs.getObject("patient_birth_date", LocalDate.class),
                rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                rs.getString("facility_name"), rs.getObject("department_id", UUID.class),
                rs.getString("department_name"), rs.getObject("doctor_user_id", UUID.class),
                rs.getString("doctor_display_name"), rs.getObject("slot_date", LocalDate.class),
                rs.getObject("start_time", java.sql.Time.class).toString(),
                rs.getObject("end_time", java.sql.Time.class).toString(),
                AppointmentWire.VisitTypeValue.valueOf(rs.getString("visit_type")),
                AppointmentWire.SourceValue.valueOf(rs.getString("source")),
                AppointmentWire.StatusValue.valueOf(rs.getString("status")),
                rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
                cancelledAt == null ? null : cancelledAt.toInstant(), rs.getObject("encounter_id", UUID.class),
                version, sha256(appointmentId + "|" + version));
    }

    private void requireActivePatient(UUID tenantId, UUID patientId) {
        long count = jdbc.sql("""
                select count(*) from patient where tenant_id = :tenant and patient_id = :patient
                  and status in ('ACTIVE', 'PENDING_VERIFICATION')
                """).param("tenant", tenantId).param("patient", patientId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private UUID defaultDepartment(UUID tenantId, UUID facilityId) {
        return jdbc.sql("""
                select department_id from clinical_department
                where tenant_id = :tenant and facility_id = :facility and status = 'ACTIVE'
                order by department_code limit 1
                """).param("tenant", tenantId).param("facility", facilityId).query(UUID.class)
                .optional().orElseThrow(() -> invalid("department_id is required"));
    }

    private void requireSlotScope(UUID tenantId, UUID facilityId, UUID departmentId, UUID doctorUserId) {
        long count = jdbc.sql("""
                select count(*) from clinical_department department
                join app_user doctor on doctor.tenant_id = department.tenant_id
                where department.tenant_id = :tenant and department.facility_id = :facility
                  and department.department_id = :department and department.status = 'ACTIVE'
                  and doctor.user_id = :doctor and doctor.status = 'ACTIVE'
                """).param("tenant", tenantId).param("facility", facilityId)
                .param("department", departmentId).param("doctor", doctorUserId).query(Long.class).single();
        if (count != 1) throw invalid("department_id and doctor_user_id must reference active configuration");
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new AppointmentException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new AppointmentException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID resourceId, long version,
            String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + resourceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'APPOINTMENT', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("patient_hash", patientId == null ? null : sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'APPOINTMENT', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", resourceId).param("version", version).param("event_type", eventType).update();
    }

    private static AppointmentException invalid(String message) {
        return new AppointmentException("APPOINTMENT_REQUEST_INVALID", 400, message);
    }

    static AppointmentException contextDenied() {
        return new AppointmentException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested appointment context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record AppointmentHead(
            String status, long rowVersion, UUID scheduleSlotId, UUID patientId) {}
    private record QueueEntryHead(
            String status, long rowVersion, UUID appointmentId, UUID patientId) {}
    private record EncounterLink(UUID encounterId, String status, long rowVersion) {}
}
