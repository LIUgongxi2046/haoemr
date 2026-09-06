package org.haoemr.obstetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricDeliveryRecordCreateRequestWire;
import org.haoemr.contracts.ObstetricDeliveryRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ObstetricDeliveryApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ObstetricDeliveryService deliveries;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedPatient(String sexCode) {
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成产妇', :sex, :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId).param("sex", sexCode)
                .param("birth", LocalDate.of(1990, 8, 8)).update();
        return patientId;
    }

    private ObstetricDeliveryRecordWire record(
            UUID motherId, UUID neonateId, int bloodLossMl, boolean hemorrhage) {
        return deliveries.record(identity(), "delivery-" + UUID.randomUUID(),
                new ObstetricDeliveryRecordCreateRequestWire(organization, facility, motherId, neonateId,
                        ObstetricDeliveryRecordCreateRequestWire.DeliveryMethodValue.VAGINAL,
                        Instant.now(), bloodLossMl, 240, hemorrhage));
    }

    @Test
    void givenFemalePatient_whenRecording_thenRecorded() {
        UUID motherId = seedPatient("F");
        ObstetricDeliveryRecordWire recorded = record(motherId, null, 300, false);
        assertThat(recorded.bloodLossMl()).isEqualTo(300);
        assertThat(recorded.postpartumHemorrhage()).isFalse();
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<ObstetricDeliveryRecordWire> listed = deliveries.listRecords(identity(), motherId);
        assertThat(listed).extracting(ObstetricDeliveryRecordWire::deliveryRecordId)
                .contains(recorded.deliveryRecordId());
    }

    @Test
    void givenPostpartumHemorrhageWithLowBloodLoss_whenRecording_thenRejected() {
        UUID motherId = seedPatient("F");
        assertThatThrownBy(() -> record(motherId, null, 200, true))
                .isInstanceOf(ObstetricDeliveryException.class)
                .satisfies(e -> assertThat(((ObstetricDeliveryException) e).code())
                        .isEqualTo("POSTPARTUM_HEMORRHAGE_BLOOD_LOSS"));
    }

    @Test
    void givenPostpartumHemorrhageWithHighBloodLoss_whenRecording_thenAccepted() {
        UUID motherId = seedPatient("F");
        ObstetricDeliveryRecordWire recorded = record(motherId, null, 600, true);
        assertThat(recorded.postpartumHemorrhage()).isTrue();
        assertThat(recorded.bloodLossMl()).isEqualTo(600);
    }

    @Test
    void givenSameMotherNeonate_whenRecording_thenRejected() {
        UUID motherId = seedPatient("F");
        assertThatThrownBy(() -> record(motherId, motherId, 300, false))
                .isInstanceOf(ObstetricDeliveryException.class)
                .satisfies(e -> assertThat(((ObstetricDeliveryException) e).code())
                        .isEqualTo("MOTHER_NEONATE_SAME_PATIENT"));
    }

    @Test
    void givenMalePatient_whenRecording_thenRejected() {
        UUID maleId = seedPatient("M");
        assertThatThrownBy(() -> record(maleId, null, 300, false))
                .isInstanceOf(ObstetricDeliveryException.class)
                .satisfies(e -> assertThat(((ObstetricDeliveryException) e).code())
                        .isEqualTo("MOTHER_NOT_FEMALE"));
    }

    @Test
    void givenNegativeBloodLoss_whenRecording_thenRejected() {
        UUID motherId = seedPatient("F");
        assertThatThrownBy(() -> record(motherId, null, -1, false))
                .isInstanceOf(ObstetricDeliveryException.class)
                .satisfies(e -> assertThat(((ObstetricDeliveryException) e).code())
                        .isEqualTo("OBSTETRIC_DELIVERY_REQUEST_INVALID"));
    }

    @Test
    void givenRecord_whenTampered_thenDatabaseRejectsMutation() {
        UUID motherId = seedPatient("F");
        ObstetricDeliveryRecordWire recorded = record(motherId, null, 300, false);
        assertThatThrownBy(() -> jdbc.sql("""
                update obstetric_delivery_record set blood_loss_ml = 999
                where tenant_id = cast(:tenant as uuid) and delivery_record_id = :record
                """).param("tenant", TENANT).param("record", recorded.deliveryRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
