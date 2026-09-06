package org.haoemr.emergency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyResuscitationCompleteRequestWire;
import org.haoemr.contracts.EmergencyResuscitationStartRequestWire;
import org.haoemr.contracts.EmergencyResuscitationWire;
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
final class EmergencyResuscitationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private EmergencyResuscitationService resuscitations;

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
                values (cast(:tenant as uuid), :patient, '合成抢救患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1965, 4, 4)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'EMERGENCY', 'IN_PROGRESS', now(), 'SYNTHETIC-RESUSCITATION', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private EmergencyResuscitationWire start(Context context) {
        return resuscitations.start(identity(), "resus-" + UUID.randomUUID(),
                new EmergencyResuscitationStartRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), Instant.now()));
    }

    private EmergencyResuscitationWire complete(
            Context context, EmergencyResuscitationWire resus, long version, String outcome) {
        return resuscitations.complete(identity(), "complete-" + UUID.randomUUID(), resus.resuscitationId(),
                new EmergencyResuscitationCompleteRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), version,
                        EmergencyResuscitationCompleteRequestWire.OutcomeValue.valueOf(outcome)));
    }

    @Test
    void givenResuscitation_whenStartingAndCompleting_thenLifecycleRecorded() {
        Context context = seedContext();
        EmergencyResuscitationWire started = start(context);
        assertThat(started.status()).isEqualTo(EmergencyResuscitationWire.StatusValue.IN_PROGRESS);
        assertThat(started.outcome()).isEqualTo(EmergencyResuscitationWire.OutcomeValue.PENDING);

        EmergencyResuscitationWire completed = complete(context, started, started.rowVersion(), "ROSC");
        assertThat(completed.status()).isEqualTo(EmergencyResuscitationWire.StatusValue.COMPLETED);
        assertThat(completed.outcome()).isEqualTo(EmergencyResuscitationWire.OutcomeValue.ROSC);
        assertThat(completed.endedAt()).isNotNull();

        List<EmergencyResuscitationWire> listed = resuscitations.listResuscitations(identity(), context.patientId());
        assertThat(listed).extracting(EmergencyResuscitationWire::resuscitationId)
                .contains(started.resuscitationId());
    }

    @Test
    void givenStaleVersion_whenCompleting_thenRejected() {
        Context context = seedContext();
        EmergencyResuscitationWire started = start(context);
        assertThatThrownBy(() -> complete(context, started, 999L, "ROSC"))
                .isInstanceOf(EmergencyResuscitationException.class)
                .satisfies(e -> assertThat(((EmergencyResuscitationException) e).code())
                        .isEqualTo("EMERGENCY_RESUSCITATION_VERSION_CONFLICT"));
    }

    @Test
    void givenResuscitation_whenVoiding_thenItStopsBlockingCorrectedRecord() {
        Context context = seedContext();
        EmergencyResuscitationWire started = start(context);
        EmergencyResuscitationWire voided = resuscitations.voidResuscitation(identity(), "void-" + UUID.randomUUID(),
                started.resuscitationId(), new EmergencyClinicalFactVoidRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        started.rowVersion(), "误触发抢救记录"));
        assertThat(voided.voidedAt()).isNotNull();
        EmergencyResuscitationWire replacement = start(context);
        assertThat(replacement.resuscitationId()).isNotEqualTo(started.resuscitationId());
    }

    @Test
    void givenCompletedResuscitation_whenCompletingAgain_thenRejected() {
        Context context = seedContext();
        EmergencyResuscitationWire started = start(context);
        EmergencyResuscitationWire completed = complete(context, started, started.rowVersion(), "ROSC");
        assertThatThrownBy(() -> complete(context, completed, completed.rowVersion(), "DEATH"))
                .isInstanceOf(EmergencyResuscitationException.class)
                .satisfies(e -> assertThat(((EmergencyResuscitationException) e).code())
                        .isEqualTo("EMERGENCY_RESUSCITATION_STATE_INVALID"));
    }

    @Test
    void givenResuscitationIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        EmergencyResuscitationWire started = start(context);
        assertThatThrownBy(() -> jdbc.sql("""
                update emergency_resuscitation set started_at = now()
                where tenant_id = cast(:tenant as uuid) and resuscitation_id = :resus
                """).param("tenant", TENANT).param("resus", started.resuscitationId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
