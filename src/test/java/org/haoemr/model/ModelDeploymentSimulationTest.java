package org.haoemr.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ModelDeploymentConnectionTestRequestWire;
import org.haoemr.contracts.ModelDeploymentPublishRequestWire;
import org.haoemr.contracts.ModelDeploymentRegisterRequestWire;
import org.haoemr.contracts.ModelDeploymentWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the dev-synthetic flow end to end without a real provider key:
 * register -> test connection (simulated READY) -> publish (APPROVED), which is
 * exactly what puts a model into Eva routing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class ModelDeploymentSimulationTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");

    @Autowired
    private ModelDeploymentService models;

    private static ClinicalIdentity identity() {
        return new ClinicalIdentity(TENANT, USER, List.of(ROLE));
    }

    @Test
    void givenSimulatedConnection_whenFullLifecycle_thenModelReachesEvaRoutingState() {
        ModelDeploymentWire registered = models.register(identity(), "sim-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(ORGANIZATION, FACILITY, null,
                        "SYNTHETIC", "内置演示医助模型",
                        ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.LOCAL_PREFERRED,
                        "https://synthetic-model.demo.example/v1", null, "sk-demo-12345678"));
        assertThat(registered.modelCode()).startsWith("model-");
        assertThat(registered.status()).isEqualTo(ModelDeploymentWire.StatusValue.ACTIVE);

        ModelDeploymentWire connected = models.testConnection(identity(), "sim-test-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ModelDeploymentConnectionTestRequestWire(
                        ORGANIZATION, FACILITY, registered.rowVersion()));
        assertThat(connected.connectionStatus()).isEqualTo(ModelDeploymentWire.ConnectionStatusValue.READY);

        ModelDeploymentWire published = models.publish(identity(), "sim-pub-" + UUID.randomUUID(),
                connected.modelDeploymentId(), new ModelDeploymentPublishRequestWire(
                        ORGANIZATION, FACILITY, connected.rowVersion()));
        assertThat(published.evaluationStatus()).isEqualTo(ModelDeploymentWire.EvaluationStatusValue.APPROVED);
        assertThat(published.status()).isEqualTo(ModelDeploymentWire.StatusValue.ACTIVE);
        assertThat(published.connectionStatus()).isEqualTo(ModelDeploymentWire.ConnectionStatusValue.READY);
    }
}
