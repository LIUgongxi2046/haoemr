package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev-synthetic")
final class SyntheticCredentialSynchronizationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SyntheticDataImporter importer;

    @Test
    void resynchronizesExperiencePasswordAndClearsAStaleLock() {
        jdbc.sql("""
                update dev_user_credential
                set password_hash = :password_hash, failed_attempts = 5,
                  locked_until = now() + interval '15 minutes', updated_at = now()
                where tenant_id = :tenant and user_id = :user
                """)
                .param("password_hash", new BCryptPasswordEncoder(12).encode("stale-password"))
                .param("tenant", SyntheticDataImporter.TENANT_ID)
                .param("user", SyntheticDataImporter.USER_ID)
                .update();

        importer.upsertDevelopmentCredential();

        Credential credential = jdbc.sql("""
                select username, password_hash, failed_attempts, locked_until
                from dev_user_credential
                where tenant_id = :tenant and user_id = :user
                """)
                .param("tenant", SyntheticDataImporter.TENANT_ID)
                .param("user", SyntheticDataImporter.USER_ID)
                .query((resultSet, rowNumber) -> new Credential(
                        resultSet.getString("username"),
                        resultSet.getString("password_hash"),
                        resultSet.getInt("failed_attempts"),
                        resultSet.getObject("locked_until", OffsetDateTime.class)))
                .single();

        assertThat(credential.username()).isEqualTo("linwei");
        assertThat(new BCryptPasswordEncoder(12).matches("HaoEMR-dev!", credential.passwordHash())).isTrue();
        assertThat(credential.failedAttempts()).isZero();
        assertThat(credential.lockedUntil()).isNull();
    }

    private record Credential(String username, String passwordHash, int failedAttempts, OffsetDateTime lockedUntil) {}
}
