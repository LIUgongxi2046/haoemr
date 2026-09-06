package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class TertiaryBusinessConfigurationImportTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void devSyntheticProfileImportsCompleteTertiaryHospitalBusinessConfiguration() {
        Map<String, Integer> payloadSizes = jdbc.sql("""
                select
                  max(case when config_type = 'WORKFLOW' then jsonb_array_length(payload->'nodes') end) as workflow_nodes,
                  max(case when config_type = 'FORM_TEMPLATE' then jsonb_array_length(payload->'fields') end) as form_fields,
                  max(case when config_type = 'RULE' then jsonb_array_length(payload->'rules') end) as rules,
                  max(case when config_type = 'SCOPE' then jsonb_array_length(payload->'permissions') end) as permissions
                from config_item
                where tenant_id = :tenant and status = 'ACTIVE'
                  and config_key in ('runtime-workflow-consult-v1', 'runtime-form-record-v1',
                    'runtime-rule-safety-v1', 'runtime-scope-clinical-v1')
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "workflow", rs.getInt("workflow_nodes"),
                        "form", rs.getInt("form_fields"),
                        "rules", rs.getInt("rules"),
                        "permissions", rs.getInt("permissions")))
                .single();
        assertThat(payloadSizes).containsEntry("workflow", 14).containsEntry("form", 26)
                .containsEntry("rules", 14).containsEntry("permissions", 14);

        Integer activePacks = jdbc.sql("""
                select count(*) from capability_pack
                where tenant_id = :tenant and status = 'ACTIVE' and pack_code like 'SYN-%'
                """).param("tenant", SyntheticDataImporter.TENANT_ID).query(Integer.class).single();
        Integer activeCompositions = jdbc.sql("""
                select count(*) from config_item
                where tenant_id = :tenant and config_type = 'CAPABILITY_PACK_COMPOSITION'
                  and status = 'ACTIVE' and config_key like 'composition-syn-%'
                """).param("tenant", SyntheticDataImporter.TENANT_ID).query(Integer.class).single();
        assertThat(activePacks).isGreaterThanOrEqualTo(15);
        assertThat(activeCompositions).isGreaterThanOrEqualTo(15);

        Map<String, Integer> mockProfiles = jdbc.sql("""
                select count(*) as profiles, count(distinct payload->>'workbench_id') as workbenches,
                  count(*) filter (where payload->>'hospital_level' = '三级甲等'
                    and payload->>'organization' = '江城大学附属医院'
                    and coalesce(payload->>'interface_code', '') <> ''
                    and coalesce(payload->>'manual_fallback', '') <> ''
                    and payload->>'fixture_source' = 'tertiary-business-generator-v3'
                    and payload->>'generation_method' = 'DETERMINISTIC_SEEDED'
                    and payload->>'generator_version' = 'tertiary-business-cn-v3'
                    and (payload->>'default_record_count')::int between 12 and 200
                    and payload->'record_count_range' = '[12, 200]'::jsonb
                    and payload->>'contains_real_phi' = 'false') as complete_profiles
                from config_item
                where tenant_id = :tenant and config_type = 'MOCK_INTERFACE_PROFILE'
                  and status = 'ACTIVE'
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "profiles", rs.getInt("profiles"),
                        "workbenches", rs.getInt("workbenches"),
                        "complete", rs.getInt("complete_profiles")))
                .single();
        assertThat(mockProfiles).containsEntry("profiles", 12)
                .containsEntry("workbenches", 12).containsEntry("complete", 12);
        Integer retiredAiSimulationProfiles = jdbc.sql("""
                select count(*) from config_item
                where tenant_id = :tenant and config_type = 'MOCK_INTERFACE_PROFILE'
                  and status = 'ARCHIVED'
                  and coalesce(payload->>'workbench_id', '') in (
                    'ai-capture', 'model-connection', 'model-routing')
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query(Integer.class).single();
        assertThat(retiredAiSimulationProfiles)
                .as("AI center pages backed by real model/run APIs must not retain active simulation profiles")
                .isEqualTo(3);

        Map<String, Integer> specialtyCoverage = jdbc.sql("""
                select count(*) as assessments, count(distinct department_id) as departments,
                  count(*) filter (where support_level in ('GENERAL_AVAILABLE','BASIC_CLOSED_LOOP')) as supported,
                  coalesce(sum(cardinality(missing_safety_gates)), 0) as missing_gates,
                  count(*) filter (where evidence_bundle_hash ~ '^[0-9a-f]{64}$'
                    and expires_at > now() + interval '300 days') as valid_evidence
                from department_support_assessment
                where tenant_id = :tenant
                  and department_support_assessment_id between
                    '018f0000-0000-7000-8000-00000000c411'::uuid and
                    '018f0000-0000-7000-8000-00000000c420'::uuid
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "assessments", rs.getInt("assessments"),
                        "departments", rs.getInt("departments"),
                        "supported", rs.getInt("supported"),
                        "missing", rs.getInt("missing_gates"),
                        "evidence", rs.getInt("valid_evidence")))
                .single();
        assertThat(specialtyCoverage).containsEntry("assessments", 16)
                .containsEntry("departments", 16)
                .containsEntry("supported", 16)
                .containsEntry("missing", 0)
                .containsEntry("evidence", 16);
    }

    @Test
    void devSyntheticProfileImportsTertiaryAiEvaluationReleaseFixturesWithoutUuidCollisions() {
        Map<String, Integer> evaluationFixtures = jdbc.sql("""
                select count(*) as evaluations,
                  count(distinct config_id) as ids,
                  count(distinct payload->>'target_agent') as covered_agents,
                  count(*) filter (where payload->>'hospital_level' = '三级甲等'
                    and payload->>'environment' = 'tertiary-hospital-simulation'
                    and status = 'ACTIVE' and validation_state = 'VALID'
                    and payload->>'release_gate' = 'PASSED'
                    and (payload->>'measured_score')::numeric >=
                        (payload->>'pass_threshold')::numeric) as complete
                from config_item
                where tenant_id = :tenant and config_type = 'AGENT_EVAL'
                  and config_key like 'eval-%-v1'
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "evaluations", rs.getInt("evaluations"),
                        "ids", rs.getInt("ids"),
                        "covered_agents", rs.getInt("covered_agents"),
                        "complete", rs.getInt("complete")))
                .single();
        assertThat(evaluationFixtures).containsEntry("evaluations", 19)
                .containsEntry("ids", 19)
                .containsEntry("covered_agents", 14)
                .containsEntry("complete", 19);
    }

    @Test
    void devSyntheticProfileImportsCompleteTertiaryAiModelSkillAndToolCatalogs() {
        Map<String, Integer> catalog = jdbc.sql("""
                select
                  (select count(*) from model_deployment where tenant_id = :tenant
                    and model_code in ('DEEPSEEK-V3-CLINICAL-LOCAL', 'DEEPSEEK-R1-REASONING-LOCAL',
                      'QWEN2.5-VL-MEDICAL', 'GLM4-PATIENT-COMMUNICATION',
                      'QWEN3-EMBEDDING-LOCAL', 'BGE-M3-CLINICAL-RERANK')
                    and status = 'ACTIVE') as active_simulation_models,
                  (select count(*) from model_deployment where tenant_id = :tenant
                    and model_code in ('DEEPSEEK-V3-CLINICAL-LOCAL', 'DEEPSEEK-R1-REASONING-LOCAL',
                      'QWEN2.5-VL-MEDICAL', 'GLM4-PATIENT-COMMUNICATION',
                      'QWEN3-EMBEDDING-LOCAL', 'BGE-M3-CLINICAL-RERANK')
                    and status = 'INACTIVE' and connection_status = 'NOT_CONFIGURED'
                    and evaluation_status = 'REJECTED') as retired_simulation_models,
                  (select count(*) from skill_registry where tenant_id = :tenant
                    and status = 'ACTIVE') as active_skills,
                  (select count(*) from tool_registry where tenant_id = :tenant
                    and status = 'ACTIVE') as active_tools
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "active_simulation_models", rs.getInt("active_simulation_models"),
                        "retired_models", rs.getInt("retired_simulation_models"),
                        "skills", rs.getInt("active_skills"),
                        "tools", rs.getInt("active_tools")))
                .single();
        assertThat(catalog).containsEntry("active_simulation_models", 0)
                .containsEntry("retired_models", 6)
                .containsEntry("skills", 24).containsEntry("tools", 32);
    }

    @Test
    void catalogPayloadsRemainInternallyCompleteAndDeterministic() {
        assertThat(TertiaryBusinessConfigurationCatalog.configurations()).hasSize(32)
                .allSatisfy(seed -> assertThat(seed.payload())
                        .containsKeys("schema_version", "description", "controls", "evidence"));
        assertThat(TertiaryBusinessConfigurationCatalog.capabilityPacks()).hasSize(15)
                .extracting(TertiaryBusinessConfigurationCatalog.CapabilityPackSeed::packCode)
                .doesNotHaveDuplicates();
        assertThat(TertiaryBusinessConfigurationCatalog.specialties()).hasSize(16)
                .allSatisfy(seed -> {
                    assertThat(seed.evidenceHash()).matches("[0-9a-f]{64}");
                    assertThat(seed.modules()).isNotEmpty();
                });
    }

    @Test
    void devSyntheticProfileImportsOperationalTaskPathwayEvidence() {
        Map<String, Integer> configuration = jdbc.sql("""
                select
                  count(*) filter (where config_type = 'CLINICAL_TASK_RULE') as task_rules,
                  count(*) filter (where config_type = 'CLINICAL_PATHWAY') as pathways,
                  count(*) filter (where payload->>'hospital_level' = '三级甲等') as tertiary_complete
                from config_item
                where tenant_id = :tenant and status = 'ACTIVE'
                  and payload->>'fixture_source' = 'tertiary-task-pathway-v1'
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "task_rules", rs.getInt("task_rules"),
                        "pathways", rs.getInt("pathways"),
                        "tertiary_complete", rs.getInt("tertiary_complete")))
                .single();
        assertThat(configuration).containsEntry("task_rules", 5)
                .containsEntry("pathways", 4).containsEntry("tertiary_complete", 9);

        Map<String, Integer> workflowEvidence = jdbc.sql("""
                select
                  (select count(*) from clinical_task where tenant_id = :tenant
                    and task_id between '018f0000-0000-7000-8000-00000000fc01'::uuid
                      and '018f0000-0000-7000-8000-00000000fc06'::uuid) as tasks,
                  (select count(*) from clinical_task_event where tenant_id = :tenant
                    and task_event_id between '018f0000-0000-7000-8000-00000000fd01'::uuid
                      and '018f0000-0000-7000-8000-00000000fd06'::uuid) as events,
                  (select count(*) from clinical_task_team_queue where tenant_id = :tenant
                    and queue_id between '018f0000-0000-7000-8000-00000000fe01'::uuid
                      and '018f0000-0000-7000-8000-00000000fe02'::uuid) as queue_items,
                  (select count(*) from clinical_task_notification where tenant_id = :tenant
                    and notification_id between '018f0000-0000-7000-8000-00000000ff01'::uuid
                      and '018f0000-0000-7000-8000-00000000ff03'::uuid) as notifications
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "tasks", rs.getInt("tasks"), "events", rs.getInt("events"),
                        "queue_items", rs.getInt("queue_items"), "notifications", rs.getInt("notifications")))
                .single();
        assertThat(workflowEvidence).containsEntry("tasks", 6).containsEntry("events", 6)
                .containsEntry("queue_items", 2).containsEntry("notifications", 3);
    }
}
