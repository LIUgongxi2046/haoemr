package org.haoemr.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.LabSpecimenCollectRequestWire;
import org.haoemr.contracts.LabSpecimenCreateRequestWire;
import org.haoemr.contracts.LabSpecimenReceiveRequestWire;
import org.haoemr.contracts.LabSpecimenRejectRequestWire;
import org.haoemr.contracts.LabSpecimenWire;
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
final class LabSpecimenApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private LabSpecimenService specimens;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);
    private UUID laboratoryRole;

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), laboratoryRole == null
                ? List.of(UUID.fromString(ROLE)) : List.of(UUID.fromString(ROLE), laboratoryRole));
    }

    private void grantLaboratoryRole() {
        laboratoryRole = UUID.randomUUID();
        jdbc.sql("""
                insert into role_assignment(
                  tenant_id, role_assignment_id, user_id, person_id, organization_id,
                  facility_id, role_code, valid_from, status)
                select tenant_id, :laboratory_role, user_id, person_id, organization_id,
                  facility_id, 'LAB_TECHNICIAN', now() - interval '1 day', 'ACTIVE'
                from role_assignment where tenant_id=cast(:tenant as uuid)
                  and role_assignment_id=cast(:role as uuid)
                """).param("laboratory_role", laboratoryRole).param("tenant", TENANT)
                .param("role", ROLE).update();
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成检验患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1979, 8, 21)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-LAB', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private UUID seedOrderItem(Context context, String itemType) {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                values (cast(:tenant as uuid), :order, :patient, :encounter, cast(:facility as uuid),
                  'TEMPORARY', 'ACTIVE', '合成检验申请', cast(:user as uuid), cast(:user as uuid),
                  now(), 'RULESET-MEDICATION-6')
                """).param("tenant", TENANT).param("order", orderId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("facility", FACILITY).param("user", USER).update();
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, item_state)
                values (cast(:tenant as uuid), :item, :order, :item_type, :catalog, '合成检验项目',
                  1, '次', 'ACTIVE')
                """).param("tenant", TENANT).param("item", itemId).param("order", orderId)
                .param("item_type", itemType).param("catalog", "LAB-" + itemId).update();
        return itemId;
    }

    @Test
    void givenLabOrder_whenCreatingCollectingReceivingSpecimen_thenLifecycleRecorded() {
        grantLaboratoryRole();
        Context context = seedContext();
        UUID orderItemId = seedOrderItem(context, "LAB");

        LabSpecimenWire created = specimens.createSpecimen(identity(), "spec-" + UUID.randomUUID(),
                new LabSpecimenCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), orderItemId, LabSpecimenCreateRequestWire.SpecimenTypeValue.BLOOD));
        assertThat(created.collectionStatus()).isEqualTo(LabSpecimenWire.CollectionStatusValue.ORDERED);

        LabSpecimenWire collected = specimens.collectSpecimen(identity(), "collect-" + UUID.randomUUID(),
                created.specimenId(), new LabSpecimenCollectRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), created.rowVersion()));
        assertThat(collected.collectionStatus()).isEqualTo(LabSpecimenWire.CollectionStatusValue.COLLECTED);
        assertThat(collected.collectedAt()).isNotNull();

        LabSpecimenWire received = specimens.receiveSpecimen(identity(), "receive-" + UUID.randomUUID(),
                created.specimenId(), new LabSpecimenReceiveRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), collected.rowVersion()));
        assertThat(received.collectionStatus()).isEqualTo(LabSpecimenWire.CollectionStatusValue.RECEIVED);
        assertThat(received.receivedAt()).isNotNull();

        List<LabSpecimenWire> listed = specimens.listSpecimens(
                identity(), organization, facility, context.patientId(), context.encounterId());
        assertThat(listed).extracting(LabSpecimenWire::specimenId).contains(created.specimenId());
    }

    @Test
    void givenNonLabOrderItem_whenCreatingSpecimen_thenOrderTypeInvalid() {
        grantLaboratoryRole();
        Context context = seedContext();
        UUID medicationItemId = seedOrderItem(context, "MEDICATION");
        assertThatThrownBy(() -> specimens.createSpecimen(identity(), "spec-" + UUID.randomUUID(),
                new LabSpecimenCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), medicationItemId, LabSpecimenCreateRequestWire.SpecimenTypeValue.BLOOD)))
                .isInstanceOf(LabSpecimenException.class)
                .satisfies(e -> assertThat(((LabSpecimenException) e).code())
                        .isEqualTo("LAB_SPECIMEN_ORDER_TYPE_INVALID"));
    }

    @Test
    void givenCollectedSpecimen_whenRejectingWithReason_thenLifecycleAndReasonRecorded() {
        grantLaboratoryRole();
        Context context = seedContext();
        UUID orderItemId = seedOrderItem(context, "LAB");
        LabSpecimenWire created = specimens.createSpecimen(identity(), "spec-" + UUID.randomUUID(),
                new LabSpecimenCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), orderItemId, LabSpecimenCreateRequestWire.SpecimenTypeValue.BLOOD));
        LabSpecimenWire collected = specimens.collectSpecimen(identity(), "collect-" + UUID.randomUUID(),
                created.specimenId(), new LabSpecimenCollectRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), created.rowVersion()));

        LabSpecimenWire rejected = specimens.rejectSpecimen(identity(), "reject-" + UUID.randomUUID(),
                created.specimenId(), new LabSpecimenRejectRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        collected.rowVersion(), "标本溶血，需重新采集"));

        assertThat(rejected.collectionStatus()).isEqualTo(LabSpecimenWire.CollectionStatusValue.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("标本溶血，需重新采集");
        assertThatThrownBy(() -> specimens.receiveSpecimen(identity(), "receive-" + UUID.randomUUID(),
                created.specimenId(), new LabSpecimenReceiveRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), rejected.rowVersion())))
                .isInstanceOf(LabSpecimenException.class);
    }

    @Test
    void givenSpecimenIdentity_whenTampered_thenDatabaseRejectsMutation() {
        grantLaboratoryRole();
        Context context = seedContext();
        UUID orderItemId = seedOrderItem(context, "LAB");
        LabSpecimenWire created = specimens.createSpecimen(identity(), "spec-" + UUID.randomUUID(),
                new LabSpecimenCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), orderItemId, LabSpecimenCreateRequestWire.SpecimenTypeValue.URINE));
        assertThatThrownBy(() -> jdbc.sql("""
                update lab_specimen set specimen_type = 'BLOOD'
                where tenant_id = cast(:tenant as uuid) and specimen_id = :specimen
                """).param("tenant", TENANT).param("specimen", created.specimenId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenClinicianOnly_whenCreatingSpecimen_thenRoleIsRejected() {
        Context context = seedContext();
        UUID orderItemId = seedOrderItem(context, "LAB");

        assertThatThrownBy(() -> specimens.createSpecimen(identity(), "spec-" + UUID.randomUUID(),
                new LabSpecimenCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), orderItemId, LabSpecimenCreateRequestWire.SpecimenTypeValue.BLOOD)))
                .isInstanceOf(LabSpecimenException.class)
                .satisfies(error -> assertThat(((LabSpecimenException) error).code())
                        .isEqualTo("LAB_SPECIMEN_ROLE_REQUIRED"));
    }

    @AfterEach
    void removeTemporaryLaboratoryRole() {
        if (laboratoryRole == null) return;
        jdbc.sql("""
                delete from role_assignment where tenant_id=cast(:tenant as uuid)
                  and role_assignment_id=:role
                """).param("tenant", TENANT).param("role", laboratoryRole).update();
        laboratoryRole = null;
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
