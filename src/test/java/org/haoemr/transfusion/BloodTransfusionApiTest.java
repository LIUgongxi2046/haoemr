package org.haoemr.transfusion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.BloodTransfusionReactionRequestWire;
import org.haoemr.contracts.BloodTransfusionRecordRequestWire;
import org.haoemr.contracts.BloodTransfusionWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class BloodTransfusionApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String VERIFIER = "018f0000-0000-7000-8000-00000000aa06";

    @Autowired
    private BloodTransfusionService transfusions;

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
                values (cast(:tenant as uuid), :patient, '合成输血患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1972, 2, 2)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-BT', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    @Test
    void givenMatchingBlood_whenRecordingTransfusion_thenRecordedWithDoubleCheckAndReaction() {
        Context context = seedContext();
        BloodTransfusionWire recorded = transfusions.recordTransfusion(identity(), "bt-" + UUID.randomUUID(),
                new BloodTransfusionRecordRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), BloodTransfusionRecordRequestWire.BloodProductValue.RED_CELLS,
                        BloodTransfusionRecordRequestWire.BloodTypeValue.O_POS, "U-2026-0001", 250,
                        Instant.now(), UUID.fromString(VERIFIER), "床旁双人核验通过"));
        assertThat(recorded.bloodType()).isEqualTo(BloodTransfusionWire.BloodTypeValue.O_POS);
        assertThat(recorded.administeredBy()).isEqualTo(UUID.fromString(USER));
        assertThat(recorded.verifiedBy()).isEqualTo(UUID.fromString(VERIFIER));

        BloodTransfusionWire reaction = transfusions.recordReaction(identity(), "reaction-" + UUID.randomUUID(),
                recorded.transfusionId(), new BloodTransfusionReactionRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        recorded.rowVersion(), BloodTransfusionReactionRequestWire.ReactionTypeValue.ALLERGIC));
        assertThat(reaction.reactionType()).isEqualTo(BloodTransfusionWire.ReactionTypeValue.ALLERGIC);
        assertThat(reaction.reactionNotedAt()).isNotNull();
    }

    @Test
    void givenSelfVerification_whenRecordingTransfusion_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> transfusions.recordTransfusion(identity(), "bt-" + UUID.randomUUID(),
                new BloodTransfusionRecordRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), BloodTransfusionRecordRequestWire.BloodProductValue.PLASMA,
                        BloodTransfusionRecordRequestWire.BloodTypeValue.A_POS, "U-2026-0002", 200,
                        Instant.now(), UUID.fromString(USER), null)))
                .isInstanceOf(BloodTransfusionException.class)
                .satisfies(e -> assertThat(((BloodTransfusionException) e).code())
                        .isEqualTo("BLOOD_TRANSFUSION_REQUEST_INVALID"));
    }

    @Test
    void givenTransfusionRecord_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        BloodTransfusionWire recorded = transfusions.recordTransfusion(identity(), "bt-" + UUID.randomUUID(),
                new BloodTransfusionRecordRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), BloodTransfusionRecordRequestWire.BloodProductValue.RED_CELLS,
                        BloodTransfusionRecordRequestWire.BloodTypeValue.B_POS, "U-2026-0003", 300,
                        Instant.now(), UUID.fromString(VERIFIER), null));
        assertThatThrownBy(() -> jdbc.sql("""
                update blood_transfusion set volume_ml = 999
                where tenant_id = cast(:tenant as uuid) and transfusion_id = :transfusion
                """).param("tenant", TENANT).param("transfusion", recorded.transfusionId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
