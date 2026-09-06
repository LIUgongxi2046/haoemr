package org.haoemr.ophthalmology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.OphthalmologyPreopVerificationCreateRequestWire;
import org.haoemr.contracts.OphthalmologyPreopVerificationWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class OphthalmologyPreopVerificationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";

    @Autowired
    private OphthalmologyPreopVerificationService verifications;

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
                values (cast(:tenant as uuid), :patient, '合成眼科患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1970, 2, 2)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-OPH', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private OphthalmologyPreopVerificationWire record(Context context, UUID witnessedBy) {
        return verifications.record(identity(), "preop-" + UUID.randomUUID(),
                new OphthalmologyPreopVerificationCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), OphthalmologyPreopVerificationCreateRequestWire.SurgicalEyeValue.OD,
                        witnessedBy, Instant.now()));
    }

    @Test
    void givenVerification_whenRecording_thenRecorded() {
        Context context = seedContext();
        OphthalmologyPreopVerificationWire recorded = record(context, UUID.fromString(COLLABORATOR));
        assertThat(recorded.surgicalEye()).isEqualTo(OphthalmologyPreopVerificationWire.SurgicalEyeValue.OD);
        assertThat(recorded.verifiedBy()).isEqualTo(UUID.fromString(USER));
        assertThat(recorded.witnessedBy()).isEqualTo(UUID.fromString(COLLABORATOR));

        List<OphthalmologyPreopVerificationWire> listed = verifications.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(OphthalmologyPreopVerificationWire::verificationId)
                .contains(recorded.verificationId());
    }

    @Test
    void givenSelfWitness_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, UUID.fromString(USER)))
                .isInstanceOf(OphthalmologyPreopVerificationException.class)
                .satisfies(e -> assertThat(((OphthalmologyPreopVerificationException) e).code())
                        .isEqualTo("SELF_VERIFICATION_FORBIDDEN"));
    }

    @Test
    void givenSameProviderBypass_whenInserting_thenDatabaseRejects() {
        Context context = seedContext();
        assertThatThrownBy(() -> jdbc.sql("""
                insert into ophthalmology_preop_verification(
                  tenant_id, verification_id, patient_id, encounter_id, facility_id, surgical_eye,
                  verified_by, witnessed_by, verified_at)
                values (cast(:tenant as uuid), :verification, :patient, :encounter, cast(:facility as uuid),
                  'OD', cast(:user as uuid), cast(:user as uuid), now())
                """).param("tenant", TENANT).param("verification", UUID.randomUUID())
                .param("patient", context.patientId()).param("encounter", context.encounterId())
                .param("facility", FACILITY).param("user", USER).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenVerification_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        OphthalmologyPreopVerificationWire recorded = record(context, UUID.fromString(COLLABORATOR));
        assertThatThrownBy(() -> jdbc.sql("""
                update ophthalmology_preop_verification set surgical_eye = 'OS'
                where tenant_id = cast(:tenant as uuid) and verification_id = :verification
                """).param("tenant", TENANT).param("verification", recorded.verificationId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
