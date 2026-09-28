package org.haoemr.installation;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Upgrade a populated V388 fixture without editing any applied migration or existing clinical facts. */
final class ConfigurationInstallationUpgradeTest {
    @Test void additiveMigrationPreservesExistingRecordsAndHistoricalChecksums() throws Exception {
        String url=System.getenv("HAOEMR_UPGRADE_DB_URL");assumeTrue(url!=null);
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/haoemr_iteration_[a-z_]+$");
        var source=new DriverManagerDataSource(url,System.getenv().getOrDefault("HAOEMR_DB_USER","liuhaoxian"),"");
        var jdbc=JdbcClient.create(source);
        assertThat(jdbc.sql("select count(*) from pg_tables where schemaname='public'").query(Long.class).single()).isZero();
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("388").load().migrate();
        UUID tenant=UUID.randomUUID();
        jdbc.sql("insert into tenant(tenant_id,tenant_code,display_name,status) values (:id,:code,'升级保留医院','ACTIVE')").param("id",tenant).param("code",tenant.toString()).update();
        jdbc.sql("insert into patient(tenant_id,patient_id,display_name,sex_code,birth_date,status) values (:tenant,:id,'合成历史患者','UNKNOWN','1980-01-01','ACTIVE')").param("tenant",tenant).param("id",UUID.randomUUID()).update();
        var before=snapshot(jdbc);
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);flyway.validate();
        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.sql("select count(*) from configuration_pack_operation").query(Long.class).single()).isZero();
        Files.writeString(Path.of("output/iteration-20260910/09-upgrade-evidence.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("prior_data_and_checksums_unchanged",true,"before",before,"added_version","389")));
    }
    private Map<String,String> snapshot(JdbcClient jdbc){
        return Map.of("tenants",jdbc.sql("select coalesce(jsonb_agg(to_jsonb(t) order by tenant_id),'[]')::text from tenant t").query(String.class).single(),
            "patients",jdbc.sql("select coalesce(jsonb_agg(to_jsonb(p) order by patient_id),'[]')::text from patient p").query(String.class).single(),
            "migrations",jdbc.sql("select jsonb_agg(jsonb_build_object('version',version,'checksum',checksum) order by installed_rank)::text from flyway_schema_history where version::int<=388").query(String.class).single());
    }
}
