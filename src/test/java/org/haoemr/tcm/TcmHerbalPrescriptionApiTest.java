package org.haoemr.tcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.TcmHerbalPrescriptionCreateRequestWire;
import org.haoemr.contracts.TcmHerbalPrescriptionWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class TcmHerbalPrescriptionApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private TcmHerbalPrescriptionService prescriptions;

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
                .param("birth", LocalDate.of(1975, 5, 5)).update();
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

    private TcmHerbalPrescriptionWire record(Context context, boolean toxicHerb, String precautions) {
        return prescriptions.record(identity(), "tcm-" + UUID.randomUUID(),
                new TcmHerbalPrescriptionCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), "四君子汤", "人参、白术、茯苓、甘草", toxicHerb, precautions, Instant.now()));
    }

    @Test
    void givenPrescriptionWithoutToxicHerb_whenRecording_thenRecorded() {
        Context context = seedContext();
        TcmHerbalPrescriptionWire recorded = record(context, false, null);
        assertThat(recorded.containsToxicHerb()).isFalse();
        assertThat(recorded.formulaName()).isEqualTo("四君子汤");
        assertThat(recorded.prescribedBy()).isEqualTo(UUID.fromString(USER));

        List<TcmHerbalPrescriptionWire> listed = prescriptions.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(TcmHerbalPrescriptionWire::prescriptionId)
                .contains(recorded.prescriptionId());
    }

    @Test
    void givenToxicHerbWithPrecautions_whenRecording_thenAccepted() {
        Context context = seedContext();
        TcmHerbalPrescriptionWire recorded = record(context, true, "附子先煎 1 小时并控制剂量");
        assertThat(recorded.containsToxicHerb()).isTrue();
        assertThat(recorded.toxicHerbPrecautions()).isEqualTo("附子先煎 1 小时并控制剂量");
    }

    @Test
    void givenToxicHerbWithoutPrecautions_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, true, null))
                .isInstanceOf(TcmHerbalPrescriptionException.class)
                .satisfies(e -> assertThat(((TcmHerbalPrescriptionException) e).code())
                        .isEqualTo("TCM_TOXIC_HERB_PRECAUTIONS_REQUIRED"));
    }

    @Test
    void givenPrescription_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        TcmHerbalPrescriptionWire recorded = record(context, false, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update tcm_herbal_prescription set herbs = '篡改'
                where tenant_id = cast(:tenant as uuid) and prescription_id = :prescription
                """).param("tenant", TENANT).param("prescription", recorded.prescriptionId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
