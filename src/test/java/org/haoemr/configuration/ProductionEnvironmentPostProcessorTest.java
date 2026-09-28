package org.haoemr.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.support.EnvironmentPostProcessorsFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

final class ProductionEnvironmentPostProcessorTest {

    private final ProductionEnvironmentPostProcessor processor = new ProductionEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication(Object.class);

    @TempDir
    Path temporaryDirectory;

    @Test
    void bootstrapCannotRunInWebOrSyntheticMode() {
        var synthetic=new MockEnvironment().withProperty("haoemr.bootstrap.enabled","true").withProperty("spring.main.web-application-type","none");
        synthetic.setActiveProfiles("dev-synthetic");
        assertThatThrownBy(()->processor.postProcessEnvironment(synthetic,application)).hasMessageContaining("non-web command");
        var web=productionEnvironment(completeProperties(false));
        web.setProperty("haoemr.bootstrap.enabled","true");
        assertThatThrownBy(()->processor.postProcessEnvironment(web,application)).hasMessageContaining("non-web command");
        web.setProperty("spring.main.web-application-type","none");
        assertThatCode(()->processor.postProcessEnvironment(web,application)).doesNotThrowAnyException();
    }

    @Test
    void givenANonProductionProfile_whenConfigurationIsAbsent_thenValidationIsNotApplied() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "dev-synthetic");
        environment.setActiveProfiles("dev-synthetic");

        assertThatCode(() -> processor.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void givenThePackagedApplication_whenLoadingBootFactories_thenTheProductionGateIsRegistered() {
        var processors = EnvironmentPostProcessorsFactory
                .fromSpringFactories(getClass().getClassLoader())
                .getEnvironmentPostProcessors(new DeferredLogs(), new DefaultBootstrapContext());

        assertThat(processors).anyMatch(ProductionEnvironmentPostProcessor.class::isInstance);
    }

    @Test
    void givenProductionWithMissingConfiguration_whenBootstrapping_thenAllCriticalDomainsFailClosed() {
        MockEnvironment environment = productionEnvironment(Map.of());

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(ApplicationContextException.class)
                .hasMessageContaining("Production configuration rejected")
                .hasMessageContaining("identity.issuer-uri")
                .hasMessageContaining("signing.ca-base-uri")
                .hasMessageContaining("encryption.kms-key-uri")
                .hasMessageContaining("storage.endpoint")
                .hasMessageContaining("archive.ocr-endpoint")
                .hasMessageContaining("archive.cda-validation-endpoint")
                .hasMessageContaining("archive.malware-scanner.host")
                .hasMessageContaining("integration.truststore-ref")
                .hasMessageContaining("database.password-ref");
    }

    @Test
    void givenProductionCombinedWithSyntheticIdentity_whenBootstrapping_thenProfilesAreRejected() {
        MockEnvironment environment = productionEnvironment(completeProperties(false));
        environment.setActiveProfiles("prod", "dev-synthetic");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(ApplicationContextException.class)
                .hasMessageContaining("prod cannot be combined with dev-synthetic");
    }

    @Test
    void givenInlineSecretAndHttpEndpoint_whenBootstrapping_thenTheyAreRejectedWithoutLeakingTheSecret() {
        Map<String, Object> properties = completeProperties(false);
        properties.put("haoemr.production.signing.private-key-ref", "never-log-this-secret");
        properties.put("haoemr.production.identity.issuer-uri", "http://idp.hospital.example");
        MockEnvironment environment = productionEnvironment(properties);

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(ApplicationContextException.class)
                .hasMessageContaining("must use env:// or file://")
                .hasMessageContaining("must be an absolute https URI")
                .hasMessageNotContaining("never-log-this-secret");
    }

    @Test
    void givenAnEnvironmentSecretReferenceWithoutAProvisionedTarget_whenBootstrapping_thenItFailsClosed() {
        Map<String, Object> properties = completeProperties(false);
        properties.remove("TEST_STORAGE_SECRET");
        MockEnvironment environment = productionEnvironment(properties);

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(ApplicationContextException.class)
                .hasMessageContaining("storage.secret-key-ref: referenced environment secret is unavailable");
    }

    @Test
    void givenAReadableMountedSecretFile_whenBootstrapping_thenTheFileReferenceIsAccepted() throws Exception {
        Path mountedSecret = temporaryDirectory.resolve("ca-private-key");
        Files.writeString(mountedSecret, "synthetic-test-secret");
        Map<String, Object> properties = completeProperties(false);
        properties.put("haoemr.production.signing.private-key-ref", mountedSecret.toUri().toString());
        MockEnvironment environment = productionEnvironment(properties);

        assertThatCode(() -> processor.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void givenAiEnabledWithoutItsControlledConfiguration_whenBootstrapping_thenItFailsClosed() {
        Map<String, Object> properties = completeProperties(false);
        properties.put("haoemr.production.ai.enabled", "true");
        MockEnvironment environment = productionEnvironment(properties);

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(ApplicationContextException.class)
                .hasMessageContaining("ai.base-uri")
                .hasMessageContaining("ai.model-id")
                .hasMessageContaining("ai.api-key-ref");
    }

    @Test
    void givenCompleteOnPremProductionReferences_whenBootstrapping_thenConfigurationGatePasses() {
        MockEnvironment environment = productionEnvironment(completeProperties(false));

        assertThatCode(() -> processor.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void givenCompleteAiConfiguration_whenBootstrapping_thenConfigurationGatePasses() {
        MockEnvironment environment = productionEnvironment(completeProperties(true));

        assertThatCode(() -> processor.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void givenResourceServerIssuerOrAudienceDrift_whenBootstrapping_thenConfigurationIsRejected() {
        Map<String, Object> properties = completeProperties(false);
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://other-idp.example/oidc");
        properties.put("spring.security.oauth2.resourceserver.jwt.audiences", "other-application");
        MockEnvironment environment = productionEnvironment(properties);

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
                .isInstanceOf(ApplicationContextException.class)
                .hasMessageContaining("jwt.issuer-uri: must match")
                .hasMessageContaining("jwt.audiences: must match");
    }

    private static MockEnvironment productionEnvironment(Map<String, Object> properties) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addFirst(new MapPropertySource("test-production", properties));
        return environment;
    }

    private static Map<String, Object> completeProperties(boolean aiEnabled) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("haoemr.synthetic-dataset-enabled", "false");
        properties.put("spring.datasource.url", "jdbc:postgresql://db.hospital.example/haoemr");
        properties.put("spring.datasource.username", "haoemr_app");
        properties.put("haoemr.production.deployment-id", "hospital-a-prod");
        properties.put("haoemr.production.allow-development-identity", "false");
        properties.put("haoemr.production.data-residency", "ON_PREM_ONLY");
        properties.put("haoemr.production.identity.issuer-uri", "https://idp.hospital.example/oidc");
        properties.put("haoemr.production.identity.audience", "haoemr-clinical");
        properties.put("haoemr.production.identity.required-acr", "urn:haoemr:acr:mfa");
        properties.put("haoemr.production.identity.mfa-required", "true");
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://idp.hospital.example/oidc");
        properties.put("spring.security.oauth2.resourceserver.jwt.audiences", "haoemr-clinical");
        properties.put("haoemr.production.signing.ca-base-uri", "https://ca.hospital.example/api");
        properties.put("haoemr.production.signing.timestamp-uri", "https://tsa.hospital.example/api");
        properties.put("haoemr.production.signing.client-certificate-ref", "env://TEST_CA_CERT");
        properties.put("haoemr.production.signing.private-key-ref", "env://TEST_CA_KEY");
        properties.put("haoemr.production.signing.trust-anchor-ref", "env://TEST_CA_TRUST");
        properties.put("haoemr.production.encryption.kms-key-uri", "kms://hospital-kms/haoemr-master");
        properties.put("haoemr.production.encryption.database-key-ref", "env://TEST_DATABASE_KEY");
        properties.put("haoemr.production.encryption.object-key-ref", "env://TEST_OBJECT_KEY");
        properties.put("haoemr.production.storage.endpoint", "https://objects.hospital.example");
        properties.put("haoemr.production.storage.bucket", "haoemr-records");
        properties.put("haoemr.production.storage.object-lock-required", "true");
        properties.put("haoemr.production.storage.access-key-ref", "env://TEST_STORAGE_ACCESS");
        properties.put("haoemr.production.storage.secret-key-ref", "env://TEST_STORAGE_SECRET");
        properties.put("haoemr.archive.ocr-endpoint", "https://ocr.hospital.example/v1/extract");
        properties.put("haoemr.archive.cda-validation-endpoint", "https://hie.hospital.example/v1/cda/validate");
        properties.put("haoemr.archive.malware-scanner.host", "clamav.hospital.internal");
        properties.put("haoemr.production.integration.truststore-ref", "env://TEST_INTEGRATION_TRUST");
        properties.put("haoemr.production.integration.client-certificate-ref", "env://TEST_INTEGRATION_CERT");
        properties.put("haoemr.production.database.password-ref", "env://TEST_DATABASE_PASSWORD");
        properties.put("haoemr.production.ai.enabled", Boolean.toString(aiEnabled));
        properties.put("TEST_CA_CERT", "resolved");
        properties.put("TEST_CA_KEY", "resolved");
        properties.put("TEST_CA_TRUST", "resolved");
        properties.put("TEST_DATABASE_KEY", "resolved");
        properties.put("TEST_OBJECT_KEY", "resolved");
        properties.put("TEST_STORAGE_ACCESS", "resolved");
        properties.put("TEST_STORAGE_SECRET", "resolved");
        properties.put("TEST_INTEGRATION_TRUST", "resolved");
        properties.put("TEST_INTEGRATION_CERT", "resolved");
        properties.put("TEST_DATABASE_PASSWORD", "resolved");
        if (aiEnabled) {
            properties.put("haoemr.production.ai.base-uri", "https://model.hospital.example/v1");
            properties.put("haoemr.production.ai.model-id", "deepseek-local-approved");
            properties.put("haoemr.production.ai.api-key-ref", "env://TEST_AI_KEY");
            properties.put("haoemr.production.ai.residency", "ON_PREM_ONLY");
            properties.put("TEST_AI_KEY", "resolved");
        }
        return properties;
    }
}
