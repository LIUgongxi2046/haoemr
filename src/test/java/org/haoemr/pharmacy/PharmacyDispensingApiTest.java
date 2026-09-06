package org.haoemr.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PharmacyDispensingPrepareRequestWire;
import org.haoemr.contracts.PharmacyDispensingTransitionRequestWire;
import org.haoemr.contracts.PharmacyDispensingUpdateRequestWire;
import org.haoemr.contracts.PharmacyDispensingVoidRequestWire;
import org.haoemr.contracts.PharmacyDispensingWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class PharmacyDispensingApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";
    private static final String COLLABORATOR_ROLE = "018f0000-0000-7000-8000-00000000aa07";

    @Autowired
    private PharmacyDispensingService dispensings;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity dispenser() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity verifier() {
        return new ClinicalIdentity(tenant, UUID.fromString(COLLABORATOR), List.of(UUID.fromString(COLLABORATOR_ROLE)));
    }

    private Context seedContext() {
        return seedContext("OUTPATIENT");
    }

    private Context seedContext(String encounterType) {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成药房患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1988, 12, 12)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), :encounter_type, 'IN_PROGRESS', now(), 'SYNTHETIC-PHARMACY', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("encounter_type", encounterType)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private MedicationOrder seedMedicationOrder(Context context, double quantity) {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                values (cast(:tenant as uuid), :order, :patient, :encounter, cast(:facility as uuid),
                  'TEMPORARY', 'ACTIVE', '住院抗感染治疗', cast(:user as uuid), cast(:user as uuid),
                  now(), 'RULESET-MEDICATION-6')
                """).param("tenant", TENANT).param("order", orderId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("facility", FACILITY).param("user", USER).update();
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, item_state, drug_code, dose_value, dose_unit, route_code)
                values (cast(:tenant as uuid), :item, :order, 'MEDICATION', 'MED-IP-CEF', '头孢曲松钠',
                  :quantity, '支', 'ACTIVE', 'DRUG-IP-CEF', 1, 'g', 'IV')
                """).param("tenant", TENANT).param("item", itemId).param("order", orderId)
                .param("quantity", quantity).update();
        return new MedicationOrder(orderId, itemId);
    }

    private PharmacyDispensingWire prepare(Context context) {
        MedicationOrder order = seedMedicationOrder(context, 20.0);
        return dispensings.prepare(dispenser(), "pharm-" + UUID.randomUUID(),
                new PharmacyDispensingPrepareRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), order.orderId(), order.itemId(),
                        "MED-IP-CEF", "BATCH-" + UUID.randomUUID().toString().substring(0, 8),
                        10.0, "支", Instant.now()));
    }

    private PharmacyDispensingPrepareRequestWire inpatientPrepare(
            Context context, MedicationOrder order, double quantity, String drugCode, String unit) {
        return new PharmacyDispensingPrepareRequestWire(
                organization, facility, context.patientId(), context.encounterId(), order.orderId(), order.itemId(),
                drugCode, "IP-BATCH-" + UUID.randomUUID().toString().substring(0, 8), quantity, unit, Instant.now());
    }

    private PharmacyDispensingWire transition(
            ClinicalIdentity actor, Context context, PharmacyDispensingWire dispensing, String transition) {
        return dispensings.transition(actor, "pharm-t-" + UUID.randomUUID(), dispensing.dispensingId(),
                new PharmacyDispensingTransitionRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), dispensing.rowVersion(),
                        PharmacyDispensingTransitionRequestWire.TransitionValue.valueOf(transition)));
    }

    @Test
    void givenDispensing_whenPreparingVerifyingAndDispensing_thenLifecycleRecorded() {
        Context context = seedContext();
        PharmacyDispensingWire prepared = prepare(context);
        assertThat(prepared.status()).isEqualTo(PharmacyDispensingWire.StatusValue.PREPARED);
        assertThat(prepared.dispensedBy()).isEqualTo(UUID.fromString(USER));
        assertThat(prepared.verifiedBy()).isNull();

        PharmacyDispensingWire verified = transition(verifier(), context, prepared, "VERIFY");
        assertThat(verified.status()).isEqualTo(PharmacyDispensingWire.StatusValue.VERIFIED);
        assertThat(verified.verifiedBy()).isEqualTo(UUID.fromString(COLLABORATOR));
        assertThat(verified.verifiedAt()).isNotNull();

        PharmacyDispensingWire dispensed = transition(verifier(), context, verified, "DISPENSE");
        assertThat(dispensed.status()).isEqualTo(PharmacyDispensingWire.StatusValue.DISPENSED);
        assertThat(dispensed.dispensedAt()).isNotNull();

        List<PharmacyDispensingWire> listed = dispensings.listDispensings(dispenser(), context.patientId(), facility);
        assertThat(listed).extracting(PharmacyDispensingWire::dispensingId).contains(prepared.dispensingId());
    }

    @Test
    void givenSameUser_whenVerifying_thenRejected() {
        Context context = seedContext();
        PharmacyDispensingWire prepared = prepare(context);
        assertThatThrownBy(() -> transition(dispenser(), context, prepared, "VERIFY"))
                .isInstanceOf(PharmacyDispensingException.class)
                .satisfies(e -> assertThat(((PharmacyDispensingException) e).code())
                        .isEqualTo("PHARMACY_SELF_VERIFICATION_FORBIDDEN"));
    }

    @Test
    void givenInvalidTransition_whenDispensingBeforeVerify_thenRejected() {
        Context context = seedContext();
        PharmacyDispensingWire prepared = prepare(context);
        assertThatThrownBy(() -> transition(verifier(), context, prepared, "DISPENSE"))
                .isInstanceOf(PharmacyDispensingException.class)
                .satisfies(e -> assertThat(((PharmacyDispensingException) e).code())
                        .isEqualTo("PHARMACY_DISPENSING_STATE_INVALID"));
    }

    @Test
    void givenPreparedDispensing_whenEditing_thenFactsAndVersionAreUpdated() {
        Context context = seedContext();
        PharmacyDispensingWire prepared = prepare(context);

        PharmacyDispensingWire updated = dispensings.update(dispenser(), "pharm-u-" + UUID.randomUUID(),
                prepared.dispensingId(), new PharmacyDispensingUpdateRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), prepared.rowVersion(),
                        "MED-IP-CEF", "BATCH-CORRECTED", 12.0, "支"));

        assertThat(updated.drugCode()).isEqualTo("MED-IP-CEF");
        assertThat(updated.batchNumber()).isEqualTo("BATCH-CORRECTED");
        assertThat(updated.quantity()).isEqualTo(12.0);
        assertThat(updated.quantityUnit()).isEqualTo("支");
        assertThat(updated.rowVersion()).isEqualTo(prepared.rowVersion() + 1);
    }

    @Test
    void givenPreparedDispensing_whenVoiding_thenEvidenceRetainedAndTransitionBlocked() {
        Context context = seedContext();
        PharmacyDispensingWire prepared = prepare(context);

        PharmacyDispensingWire voided = dispensings.voidDispensing(dispenser(), "pharm-v-" + UUID.randomUUID(),
                prepared.dispensingId(), new PharmacyDispensingVoidRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), prepared.rowVersion(),
                        "摆药批次录入错误，停止后重新摆药"));

        assertThat(voided.voidedAt()).isNotNull();
        assertThat(voided.voidReason()).contains("批次");
        assertThat(dispensings.listDispensings(dispenser(), context.patientId(), facility))
                .extracting(PharmacyDispensingWire::dispensingId).contains(prepared.dispensingId());
        assertThatThrownBy(() -> transition(verifier(), context, voided, "VERIFY"))
                .isInstanceOf(PharmacyDispensingException.class)
                .satisfies(e -> assertThat(((PharmacyDispensingException) e).code())
                        .isEqualTo("PHARMACY_DISPENSING_STATE_INVALID"));
    }

    @Test
    void givenVerifiedDispensing_whenEditingOrVoidingDispensed_thenStateRulesReject() {
        Context context = seedContext();
        PharmacyDispensingWire prepared = prepare(context);
        PharmacyDispensingWire verified = transition(verifier(), context, prepared, "VERIFY");
        assertThatThrownBy(() -> dispensings.update(dispenser(), "pharm-u-" + UUID.randomUUID(),
                verified.dispensingId(), new PharmacyDispensingUpdateRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), verified.rowVersion(),
                        verified.drugCode(), verified.batchNumber(), verified.quantity(), verified.quantityUnit())))
                .isInstanceOf(PharmacyDispensingException.class);
        PharmacyDispensingWire dispensed = transition(verifier(), context, verified, "DISPENSE");
        assertThatThrownBy(() -> dispensings.voidDispensing(dispenser(), "pharm-v-" + UUID.randomUUID(),
                dispensed.dispensingId(), new PharmacyDispensingVoidRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), dispensed.rowVersion(),
                        "已发药记录不能直接作废")))
                .isInstanceOf(PharmacyDispensingException.class);
    }

    @Test
    void givenDispensingIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        PharmacyDispensingWire prepared = prepare(context);
        PharmacyDispensingWire verified = transition(verifier(), context, prepared, "VERIFY");
        assertThatThrownBy(() -> jdbc.sql("""
                update pharmacy_dispensing set batch_number = '篡改'
                where tenant_id = cast(:tenant as uuid) and dispensing_id = :dispensing
                """).param("tenant", TENANT).param("dispensing", verified.dispensingId()).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                delete from pharmacy_dispensing
                where tenant_id = cast(:tenant as uuid) and dispensing_id = :dispensing
                """).param("tenant", TENANT).param("dispensing", verified.dispensingId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenPrescriptionDispensing_whenOrderMissingMismatchedOrOverQuantity_thenServerBlocksIt() {
        Context context = seedContext("INPATIENT");
        MedicationOrder order = seedMedicationOrder(context, 10.0);

        assertThatThrownBy(() -> dispensings.prepare(dispenser(), "ip-missing-" + UUID.randomUUID(),
                new PharmacyDispensingPrepareRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), null, null,
                        "MED-IP-CEF", "IP-BATCH-MISSING", 1.0, "支", Instant.now())))
                .isInstanceOf(PharmacyDispensingException.class)
                .satisfies(error -> assertThat(((PharmacyDispensingException) error).code())
                        .isEqualTo("PHARMACY_DISPENSING_ORDER_REQUIRED"));

        assertThatThrownBy(() -> dispensings.prepare(dispenser(), "ip-mismatch-" + UUID.randomUUID(),
                inpatientPrepare(context, order, 1.0, "MED-IP-WRONG", "支")))
                .isInstanceOf(PharmacyDispensingException.class)
                .satisfies(error -> assertThat(((PharmacyDispensingException) error).code())
                        .isEqualTo("PHARMACY_DISPENSING_ORDER_MISMATCH"));

        PharmacyDispensingWire prepared = dispensings.prepare(dispenser(), "ip-ok-" + UUID.randomUUID(),
                inpatientPrepare(context, order, 8.0, "MED-IP-CEF", "支"));
        assertThat(prepared.orderId()).isEqualTo(order.orderId());
        assertThat(prepared.orderItemId()).isEqualTo(order.itemId());

        assertThatThrownBy(() -> dispensings.prepare(dispenser(), "ip-exceed-" + UUID.randomUUID(),
                inpatientPrepare(context, order, 3.0, "MED-IP-CEF", "支")))
                .isInstanceOf(PharmacyDispensingException.class)
                .satisfies(error -> assertThat(((PharmacyDispensingException) error).code())
                        .isEqualTo("PHARMACY_DISPENSING_QUANTITY_EXCEEDED"));
    }

    @Test
    void givenOutpatientDispensingWithoutSignedPrescription_whenPreparing_thenServerBlocksIt() {
        Context context = seedContext("OUTPATIENT");
        assertThatThrownBy(() -> dispensings.prepare(dispenser(), "op-missing-" + UUID.randomUUID(),
                new PharmacyDispensingPrepareRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), null, null,
                        "MED-IP-CEF", "OP-BATCH-MISSING", 1.0, "支", Instant.now())))
                .isInstanceOf(PharmacyDispensingException.class)
                .satisfies(error -> assertThat(((PharmacyDispensingException) error).code())
                        .isEqualTo("PHARMACY_DISPENSING_ORDER_REQUIRED"));
    }

    @Test
    void givenProductionRoleEnforcement_whenClinicianPrepares_thenPharmacistRoleIsRequired() {
        Context context = seedContext();
        dispensings.requireClinicalOperationRoles = true;
        try {
            assertThatThrownBy(() -> prepare(context))
                    .isInstanceOf(PharmacyDispensingException.class)
                    .satisfies(error -> assertThat(((PharmacyDispensingException) error).code())
                            .isEqualTo("PHARMACIST_ROLE_REQUIRED"));
        } finally {
            dispensings.requireClinicalOperationRoles = false;
        }
    }

    private record Context(UUID patientId, UUID encounterId) {}

    private record MedicationOrder(UUID orderId, UUID itemId) {}
}
