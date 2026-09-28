package org.haoemr.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class SyntheticFixtureIdentityTest {
    @Test void seededStableIdsMeetTheExistingWireUuidContract() {
        String url=System.getenv("HAOEMR_INSTALL_DB_URL");assumeTrue(url!=null);
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/haoemr_iteration_[a-z_]+$");
        var jdbc=JdbcClient.create(new DriverManagerDataSource(url,"liuhaoxian",""));
        var ids=jdbc.sql("select overlay(overlay(md5('tertiary-audit:' || n) placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid::text from generate_series(1,100) n").query(String.class).list();
        assertThat(ids).hasSize(100).doesNotHaveDuplicates();
        assertThat(ids).allMatch(id->id.matches("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}"));
    }
}
