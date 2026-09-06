package org.haoemr.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.haoemr.HaoEmrApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = HaoEmrApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/haoemr_dev",
            "spring.datasource.username=liuhaoxian",
            "spring.datasource.password=",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.hospital.example/oidc",
            "spring.security.oauth2.resourceserver.jwt.audiences=haoemr-clinical",
            "haoemr.synthetic-dataset-enabled=false",
            "haoemr.production.deployment-id=c01-prod-security-test",
            "haoemr.production.allow-development-identity=false",
            "haoemr.production.data-residency=ON_PREM_ONLY",
            "haoemr.production.database.password-ref=env://TEST_DATABASE_PASSWORD",
            "haoemr.production.identity.issuer-uri=https://idp.hospital.example/oidc",
            "haoemr.production.identity.audience=haoemr-clinical",
            "haoemr.production.identity.required-acr=urn:haoemr:acr:mfa",
            "haoemr.production.identity.mfa-required=true",
            "haoemr.production.signing.ca-base-uri=https://ca.hospital.example/api",
            "haoemr.production.signing.timestamp-uri=https://tsa.hospital.example/api",
            "haoemr.production.signing.client-certificate-ref=env://TEST_CA_CERT",
            "haoemr.production.signing.private-key-ref=env://TEST_CA_KEY",
            "haoemr.production.signing.trust-anchor-ref=env://TEST_CA_TRUST",
            "haoemr.production.encryption.kms-key-uri=kms://hospital-kms/haoemr-master",
            "haoemr.production.encryption.database-key-ref=env://TEST_DATABASE_KEY",
            "haoemr.production.encryption.object-key-ref=env://TEST_OBJECT_KEY",
            "haoemr.production.storage.endpoint=https://objects.hospital.example",
            "haoemr.production.storage.bucket=haoemr-records",
            "haoemr.production.storage.object-lock-required=true",
            "haoemr.production.storage.access-key-ref=env://TEST_STORAGE_ACCESS",
            "haoemr.production.storage.secret-key-ref=env://TEST_STORAGE_SECRET",
            "haoemr.archive.ocr-endpoint=https://ocr.hospital.example/api",
            "haoemr.archive.cda-validation-endpoint=https://cda.hospital.example/api",
            "haoemr.archive.malware-scanner.host=malware-scan.hospital.example",
            "haoemr.production.integration.truststore-ref=env://TEST_INTEGRATION_TRUST",
            "haoemr.production.integration.client-certificate-ref=env://TEST_INTEGRATION_CERT",
            "haoemr.production.ai.enabled=false",
            "TEST_DATABASE_PASSWORD=resolved",
            "TEST_CA_CERT=resolved",
            "TEST_CA_KEY=resolved",
            "TEST_CA_TRUST=resolved",
            "TEST_DATABASE_KEY=resolved",
            "TEST_OBJECT_KEY=resolved",
            "TEST_STORAGE_ACCESS=resolved",
            "TEST_STORAGE_SECRET=resolved",
            "TEST_INTEGRATION_TRUST=resolved",
            "TEST_INTEGRATION_CERT=resolved"
        })
@ActiveProfiles("prod")
@Import(ProdSecurityApiTest.SecurityTestConfiguration.class)
final class ProdSecurityApiTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000c101");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000c102");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000c103");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000c104");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000c105");
    private static final String SUBJECT = "c01-production-clinician";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void createProductionIdentityFixture() {
        jdbc.sql("insert into tenant(tenant_id, tenant_code, display_name, status) values (:id, 'C01-PROD-TEST', 'C01 production test', 'ACTIVE') on conflict (tenant_id) do update set status = 'ACTIVE'")
                .param("id", TENANT).update();
        jdbc.sql("insert into organization(tenant_id, organization_id, organization_code, display_name, status) values (:tenant, :id, 'C01-ORG', 'C01 organization', 'ACTIVE') on conflict (tenant_id, organization_id) do update set status = 'ACTIVE'")
                .param("tenant", TENANT).param("id", ORGANIZATION).update();
        jdbc.sql("insert into facility(tenant_id, organization_id, facility_id, facility_code, display_name, status) values (:tenant, :org, :id, 'C01-FAC', 'C01 facility', 'ACTIVE') on conflict (tenant_id, facility_id) do update set status = 'ACTIVE'")
                .param("tenant", TENANT).param("org", ORGANIZATION).param("id", FACILITY).update();
        jdbc.sql("insert into app_user(tenant_id, user_id, external_subject, display_name, status) values (:tenant, :id, :subject, 'C01 clinician', 'ACTIVE') on conflict (tenant_id, user_id) do update set status = 'ACTIVE'")
                .param("tenant", TENANT).param("id", USER).param("subject", SUBJECT).update();
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, valid_until, status)
                values (:tenant, :role, :user, :org, :facility, 'CLINICIAN', now() - interval '1 minute', null, 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do update
                  set valid_from = excluded.valid_from, valid_until = null, status = 'ACTIVE'
                """).param("tenant", TENANT).param("role", ROLE).param("user", USER)
                .param("org", ORGANIZATION).param("facility", FACILITY).update();
    }

    @AfterEach
    void removeProductionIdentityFixture() {
        jdbc.sql("delete from role_assignment where tenant_id = :tenant and role_assignment_id = :role")
                .param("tenant", TENANT).param("role", ROLE).update();
        jdbc.sql("delete from app_user where tenant_id = :tenant and user_id = :user")
                .param("tenant", TENANT).param("user", USER).update();
        jdbc.sql("delete from workforce_person where tenant_id = :tenant and person_id = :person")
                .param("tenant", TENANT).param("person", USER).update();
        jdbc.sql("delete from facility where tenant_id = :tenant and facility_id = :facility")
                .param("tenant", TENANT).param("facility", FACILITY).update();
        jdbc.sql("delete from organization where tenant_id = :tenant and organization_id = :org")
                .param("tenant", TENANT).param("org", ORGANIZATION).update();
        jdbc.sql("delete from tenant where tenant_id = :tenant").param("tenant", TENANT).update();
    }

    @Test
    void givenNoBearerToken_whenCallingClinicalApi_thenJson401IsReturned() throws Exception {
        HttpResponse<String> response = get("/api/v1/c01-identity-probe", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("AUTHENTICATION_REQUIRED");
        assertThat(response.body()).doesNotContain(SUBJECT);
    }

    @Test
    void givenRejectedBearerToken_whenCallingClinicalApi_thenJson401IsReturned() throws Exception {
        HttpResponse<String> response = get("/api/v1/c01-identity-probe", "rejected-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("AUTHENTICATION_REQUIRED");
    }

    @Test
    void givenVerifiedMfaBearerToken_whenCallingClinicalApi_thenDatabaseIdentityIsUsed() throws Exception {
        HttpResponse<String> response = get("/api/v1/c01-identity-probe", "verified-token");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(TENANT.toString(), USER.toString(), ROLE.toString());
        assertThat(response.body()).doesNotContain("SUPER_ADMIN");
    }

    @Test
    void givenVerifiedTokenWithoutRequiredMfa_whenCallingClinicalApi_thenMappingFailsClosed() throws Exception {
        HttpResponse<String> response = get("/api/v1/c01-identity-probe", "password-only-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("AUTHENTICATION_REQUIRED");
    }

    @Test
    void givenNoBearerToken_whenCallingReadiness_thenOnlyReadinessIsPublic() throws Exception {
        assertThat(get("/api/v1/system/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get("/not-an-api", "verified-token").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfiguration {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case "verified-token" -> verifiedJwt("urn:haoemr:acr:mfa");
                case "password-only-token" -> verifiedJwt("urn:haoemr:acr:password");
                default -> throw new BadJwtException("synthetic decoder rejection");
            };
        }

        @Bean
        IdentityProbeController identityProbeController(ClinicalIdentityProvider identities) {
            return new IdentityProbeController(identities);
        }

        private static Jwt verifiedJwt(String acr) {
            return Jwt.withTokenValue("verified-token")
                    .header("alg", "RS256")
                    .subject(SUBJECT)
                    .issuer("https://idp.hospital.example/oidc")
                    .audience(List.of("haoemr-clinical"))
                    .issuedAt(Instant.now().minusSeconds(60))
                    .expiresAt(Instant.now().plusSeconds(300))
                    .claim("tenant_id", TENANT.toString())
                    .claim("acr", acr)
                    .claim("roles", List.of("SUPER_ADMIN"))
                    .build();
        }
    }

    @RestController
    static class IdentityProbeController {

        private final ClinicalIdentityProvider identities;

        IdentityProbeController(ClinicalIdentityProvider identities) {
            this.identities = identities;
        }

        @GetMapping("/api/v1/c01-identity-probe")
        ClinicalIdentity currentIdentity() {
            return identities.current(null);
        }
    }
}
