package org.haoemr.dermatology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DermatologyRecordCreateRequestWire;
import org.haoemr.contracts.DermatologyRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DermatologyRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DermatologyService records;

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
                values (cast(:tenant as uuid), :patient, '合成皮肤科患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1966, 11, 11)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-DERMATOLOGY', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private DermatologyRecordCreateRequestWire command(
            Context context, String bodySite, Double bsa, Double pasi) {
        return new DermatologyRecordCreateRequestWire(organization, facility, context.patientId(),
                context.encounterId(),
                DermatologyRecordCreateRequestWire.BodySiteValue.valueOf(bodySite), bsa, pasi);
    }

    @Test
    void givenPatient_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        DermatologyRecordWire created = records.createRecord(identity(), "derm-" + UUID.randomUUID(),
                command(context, "TRUNK", 18.5, 14.0));
        assertThat(created.bodySite()).isEqualTo(DermatologyRecordWire.BodySiteValue.TRUNK);
        assertThat(created.bsaPercent()).isEqualTo(18.5);
        assertThat(created.pasiScore()).isEqualTo(14.0);
        assertThat(created.status()).isEqualTo(DermatologyRecordWire.StatusValue.ACTIVE);

        List<DermatologyRecordWire> listed = records.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(DermatologyRecordWire::dermatologyRecordId)
                .contains(created.dermatologyRecordId());
    }

    @Test
    void givenOutOfRangeBsa_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "derm-" + UUID.randomUUID(),
                command(context, "TRUNK", 120.0, null)))
                .isInstanceOf(DermatologyException.class)
                .satisfies(e -> assertThat(((DermatologyException) e).code()).isEqualTo("DERMATOLOGY_REQUEST_INVALID"));
    }

    @Test
    void givenOutOfRangePasi_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "derm-" + UUID.randomUUID(),
                command(context, "TRUNK", 20.0, 80.0)))
                .isInstanceOf(DermatologyException.class)
                .satisfies(e -> assertThat(((DermatologyException) e).code()).isEqualTo("DERMATOLOGY_REQUEST_INVALID"));
    }

    @Test
    void givenRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        DermatologyRecordWire created = records.createRecord(identity(), "derm-" + UUID.randomUUID(),
                command(context, "FACE", 5.0, null));
        assertThatThrownBy(() -> jdbc.sql("""
                update dermatology_record set body_site = 'TRUNK'
                where tenant_id = cast(:tenant as uuid) and dermatology_record_id = :record
                """).param("tenant", TENANT).param("record", created.dermatologyRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
