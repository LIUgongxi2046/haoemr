package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class TertiaryBusinessConfigurationDatasetImportTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void catalogProvidesEightDistinctTertiaryHospitalProfilesForEveryBusinessConfigurationType() {
        List<TertiaryBusinessConfigurationCatalog.ConfigurationSeed> configurations =
                TertiaryBusinessConfigurationCatalog.configurations();

        assertThat(configurations).hasSize(32)
                .extracting(TertiaryBusinessConfigurationCatalog.ConfigurationSeed::runtimeId)
                .doesNotHaveDuplicates();
        assertThat(configurations)
                .extracting(TertiaryBusinessConfigurationCatalog.ConfigurationSeed::runtimeKey)
                .doesNotHaveDuplicates();

        Map<String, Long> counts = configurations.stream().collect(Collectors.groupingBy(
                TertiaryBusinessConfigurationCatalog.ConfigurationSeed::configType,
                Collectors.counting()));
        assertThat(counts).containsEntry("WORKFLOW", 8L)
                .containsEntry("FORM_TEMPLATE", 8L)
                .containsEntry("RULE", 8L)
                .containsEntry("SCOPE", 8L);

        for (String type : List.of("WORKFLOW", "FORM_TEMPLATE", "RULE", "SCOPE")) {
            List<TertiaryBusinessConfigurationCatalog.ConfigurationSeed> profiles = configurations.stream()
                    .filter(seed -> type.equals(seed.configType())).toList();
            assertThat(profiles).extracting(seed -> String.valueOf(seed.payload().get("profile_code")))
                    .doesNotHaveDuplicates().allSatisfy(code -> assertThat(code).isNotBlank());
            assertThat(profiles).allSatisfy(seed -> assertThat(seed.payload())
                    .containsEntry("fixture_source", "tertiary-hospital-business-config-v3")
                    .containsEntry("hospital_level", "三级甲等")
                    .containsEntry("organization", "江城大学附属医院")
                    .containsKeys("controls", "evidence", "data_policy"));
        }

        assertThat(configurations.stream().filter(seed -> "WORKFLOW".equals(seed.configType())))
                .allSatisfy(seed -> assertThat((List<?>) seed.payload().get("nodes")).hasSizeGreaterThanOrEqualTo(10));
        assertThat(configurations.stream().filter(seed -> "FORM_TEMPLATE".equals(seed.configType())))
                .allSatisfy(seed -> assertThat((List<?>) seed.payload().get("fields")).hasSizeGreaterThanOrEqualTo(12));
        assertThat(configurations.stream().filter(seed -> "RULE".equals(seed.configType())))
                .allSatisfy(seed -> assertThat((List<?>) seed.payload().get("rules")).hasSizeGreaterThanOrEqualTo(4));
        assertThat(configurations.stream().filter(seed -> "SCOPE".equals(seed.configType())))
                .allSatisfy(seed -> assertThat((List<?>) seed.payload().get("permissions")).hasSizeGreaterThanOrEqualTo(5));
    }

    @Test
    void devSyntheticProfileImportsTheCompleteTertiaryHospitalBusinessConfigurationDataset() {
        for (String type : List.of("WORKFLOW", "FORM_TEMPLATE", "RULE", "SCOPE")) {
            Map<String, Integer> imported = jdbc.sql("""
                    select count(*) as configurations,
                      count(distinct payload->>'profile_code') as profiles,
                      count(*) filter (where status = 'ACTIVE' and validation_state = 'VALID'
                        and approval_state = 'APPROVED' and published_at is not null
                        and payload->>'hospital_level' = '三级甲等'
                        and payload->>'organization' = '江城大学附属医院') as complete
                    from config_item
                    where tenant_id = :tenant and config_type = :type
                      and status = 'ACTIVE'
                      and payload->>'fixture_source' = 'tertiary-hospital-business-config-v3'
                    """).param("tenant", SyntheticDataImporter.TENANT_ID).param("type", type)
                    .query((rs, row) -> Map.of(
                            "configurations", rs.getInt("configurations"),
                            "profiles", rs.getInt("profiles"),
                            "complete", rs.getInt("complete")))
                    .single();
            assertThat(imported).containsEntry("configurations", 8)
                    .containsEntry("profiles", 8).containsEntry("complete", 8);
        }

        Integer revisions = jdbc.sql("""
                select count(distinct item.config_id)
                from config_item item
                join config_item_revision revision on revision.tenant_id = item.tenant_id
                  and revision.config_id = item.config_id
                where item.tenant_id = :tenant
                  and item.payload->>'fixture_source' = 'tertiary-hospital-business-config-v3'
                  and item.config_key like 'runtime-%'
                """).param("tenant", SyntheticDataImporter.TENANT_ID).query(Integer.class).single();
        assertThat(revisions).isEqualTo(32);

        Integer demoRows = jdbc.sql("""
                select count(*) from config_item
                where tenant_id = :tenant and config_type in ('WORKFLOW','FORM_TEMPLATE','RULE','SCOPE')
                  and (lower(config_key) like '%demo%' or lower(display_name) like '%demo%'
                    or display_name like '%演示%' or display_name like '%示例%')
                """).param("tenant", SyntheticDataImporter.TENANT_ID).query(Integer.class).single();
        assertThat(demoRows).isZero();
    }
}
