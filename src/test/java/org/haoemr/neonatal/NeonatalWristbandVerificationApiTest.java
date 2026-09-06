package org.haoemr.neonatal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalWristbandVerificationCreateRequestWire;
import org.haoemr.contracts.NeonatalWristbandVerificationWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NeonatalWristbandVerificationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";

    @Autowired
    private NeonatalWristbandVerificationService verifications;

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
                values (cast(:tenant as uuid), :patient, '合成新生儿', :sex, :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId).param("sex", sexCode)
                .param("birth", LocalDate.of(2026, 1, 1)).update();
        return patientId;
    }

    private NeonatalWristbandVerificationWire record(
            UUID neonateId, UUID motherId, UUID witnessedBy) {
        return verifications.record(identity(), "wristband-" + UUID.randomUUID(),
                new NeonatalWristbandVerificationCreateRequestWire(organization, facility, neonateId, motherId,
                        "WB-" + UUID.randomUUID().toString().substring(0, 8),
                        "SP-" + UUID.randomUUID().toString().substring(0, 8), witnessedBy, Instant.now()));
    }

    @Test
    void givenVerification_whenRecording_thenRecorded() {
        UUID neonateId = seedPatient("F");
        UUID motherId = seedPatient("F");
        NeonatalWristbandVerificationWire recorded = record(neonateId, motherId, UUID.fromString(COLLABORATOR));
        assertThat(recorded.verifiedBy()).isEqualTo(UUID.fromString(USER));
        assertThat(recorded.witnessedBy()).isEqualTo(UUID.fromString(COLLABORATOR));

        List<NeonatalWristbandVerificationWire> listed = verifications.listRecords(identity(), neonateId);
        assertThat(listed).extracting(NeonatalWristbandVerificationWire::verificationId)
                .contains(recorded.verificationId());
    }

    @Test
    void givenSelfWitness_whenRecording_thenRejected() {
        UUID neonateId = seedPatient("F");
        UUID motherId = seedPatient("F");
        assertThatThrownBy(() -> record(neonateId, motherId, UUID.fromString(USER)))
                .isInstanceOf(NeonatalWristbandVerificationException.class)
                .satisfies(e -> assertThat(((NeonatalWristbandVerificationException) e).code())
                        .isEqualTo("SELF_VERIFICATION_FORBIDDEN"));
    }

    @Test
    void givenSameMotherNeonate_whenRecording_thenRejected() {
        UUID sameId = seedPatient("F");
        assertThatThrownBy(() -> record(sameId, sameId, UUID.fromString(COLLABORATOR)))
                .isInstanceOf(NeonatalWristbandVerificationException.class)
                .satisfies(e -> assertThat(((NeonatalWristbandVerificationException) e).code())
                        .isEqualTo("MOTHER_NEONATE_SAME_PATIENT"));
    }

    @Test
    void givenMaleMother_whenRecording_thenRejected() {
        UUID neonateId = seedPatient("F");
        UUID maleMotherId = seedPatient("M");
        assertThatThrownBy(() -> record(neonateId, maleMotherId, UUID.fromString(COLLABORATOR)))
                .isInstanceOf(NeonatalWristbandVerificationException.class)
                .satisfies(e -> assertThat(((NeonatalWristbandVerificationException) e).code())
                        .isEqualTo("MOTHER_NOT_FEMALE"));
    }

    @Test
    void givenVerification_whenTampered_thenDatabaseRejectsMutation() {
        UUID neonateId = seedPatient("F");
        UUID motherId = seedPatient("F");
        NeonatalWristbandVerificationWire recorded = record(neonateId, motherId, UUID.fromString(COLLABORATOR));
        assertThatThrownBy(() -> jdbc.sql("""
                update neonatal_wristband_verification set specimen_code = '篡改标本'
                where tenant_id = cast(:tenant as uuid) and verification_id = :verification
                """).param("tenant", TENANT).param("verification", recorded.verificationId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
