package org.haoemr.tcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.TcmRecordCreateRequestWire;
import org.haoemr.contracts.TcmRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class TcmRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private TcmService records;

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
                values (cast(:tenant as uuid), :patient, '合成中医患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1975, 4, 4)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-TCM', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private TcmRecordCreateRequestWire command(
            Context context, String syndrome, String principle, String formula,
            boolean toxic, String precautions) {
        return new TcmRecordCreateRequestWire(organization, facility, context.patientId(),
                context.encounterId(), syndrome, principle, formula, toxic, precautions);
    }

    @Test
    void givenPatient_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        TcmRecordWire created = records.createRecord(identity(), "tcm-" + UUID.randomUUID(),
                command(context, "肝郁气滞", "疏肝解郁", "逍遥散", false, null));
        assertThat(created.syndromePattern()).isEqualTo("肝郁气滞");
        assertThat(created.treatmentPrinciple()).isEqualTo("疏肝解郁");
        assertThat(created.formulaName()).isEqualTo("逍遥散");
        assertThat(created.containsToxicHerb()).isFalse();
        assertThat(created.status()).isEqualTo(TcmRecordWire.StatusValue.ACTIVE);

        List<TcmRecordWire> listed = records.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(TcmRecordWire::tcmRecordId).contains(created.tcmRecordId());
    }

    @Test
    void givenToxicHerbWithoutPrecautions_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "tcm-" + UUID.randomUUID(),
                command(context, "寒湿痹阻", "温经散寒", "乌头汤", true, null)))
                .isInstanceOf(TcmException.class)
                .satisfies(e -> assertThat(((TcmException) e).code()).isEqualTo("TCM_REQUEST_INVALID"));
    }

    @Test
    void givenToxicHerbWithPrecautions_whenCreating_thenAccepted() {
        Context context = seedContext();
        TcmRecordWire created = records.createRecord(identity(), "tcm-" + UUID.randomUUID(),
                command(context, "寒湿痹阻", "温经散寒", "乌头汤", true, "附子先煎 1 小时，监测心率与舌麻"));
        assertThat(created.containsToxicHerb()).isTrue();
        assertThat(created.toxicHerbPrecautions()).isEqualTo("附子先煎 1 小时，监测心率与舌麻");
    }

    @Test
    void givenRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        TcmRecordWire created = records.createRecord(identity(), "tcm-" + UUID.randomUUID(),
                command(context, "气虚", "补气", "四君子汤", false, null));
        assertThatThrownBy(() -> jdbc.sql("""
                update tcm_record set formula_name = '麻黄汤'
                where tenant_id = cast(:tenant as uuid) and tcm_record_id = :record
                """).param("tenant", TENANT).param("record", created.tcmRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
