package org.haoemr.clinical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.haoemr.contracts.ConfigurationPackManifestWire;
import org.haoemr.contracts.ConfigurationPackTemplateWire;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Version 1 accepts one closed JSON document, never archives, scripts, SQL or arbitrary schema. */
@Component
final class ConfigurationPackCodec {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private final ObjectMapper mapper;
    ConfigurationPackCodec(ObjectMapper mapper) { this.mapper = mapper; }

    ConfigurationPackManifestWire parse(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw invalid("配置包为空或超过 10 MB");
        try {
            JsonNode node = mapper.readTree(raw);
            fields(node, Set.of("schema_version", "code", "version", "name", "type", "app_version", "publisher", "license", "dependencies", "templates"));
            if (!node.path("schema_version").isInt() || node.path("schema_version").asInt() != 1) throw invalid("仅支持清单版本 1");
            code(node, "code", 64); version(node, "version"); label(node, "name", 256);
            label(node, "publisher", 128); label(node, "license", 256); label(node, "app_version", 64);
            if (!List.of("CONFIG", "DEMO").contains(node.path("type").asText())) throw invalid("配置包类型无效");
            if (!node.path("templates").isArray() || node.path("templates").size() < 1 || node.path("templates").size() > 500)
                throw invalid("配置包必须包含 1–500 个模板条目");
            var codes = new HashSet<String>(); var types = new HashSet<String>();
            for (JsonNode item : node.path("templates")) {
                fields(item, Set.of("code", "name", "document_type", "fields", "required_fields"));
                code(item, "code", 128); code(item, "document_type", 96); label(item, "name", 256);
                if (!codes.add(item.path("code").asText()) || !types.add(item.path("document_type").asText()))
                    throw invalid("条目编码或同范围文书类型重复");
                Set<String> available = fieldList(item.path("fields"), false);
                if (!available.containsAll(fieldList(item.path("required_fields"), true))) throw invalid("必填字段未定义");
            }
            if (!node.path("dependencies").isArray() || node.path("dependencies").size() > 100) throw invalid("依赖清单无效");
            var deps = new HashSet<String>();
            for (JsonNode dependency : node.path("dependencies")) {
                fields(dependency, Set.of("code", "version")); code(dependency, "code", 64); version(dependency, "version");
                if (!deps.add(dependency.path("code").asText()) || dependency.path("code").asText().equals(node.path("code").asText()))
                    throw invalid("依赖重复或包依赖自身");
            }
            return mapper.treeToValue(node, ConfigurationPackManifestWire.class);
        } catch (ConfigurationPackException known) { throw known; }
        catch (Exception malformed) { throw invalid("配置包不是符合规范的 JSON 文件"); }
    }

    private static Set<String> fieldList(JsonNode values, boolean emptyAllowed) {
        if (!values.isArray() || values.size() > 100 || (!emptyAllowed && values.isEmpty())) throw invalid("模板字段数量无效");
        var result = new HashSet<String>();
        for (JsonNode field : values) {
            if (!field.isTextual() || !field.asText().matches("[a-z][a-z0-9_]{0,95}") || !result.add(field.asText()))
                throw invalid("模板字段必须为不重复的稳定字段编码");
        }
        return result;
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject() || !node.propertyNames().equals(expected)) throw invalid("配置包存在未知字段或缺失必需字段");
    }
    private static void label(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max || value.asText().chars().anyMatch(c -> c < 32))
            throw invalid("配置包字段无效：" + field);
    }
    private static void code(JsonNode node, String field, int max) {
        label(node, field, max);
        if (!node.path(field).asText().matches("[A-Za-z][A-Za-z0-9_-]*")) throw invalid("配置编码格式无效");
    }
    private static void version(JsonNode node, String field) {
        if (!node.path(field).isTextual() || !node.path(field).asText().matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})"))
            throw invalid("包版本必须采用三段非负整数，不接受范围或预发布版本");
    }
    Map<String, Object> schema(ConfigurationPackTemplateWire item) {
        Map<String, Object> properties = new LinkedHashMap<>();
        item.fields().forEach(field -> properties.put(field, Map.of("type", "string")));
        return Map.of("type", "object", "properties", properties, "additionalProperties", false);
    }
    Map<String, Object> display(ConfigurationPackTemplateWire item) { return Map.of("field_order", item.fields()); }
    String content(Object schema, Object required, Object display) {
        // A JSON tree supplies structural equality independent of SQL JSONB key order.
        return json(Map.of("section_schema", schema, "required_fields", required, "display_rules", display));
    }
    boolean same(String a, String b) { return mapper.readTree(a).equals(mapper.readTree(b)); }
    String json(Object value) { return mapper.writeValueAsString(value); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static ConfigurationPackException invalid(String message) { return new ConfigurationPackException("CONFIG_PACK_INVALID", 400, message); }
}
