package org.haoemr.imaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ImagingOrderCreateRequestWire;
import org.haoemr.contracts.ImagingOrderTransitionRequestWire;
import org.haoemr.contracts.ImagingOrderWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ImagingOrderApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ImagingOrderService orders;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);
    private UUID radiologistRole;

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity radiologistIdentity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER),
                List.of(UUID.fromString(ROLE), radiologistRole));
    }

    private void grantRadiologistRole() {
        radiologistRole = UUID.randomUUID();
        jdbc.sql("""
                insert into role_assignment(
                  tenant_id, role_assignment_id, user_id, person_id, organization_id,
                  facility_id, role_code, valid_from, status)
                select tenant_id, :radiologist_role, user_id, person_id, organization_id,
                  facility_id, 'RADIOLOGIST', now() - interval '1 day', 'ACTIVE'
                from role_assignment where tenant_id=cast(:tenant as uuid)
                  and role_assignment_id=cast(:role as uuid)
                """).param("radiologist_role", radiologistRole).param("tenant", TENANT)
                .param("role", ROLE).update();
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成影像患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1975, 10, 10)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-IMAGING', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private ImagingOrderWire create(Context context, String modality, String bodyPart, String laterality) {
        return orders.createOrder(identity(), "img-" + UUID.randomUUID(),
                new ImagingOrderCreateRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        ImagingOrderCreateRequestWire.ModalityValue.valueOf(modality),
                        ImagingOrderCreateRequestWire.BodyPartValue.valueOf(bodyPart),
                        ImagingOrderCreateRequestWire.LateralityValue.valueOf(laterality),
                        false, Instant.now()));
    }

    private ImagingOrderWire transition(Context context, ImagingOrderWire order, String transition) {
        return orders.transitionOrder(radiologistIdentity(), "img-t-" + UUID.randomUUID(), order.imagingOrderId(),
                new ImagingOrderTransitionRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), order.rowVersion(),
                        ImagingOrderTransitionRequestWire.TransitionValue.valueOf(transition)));
    }

    @Test
    void givenOrder_whenPerformingAndReporting_thenLifecycleRecorded() {
        grantRadiologistRole();
        Context context = seedContext();
        ImagingOrderWire created = create(context, "CT", "CHEST", "NONE");
        assertThat(created.status()).isEqualTo(ImagingOrderWire.StatusValue.ORDERED);

        ImagingOrderWire performed = transition(context, created, "PERFORM");
        assertThat(performed.status()).isEqualTo(ImagingOrderWire.StatusValue.PERFORMED);
        assertThat(performed.performedAt()).isNotNull();

        ImagingOrderWire reported = transition(context, performed, "REPORT");
        assertThat(reported.status()).isEqualTo(ImagingOrderWire.StatusValue.REPORTED);
        assertThat(reported.reportedAt()).isNotNull();

        List<ImagingOrderWire> listed = orders.listOrders(identity(), context.patientId());
        assertThat(listed).extracting(ImagingOrderWire::imagingOrderId).contains(created.imagingOrderId());
    }

    @Test
    void givenPairedBodyPartWithoutLaterality_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> create(context, "XRAY", "UPPER_EXTREMITY", "NONE"))
                .isInstanceOf(ImagingOrderException.class)
                .satisfies(e -> assertThat(((ImagingOrderException) e).code())
                        .isEqualTo("IMAGING_ORDER_REQUEST_INVALID"));
    }

    @Test
    void givenInvalidTransition_whenTransitioning_thenRejected() {
        grantRadiologistRole();
        Context context = seedContext();
        ImagingOrderWire created = create(context, "MRI", "HEAD", "NONE");
        assertThatThrownBy(() -> transition(context, created, "REPORT"))
                .isInstanceOf(ImagingOrderException.class)
                .satisfies(e -> assertThat(((ImagingOrderException) e).code())
                        .isEqualTo("IMAGING_ORDER_STATE_INVALID"));
    }

    @Test
    void givenOrderIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ImagingOrderWire created = create(context, "ULTRASOUND", "ABDOMEN", "NONE");
        assertThatThrownBy(() -> jdbc.sql("""
                update imaging_order set body_part = 'CHEST'
                where tenant_id = cast(:tenant as uuid) and imaging_order_id = :order
                """).param("tenant", TENANT).param("order", created.imagingOrderId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenClinicianOnly_whenPerformingImaging_thenRoleIsRejected() {
        Context context = seedContext();
        ImagingOrderWire created = create(context, "CT", "CHEST", "NONE");

        assertThatThrownBy(() -> orders.transitionOrder(identity(), "img-t-" + UUID.randomUUID(),
                created.imagingOrderId(), new ImagingOrderTransitionRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), created.rowVersion(),
                        ImagingOrderTransitionRequestWire.TransitionValue.PERFORM)))
                .isInstanceOf(ImagingOrderException.class)
                .satisfies(error -> assertThat(((ImagingOrderException) error).code())
                        .isEqualTo("IMAGING_ORDER_RADIOLOGIST_ROLE_REQUIRED"));
    }

    @AfterEach
    void removeTemporaryRadiologistRole() {
        if (radiologistRole == null) return;
        jdbc.sql("""
                delete from role_assignment where tenant_id=cast(:tenant as uuid)
                  and role_assignment_id=:role
                """).param("tenant", TENANT).param("role", radiologistRole).update();
        radiologistRole = null;
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
