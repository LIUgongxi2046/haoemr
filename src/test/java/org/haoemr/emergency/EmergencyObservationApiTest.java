package org.haoemr.emergency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyObservationCompleteRequestWire;
import org.haoemr.contracts.EmergencyObservationStartRequestWire;
import org.haoemr.contracts.EmergencyObservationWire;
import org.haoemr.contracts.EmergencyClinicalFactVoidRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class EmergencyObservationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private EmergencyObservationService observations;

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
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成留观患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1992, 8, 8)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'EMERGENCY', 'IN_PROGRESS', now(), 'SYNTHETIC-OBSERVATION', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private EmergencyObservationWire start(Context context) {
        return observations.startObservation(identity(), "obs-" + UUID.randomUUID(),
                new EmergencyObservationStartRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), Instant.now()));
    }

    @Test
    void givenObservation_whenStartingAndCompleting_thenLifecycleRecorded() {
        Context context = seedContext();
        EmergencyObservationWire started = start(context);
        assertThat(started.status()).isEqualTo(EmergencyObservationWire.StatusValue.OBSERVING);
        assertThat(started.disposition()).isEqualTo(EmergencyObservationWire.DispositionValue.PENDING);

        EmergencyObservationWire completed = observations.completeObservation(identity(), "complete-" + UUID.randomUUID(),
                started.observationId(), new EmergencyObservationCompleteRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        started.rowVersion(), EmergencyObservationCompleteRequestWire.DispositionValue.DISCHARGED));
        assertThat(completed.status()).isEqualTo(EmergencyObservationWire.StatusValue.COMPLETED);
        assertThat(completed.disposition()).isEqualTo(EmergencyObservationWire.DispositionValue.DISCHARGED);
        assertThat(completed.completedAt()).isNotNull();

        List<EmergencyObservationWire> listed = observations.listObservations(identity(), context.patientId());
        assertThat(listed).extracting(EmergencyObservationWire::observationId).contains(started.observationId());
    }

    @Test
    void givenStaleRowVersion_whenCompleting_thenRejected() {
        Context context = seedContext();
        EmergencyObservationWire started = start(context);
        assertThatThrownBy(() -> observations.completeObservation(identity(), "complete-" + UUID.randomUUID(),
                started.observationId(), new EmergencyObservationCompleteRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        999L, EmergencyObservationCompleteRequestWire.DispositionValue.ADMITTED)))
                .isInstanceOf(EmergencyObservationException.class)
                .satisfies(e -> assertThat(((EmergencyObservationException) e).code())
                        .isEqualTo("EMERGENCY_OBSERVATION_VERSION_CONFLICT"));
    }

    @Test
    void givenObservation_whenVoiding_thenReasonIsAuditedAndReplacementCanStart() {
        Context context = seedContext();
        EmergencyObservationWire started = start(context);
        EmergencyObservationWire voided = observations.voidObservation(identity(), "void-" + UUID.randomUUID(),
                started.observationId(), new EmergencyClinicalFactVoidRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        started.rowVersion(), "误为该患者开启留观"));
        assertThat(voided.voidedAt()).isNotNull();
        assertThat(voided.voidReason()).isEqualTo("误为该患者开启留观");
        EmergencyObservationWire replacement = start(context);
        assertThat(replacement.observationId()).isNotEqualTo(started.observationId());
        assertThat(replacement.voidedAt()).isNull();
    }

    @Test
    void givenAlreadyCompleted_whenCompletingAgain_thenRejected() {
        Context context = seedContext();
        EmergencyObservationWire started = start(context);
        observations.completeObservation(identity(), "complete-" + UUID.randomUUID(),
                started.observationId(), new EmergencyObservationCompleteRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        started.rowVersion(), EmergencyObservationCompleteRequestWire.DispositionValue.TRANSFERRED));
        assertThatThrownBy(() -> observations.completeObservation(identity(), "complete-" + UUID.randomUUID(),
                started.observationId(), new EmergencyObservationCompleteRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        2L, EmergencyObservationCompleteRequestWire.DispositionValue.DISCHARGED)))
                .isInstanceOf(EmergencyObservationException.class)
                .satisfies(e -> assertThat(((EmergencyObservationException) e).code())
                        .isEqualTo("EMERGENCY_OBSERVATION_STATE_INVALID"));
    }

    @Test
    void givenObservationIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        EmergencyObservationWire started = start(context);
        assertThatThrownBy(() -> jdbc.sql("""
                update emergency_observation set observation_started_at = now()
                where tenant_id = cast(:tenant as uuid) and observation_id = :observation
                """).param("tenant", TENANT).param("observation", started.observationId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
