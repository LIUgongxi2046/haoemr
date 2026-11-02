package org.haoemr.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

class SyntheticDemoProcessingPolicyTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final List<String> SCOPES = List.of("RECORDS", "ORDERS", "RESULTS", "TASKS", "CONFIGURATION");

    @Test
    void allowsOnlyTheDedicatedDemoEnvironmentAndTenant() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        assertThatCode(() -> SyntheticDemoProcessingPolicy.requireEnvironment(environment, TENANT, SCOPES))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireEnvironment(environment, UUID.randomUUID(), SCOPES))
                .isInstanceOf(ModelDeploymentException.class);
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireEnvironment(environment, TENANT, List.of("ATTACHMENTS")))
                .isInstanceOf(ModelDeploymentException.class);
    }

    @Test
    void rejectsProductionAndCombinedProfiles() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("production");
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireEnvironment(environment, TENANT, SCOPES))
                .isInstanceOf(ModelDeploymentException.class);
        environment.setActiveProfiles("dev-synthetic", "production");
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireEnvironment(environment, TENANT, SCOPES))
                .isInstanceOf(ModelDeploymentException.class);
    }

    @Test
    void requiresTheAuthorizedProviderAndExactEndpoint() {
        assertThatCode(() -> SyntheticDemoProcessingPolicy.requireProvider("DEEPSEEK", "https://api.deepseek.com"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireProvider("DOUBAO", "https://api.deepseek.com"))
                .isInstanceOf(ModelDeploymentException.class);
        assertThatThrownBy(() -> SyntheticDemoProcessingPolicy.requireProvider("DEEPSEEK", "https://example.org"))
                .isInstanceOf(ModelDeploymentException.class);
    }
}
