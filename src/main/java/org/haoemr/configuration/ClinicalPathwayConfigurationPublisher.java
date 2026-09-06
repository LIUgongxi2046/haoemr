package org.haoemr.configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Materializes an approved configuration version into the executable inpatient pathway catalog. */
@Service
final class ClinicalPathwayConfigurationPublisher {
    private final JdbcClient jdbc;

    ClinicalPathwayConfigurationPublisher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void publish(ClinicalIdentity identity, UUID configId, long configVersion, String displayName,
            Map<String, Object> payload) {
        String code = required(payload, "pathway_code");
        int versionNo = Math.toIntExact(number(payload.get("version_no")));
        UUID candidateDefinitionId = stableId(identity.tenantId() + "|pathway|" + code);
        UUID versionId = stableId(identity.tenantId() + "|pathway|" + code + "|" + versionNo);
        UUID approvedBy = jdbc.sql("""
                select approved_by from config_item
                where tenant_id = :tenant and config_id = :config and status = 'ACTIVE'
                """).param("tenant", identity.tenantId()).param("config", configId)
                .query(UUID.class).single();

        jdbc.sql("""
                insert into clinical_pathway_definition(
                  tenant_id, pathway_definition_id, pathway_code, display_name, specialty_code,
                  diagnosis_code, status, created_by)
                values (:tenant, :definition, :code, :name, :specialty, :diagnosis, 'ACTIVE', :author)
                on conflict (tenant_id, pathway_code) do update set
                  display_name = excluded.display_name, specialty_code = excluded.specialty_code,
                  diagnosis_code = excluded.diagnosis_code, status = 'ACTIVE'
                """).param("tenant", identity.tenantId()).param("definition", candidateDefinitionId)
                .param("code", code).param("name", displayName)
                .param("specialty", required(payload, "specialty_code"))
                .param("diagnosis", required(payload, "diagnosis_code"))
                .param("author", identity.userId()).update();
        UUID definitionId = jdbc.sql("""
                select pathway_definition_id from clinical_pathway_definition
                where tenant_id = :tenant and pathway_code = :code
                """).param("tenant", identity.tenantId()).param("code", code)
                .query(UUID.class).single();
        int inserted = jdbc.sql("""
                insert into clinical_pathway_version(
                  tenant_id, pathway_version_id, pathway_definition_id, version_no, status,
                  admission_criteria, created_by, approved_by, published_at,
                  source_config_id, source_config_version)
                values (:tenant, :version, :definition, :version_no, 'PUBLISHED', :criteria,
                  :author, :approver, now(), :config, :config_version)
                on conflict (tenant_id, pathway_definition_id, version_no) do nothing
                """).param("tenant", identity.tenantId()).param("version", versionId)
                .param("definition", definitionId).param("version_no", versionNo)
                .param("criteria", required(payload, "admission_criteria"))
                .param("author", identity.userId()).param("approver", approvedBy)
                .param("config", configId).param("config_version", configVersion).update();
        if (inserted != 1) {
            throw new ConfigurationException("PATHWAY_VERSION_CONFLICT", 409,
                    "该临床路径版本已发布，请创建更高版本");
        }

        int stageSequence = 0;
        for (Map<String, Object> stage : objects(payload.get("stages"))) {
            stageSequence++;
            String stageCode = required(stage, "code");
            int[] days = days(stage);
            jdbc.sql("""
                    insert into clinical_pathway_stage(
                      tenant_id, pathway_version_id, stage_code, display_name, sequence_no,
                      expected_day_start, expected_day_end)
                    values (:tenant, :version, :code, :name, :sequence, :start, :end)
                    """).param("tenant", identity.tenantId()).param("version", versionId)
                    .param("code", stageCode).param("name", required(stage, "name"))
                    .param("sequence", stageSequence).param("start", days[0]).param("end", days[1]).update();
            int taskSequence = 0;
            for (Object rawTask : list(stage.get("tasks"))) {
                taskSequence++;
                Task task = task(rawTask);
                jdbc.sql("""
                        insert into clinical_pathway_stage_task(
                          tenant_id, pathway_version_id, stage_code, task_code, display_name,
                          source_type, source_key, required, sequence_no)
                        values (:tenant, :version, :stage, :code, :name, :source_type,
                          :source_key, :required, :sequence)
                        """).param("tenant", identity.tenantId()).param("version", versionId)
                        .param("stage", stageCode).param("code", task.code()).param("name", task.name())
                        .param("source_type", task.sourceType()).param("source_key", task.sourceKey())
                        .param("required", task.required()).param("sequence", taskSequence).update();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        return rows.stream().filter(Map.class::isInstance).map(row -> (Map<String, Object>) row).toList();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> rows ? rows : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Task task(Object raw) {
        if (raw instanceof Map<?, ?> value) {
            Map<String, Object> map = (Map<String, Object>) value;
            return new Task(required(map, "code"), required(map, "name"), required(map, "source_type"),
                    required(map, "source_key"), !Boolean.FALSE.equals(map.get("required")));
        }
        List<?> row = (List<?>) raw;
        return new Task(String.valueOf(row.get(0)), String.valueOf(row.get(1)), String.valueOf(row.get(2)),
                String.valueOf(row.get(3)), row.size() < 5 || !Boolean.FALSE.equals(row.get(4)));
    }

    private static int[] days(Map<String, Object> stage) {
        if (stage.get("start") instanceof Number start && stage.get("end") instanceof Number end) {
            return new int[] {start.intValue(), end.intValue()};
        }
        String value = String.valueOf(stage.getOrDefault("days", "0"));
        String[] parts = value.split("-", 2);
        int start = Integer.parseInt(parts[0].trim());
        int end = parts.length == 1 ? start : Integer.parseInt(parts[1].trim());
        return new int[] {start, end};
    }

    private static String required(Map<String, Object> payload, String key) {
        String value = String.valueOf(payload.getOrDefault(key, "")).trim();
        if (value.isEmpty()) throw new ConfigurationException(
                "CONFIG_PAYLOAD_INVALID", 422, "临床路径缺少字段：" + key);
        return value;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Task(String code, String name, String sourceType, String sourceKey, boolean required) {}
}
