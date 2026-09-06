package org.haoemr.nursing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NursingBedsideNoteCreateRequestWire;
import org.haoemr.contracts.NursingBedsideNoteWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NursingBedsideNoteApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private NursingService nursing;

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
                values (cast(:tenant as uuid), :patient, '合成床旁记录患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1968, 3, 3)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-BEDSIDE', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private NursingBedsideNoteWire sync(Context context, Instant recordedAt, Instant syncedAt, String content) {
        return nursing.syncBedsideNote(identity(), "bedside-" + UUID.randomUUID(),
                new NursingBedsideNoteCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), NursingBedsideNoteCreateRequestWire.NoteTypeValue.VITAL_SIGNS,
                        recordedAt, syncedAt, "bedside-pad-01", content));
    }

    @Test
    void givenOfflineNote_whenSyncingAndListing_thenRecordedWithDualTimestamp() {
        Context context = seedContext();
        Instant recordedAt = Instant.now().minus(35, ChronoUnit.MINUTES);
        Instant syncedAt = Instant.now();
        NursingBedsideNoteWire created = sync(context, recordedAt, syncedAt, "体温 36.8℃，脉搏 72 次/分");
        assertThat(created.recordedAt()).isEqualTo(recordedAt);
        assertThat(created.syncedAt()).isEqualTo(syncedAt);
        assertThat(created.noteType()).isEqualTo(NursingBedsideNoteWire.NoteTypeValue.VITAL_SIGNS);

        List<NursingBedsideNoteWire> listed = nursing.listBedsideNotes(identity(), context.patientId());
        assertThat(listed).extracting(NursingBedsideNoteWire::noteId).contains(created.noteId());
    }

    @Test
    void givenRecordedAfterSynced_whenSyncing_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> sync(context, Instant.now(), Instant.now().minus(1, ChronoUnit.MINUTES), "逆行时间记录"))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code())
                        .isEqualTo("NURSING_BEDSIDE_NOTE_TIME_ORDER_INVALID"));
    }

    @Test
    void givenRecordedAfterSynced_whenBypassingService_thenDatabaseRejects() {
        Context context = seedContext();
        assertThatThrownBy(() -> jdbc.sql("""
                insert into nursing_bedside_note(
                  tenant_id, note_id, patient_id, encounter_id, facility_id,
                  note_type, recorded_at, synced_at, device_id, content)
                values (cast(:tenant as uuid), :note, :patient, :encounter, cast(:facility as uuid),
                  'NURSING_NOTE', now(), now() - interval '5 minutes', 'bedside-pad-01', '逆行双时间')
                """).param("tenant", TENANT).param("note", UUID.randomUUID())
                .param("patient", context.patientId()).param("encounter", context.encounterId())
                .param("facility", FACILITY).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenNoteIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        NursingBedsideNoteWire created = sync(context, Instant.now().minus(5, ChronoUnit.MINUTES), Instant.now(), "翻身已执行");
        assertThatThrownBy(() -> jdbc.sql("""
                update nursing_bedside_note set content = '篡改'
                where tenant_id = cast(:tenant as uuid) and note_id = :note
                """).param("tenant", TENANT).param("note", created.noteId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenSameIdempotencyKey_whenSyncingTwice_thenRejected() {
        Context context = seedContext();
        String key = "bedside-replay-" + UUID.randomUUID();
        nursing.syncBedsideNote(identity(), key,
                new NursingBedsideNoteCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), NursingBedsideNoteCreateRequestWire.NoteTypeValue.NURSING_NOTE,
                        Instant.now().minus(3, ChronoUnit.MINUTES), Instant.now(), "bedside-pad-01", "已协助翻身"));
        assertThatThrownBy(() -> nursing.syncBedsideNote(identity(), key,
                new NursingBedsideNoteCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), NursingBedsideNoteCreateRequestWire.NoteTypeValue.NURSING_NOTE,
                        Instant.now().minus(3, ChronoUnit.MINUTES), Instant.now(), "bedside-pad-01", "已协助翻身")))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code()).isEqualTo("IDEMPOTENCY_REPLAY"));
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
