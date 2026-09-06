package org.haoemr.dental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DentalTreatmentRecordCreateRequestWire;
import org.haoemr.contracts.DentalTreatmentRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DentalTreatmentApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DentalTreatmentService treatments;

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
                values (cast(:tenant as uuid), :patient, '合成口腔患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1992, 10, 10)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-DENTAL', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private DentalTreatmentRecordWire record(
            Context context, String toothNotation,
            DentalTreatmentRecordCreateRequestWire.TreatmentTypeValue type, String batch) {
        return treatments.record(identity(), "dental-" + UUID.randomUUID(),
                new DentalTreatmentRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), toothNotation, type, batch, Instant.now()));
    }

    private UUID activeDentalRecord(Context context, String toothNotation) {
        UUID recordId = UUID.randomUUID();
        jdbc.sql("""
                insert into dental_record(
                  tenant_id, dental_record_id, patient_id, encounter_id, facility_id, tooth_notation, status)
                values (cast(:tenant as uuid), :record, :patient, :encounter, cast(:facility as uuid), :tooth, 'ACTIVE')
                """).param("tenant", TENANT).param("record", recordId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("facility", FACILITY).param("tooth", toothNotation).update();
        return recordId;
    }

    @Test
    void givenRestorativeTreatmentWithBatch_whenRecording_thenRecorded() {
        Context context = seedContext();
        UUID dentalRecordId = activeDentalRecord(context, "11");
        DentalTreatmentRecordWire recorded = record(context, "11",
                DentalTreatmentRecordCreateRequestWire.TreatmentTypeValue.FILLING, "BATCH-2026-001");
        assertThat(recorded.toothNotation()).isEqualTo("11");
        assertThat(recorded.materialBatch()).isEqualTo("BATCH-2026-001");
        assertThat(recorded.performedBy()).isEqualTo(UUID.fromString(USER));
        assertThat(jdbc.sql("""
                select status from dental_record where tenant_id = cast(:tenant as uuid) and dental_record_id = :record
                """).param("tenant", TENANT).param("record", dentalRecordId).query(String.class).single())
                .isEqualTo("COMPLETED");

        List<DentalTreatmentRecordWire> listed = treatments.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(DentalTreatmentRecordWire::dentalTreatmentRecordId)
                .contains(recorded.dentalTreatmentRecordId());
    }

    @Test
    void givenRestorativeTreatmentWithoutBatch_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, "11",
                DentalTreatmentRecordCreateRequestWire.TreatmentTypeValue.FILLING, null))
                .isInstanceOf(DentalTreatmentException.class)
                .satisfies(e -> assertThat(((DentalTreatmentException) e).code())
                        .isEqualTo("DENTAL_TREATMENT_MATERIAL_BATCH_REQUIRED"));
    }

    @Test
    void givenNonRestorativeTreatmentWithoutBatch_whenRecording_thenAccepted() {
        Context context = seedContext();
        DentalTreatmentRecordWire recorded = record(context, "36",
                DentalTreatmentRecordCreateRequestWire.TreatmentTypeValue.CLEANING, null);
        assertThat(recorded.materialBatch()).isNull();
    }

    @Test
    void givenInvalidToothNotation_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, "99",
                DentalTreatmentRecordCreateRequestWire.TreatmentTypeValue.CLEANING, null))
                .isInstanceOf(DentalTreatmentException.class)
                .satisfies(e -> assertThat(((DentalTreatmentException) e).code())
                        .isEqualTo("DENTAL_TREATMENT_REQUEST_INVALID"));
    }

    @Test
    void givenTreatment_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        DentalTreatmentRecordWire recorded = record(context, "11",
                DentalTreatmentRecordCreateRequestWire.TreatmentTypeValue.FILLING, "BATCH-2026-001");
        assertThatThrownBy(() -> jdbc.sql("""
                update dental_treatment_record set material_batch = '篡改批次'
                where tenant_id = cast(:tenant as uuid) and dental_treatment_record_id = :record
                """).param("tenant", TENANT).param("record", recorded.dentalTreatmentRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
