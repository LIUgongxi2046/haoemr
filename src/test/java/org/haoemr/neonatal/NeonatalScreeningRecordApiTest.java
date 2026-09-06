package org.haoemr.neonatal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalScreeningRecordCreateRequestWire;
import org.haoemr.contracts.NeonatalScreeningRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NeonatalScreeningRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private NeonatalScreeningRecordService screenings;

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
        UUID motherId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成筛查新生儿', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(2026, 1, 2)).update();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成筛查母亲', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", motherId)
                .param("birth", LocalDate.of(1990, 2, 2)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-NSCR', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, motherId, encounterId);
    }

    private NeonatalScreeningRecordWire record(
            Context context, NeonatalScreeningRecordCreateRequestWire.ScreeningResultValue result, String referredTo) {
        return screenings.record(identity(), "screening-" + UUID.randomUUID(),
                new NeonatalScreeningRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.motherId(), context.encounterId(),
                        NeonatalScreeningRecordCreateRequestWire.ScreeningTypeValue.HEARING,
                        result, referredTo, Instant.now()));
    }

    @Test
    void givenPassScreening_whenRecording_thenRecorded() {
        Context context = seedContext();
        NeonatalScreeningRecordWire recorded = record(context,
                NeonatalScreeningRecordCreateRequestWire.ScreeningResultValue.PASS, null);
        assertThat(recorded.screeningResult()).isEqualTo(NeonatalScreeningRecordWire.ScreeningResultValue.PASS);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<NeonatalScreeningRecordWire> listed = screenings.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(NeonatalScreeningRecordWire::screeningId).contains(recorded.screeningId());
    }

    @Test
    void givenReferScreeningWithTarget_whenRecording_thenAccepted() {
        Context context = seedContext();
        NeonatalScreeningRecordWire recorded = record(context,
                NeonatalScreeningRecordCreateRequestWire.ScreeningResultValue.REFER, "耳鼻喉科");
        assertThat(recorded.referredTo()).isEqualTo("耳鼻喉科");
    }

    @Test
    void givenReferScreeningWithoutTarget_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context,
                NeonatalScreeningRecordCreateRequestWire.ScreeningResultValue.REFER, null))
                .isInstanceOf(NeonatalScreeningRecordException.class)
                .satisfies(e -> assertThat(((NeonatalScreeningRecordException) e).code())
                        .isEqualTo("NEONATAL_SCREENING_REFER_REQUIRED"));
    }

    @Test
    void givenSameMotherNeonate_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> screenings.record(identity(), "screening-" + UUID.randomUUID(),
                new NeonatalScreeningRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.patientId(), context.encounterId(),
                        NeonatalScreeningRecordCreateRequestWire.ScreeningTypeValue.HEARING,
                        NeonatalScreeningRecordCreateRequestWire.ScreeningResultValue.PASS, null, Instant.now())))
                .isInstanceOf(NeonatalScreeningRecordException.class)
                .satisfies(e -> assertThat(((NeonatalScreeningRecordException) e).code())
                        .isEqualTo("MOTHER_NEONATE_SAME_PATIENT"));
    }

    @Test
    void givenScreening_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        NeonatalScreeningRecordWire recorded = record(context,
                NeonatalScreeningRecordCreateRequestWire.ScreeningResultValue.PASS, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update neonatal_screening_record set screening_result = 'REFER'
                where tenant_id = cast(:tenant as uuid) and screening_id = :screening
                """).param("tenant", TENANT).param("screening", recorded.screeningId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID motherId, UUID encounterId) {}
}
