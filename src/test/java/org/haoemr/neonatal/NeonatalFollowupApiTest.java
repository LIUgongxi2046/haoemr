package org.haoemr.neonatal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalFollowupRecordCreateRequestWire;
import org.haoemr.contracts.NeonatalFollowupRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NeonatalFollowupApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private NeonatalFollowupService followups;

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
                values (cast(:tenant as uuid), :patient, '合成neonatal随访患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(2019, 6, 6)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-FUP', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private NeonatalFollowupRecordWire record(Context context, boolean attended, String noShowReason) {
        return followups.record(identity(), "followup-" + UUID.randomUUID(),
                new NeonatalFollowupRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), "生长发育复查", Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS),
                        attended, noShowReason, "复查体重", Instant.now()));
    }

    @Test
    void givenAttendedFollowup_whenRecording_thenRecorded() {
        Context context = seedContext();
        NeonatalFollowupRecordWire recorded = record(context, true, null);
        assertThat(recorded.attended()).isTrue();
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<NeonatalFollowupRecordWire> listed = followups.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(NeonatalFollowupRecordWire::followupId).contains(recorded.followupId());
    }

    @Test
    void givenNoShowWithReason_whenRecording_thenAccepted() {
        Context context = seedContext();
        NeonatalFollowupRecordWire recorded = record(context, false, "家长未带新生儿复诊");
        assertThat(recorded.attended()).isFalse();
        assertThat(recorded.noShowReason()).isEqualTo("家长未带新生儿复诊");
    }

    @Test
    void givenNoShowWithoutReason_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, false, null))
                .isInstanceOf(NeonatalFollowupException.class)
                .satisfies(e -> assertThat(((NeonatalFollowupException) e).code())
                        .isEqualTo("NEONATAL_FOLLOWUP_NO_SHOW_REASON_REQUIRED"));
    }

    @Test
    void givenFollowup_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        NeonatalFollowupRecordWire recorded = record(context, true, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update neonatal_followup_record set attended = false
                where tenant_id = cast(:tenant as uuid) and followup_id = :followup
                """).param("tenant", TENANT).param("followup", recorded.followupId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
