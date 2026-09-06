package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class TertiaryDataCenterDatasetImportTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void externalDatasetContainsCompleteTertiaryHospitalBusinessCoverage() throws Exception {
        String json = Files.readString(
                Path.of("samples/data/tertiary-data-center-business-v2.json"), StandardCharsets.UTF_8);
        TertiaryDataCenterDataset dataset = TertiaryDataCenterDataset.parse(objectMapper, json);

        assertThat(dataset.datasetVersion()).isEqualTo("tertiary-data-center-business-v2");
        assertThat(dataset.rows("configurations")).hasSize(38);
        assertThat(dataset.rows("source_systems")).hasSize(5);
        assertThat(dataset.rows("field_mappings")).hasSize(17);
        assertThat(dataset.rows("migration_batches")).hasSize(6);
        assertThat(dataset.rows("migration_checkpoints")).hasSize(8);
        assertThat(dataset.rows("quality_rules")).hasSize(7);
        assertThat(dataset.rows("quality_evaluations")).hasSize(16);
        assertThat(dataset.rows("research_cohorts")).hasSize(5);
        assertThat(dataset.rows("research_snapshots")).hasSize(6);
        assertThat(dataset.rows("dataset_requests")).hasSize(4);
        assertThat(dataset.rows("metric_snapshots")).hasSize(6);
    }

    @Test
    void devSyntheticProfilePersistsTheFullDataCenterDatasetAndArchivesSupersededDemoRows() {
        Map<String, Integer> configurations = jdbc.sql("""
                select
                  count(*) filter (where config_type = 'INTEGRATION_CONNECTOR') as connectors,
                  count(*) filter (where config_type = 'DEVICE_CATALOG') as devices,
                  count(*) filter (where config_type = 'RESEARCH_PROJECT') as projects,
                  count(*) filter (where config_type = 'INTEGRATION_INCIDENT') as incidents,
                  count(*) filter (where payload->>'hospital_level' = '三级甲等'
                    and payload->>'organization' = '江城大学附属医院') as complete
                from config_item
                where tenant_id = :tenant and status = 'ACTIVE'
                  and payload->>'fixture_source' = 'tertiary-data-center-business-v2'
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "connectors", rs.getInt("connectors"), "devices", rs.getInt("devices"),
                        "projects", rs.getInt("projects"), "incidents", rs.getInt("incidents"),
                        "complete", rs.getInt("complete"))).single();
        assertThat(configurations).containsEntry("connectors", 10).containsEntry("devices", 12)
                .containsEntry("projects", 8).containsEntry("incidents", 8).containsEntry("complete", 38);

        Integer superseded = jdbc.sql("""
                select count(*) from config_item
                where tenant_id = :tenant and payload->>'fixture_source' = 'tertiary-data-center-v1'
                  and status <> 'ARCHIVED'
                """).param("tenant", SyntheticDataImporter.TENANT_ID).query(Integer.class).single();
        assertThat(superseded).isZero();

        Map<String, Integer> operational = jdbc.sql("""
                select
                  (select count(*) from source_system_inventory where tenant_id = :tenant) as sources,
                  (select count(*) from source_field_mapping where tenant_id = :tenant) as mappings,
                  (select count(*) from historical_migration_batch where tenant_id = :tenant) as batches,
                  (select count(*) from historical_migration_checkpoint where tenant_id = :tenant) as checkpoints,
                  (select count(*) from data_quality_rule where tenant_id = :tenant) as rules,
                  (select count(*) from data_quality_evaluation where tenant_id = :tenant) as evaluations,
                  (select count(*) from research_cohort where tenant_id = :tenant) as cohorts,
                  (select count(*) from research_cohort_snapshot where tenant_id = :tenant) as snapshots,
                  (select count(*) from research_dataset_request where tenant_id = :tenant) as requests
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "sources", rs.getInt("sources"), "mappings", rs.getInt("mappings"),
                        "batches", rs.getInt("batches"), "checkpoints", rs.getInt("checkpoints"),
                        "rules", rs.getInt("rules"), "evaluations", rs.getInt("evaluations"),
                        "cohorts", rs.getInt("cohorts"), "snapshots", rs.getInt("snapshots"),
                        "requests", rs.getInt("requests"))).single();
        assertThat(operational.get("sources")).isGreaterThanOrEqualTo(10);
        assertThat(operational.get("mappings")).isGreaterThanOrEqualTo(24);
        assertThat(operational.get("batches")).isGreaterThanOrEqualTo(8);
        assertThat(operational.get("checkpoints")).isGreaterThanOrEqualTo(10);
        assertThat(operational.get("rules")).isGreaterThanOrEqualTo(12);
        assertThat(operational.get("evaluations")).isGreaterThanOrEqualTo(18);
        assertThat(operational.get("cohorts")).isGreaterThanOrEqualTo(8);
        assertThat(operational.get("snapshots")).isGreaterThanOrEqualTo(8);
        assertThat(operational.get("requests")).isGreaterThanOrEqualTo(6);
    }

    @Test
    void integrationAndResearchAggregatesAreStoredInDatabaseDimensions() {
        Integer operationalConnectors = jdbc.sql("""
                select count(*) from config_item
                where tenant_id = :tenant and config_type = 'INTEGRATION_CONNECTOR'
                  and status = 'ACTIVE' and (payload->>'message_volume_24h')::bigint > 0
                  and coalesce(payload->>'operational_status', '') <> ''
                """).param("tenant", SyntheticDataImporter.TENANT_ID).query(Integer.class).single();
        Integer researchSeries = jdbc.sql("""
                select count(*) from metric_snapshot
                where tenant_id = :tenant and metric_type = 'RESEARCH_STATS'
                  and dimension->>'group' in ('AGE_DISTRIBUTION', 'TREND')
                """).param("tenant", SyntheticDataImporter.TENANT_ID).query(Integer.class).single();

        assertThat(operationalConnectors).isEqualTo(10);
        assertThat(researchSeries).isEqualTo(6);
    }
}
