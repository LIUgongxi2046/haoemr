package org.haoemr.dictation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DictationNoteCreateRequestWire;
import org.haoemr.contracts.DictationNoteTransitionRequestWire;
import org.haoemr.contracts.DictationNoteWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DictationNoteApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DictationNoteService notes;

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
                values (cast(:tenant as uuid), :patient, '合成听写患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1969, 9, 9)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-DICTATION', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private DictationNoteWire create(Context context) {
        return notes.create(identity(), "dict-" + UUID.randomUUID(),
                new DictationNoteCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), "患者主诉胸闷，既往高血压病史，建议进一步检查"));
    }

    private DictationNoteWire transition(Context context, DictationNoteWire note, String transition) {
        return notes.transition(identity(), "dict-t-" + UUID.randomUUID(), note.dictationNoteId(),
                new DictationNoteTransitionRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), note.rowVersion(),
                        DictationNoteTransitionRequestWire.TransitionValue.valueOf(transition)));
    }

    @Test
    void givenNote_whenReviewingAndSigning_thenLifecycleRecorded() {
        Context context = seedContext();
        DictationNoteWire draft = create(context);
        assertThat(draft.status()).isEqualTo(DictationNoteWire.StatusValue.DRAFT);

        DictationNoteWire reviewed = transition(context, draft, "REVIEW");
        assertThat(reviewed.status()).isEqualTo(DictationNoteWire.StatusValue.REVIEWED);
        assertThat(reviewed.reviewedAt()).isNotNull();

        DictationNoteWire signed = transition(context, reviewed, "SIGN");
        assertThat(signed.status()).isEqualTo(DictationNoteWire.StatusValue.SIGNED);
        assertThat(signed.signedAt()).isNotNull();

        List<DictationNoteWire> listed = notes.listNotes(identity(), context.patientId());
        assertThat(listed).extracting(DictationNoteWire::dictationNoteId).contains(draft.dictationNoteId());
    }

    @Test
    void givenInvalidTransition_whenSigningDraft_thenRejected() {
        Context context = seedContext();
        DictationNoteWire draft = create(context);
        assertThatThrownBy(() -> transition(context, draft, "SIGN"))
                .isInstanceOf(DictationNoteException.class)
                .satisfies(e -> assertThat(((DictationNoteException) e).code())
                        .isEqualTo("DICTATION_NOTE_STATE_INVALID"));
    }

    @Test
    void givenNoteTranscript_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        DictationNoteWire draft = create(context);
        assertThatThrownBy(() -> jdbc.sql("""
                update dictation_note set transcript = '篡改'
                where tenant_id = cast(:tenant as uuid) and dictation_note_id = :note
                """).param("tenant", TENANT).param("note", draft.dictationNoteId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
