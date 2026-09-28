package org.haoemr.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Opt-in: dedicated empty database only, never the default development database. */
final class CleanInstallationMigrationTest {
    @Test
    void existingMigrationChainCreatesNoHospitalOrClinicalFixtures() throws Exception {
        String url = System.getenv("HAOEMR_CLEAN_DB_URL");
        assumeTrue(url != null, "Set HAOEMR_CLEAN_DB_URL to a dedicated empty iteration database");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/haoemr_iteration_[a-z_]+$");
        var source = new DriverManagerDataSource(url, System.getenv().getOrDefault("HAOEMR_DB_USER", "liuhaoxian"), "");
        var jdbc = JdbcClient.create(source);
        assertThat(jdbc.sql("select count(*) from information_schema.tables where table_schema='public'")
                .query(Long.class).single()).isZero();
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        var result = flyway.migrate();
        flyway.validate();
        var tables = jdbc.sql("select tablename from pg_tables where schemaname='public' order by tablename")
                .query(String.class).list();
        var counts = new LinkedHashMap<String, Long>();
        for (String table : tables) {
            assertThat(table).matches("[a-z_][a-z0-9_]*");
            counts.put(table, jdbc.sql("select count(*) from \"" + table + "\"").query(Long.class).single());
        }
        Files.createDirectories(Path.of("output/iteration-20260910"));
        Files.writeString(Path.of("output/iteration-20260910/empty-migration-inventory-v" + flyway.info().current().getVersion() + ".json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(counts));
        assertThat(result.migrationsExecuted).isPositive();
        for (String table : new String[]{"tenant", "organization", "app_user", "patient", "encounter",
                "clinical_document", "clinical_document_version", "clinical_order", "clinical_document_template"}) {
            assertThat(counts).containsKey(table);
            assertThat(counts.get(table)).as(table + " must be empty").isZero();
        }
    }
}
