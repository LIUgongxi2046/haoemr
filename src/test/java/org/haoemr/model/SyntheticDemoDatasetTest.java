package org.haoemr.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "HAOEMR_DEMO_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:.+")
class SyntheticDemoDatasetTest {
    @Test
    void checksActualDemoDatasetAndRejectsUnmatchedPatientOrTenant() {
        var dataSource = new DriverManagerDataSource(System.getenv("HAOEMR_DEMO_TEST_DB_URL"), "liuhaoxian", "");
        var jdbc = JdbcClient.create(dataSource);
        var tenant = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
        var patient = UUID.fromString("018f0000-0000-7000-8000-000000000001");
        var encounter = UUID.fromString("018f0000-0000-7000-8000-000000000101");
        assertThatCode(() -> SyntheticDemoProcessingPolicy.requireDataset(jdbc, tenant, patient, encounter))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireDataset(jdbc, tenant, UUID.randomUUID(), encounter))
                .isInstanceOf(ModelDeploymentException.class);
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireDataset(jdbc, tenant,
                UUID.fromString("018f0000-0000-7000-8000-000000000006"), null))
                .isInstanceOf(ModelDeploymentException.class);
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireDataset(jdbc, UUID.randomUUID(), null, null))
                .isInstanceOf(ModelDeploymentException.class);
    }
}
