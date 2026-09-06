package org.haoemr.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EncounterStateEventWire;
import org.haoemr.contracts.EncounterWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class EncounterStateMachineApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String PATIENT = "018f0000-0000-7000-8000-000000000001";

    @Autowired
    private ClinicalLifecycleService clinical;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private static void assertCommandCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            String expectedCode) {
        assertThatThrownBy(callable)
                .isInstanceOf(ClinicalCommandException.class)
                .satisfies(e -> assertThat(((ClinicalCommandException) e).code()).isEqualTo(expectedCode));
    }

    private EncounterWire create(String initialStatus, Instant startedAt) {
        return create(UUID.fromString(PATIENT), initialStatus, startedAt);
    }

    private EncounterWire create(UUID patientId, String initialStatus, Instant startedAt) {
        return create(patientId, "EMERGENCY", initialStatus, startedAt);
    }

    private EncounterWire create(UUID patientId, String encounterType, String initialStatus, Instant startedAt) {
        String key = "enc-sm-" + UUID.randomUUID();
        return clinical.createEncounter(identity(), key, organization, facility, patientId,
                encounterType, initialStatus, null, null, startedAt, "HAOEMR-TEST", key);
    }

    private UUID seedPatient() {
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成就诊列表患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1995, 5, 5)).update();
        return patientId;
    }

    @Test
    void givenPlannedCreation_whenTransitionsFollowStateMachine_thenEachStepAppendsEvidence() {
        Instant start = Instant.parse("2026-08-21T00:00:00Z");
        EncounterWire planned = create("PLANNED", start);
        assertThat(planned.status()).isEqualTo(EncounterWire.StatusValue.PLANNED);
        assertThat(planned.rowVersion()).isEqualTo(1L);

        EncounterWire arrived = clinical.transitionEncounter(identity(), "enc-sm-t-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 1L, "ARRIVED",
                start.plusSeconds(60), "ARRIVED_ON_SITE");
        assertThat(arrived.status()).isEqualTo(EncounterWire.StatusValue.ARRIVED);
        assertThat(arrived.rowVersion()).isEqualTo(2L);

        EncounterWire inProgress = clinical.transitionEncounter(identity(), "enc-sm-t-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 2L, "IN_PROGRESS",
                start.plusSeconds(120), "CARE_STARTED");
        assertThat(inProgress.status()).isEqualTo(EncounterWire.StatusValue.IN_PROGRESS);

        EncounterWire suspended = clinical.transitionEncounter(identity(), "enc-sm-t-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 3L, "SUSPENDED",
                start.plusSeconds(180), "患者暂离病区等待检查");
        assertThat(suspended.status()).isEqualTo(EncounterWire.StatusValue.SUSPENDED);
        assertThat(suspended.rowVersion()).isEqualTo(4L);

        EncounterWire resumed = clinical.transitionEncounter(identity(), "enc-sm-t-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 4L, "IN_PROGRESS",
                start.plusSeconds(240), "CARE_RESUMED");
        assertThat(resumed.status()).isEqualTo(EncounterWire.StatusValue.IN_PROGRESS);

        EncounterWire finished = clinical.transitionEncounter(identity(), "enc-sm-t-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 5L, "FINISHED",
                start.plusSeconds(300), "ENCOUNTER_COMPLETED");
        assertThat(finished.status()).isEqualTo(EncounterWire.StatusValue.FINISHED);
        assertThat(finished.endedAt()).isNotNull();
        assertThat(finished.rowVersion()).isEqualTo(6L);

        List<EncounterStateEventWire> events = clinical.listEncounterStateEvents(
                identity(), organization, facility, UUID.fromString(PATIENT), planned.encounterId());
        assertThat(events).hasSize(6);
        assertThat(events).extracting(EncounterStateEventWire::toStatus)
                .containsExactly(EncounterStateEventWire.ToStatusValue.FINISHED,
                        EncounterStateEventWire.ToStatusValue.IN_PROGRESS,
                        EncounterStateEventWire.ToStatusValue.SUSPENDED,
                        EncounterStateEventWire.ToStatusValue.IN_PROGRESS,
                        EncounterStateEventWire.ToStatusValue.ARRIVED,
                        EncounterStateEventWire.ToStatusValue.PLANNED);
        assertThat(events).extracting(EncounterStateEventWire::versionNo)
                .containsExactly(6L, 5L, 4L, 3L, 2L, 1L);
        assertThat(events.get(5).fromStatus()).isNull();
        assertThat(events.get(5).reason()).isEqualTo("ENCOUNTER_CREATED");
    }

    @Test
    void givenNoInitialStatus_whenCreated_thenDefaultsToInProgress() {
        EncounterWire encounter = create(null, Instant.parse("2026-08-21T01:00:00Z"));
        assertThat(encounter.status()).isEqualTo(EncounterWire.StatusValue.IN_PROGRESS);
        List<EncounterStateEventWire> events = clinical.listEncounterStateEvents(
                identity(), organization, facility, UUID.fromString(PATIENT), encounter.encounterId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).toStatus()).isEqualTo(EncounterStateEventWire.ToStatusValue.IN_PROGRESS);
    }

    @Test
    void givenOutpatientWithoutClinicalClosureEvidence_whenFinishing_thenServerRejectsTransition() {
        Instant startedAt = Instant.parse("2026-08-21T01:30:00Z");
        EncounterWire encounter = create(UUID.fromString(PATIENT), "OUTPATIENT", "IN_PROGRESS", startedAt);

        assertCommandCode(() -> clinical.transitionEncounter(identity(), "enc-sm-opd-close-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), encounter.encounterId(), 1L, "FINISHED",
                startedAt.plusSeconds(300), "门诊诊疗已完成"), "OUTPATIENT_CLOSURE_DOCUMENT_REQUIRED");
        assertThat(clinical.getEncounter(identity(), organization, facility, UUID.fromString(PATIENT),
                encounter.encounterId()).status()).isEqualTo(EncounterWire.StatusValue.IN_PROGRESS);
    }

    @Test
    void givenIllegalTransition_whenRequested_thenRejectedWithoutSideEffects() {
        EncounterWire planned = create("PLANNED", Instant.parse("2026-08-21T02:00:00Z"));

        // PLANNED cannot jump straight to IN_PROGRESS (must pass through ARRIVED).
        assertCommandCode(() -> clinical.transitionEncounter(identity(), "enc-sm-i-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 1L, "IN_PROGRESS",
                Instant.parse("2026-08-21T02:01:00Z"), "SKIP_ARRIVED"), "INVALID_ENCOUNTER_TRANSITION");

        EncounterWire arrived = clinical.transitionEncounter(identity(), "enc-sm-i-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 1L, "ARRIVED",
                Instant.parse("2026-08-21T02:01:00Z"), "ARRIVED_ON_SITE");

        // IN_PROGRESS cannot go back to PLANNED or ARRIVED.
        EncounterWire inProgress = clinical.transitionEncounter(identity(), "enc-sm-i-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 2L, "IN_PROGRESS",
                Instant.parse("2026-08-21T02:02:00Z"), "CARE_STARTED");
        assertCommandCode(() -> clinical.transitionEncounter(identity(), "enc-sm-i-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 3L, "ARRIVED",
                Instant.parse("2026-08-21T02:03:00Z"), "GO_BACK"), "INVALID_ENCOUNTER_TRANSITION");

        // Terminal states cannot transition further.
        EncounterWire finished = clinical.transitionEncounter(identity(), "enc-sm-i-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 3L, "FINISHED",
                Instant.parse("2026-08-21T02:03:00Z"), "ENCOUNTER_COMPLETED");
        assertCommandCode(() -> clinical.transitionEncounter(identity(), "enc-sm-i-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 4L, "IN_PROGRESS",
                Instant.parse("2026-08-21T02:04:00Z"), "REOPEN"), "INVALID_ENCOUNTER_TRANSITION");
        assertThat(finished.status()).isEqualTo(EncounterWire.StatusValue.FINISHED);
    }

    @Test
    void givenStaleExpectedVersion_whenTransitioning_thenConflict() {
        EncounterWire planned = create("PLANNED", Instant.parse("2026-08-21T03:00:00Z"));
        clinical.transitionEncounter(identity(), "enc-sm-v-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 1L, "ARRIVED",
                Instant.parse("2026-08-21T03:01:00Z"), "ARRIVED_ON_SITE");

        assertCommandCode(() -> clinical.transitionEncounter(identity(), "enc-sm-v-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 1L, "IN_PROGRESS",
                Instant.parse("2026-08-21T03:02:00Z"), "CARE_STARTED"), "VERSION_CONFLICT");
    }

    @Test
    void givenSuspendOrCancelWithoutReason_whenTransitioning_thenRejected() {
        EncounterWire inProgress = create("IN_PROGRESS", Instant.parse("2026-08-21T04:00:00Z"));
        assertCommandCode(() -> clinical.transitionEncounter(identity(), "enc-sm-r-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), inProgress.encounterId(), 1L, "SUSPENDED",
                Instant.parse("2026-08-21T04:01:00Z"), " "), "ENCOUNTER_TRANSITION_REASON_REQUIRED");

        EncounterWire planned = create("PLANNED", Instant.parse("2026-08-21T04:10:00Z"));
        assertCommandCode(() -> clinical.transitionEncounter(identity(), "enc-sm-r-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 1L, "CANCELLED",
                Instant.parse("2026-08-21T04:11:00Z"), null), "ENCOUNTER_TRANSITION_REASON_REQUIRED");
    }

    @Test
    void givenTerminalState_whenCancelled_thenEndedAtSetAndEvidenceImmutable() {
        EncounterWire planned = create("PLANNED", Instant.parse("2026-08-21T05:00:00Z"));
        EncounterWire cancelled = clinical.transitionEncounter(identity(), "enc-sm-c-" + UUID.randomUUID(),
                organization, facility, UUID.fromString(PATIENT), planned.encounterId(), 1L, "CANCELLED",
                Instant.parse("2026-08-21T05:01:00Z"), "患者取消就诊");
        assertThat(cancelled.status()).isEqualTo(EncounterWire.StatusValue.CANCELLED);
        assertThat(cancelled.endedAt()).isNotNull();

        UUID stateEventId = jdbc.sql("""
                select encounter_state_event_id from encounter_state_event
                where tenant_id = :tenant and encounter_id = :encounter and version_no = 2
                """).param("tenant", tenant).param("encounter", planned.encounterId())
                .query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("update encounter_state_event set reason = 'TAMPERED' where tenant_id = :tenant and encounter_state_event_id = :id")
                .param("tenant", tenant).param("id", stateEventId).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from encounter_state_event where tenant_id = :tenant and encounter_state_event_id = :id")
                .param("tenant", tenant).param("id", stateEventId).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenPatientEncounterListing_whenQueried_thenReturnsCurrentHead() {
        UUID patientId = seedPatient();
        EncounterWire created = create(patientId, "PLANNED", Instant.now());
        List<EncounterWire> encounters = clinical.listPatientEncounters(
                identity(), organization, facility, patientId);
        assertThat(encounters).extracting(EncounterWire::encounterId).first().isEqualTo(created.encounterId());

        EncounterWire fetched = clinical.getEncounter(
                identity(), organization, facility, patientId, created.encounterId());
        assertThat(fetched.status()).isEqualTo(EncounterWire.StatusValue.PLANNED);
        assertThat(fetched.departmentId()).isNull();
        assertThat(fetched.responsibleUserId()).isNull();
    }
}
