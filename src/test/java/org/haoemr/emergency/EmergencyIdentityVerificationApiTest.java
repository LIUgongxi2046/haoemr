package org.haoemr.emergency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyIdentityVerificationCreateRequestWire;
import org.haoemr.contracts.EmergencyIdentityVerificationWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class EmergencyIdentityVerificationApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");

    @Autowired private EmergencyIdentityVerificationService verifications;
    @Autowired private JdbcClient jdbc;

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(TENANT, USER, List.of(ROLE));
    }

    private Context seedEmergencyPatient(String identifier, String encounterStatus) {
        identifier = identifier + "-" + UUID.randomUUID().toString().substring(0, 8);
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (:tenant, :patient, '急诊腕带核验患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1982, 5, 12)).update();
        jdbc.sql("""
                insert into patient_identifier(
                  tenant_id, patient_identifier_id, patient_id, assigning_authority,
                  identifier_type, identifier_hash, masked_value, source_system)
                values (:tenant, :identifier_id, :patient, 'HAOEMR-ED', 'WRISTBAND',
                  decode(:identifier_hash, 'hex'), :masked, 'HAOEMR')
                """).param("tenant", TENANT).param("identifier_id", UUID.randomUUID())
                .param("patient", patientId).param("identifier_hash", sha256(identifier))
                .param("masked", "******" + identifier.substring(identifier.length() - 2)).update();
        jdbc.sql("""
                insert into encounter(
                  tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, ended_at, source_system, source_key)
                values (:tenant, :encounter, :patient, :organization, :facility,
                  'EMERGENCY', :status, now(), case when :status = 'FINISHED' then now() else null end,
                  'TEST', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("status", encounterStatus).param("source_key", "ed-" + encounterId).update();
        return new Context(patientId, encounterId, identifier);
    }

    private EmergencyIdentityVerificationWire verify(Context context, String identifier) {
        return verifications.verify(identity(), "identity-" + UUID.randomUUID(),
                new EmergencyIdentityVerificationCreateRequestWire(
                        ORGANIZATION, FACILITY, context.patientId(), context.encounterId(), identifier,
                        EmergencyIdentityVerificationCreateRequestWire.VerificationPurposeValue.MEDICATION,
                        Instant.now()));
    }

    @Test
    void matchingWristbandCreatesImmutableMatchedEvidenceWithoutStoringRawIdentifier() {
        Context context = seedEmergencyPatient("ED-WB-20260831-001", "IN_PROGRESS");

        EmergencyIdentityVerificationWire result = verify(context, context.identifier());

        assertThat(result.outcome()).isEqualTo(EmergencyIdentityVerificationWire.OutcomeValue.MATCHED);
        assertThat(result.identifierType()).isEqualTo("WRISTBAND");
        assertThat(result.maskedIdentifier())
                .endsWith(context.identifier().substring(context.identifier().length() - 2))
                .doesNotContain(context.identifier());
        assertThat(verifications.list(identity(), context.patientId()))
                .extracting(EmergencyIdentityVerificationWire::verificationId)
                .contains(result.verificationId());
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = :tenant
                  and resource_type = 'EMERGENCY_IDENTITY_VERIFICATION' and resource_id = :resource
                """).param("tenant", TENANT).param("resource", result.verificationId())
                .query(Long.class).single()).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.sql("""
                update emergency_identity_verification set outcome = 'NOT_FOUND'
                where tenant_id = :tenant and verification_id = :verification
                """).param("tenant", TENANT).param("verification", result.verificationId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void wristbandBelongingToAnotherPatientIsRecordedAsMismatchWithoutLeakingIdentity() {
        Context target = seedEmergencyPatient("ED-WB-20260831-010", "IN_PROGRESS");
        Context other = seedEmergencyPatient("ED-WB-20260831-011", "IN_PROGRESS");

        EmergencyIdentityVerificationWire result = verify(target, other.identifier());

        assertThat(result.outcome()).isEqualTo(EmergencyIdentityVerificationWire.OutcomeValue.MISMATCHED);
        assertThat(result.identifierType()).isNull();
        assertThat(result.patientId()).isEqualTo(target.patientId());
    }

    @Test
    void unknownWristbandIsRecordedAsNotFound() {
        Context target = seedEmergencyPatient("ED-WB-20260831-020", "IN_PROGRESS");
        assertThat(verify(target, "ED-WB-UNKNOWN-999").outcome())
                .isEqualTo(EmergencyIdentityVerificationWire.OutcomeValue.NOT_FOUND);
    }

    @Test
    void finishedEmergencyEncounterCannotCreateBedsideVerification() {
        Context finished = seedEmergencyPatient("ED-WB-20260831-030", "FINISHED");
        assertThatThrownBy(() -> verify(finished, finished.identifier()))
                .isInstanceOf(EmergencyIdentityVerificationException.class)
                .satisfies(error -> assertThat(((EmergencyIdentityVerificationException) error).code())
                        .isEqualTo("CONTEXT_NOT_PERMITTED"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Context(UUID patientId, UUID encounterId, String identifier) {}
}
