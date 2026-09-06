package org.haoemr.development;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Imports persisted task-center workload and executable pathway templates for development. */
@Component
@Profile("dev-synthetic")
final class TertiaryTaskCenterDataImporter {
    private static final UUID TENANT_ID = SyntheticDataImporter.TENANT_ID;
    private static final UUID FACILITY_ID = SyntheticDataImporter.FACILITY_ID;
    private static final UUID DEPARTMENT_ID = SyntheticDataImporter.SYNTHETIC_DEPARTMENT_ID;
    private static final UUID WARD_ID = SyntheticDataImporter.SYNTHETIC_WARD_ID;
    private static final UUID USER_ID = SyntheticDataImporter.USER_ID;
    private static final UUID APPROVER_ID = SyntheticDataImporter.COLLABORATOR_USER_ID;
    private static final String RESOURCE = "synthetic/tertiary-task-center-v2.json";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    TertiaryTaskCenterDataImporter(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    void importData() {
        JsonNode dataset = readDataset();
        transactions.executeWithoutResult(status -> {
            seedTaskRules(dataset);
            seedPathwayCatalogAndRules(dataset);
            retireLegacyOperationalTasks(dataset.path("operational_tasks").size());
            seedOperationalTasks(dataset);
            seedTaskEvents();
            seedTeamQueue();
            seedNotifications();
        });
    }

    private JsonNode readDataset() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            if (!root.path("task_rules").isArray() || root.path("task_rules").size() < 8
                    || !root.path("operational_tasks").isArray() || root.path("operational_tasks").size() < 24
                    || !root.path("pathways").isArray() || root.path("pathways").size() < 8) {
                throw new IllegalStateException("Tertiary task-center dataset is incomplete");
            }
            return root;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load " + RESOURCE, exception);
        }
    }

    private void seedTaskRules(JsonNode dataset) {
        for (JsonNode rule : dataset.path("task_rules")) {
            String key = required(rule, "key");
            var payload = objectMapper.createObjectNode();
            payload.put("schema_version", 1);
            payload.put("fixture_source", "tertiary-task-center-v2");
            payload.put("dataset_version", required(dataset, "dataset_version"));
            payload.put("hospital_level", required(dataset, "hospital_level"));
            payload.put("task_type", required(rule, "task_type"));
            payload.put("risk_level", required(rule, "risk_level"));
            payload.put("due_minutes", rule.path("due_minutes").asInt());
            payload.put("escalation_minutes", rule.path("escalation_minutes").asInt());
            payload.put("assignment_strategy", required(rule, "assignment_strategy"));
            payload.put("completion_source", "权威业务对象终态");
            payload.set("applies_to", rule.path("applies_to"));
            payload.putArray("channels").add("IN_APP").add("OUTBOX");
            payload.put("enabled", true);
            upsertConfig("CLINICAL_TASK_RULE", key, required(rule, "name"), payload);
        }
    }

    private void seedPathwayCatalogAndRules(JsonNode dataset) {
        for (JsonNode pathway : dataset.path("pathways")) {
            String code = required(pathway, "code");
            UUID definitionId = id("pathway-definition:" + code);
            UUID versionId = id("pathway-version:" + code + ":" + pathway.path("version").asInt());
            ObjectNode payload = ((ObjectNode) pathway).deepCopy();
            payload.put("schema_version", 1);
            payload.put("fixture_source", "tertiary-task-center-v2");
            payload.put("dataset_version", required(dataset, "dataset_version"));
            payload.put("hospital_level", required(dataset, "hospital_level"));
            payload.put("pathway_code", code);
            payload.put("specialty_code", required(pathway, "specialty"));
            payload.put("diagnosis_code", required(pathway, "diagnosis"));
            payload.put("version_no", pathway.path("version").asInt());
            payload.put("publication_scope", "江城大学附属医院本部");
            payload.put("version_immutable_after_publish", true);
            payload.set("variance_rules", dataset.path("variance_rules"));
            payload.set("completion_rules", dataset.path("completion_rules"));
            payload.set("exit_rules", dataset.path("exit_rules"));
            upsertConfig("CLINICAL_PATHWAY", required(pathway, "config_key"), required(pathway, "name"), payload);

            jdbc.sql("""
                    insert into clinical_pathway_definition(
                      tenant_id, pathway_definition_id, pathway_code, display_name, specialty_code,
                      diagnosis_code, status, created_by, created_at)
                    values (:tenant, :definition, :code, :name, :specialty, :diagnosis,
                      'ACTIVE', :author, now() - interval '45 days')
                    on conflict (tenant_id, pathway_code) do nothing
                    """).param("tenant", TENANT_ID).param("definition", definitionId).param("code", code)
                    .param("name", required(pathway, "name")).param("specialty", required(pathway, "specialty"))
                    .param("diagnosis", required(pathway, "diagnosis")).param("author", USER_ID).update();
            jdbc.sql("""
                    insert into clinical_pathway_version(
                      tenant_id, pathway_version_id, pathway_definition_id, version_no, status,
                      admission_criteria, created_by, approved_by, created_at, published_at)
                    values (:tenant, :version, :definition, :version_no, 'PUBLISHED', :criteria,
                      :author, :approver, now() - interval '40 days', now() - interval '30 days')
                    on conflict (tenant_id, pathway_definition_id, version_no) do nothing
                    """).param("tenant", TENANT_ID).param("version", versionId).param("definition", definitionId)
                    .param("version_no", pathway.path("version").asInt())
                    .param("criteria", required(pathway, "admission_criteria"))
                    .param("author", USER_ID).param("approver", APPROVER_ID).update();

            int stageSequence = 0;
            for (JsonNode stage : pathway.path("stages")) {
                stageSequence++;
                String stageCode = required(stage, "code");
                jdbc.sql("""
                        insert into clinical_pathway_stage(
                          tenant_id, pathway_version_id, stage_code, display_name, sequence_no,
                          expected_day_start, expected_day_end)
                        values (:tenant, :version, :code, :name, :sequence, :day_start, :day_end)
                        on conflict (tenant_id, pathway_version_id, stage_code) do nothing
                        """).param("tenant", TENANT_ID).param("version", versionId).param("code", stageCode)
                        .param("name", required(stage, "name")).param("sequence", stageSequence)
                        .param("day_start", stage.path("start").asInt()).param("day_end", stage.path("end").asInt()).update();
                int taskSequence = 0;
                for (JsonNode task : stage.path("tasks")) {
                    taskSequence++;
                    jdbc.sql("""
                            insert into clinical_pathway_stage_task(
                              tenant_id, pathway_version_id, stage_code, task_code, display_name,
                              source_type, source_key, required, sequence_no)
                            values (:tenant, :version, :stage, :task_code, :name,
                              :source_type, :source_key, :required, :sequence)
                            on conflict (tenant_id, pathway_version_id, task_code) do nothing
                            """).param("tenant", TENANT_ID).param("version", versionId).param("stage", stageCode)
                            .param("task_code", task.path(0).stringValue()).param("name", task.path(1).stringValue())
                            .param("source_type", task.path(2).stringValue()).param("source_key", task.path(3).stringValue())
                            .param("required", task.path(4).asBoolean()).param("sequence", taskSequence).update();
                }
            }
        }
    }

    private void upsertConfig(String type, String key, String name, JsonNode payload) {
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                values (:tenant, :id, :type, :key, :name, cast(:payload as jsonb),
                  'ACTIVE', 1, 1, 'VALID', '[]'::jsonb, 'APPROVED', :approver,
                  now() - interval '30 days', :author)
                on conflict (tenant_id, config_type, config_key) where status = 'ACTIVE' do update set
                  display_name = excluded.display_name, payload = excluded.payload,
                  status = 'ACTIVE', validation_state = 'VALID', validation_errors = '[]'::jsonb,
                  approval_state = 'APPROVED', approved_by = excluded.approved_by,
                  published_at = coalesce(config_item.published_at, excluded.published_at),
                  updated_at = now(), row_version = config_item.row_version + 1
                where config_item.display_name is distinct from excluded.display_name
                   or config_item.payload is distinct from excluded.payload
                   or config_item.status <> 'ACTIVE'
                """).param("tenant", TENANT_ID).param("id", id("config:" + type + ":" + key))
                .param("type", type).param("key", key).param("name", name)
                .param("payload", payload.toString()).param("approver", APPROVER_ID).param("author", USER_ID).update();
    }

    private void seedOperationalTasks(JsonNode dataset) {
        for (JsonNode task : dataset.path("operational_tasks")) {
            int ordinal = task.path("ordinal").asInt();
            String domain = required(task, "domain");
            UUID patientId = switch (domain) {
                case "outpatient" -> UUID.fromString("018f0000-0000-7000-8000-000000000001");
                case "inpatient" -> UUID.fromString("018f0000-0000-7000-8000-000000000002");
                case "emergency" -> UUID.fromString("018f0000-0000-7000-8000-000000000003");
                default -> throw new IllegalStateException("Unsupported task-center domain: " + domain);
            };
            UUID encounterId = switch (domain) {
                case "outpatient" -> UUID.fromString("018f0000-0000-7000-8000-000000000101");
                case "inpatient" -> UUID.fromString("018f0000-0000-7000-8000-000000000102");
                case "emergency" -> UUID.fromString("018f0000-0000-7000-8000-000000000103");
                default -> throw new IllegalStateException("Unsupported task-center domain: " + domain);
            };
            String state = required(task, "state");
            jdbc.sql("""
                    insert into clinical_task(
                      tenant_id, task_id, patient_id, encounter_id, facility_id, ward_id,
                      source_type, source_id, task_type, title, risk_level, state, business_state,
                      assigned_user_id, claimed_by, due_at, source_route, row_version, created_at, updated_at)
                    values (:tenant,
                      overlay(overlay(md5(:task_seed) placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                      :patient, :encounter, :facility, cast(:ward as uuid), :source_type,
                      overlay(overlay(md5(:source_seed) placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                      :task_type, :title,
                      :risk, :state, :business_state, :author, cast(:claimed_by as uuid),
                      now() + :due_minutes * interval '1 minute', :route, 1,
                      now() - :created_minutes * interval '1 minute',
                      now() - :updated_minutes * interval '1 minute')
                    on conflict (tenant_id, source_type, source_id, task_type) do nothing
                    """).param("tenant", TENANT_ID)
                    .param("task_seed", "tertiary-task-center-v2:task:" + ordinal)
                    .param("source_seed", "tertiary-task-center-v2:source:" + ordinal)
                    .param("patient", patientId).param("encounter", encounterId).param("facility", FACILITY_ID)
                    .param("ward", "inpatient".equals(domain) ? WARD_ID : null)
                    .param("source_type", required(task, "source_type"))
                    .param("task_type", required(task, "task_type")).param("title", required(task, "title"))
                    .param("risk", required(task, "risk_level")).param("state", state)
                    .param("business_state", required(task, "business_state"))
                    .param("author", USER_ID)
                    .param("claimed_by", List.of("CLAIMED", "IN_PROGRESS").contains(state) ? USER_ID : null)
                    .param("due_minutes", task.path("due_minutes").asInt())
                    .param("route", required(task, "source_route"))
                    .param("created_minutes", ordinal * 17).param("updated_minutes", ordinal * 5).update();
        }
    }

    /**
     * Retires the pre-RFC fixture identities without deleting their immutable event history.
     * Older development databases cast a raw MD5 digest to UUID, so the version and variant
     * bits could violate the public contract even though PostgreSQL accepted the value.
     */
    private void retireLegacyOperationalTasks(int taskCount) {
        jdbc.sql("""
                with seeds as (
                  select ordinal,
                    md5('tertiary-task-center-v2:task:' || ordinal)::uuid as legacy_task_id,
                    overlay(overlay(md5('tertiary-task-center-v2:task:' || ordinal)
                      placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid as current_task_id,
                    md5('tertiary-task-center-v2:source:' || ordinal)::uuid as legacy_source_id,
                    overlay(overlay(md5('tertiary-task-center-v2:source:' || ordinal)
                      placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid as current_source_id
                  from generate_series(1, :task_count) ordinal
                ), legacy as (
                  select task.task_id, task.state
                  from clinical_task task join seeds
                    on task.task_id = seeds.legacy_task_id and task.source_id = seeds.legacy_source_id
                  where task.tenant_id = :tenant and task.task_type like 'TCV2_%'
                    and task.state <> 'WITHDRAWN'
                    and (seeds.legacy_task_id <> seeds.current_task_id
                      or seeds.legacy_source_id <> seeds.current_source_id)
                )
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type, previous_state,
                  resulting_state, actor_user_id, reason, occurred_at)
                select :tenant,
                  overlay(overlay(md5('tertiary-task-center-v2:legacy-retired:' || task_id)
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  task_id, 'SOURCE_WITHDRAWN', state, 'WITHDRAWN', :author,
                  '旧版仿真 UUID 不符合公开数据契约，已保留历史证据并退出工作队列', now()
                from legacy
                on conflict (tenant_id, task_event_id) do nothing
                """).param("task_count", taskCount).param("tenant", TENANT_ID)
                .param("author", USER_ID).update();
        jdbc.sql("""
                with seeds as (
                  select
                    md5('tertiary-task-center-v2:task:' || ordinal)::uuid as legacy_task_id,
                    overlay(overlay(md5('tertiary-task-center-v2:task:' || ordinal)
                      placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid as current_task_id,
                    md5('tertiary-task-center-v2:source:' || ordinal)::uuid as legacy_source_id,
                    overlay(overlay(md5('tertiary-task-center-v2:source:' || ordinal)
                      placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid as current_source_id
                  from generate_series(1, :task_count) ordinal
                )
                update clinical_task task set state = 'WITHDRAWN',
                  business_state = '旧版仿真标识已退役', row_version = row_version + 1, updated_at = now()
                from seeds where task.tenant_id = :tenant and task.task_type like 'TCV2_%'
                  and task.task_id = seeds.legacy_task_id and task.source_id = seeds.legacy_source_id
                  and task.state <> 'WITHDRAWN'
                  and (seeds.legacy_task_id <> seeds.current_task_id
                    or seeds.legacy_source_id <> seeds.current_source_id)
                """).param("task_count", taskCount).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                with seeds as (
                  select md5('tertiary-task-center-v2:task:' || ordinal)::uuid as legacy_task_id
                  from generate_series(1, :task_count) ordinal
                )
                delete from clinical_task_team_queue queue using seeds
                where queue.tenant_id = :tenant
                  and queue.clinical_task_id = seeds.legacy_task_id
                """).param("task_count", taskCount).param("tenant", TENANT_ID).update();
    }

    private void seedTaskEvents() {
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type, previous_state,
                  resulting_state, actor_user_id, reason, occurred_at)
                select :tenant,
                  overlay(overlay(md5('tertiary-task-center-v2:event:' || task_id) placing '3' from 13 for 1)
                    placing '8' from 17 for 1)::uuid,
                  task_id, 'CREATED', null, state, :author,
                  '由三级医院业务规则生成的仿真任务', created_at
                from clinical_task
                where tenant_id=:tenant and task_type like 'TCV2_%' and state <> 'WITHDRAWN'
                on conflict (tenant_id, task_event_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
    }

    private void seedTeamQueue() {
        jdbc.sql("""
                with ranked as (
                  select task_id, row_number() over(order by created_at, task_id) ordinal
                  from clinical_task where tenant_id=:tenant and task_type like 'TCV2_%'
                    and state <> 'WITHDRAWN'
                    and encounter_id='018f0000-0000-7000-8000-000000000102'::uuid
                )
                insert into clinical_task_team_queue(
                  tenant_id, queue_id, facility_id, department_id, clinical_task_id,
                  queue_status, enqueued_by, enqueued_at, claimed_by, claimed_at, row_version)
                select :tenant,
                  overlay(overlay(md5('tertiary-task-center-v2:queue:' || task_id) placing '3' from 13 for 1)
                    placing '8' from 17 for 1)::uuid,
                  :facility, :department, task_id,
                  case when ordinal % 3=0 then 'COMPLETED' when ordinal % 2=0 then 'CLAIMED' else 'ENQUEUED' end,
                  :author, now()-(ordinal*13)*interval '1 minute',
                  case when ordinal % 3=0 or ordinal % 2=0 then :author else null end,
                  case when ordinal % 3=0 or ordinal % 2=0 then now()-(ordinal*8)*interval '1 minute' else null end,
                  case when ordinal % 3=0 or ordinal % 2=0 then 2 else 1 end
                from ranked
                on conflict do nothing
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID)
                .param("department", DEPARTMENT_ID).param("author", USER_ID).update();
    }

    private void seedNotifications() {
        jdbc.sql("""
                with kinds as (
                  select * from (values
                    (1,'CREATED','IN_APP','DELIVERED',1),
                    (2,'ESCALATED','OUTBOX','PENDING',0),
                    (3,'OVERDUE','IN_APP','FAILED',2)
                  ) v(ordinal,kind,channel,status,attempt_count)
                )
                insert into clinical_task_notification(
                  tenant_id, notification_id, task_id, recipient_user_id, kind, channel,
                  status, attempt_count, delivered_at, last_error, row_version,
                  created_at, updated_at, scheduled_at)
                select :tenant,
                  overlay(overlay(md5('tertiary-task-center-v2:notification:' || task.task_id || ':' || kinds.ordinal)
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  task.task_id, case when kinds.ordinal=2 then :collaborator else :author end,
                  kinds.kind, kinds.channel, kinds.status, kinds.attempt_count,
                  case when kinds.status='DELIVERED' then now()-interval '12 minutes' else null end,
                  case when kinds.status='FAILED' then '院内消息网关应答超时，已保留幂等重试证据' else null end,
                  case when kinds.status='PENDING' then 1 else 2 end,
                  task.created_at, now()-interval '5 minutes',
                  case when kinds.status='PENDING' then now()+interval '10 minutes' else task.created_at end
                from clinical_task task cross join kinds
                where task.tenant_id=:tenant and task.task_type like 'TCV2_%'
                  and task.state <> 'WITHDRAWN'
                on conflict (tenant_id, notification_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).param("collaborator", APPROVER_ID).update();
        jdbc.sql("""
                insert into clinical_task_in_app_delivery(
                  tenant_id, delivery_id, notification_id, recipient_user_id, delivered_at)
                select notification.tenant_id,
                  overlay(overlay(md5('tertiary-task-center-v2:delivery:' || notification.notification_id)
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  notification.notification_id, notification.recipient_user_id, notification.delivered_at
                from clinical_task_notification notification
                join clinical_task task on task.tenant_id = notification.tenant_id
                  and task.task_id = notification.task_id
                where notification.tenant_id = :tenant and task.task_type like 'TCV2_%'
                  and task.state <> 'WITHDRAWN'
                  and notification.channel = 'IN_APP' and notification.status = 'DELIVERED'
                on conflict (tenant_id, notification_id) do nothing
                """).param("tenant", TENANT_ID).update();
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("haoemr:tertiary-task-center-v2:" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String required(JsonNode node, String field) {
        JsonNode fieldValue = node.path(field);
        if (!fieldValue.isString() || fieldValue.stringValue().isBlank()) {
            throw new IllegalStateException("Missing task-center dataset field: " + field);
        }
        String value = fieldValue.stringValue().trim();
        return value;
    }
}
