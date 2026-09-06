package org.haoemr.emergency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyPreadmissionLinkRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionRegisterRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionUpdateRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionVoidRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class EmergencyPreadmissionApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private EmergencyPreadmissionService preadmissions;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private EmergencyPreadmissionWire register() {
        return preadmissions.register(identity(), "pread-" + UUID.randomUUID(),
                new EmergencyPreadmissionRegisterRequestWire(organization, facility,
                        "TEMP-" + UUID.randomUUID().toString().substring(0, 8), "危重患者先救治后补登"));
    }

    private UUID seedPatient() {
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成补登患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1985, 8, 8)).update();
        return patientId;
    }

    private EmergencyPreadmissionWire link(EmergencyPreadmissionWire preadmission, UUID patientId, long version) {
        return preadmissions.link(identity(), "pread-l-" + UUID.randomUUID(), preadmission.preadmissionId(),
                new EmergencyPreadmissionLinkRequestWire(organization, facility, patientId, version));
    }

    @Test
    void givenPreadmission_whenLinkingToPatient_thenRegistered() {
        EmergencyPreadmissionWire registered = register();
        assertThat(registered.status()).isEqualTo(EmergencyPreadmissionWire.StatusValue.UNREGISTERED);

        UUID patientId = seedPatient();
        EmergencyPreadmissionWire linked = link(registered, patientId, registered.rowVersion());
        assertThat(linked.status()).isEqualTo(EmergencyPreadmissionWire.StatusValue.REGISTERED);
        assertThat(linked.registeredPatientId()).isEqualTo(patientId);
        assertThat(linked.registeredAt()).isNotNull();

        List<EmergencyPreadmissionWire> listed = preadmissions.listPreadmissions(identity(), facility);
        assertThat(listed).extracting(EmergencyPreadmissionWire::preadmissionId).contains(registered.preadmissionId());
    }

    @Test
    void givenStaleVersion_whenLinking_thenRejected() {
        EmergencyPreadmissionWire registered = register();
        UUID patientId = seedPatient();
        assertThatThrownBy(() -> link(registered, patientId, 999L))
                .isInstanceOf(EmergencyPreadmissionException.class)
                .satisfies(e -> assertThat(((EmergencyPreadmissionException) e).code())
                        .isEqualTo("EMERGENCY_PREADMISSION_VERSION_CONFLICT"));
    }

    @Test
    void givenAlreadyRegistered_whenLinkingAgain_thenRejected() {
        EmergencyPreadmissionWire registered = register();
        UUID patientId = seedPatient();
        EmergencyPreadmissionWire linked = link(registered, patientId, registered.rowVersion());
        assertThatThrownBy(() -> link(linked, patientId, linked.rowVersion()))
                .isInstanceOf(EmergencyPreadmissionException.class)
                .satisfies(e -> assertThat(((EmergencyPreadmissionException) e).code())
                        .isEqualTo("EMERGENCY_PREADMISSION_STATE_INVALID"));
    }

    @Test
    void givenPreadmissionIdentity_whenTampered_thenDatabaseRejectsMutation() {
        EmergencyPreadmissionWire registered = register();
        assertThatThrownBy(() -> jdbc.sql("""
                update emergency_preadmission set temporary_identifier = 'TAMPERED'
                where tenant_id = cast(:tenant as uuid) and preadmission_id = :preadmission
                """).param("tenant", TENANT).param("preadmission", registered.preadmissionId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenUnregisteredPreadmission_whenCorrectingAndVoiding_thenActiveQueueChanges() {
        EmergencyPreadmissionWire created = register();
        EmergencyPreadmissionWire corrected = preadmissions.update(identity(), "pread-u-" + UUID.randomUUID(),
                created.preadmissionId(), new EmergencyPreadmissionUpdateRequestWire(
                        organization, facility, "TEMP-CORRECTED-" + UUID.randomUUID().toString().substring(0, 6),
                        "急诊身份信息复核后更正", created.rowVersion()));
        assertThat(corrected.preadmissionId()).isNotEqualTo(created.preadmissionId());
        assertThat(preadmissions.listPreadmissions(identity(), facility))
                .extracting(EmergencyPreadmissionWire::preadmissionId)
                .contains(corrected.preadmissionId()).doesNotContain(created.preadmissionId());

        preadmissions.voidPreadmission(identity(), "pread-v-" + UUID.randomUUID(), corrected.preadmissionId(),
                new EmergencyPreadmissionVoidRequestWire(
                        organization, facility, corrected.rowVersion(), "重复临时登记删除"));
        assertThat(preadmissions.listPreadmissions(identity(), facility))
                .extracting(EmergencyPreadmissionWire::preadmissionId)
                .doesNotContain(corrected.preadmissionId());
    }
}
