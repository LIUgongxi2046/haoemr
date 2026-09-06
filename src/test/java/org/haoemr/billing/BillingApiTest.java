package org.haoemr.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ChargeItemRequestWire;
import org.haoemr.contracts.ChargeItemReverseRequestWire;
import org.haoemr.contracts.ChargeItemWire;
import org.haoemr.contracts.PriceCatalogVersionRequestWire;
import org.haoemr.contracts.PriceCatalogVersionWire;
import org.haoemr.contracts.EncounterSettlementWire;
import org.haoemr.contracts.SettlementReceiptCreateRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class BillingApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private BillingService billing;

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
                values (cast(:tenant as uuid), :patient, '合成收费患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1982, 4, 17)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-CHARGE', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private PriceCatalogVersionWire seedPrice(String itemCode, double unitPrice) {
        return billing.createPriceVersion(identity(), "price-" + UUID.randomUUID(),
                new PriceCatalogVersionRequestWire(organization, facility, "CAT-SYN", itemCode,
                        "合成收费项目", unitPrice, "次", LocalDate.now(), "SYN-1"));
    }

    @Test
    void givenActivePrice_whenCharging_thenAmountSnapshottedAndReversible() {
        Context context = seedContext();
        String itemCode = "ITEM-" + UUID.randomUUID();
        seedPrice(itemCode, 10.50);

        ChargeItemWire charged = billing.createCharge(identity(), "charge-" + UUID.randomUUID(),
                new ChargeItemRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        itemCode, 3.0));
        assertThat(charged.status()).isEqualTo(ChargeItemWire.StatusValue.CHARGED);
        assertThat(charged.unitPrice()).isEqualTo(10.50);
        assertThat(charged.amount()).isEqualTo(31.50);

        ChargeItemWire reversed = billing.reverseCharge(identity(), "reverse-" + UUID.randomUUID(),
                charged.chargeItemId(), new ChargeItemReverseRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        charged.rowVersion(), "重复计费冲正"));
        assertThat(reversed.status()).isEqualTo(ChargeItemWire.StatusValue.REVERSED);
        assertThat(reversed.reversedAt()).isNotNull();
        assertThat(reversed.reverseReason()).contains("冲正");
        assertThat(jdbc.sql("""
                select count(*) from charge_item
                where tenant_id = cast(:tenant as uuid) and charge_item_id = :charge
                """).param("tenant", TENANT).param("charge", charged.chargeItemId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void givenNoActivePrice_whenCharging_thenPriceNotAvailable() {
        Context context = seedContext();
        assertThatThrownBy(() -> billing.createCharge(identity(), "charge-" + UUID.randomUUID(),
                new ChargeItemRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        "ITEM-MISSING", 1.0)))
                .isInstanceOf(BillingException.class)
                .satisfies(e -> assertThat(((BillingException) e).code()).isEqualTo("PRICE_NOT_AVAILABLE"));
    }

    @Test
    void givenReversedCharge_whenReversedAgain_thenStateInvalid() {
        Context context = seedContext();
        String itemCode = "ITEM-" + UUID.randomUUID();
        seedPrice(itemCode, 5.00);
        ChargeItemWire charged = billing.createCharge(identity(), "charge-" + UUID.randomUUID(),
                new ChargeItemRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        itemCode, 1.0));
        billing.reverseCharge(identity(), "reverse-" + UUID.randomUUID(), charged.chargeItemId(),
                new ChargeItemReverseRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), charged.rowVersion(), "首次冲正"));
        assertThatThrownBy(() -> billing.reverseCharge(identity(), "reverse-" + UUID.randomUUID(),
                charged.chargeItemId(), new ChargeItemReverseRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), 2L, "二次冲正")))
                .isInstanceOf(BillingException.class)
                .satisfies(e -> assertThat(((BillingException) e).code()).isEqualTo("CHARGE_STATE_INVALID"));
    }

    @Test
    void givenCompletedClinicalFact_whenAutomaticallyCharged_thenPriceAndSourceAreFrozenOnce() {
        Context context = seedContext();
        String itemCode = "ITEM-AUTO-" + UUID.randomUUID();
        seedPrice(itemCode, 18.50);
        UUID sourceId = UUID.randomUUID();

        billing.createAutomaticCharge(identity(), context.patientId(), context.encounterId(), facility,
                itemCode, new BigDecimal("2.000000"), "次", "ORDER_EXECUTION", sourceId);
        billing.createAutomaticCharge(identity(), context.patientId(), context.encounterId(), facility,
                itemCode, new BigDecimal("2.000000"), "次", "ORDER_EXECUTION", sourceId);

        assertThat(jdbc.sql("""
                select count(*) from charge_item where tenant_id = :tenant
                  and source_type = 'ORDER_EXECUTION' and source_id = :source
                """).param("tenant", tenant).param("source", sourceId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select amount from charge_item where tenant_id = :tenant
                  and source_type = 'ORDER_EXECUTION' and source_id = :source
                """).param("tenant", tenant).param("source", sourceId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("37.00");
    }

    @Test
    void givenCompletedClinicalFactWithoutPrice_whenAutomaticallyCharged_thenClinicalFactIsNotBlocked() {
        Context context = seedContext();
        UUID sourceId = UUID.randomUUID();

        billing.createAutomaticCharge(identity(), context.patientId(), context.encounterId(), facility,
                "ITEM-NO-PRICE", BigDecimal.ONE, "次", "ORDER_EXECUTION", sourceId);

        assertThat(jdbc.sql("""
                select exception_code from billing_charge_exception where tenant_id = :tenant
                  and source_type = 'ORDER_EXECUTION' and source_id = :source
                """).param("tenant", tenant).param("source", sourceId)
                .query(String.class).single()).isEqualTo("PRICE_NOT_AVAILABLE");
    }

    @Test
    void givenExternalReceipt_whenRecorded_thenSettlementIsDerivedWithoutSimulatingPayment() {
        Context context = seedContext();
        String itemCode = "ITEM-SETTLEMENT-" + UUID.randomUUID();
        seedPrice(itemCode, 20.00);
        billing.createCharge(identity(), "charge-" + UUID.randomUUID(),
                new ChargeItemRequestWire(organization, facility, context.patientId(), context.encounterId(), itemCode, 1.0));

        billing.recordSettlementReceipt(identity(), "receipt-" + UUID.randomUUID(),
                new SettlementReceiptCreateRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        12.00, "SELF_PAY", "HIS_CASHIER", "RCPT-" + UUID.randomUUID(), Instant.now()));

        EncounterSettlementWire settlement = billing.settlement(
                identity(), organization, facility, context.patientId(), context.encounterId());
        assertThat(settlement.status()).isEqualTo(EncounterSettlementWire.StatusValue.PARTIALLY_SETTLED);
        assertThat(settlement.chargeAmount()).isEqualTo(20.00);
        assertThat(settlement.settledAmount()).isEqualTo(12.00);
        assertThat(settlement.outstandingAmount()).isEqualTo(8.00);
        assertThat(settlement.receipts()).hasSize(1);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
