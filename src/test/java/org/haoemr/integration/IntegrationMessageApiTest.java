package org.haoemr.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.IntegrationMessageCollectRequestWire;
import org.haoemr.contracts.IntegrationMessageCollectResultWire;
import org.haoemr.contracts.IntegrationMessageReconcileRequestWire;
import org.haoemr.contracts.IntegrationMessageWire;
import org.haoemr.contracts.IntegrationReconciliationWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class IntegrationMessageApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private IntegrationMessageService messages;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenActiveConnector_whenCollecting_thenMessagesPersistedAndReconciliationComputed() {
        IntegrationMessageCollectResultWire result = messages.collect(identity(),
                "collect-" + UUID.randomUUID(),
                new IntegrationMessageCollectRequestWire(organization, facility, "lis-core-prod",
                        IntegrationMessageCollectRequestWire.DirectionValue.OUTBOUND,
                        IntegrationMessageCollectRequestWire.SimulationScenarioValue.SUCCESS, 24));

        assertThat(result.messages()).hasSize(24);
        assertThat(result.messages()).allMatch(message ->
                message.connectorCode().equals("lis-core-prod")
                        && message.interfaceCode().equals("LIS_RESULTS")
                        && message.messageStatus() == IntegrationMessageWire.MessageStatusValue.DELIVERED);
        assertThat(result.reconciliation().sentCount()).isGreaterThanOrEqualTo(24);
        assertThat(result.reconciliation().deliveredCount()).isGreaterThanOrEqualTo(24);
    }

    @Test
    void givenCollectedMessages_whenReconcilingOne_thenStatusMovesToReconciled() {
        IntegrationMessageCollectResultWire result = messages.collect(identity(),
                "collect-" + UUID.randomUUID(),
                new IntegrationMessageCollectRequestWire(organization, facility, "lis-core-prod",
                        IntegrationMessageCollectRequestWire.DirectionValue.INBOUND,
                        IntegrationMessageCollectRequestWire.SimulationScenarioValue.DEGRADED, 12));
        IntegrationMessageWire pending = result.messages().get(0);
        assertThat(pending.messageStatus()).isEqualTo(IntegrationMessageWire.MessageStatusValue.PENDING);

        IntegrationMessageWire reconciled = messages.reconcile(identity(), "reconcile-" + UUID.randomUUID(),
                pending.messageId(), new IntegrationMessageReconcileRequestWire(organization, facility));
        assertThat(reconciled.messageStatus()).isEqualTo(IntegrationMessageWire.MessageStatusValue.RECONCILED);
    }

    @Test
    void givenUnknownConnector_whenCollecting_thenRejected() {
        assertThatThrownBy(() -> messages.collect(identity(), "collect-" + UUID.randomUUID(),
                new IntegrationMessageCollectRequestWire(organization, facility, "not-a-connector",
                        null, null, null)))
                .isInstanceOf(IntegrationException.class)
                .hasMessageContaining("连接器不存在");
    }

    @Test
    void givenCollectedMessages_whenListingByStatus_thenFiltered() {
        messages.collect(identity(), "collect-" + UUID.randomUUID(),
                new IntegrationMessageCollectRequestWire(organization, facility, "lis-core-prod",
                        IntegrationMessageCollectRequestWire.DirectionValue.OUTBOUND,
                        IntegrationMessageCollectRequestWire.SimulationScenarioValue.DEGRADED, 12));
        List<IntegrationMessageWire> pending = messages.listMessages(identity(), "lis-core-prod", "PENDING");
        assertThat(pending).isNotEmpty();
        assertThat(pending).allMatch(message ->
                message.messageStatus() == IntegrationMessageWire.MessageStatusValue.PENDING);
    }

    @Test
    void givenUnavailableSimulation_whenCollecting_thenFailureIsPersistedWithoutClinicalWrite() {
        IntegrationMessageCollectResultWire result = messages.collect(identity(),
                "collect-unavailable-" + UUID.randomUUID(),
                new IntegrationMessageCollectRequestWire(organization, facility, "lis-core-prod",
                        IntegrationMessageCollectRequestWire.DirectionValue.INBOUND,
                        IntegrationMessageCollectRequestWire.SimulationScenarioValue.UNAVAILABLE, 12));

        assertThat(result.messages()).singleElement().satisfies(message -> {
            assertThat(message.messageStatus()).isEqualTo(IntegrationMessageWire.MessageStatusValue.FAILED);
            assertThat(message.errorDetail()).contains("未创建或修改任何临床事实");
        });
        assertThat(result.reconciliation().errorCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.reconciliation().status()).isEqualTo(IntegrationReconciliationWire.StatusValue.OPEN);
        assertThatThrownBy(() -> messages.reconcile(identity(), "reconcile-failed-" + UUID.randomUUID(),
                result.messages().getFirst().messageId(),
                new IntegrationMessageReconcileRequestWire(organization, facility)))
                .isInstanceOf(IntegrationException.class)
                .hasMessageContaining("不能人工改写");
    }
}
