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
final class TertiaryTaskCenterDataImportTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TertiaryTaskCenterDataImporter importer;

    @Test
    void importsPersistedDataForEveryTaskCenterSecondaryView() {
        Map<String, Integer> coverage = jdbc.sql("""
                select
                  (select count(*) from config_item where tenant_id=:tenant and status='ACTIVE'
                    and config_type='CLINICAL_TASK_RULE'
                    and payload->>'fixture_source'='tertiary-task-center-v2') as rules,
                  (select count(*) from config_item where tenant_id=:tenant and status='ACTIVE'
                    and config_type='CLINICAL_PATHWAY'
                    and payload->>'fixture_source'='tertiary-task-center-v2') as pathways,
                  (select count(*) from clinical_task where tenant_id=:tenant
                    and task_type like 'TCV2_%') as tasks,
                  (select count(distinct encounter.encounter_type) from clinical_task task
                    join encounter on encounter.tenant_id=task.tenant_id and encounter.encounter_id=task.encounter_id
                    where task.tenant_id=:tenant and task.task_type like 'TCV2_%') as care_domains,
                  (select count(*) from clinical_task where tenant_id=:tenant
                    and task_type like 'TCV2_%' and state in ('CLAIMED','IN_PROGRESS')
                    and claimed_by=:author) as collaboration_tasks,
                  (select count(*) from clinical_task_team_queue queue
                    join clinical_task task on task.tenant_id=queue.tenant_id
                      and task.task_id=queue.clinical_task_id
                    where queue.tenant_id=:tenant and task.task_type like 'TCV2_%') as queue_items,
                  (select count(*) from clinical_task_notification notification
                    join clinical_task task on task.tenant_id=notification.tenant_id
                      and task.task_id=notification.task_id
                    where notification.tenant_id=:tenant and task.task_type like 'TCV2_%') as notifications,
                  (select count(*) from clinical_task_in_app_delivery delivery
                    join clinical_task_notification notification on notification.tenant_id=delivery.tenant_id
                      and notification.notification_id=delivery.notification_id
                    join clinical_task task on task.tenant_id=notification.tenant_id
                      and task.task_id=notification.task_id
                    where delivery.tenant_id=:tenant and task.task_type like 'TCV2_%') as in_app_deliveries
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .param("author", SyntheticDataImporter.USER_ID)
                .query((rs, row) -> Map.of(
                        "rules", rs.getInt("rules"),
                        "pathways", rs.getInt("pathways"),
                        "tasks", rs.getInt("tasks"),
                        "domains", rs.getInt("care_domains"),
                        "collaboration", rs.getInt("collaboration_tasks"),
                        "queue", rs.getInt("queue_items"),
                        "notifications", rs.getInt("notifications"),
                        "deliveries", rs.getInt("in_app_deliveries")))
                .single();

        assertThat(coverage).containsEntry("rules", 8).containsEntry("pathways", 8)
                .containsEntry("tasks", 24).containsEntry("domains", 3)
                .containsEntry("collaboration", 10).containsEntry("queue", 10)
                .containsEntry("notifications", 72).containsEntry("deliveries", 24);
    }

    @Test
    void pathwayVersionsContainExecutableStagesTasksAndGovernanceRules() {
        Map<String, Integer> pathwayRules = jdbc.sql("""
                select count(*) as pathways,
                  count(*) filter (where jsonb_array_length(payload->'entry_rules') >= 2
                    and jsonb_array_length(payload->'exclusion_rules') >= 2
                    and jsonb_array_length(payload->'stages') = 4
                    and jsonb_array_length(payload->'variance_rules') >= 6
                    and jsonb_array_length(payload->'completion_rules') >= 3
                    and jsonb_array_length(payload->'exit_rules') >= 3
                    and payload->>'version_immutable_after_publish'='true') as complete
                from config_item
                where tenant_id=:tenant and config_type='CLINICAL_PATHWAY'
                  and payload->>'fixture_source'='tertiary-task-center-v2'
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "pathways", rs.getInt("pathways"),
                        "complete", rs.getInt("complete")))
                .single();
        Map<String, Integer> executable = jdbc.sql("""
                select count(distinct definition.pathway_definition_id) as definitions,
                  count(distinct version.pathway_version_id) as published_versions,
                  count(distinct (stage.pathway_version_id, stage.stage_code)) as stages,
                  count(task.task_code) as stage_tasks,
                  count(*) filter (where task.required) as required_tasks
                from clinical_pathway_definition definition
                join clinical_pathway_version version on version.tenant_id=definition.tenant_id
                  and version.pathway_definition_id=definition.pathway_definition_id
                join clinical_pathway_stage stage on stage.tenant_id=version.tenant_id
                  and stage.pathway_version_id=version.pathway_version_id
                join clinical_pathway_stage_task task on task.tenant_id=stage.tenant_id
                  and task.pathway_version_id=stage.pathway_version_id and task.stage_code=stage.stage_code
                where definition.tenant_id=:tenant and definition.pathway_code in
                  ('SEPSIS-ADULT','AECOPD','T2DM-INPATIENT','HIP-FRACTURE',
                   'ACUTE-APPENDICITIS','CESAREAN-DELIVERY','CATARACT','ACUTE-PANCREATITIS')
                  and version.status='PUBLISHED'
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((rs, row) -> Map.of(
                        "definitions", rs.getInt("definitions"),
                        "versions", rs.getInt("published_versions"),
                        "stages", rs.getInt("stages"),
                        "tasks", rs.getInt("stage_tasks"),
                        "required", rs.getInt("required_tasks")))
                .single();

        assertThat(pathwayRules).containsEntry("pathways", 8).containsEntry("complete", 8);
        assertThat(executable).containsEntry("definitions", 8).containsEntry("versions", 8)
                .containsEntry("stages", 32).containsEntry("tasks", 64)
                .containsEntry("required", 62);
    }

    @Test
    void repeatedImportIsIdempotentForBusinessIdentity() {
        Integer before = fixtureBusinessRowCount();
        importer.importData();
        assertThat(fixtureBusinessRowCount()).isEqualTo(before);
    }

    @Test
    void legacyMd5UuidFixtureIsAuditedAndRetiredInsteadOfBreakingThePublicContract() {
        int inserted = jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id, ward_id,
                  source_type, source_id, task_type, title, risk_level, state, business_state,
                  assigned_user_id, claimed_by, due_at, source_route, row_version, created_at, updated_at)
                select tenant_id, md5('tertiary-task-center-v2:task:1')::uuid,
                  patient_id, encounter_id, facility_id, ward_id, source_type,
                  md5('tertiary-task-center-v2:source:1')::uuid,
                  task_type, title, risk_level, 'PENDING', '旧版仿真待处理',
                  assigned_user_id, null, due_at, source_route, 1, created_at, updated_at
                from clinical_task
                where tenant_id=:tenant and task_id =
                  overlay(overlay(md5('tertiary-task-center-v2:task:1')
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid
                """).param("tenant", SyntheticDataImporter.TENANT_ID).update();
        assertThat(inserted).isEqualTo(1);
        int queued = jdbc.sql("""
                insert into clinical_task_team_queue(
                  tenant_id, queue_id, facility_id, department_id, clinical_task_id,
                  queue_status, enqueued_by, enqueued_at, claimed_by, claimed_at, row_version, created_at)
                select tenant_id, md5('tertiary-task-center-v2:legacy-queue:1')::uuid,
                  facility_id, department_id, md5('tertiary-task-center-v2:task:1')::uuid,
                  'ENQUEUED', enqueued_by, enqueued_at, null, null, 1, created_at
                from clinical_task_team_queue where tenant_id=:tenant
                order by created_at, queue_id limit 1
                """).param("tenant", SyntheticDataImporter.TENANT_ID).update();
        assertThat(queued).isEqualTo(1);

        importer.importData();

        assertThat(jdbc.sql("""
                select state from clinical_task where tenant_id=:tenant
                  and task_id=md5('tertiary-task-center-v2:task:1')::uuid
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query(String.class).single()).isEqualTo("WITHDRAWN");
        assertThat(jdbc.sql("""
                select count(*) from clinical_task_event where tenant_id=:tenant
                  and task_id=md5('tertiary-task-center-v2:task:1')::uuid
                  and event_type='SOURCE_WITHDRAWN' and resulting_state='WITHDRAWN'
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from clinical_task_team_queue where tenant_id=:tenant
                  and clinical_task_id=md5('tertiary-task-center-v2:task:1')::uuid
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query(Long.class).single()).isZero();
    }

    private Integer fixtureBusinessRowCount() {
        return jdbc.sql("""
                select
                  (select count(*) from config_item where tenant_id=:tenant
                    and payload->>'fixture_source'='tertiary-task-center-v2')
                  + (select count(*) from clinical_task where tenant_id=:tenant and task_type like 'TCV2_%')
                  + (select count(*) from clinical_pathway_definition where tenant_id=:tenant and pathway_code in
                    ('SEPSIS-ADULT','AECOPD','T2DM-INPATIENT','HIP-FRACTURE',
                     'ACUTE-APPENDICITIS','CESAREAN-DELIVERY','CATARACT','ACUTE-PANCREATITIS'))
                """).param("tenant", SyntheticDataImporter.TENANT_ID).query(Integer.class).single();
    }
}
