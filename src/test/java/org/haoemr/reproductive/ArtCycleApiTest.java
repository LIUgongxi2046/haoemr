package org.haoemr.reproductive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ArtCycleRecordCreateRequestWire;
import org.haoemr.contracts.ArtCycleRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ArtCycleApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ArtCycleService cycles;

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
        UUID partnerId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成生殖患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1990, 1, 1)).update();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :partner, '合成配偶', 'M', :birth2, 'ACTIVE')
                """).param("tenant", TENANT).param("partner", partnerId)
                .param("birth2", LocalDate.of(1988, 6, 6)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-ART', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, partnerId, encounterId);
    }

    @Test
    void givenReproductivePatient_whenCreatingAndListingCycle_thenLifecycleRecorded() {
        Context context = seedContext();
        ArtCycleRecordWire created = cycles.createCycle(identity(), "art-" + UUID.randomUUID(),
                new ArtCycleRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.partnerId(), context.encounterId(),
                        ArtCycleRecordCreateRequestWire.CycleTypeValue.IVF, 1, LocalDate.now(), null));
        assertThat(created.cycleType()).isEqualTo(ArtCycleRecordWire.CycleTypeValue.IVF);
        assertThat(created.ethicsConsentDate()).isEqualTo(LocalDate.now());
        assertThat(created.partnerPatientId()).isEqualTo(context.partnerId());

        List<ArtCycleRecordWire> listed = cycles.listCycles(identity(), context.patientId());
        assertThat(listed).extracting(ArtCycleRecordWire::cycleId).contains(created.cycleId());
    }

    @Test
    void givenFutureConsentDate_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> cycles.createCycle(identity(), "art-" + UUID.randomUUID(),
                new ArtCycleRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.partnerId(), context.encounterId(),
                        ArtCycleRecordCreateRequestWire.CycleTypeValue.IVF, 1, LocalDate.now().plusDays(1), null)))
                .isInstanceOf(ArtCycleException.class)
                .satisfies(e -> assertThat(((ArtCycleException) e).code()).isEqualTo("ART_CYCLE_REQUEST_INVALID"));
    }

    @Test
    void givenSelfAsPartner_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> cycles.createCycle(identity(), "art-" + UUID.randomUUID(),
                new ArtCycleRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.patientId(), context.encounterId(),
                        ArtCycleRecordCreateRequestWire.CycleTypeValue.IUI, 1, LocalDate.now(), null)))
                .isInstanceOf(ArtCycleException.class)
                .satisfies(e -> assertThat(((ArtCycleException) e).code()).isEqualTo("ART_CYCLE_REQUEST_INVALID"));
    }

    @Test
    void givenCycleIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ArtCycleRecordWire created = cycles.createCycle(identity(), "art-" + UUID.randomUUID(),
                new ArtCycleRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.partnerId(), context.encounterId(),
                        ArtCycleRecordCreateRequestWire.CycleTypeValue.ICSI, 1, LocalDate.now(), null));
        assertThatThrownBy(() -> jdbc.sql("""
                update art_cycle_record set cycle_number = 99
                where tenant_id = cast(:tenant as uuid) and cycle_id = :cycle
                """).param("tenant", TENANT).param("cycle", created.cycleId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID partnerId, UUID encounterId) {}
}
