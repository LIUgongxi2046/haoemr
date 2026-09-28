package org.haoemr.organization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Opt-in deployment command, never an anonymous HTTP endpoint or a development identity provider. */
@Component
@ConditionalOnProperty(name="haoemr.bootstrap.enabled",havingValue="true")
final class FirstAdministratorBootstrapRunner implements ApplicationRunner {
    private final FirstAdministratorBootstrap bootstrap;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    FirstAdministratorBootstrapRunner(FirstAdministratorBootstrap bootstrap,Environment environment,ConfigurableApplicationContext context) {
        this.bootstrap=bootstrap;this.environment=environment;this.context=context;
    }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if(!environment.matchesProfiles("prod")||environment.matchesProfiles("dev-synthetic")
            ||!"none".equals(environment.getProperty("spring.main.web-application-type")))
            throw new IllegalStateException("Bootstrap requires production configuration and a non-web command process");
        // ProductionEnvironmentPostProcessor has already validated mandatory configuration before bean creation.
        Path input=Path.of(environment.getRequiredProperty("haoemr.bootstrap.request-file"));
        if(!input.isAbsolute()||!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)||Files.size(input)>16384)
            throw new IllegalArgumentException("Bootstrap requires an absolute regular JSON request file of at most 16 KB");
        ObjectMapper mapper=tools.jackson.databind.json.JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        var request=mapper.readValue(Files.readString(input),FirstAdministratorBootstrap.Request.class);
        var result=bootstrap.initialize(request,environment.getRequiredProperty("haoemr.production.identity.issuer-uri"));
        // Identifiers only: no subject, password, token or credential references in output.
        System.out.println("Bootstrap identity mapping: "+mapper.writeValueAsString(result));
        context.close();
    }
}
