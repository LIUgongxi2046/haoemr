package org.haoemr.infection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.InfectionMonitoringEventReportRequestWire;
import org.haoemr.contracts.InfectionMonitoringEventResolveRequestWire;
import org.haoemr.contracts.InfectionMonitoringEventWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class InfectionEventApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private InfectionEventService events;

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
                values (cast(:tenant as uuid), :patient, '合成院感患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1955, 5, 5)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-INFECTION', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private InfectionMonitoringEventWire report(Context context, String type) {
        Instant detectedAt = Instant.now().minusSeconds(60);
        return events.report(identity(), "inf-" + UUID.randomUUID(),
                new InfectionMonitoringEventReportRequestWire(organization, facility, context.patientId(),
                        context.encounterId(),
                        InfectionMonitoringEventReportRequestWire.InfectionTypeValue.valueOf(type),
                        "ORGANISM-SYN", InfectionMonitoringEventReportRequestWire.EventCategoryValue.HAI_CASE,
                        detectedAt.minusSeconds(3600), detectedAt, 24, false,
                        "HOSPITAL-INFECTION-POLICY-V1", Instant.now()));
    }

    private InfectionMonitoringEventWire resolve(
            Context context, InfectionMonitoringEventWire event, String resolution, String conclusion) {
        return events.resolve(identity(), "inf-r-" + UUID.randomUUID(), event.infectionEventId(),
                new InfectionMonitoringEventResolveRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), event.rowVersion(),
                        InfectionMonitoringEventResolveRequestWire.ResolutionValue.valueOf(resolution), conclusion));
    }

    @Test
    void givenReportedClue_whenConfirming_thenLifecycleRecorded() {
        Context context = seedContext();
        InfectionMonitoringEventWire reported = report(context, "SURGICAL_SITE");
        assertThat(reported.status()).isEqualTo(InfectionMonitoringEventWire.StatusValue.REPORTED);
        assertThat(reported.organismCode()).isEqualTo("ORGANISM-SYN");

        InfectionMonitoringEventWire confirmed = resolve(context, reported, "CONFIRM", "确认为切口感染并启动干预");
        assertThat(confirmed.status()).isEqualTo(InfectionMonitoringEventWire.StatusValue.CONFIRMED);
        assertThat(confirmed.conclusion()).isEqualTo("确认为切口感染并启动干预");
        assertThat(confirmed.resolvedAt()).isNotNull();

        List<InfectionMonitoringEventWire> listed = events.listEvents(identity(), context.patientId());
        assertThat(listed).extracting(InfectionMonitoringEventWire::infectionEventId)
                .contains(reported.infectionEventId());
    }

    @Test
    void givenReportedClue_whenRefuting_thenRefuted() {
        Context context = seedContext();
        InfectionMonitoringEventWire reported = report(context, "URINARY_TRACT");
        InfectionMonitoringEventWire refuted = resolve(context, reported, "REFUTE", "排除导管相关尿路感染");
        assertThat(refuted.status()).isEqualTo(InfectionMonitoringEventWire.StatusValue.REFUTED);
    }

    @Test
    void givenResolvedEvent_whenResolvingAgain_thenRejected() {
        Context context = seedContext();
        InfectionMonitoringEventWire reported = report(context, "PNEUMONIA");
        InfectionMonitoringEventWire confirmed = resolve(context, reported, "CONFIRM", "确认为院内获得性肺炎");
        assertThatThrownBy(() -> resolve(context, confirmed, "REFUTE", "二次处置"))
                .isInstanceOf(InfectionEventException.class)
                .satisfies(e -> assertThat(((InfectionEventException) e).code())
                        .isEqualTo("INFECTION_EVENT_STATE_INVALID"));
    }

    @Test
    void givenEventIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        InfectionMonitoringEventWire reported = report(context, "BLOODSTREAM");
        assertThatThrownBy(() -> jdbc.sql("""
                update infection_monitoring_event set infection_type = 'OTHER'
                where tenant_id = cast(:tenant as uuid) and infection_event_id = :event
                """).param("tenant", TENANT).param("event", reported.infectionEventId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
