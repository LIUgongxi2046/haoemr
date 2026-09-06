package org.haoemr.development;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Validated, external synthetic dataset used to populate the tertiary-hospital data center. */
record TertiaryDataCenterDataset(String datasetVersion, JsonNode root) {

    private static final Map<String, Integer> MINIMUM_ROWS = Map.ofEntries(
            Map.entry("configurations", 38),
            Map.entry("source_systems", 5),
            Map.entry("field_mappings", 17),
            Map.entry("migration_batches", 6),
            Map.entry("migration_checkpoints", 8),
            Map.entry("quality_rules", 7),
            Map.entry("quality_evaluations", 16),
            Map.entry("research_cohorts", 5),
            Map.entry("research_snapshots", 6),
            Map.entry("dataset_requests", 4));

    static TertiaryDataCenterDataset parse(ObjectMapper objectMapper, String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!root.path("synthetic").asBoolean(false)) {
            throw new IllegalArgumentException("Only explicitly synthetic data-center datasets may be imported");
        }
        String version = required(root, "dataset_version");
        if (!"三级甲等".equals(required(root, "hospital_level"))) {
            throw new IllegalArgumentException("Data-center dataset must target a tertiary grade-A hospital");
        }
        required(root, "organization");

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> requirement : MINIMUM_ROWS.entrySet()) {
            JsonNode rows = root.path(requirement.getKey());
            if (!rows.isArray() || rows.size() < requirement.getValue()) {
                throw new IllegalArgumentException(requirement.getKey() + " must contain at least "
                        + requirement.getValue() + " business rows");
            }
            assertUniqueIds(requirement.getKey(), rows);
            counts.put(requirement.getKey(), rows.size());
        }

        Map<String, Integer> configurationCounts = new LinkedHashMap<>();
        for (JsonNode configuration : root.path("configurations")) {
            String type = required(configuration, "config_type");
            configurationCounts.merge(type, 1, Integer::sum);
            JsonNode payload = configuration.path("payload");
            if (!payload.isObject()
                    || !"三级甲等".equals(required(payload, "hospital_level"))
                    || !required(root, "organization").equals(required(payload, "organization"))) {
                throw new IllegalArgumentException("Configuration payload is not a complete tertiary-hospital row");
            }
            String searchable = configuration.toString().toLowerCase();
            if (searchable.contains("demo") || searchable.contains("演示") || searchable.contains("示例")) {
                throw new IllegalArgumentException("Demo placeholder found in data-center configuration dataset");
            }
        }
        assertAtLeast(configurationCounts, "INTEGRATION_CONNECTOR", 10);
        assertAtLeast(configurationCounts, "DEVICE_CATALOG", 12);
        assertAtLeast(configurationCounts, "RESEARCH_PROJECT", 8);
        assertAtLeast(configurationCounts, "INTEGRATION_INCIDENT", 8);
        return new TertiaryDataCenterDataset(version, root.deepCopy());
    }

    JsonNode rows(String name) {
        return root.path(name);
    }

    private static void assertAtLeast(Map<String, Integer> counts, String type, int minimum) {
        if (counts.getOrDefault(type, 0) < minimum) {
            throw new IllegalArgumentException(type + " must contain at least " + minimum + " rows");
        }
    }

    private static void assertUniqueIds(String collection, JsonNode rows) {
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rows) {
            String id = required(row, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate id in " + collection + ": " + id);
        }
    }

    static String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isTextual() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value.stringValue();
    }
}
