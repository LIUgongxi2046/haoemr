package org.haoemr.mock;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.MockInterfaceWire;
import org.haoemr.contracts.MockInvocationResultWire;
import org.springframework.stereotype.Service;

@Service
public final class MockInterfaceService {

    private static final Instant SIMULATION_EPOCH = Instant.parse("2026-08-24T00:00:00Z");
    private final TertiaryMockBusinessDataGenerator dataGenerator;

    public MockInterfaceService(TertiaryMockBusinessDataGenerator dataGenerator) {
        this.dataGenerator = dataGenerator;
    }

    private static final List<MockInterfaceWire> INTERFACES = List.of(
            mock("LIS_RESULTS", "LIS 检验结果查询", "INTEGRATION_LIS", "模拟检验科 LIS：返回检验结果、危急值与报告状态",
                    "HL7 FHIR R4 DiagnosticReport / Observation",
                    Map.of("patient_id", "uuid", "encounter_id", "uuid", "order_codes", "string[]"),
                    Map.of("results", "array<result>", "critical_values", "array<string>"),
                    "真实 LIS 对接：HL7 v2 ORU^R01 或 FHIR DiagnosticReport；替换 handler 为真实 LIS HTTP 客户端，保持同一请求/响应契约与危急值语义。"),
            mock("PACS_IMAGES", "PACS 影像检索", "INTEGRATION_PACS", "模拟影像 PACS：返回检查图像元数据、序列与报告状态",
                    "DICOMweb QIDO-RS / WADO-RS",
                    Map.of("study_uid", "uid", "patient_id", "uuid"),
                    Map.of("study_uid", "uid", "modality", "string", "series", "array<series>"),
                    "真实 PACS 对接：DICOMweb QIDO-RS 检索 + WADO-RS 取图；替换 handler 为 PACS DICOMweb 客户端。"),
            mock("HIS_INSURANCE", "HIS 医保结算", "INTEGRATION_HIS", "模拟医保平台：返回费用明细、报销比例与结算状态",
                    "医保平台 REST（按地区医保接口规范）",
                    Map.of("encounter_id", "uuid", "items", "array<item>"),
                    Map.of("claim_id", "string", "items", "array<item>", "reimbursed_total", "decimal"),
                    "真实医保对接：按当地医保结算接口规范（REST/WebService）；替换 handler 为医保平台客户端。"),
            mock("CA_TIMESTAMP", "CA 电子签名时间戳", "INTEGRATION_CA", "模拟 CA：返回可信时间戳、签名值与证据引用",
                    "RFC 3161 Time-Stamp Protocol (TSA)",
                    Map.of("content_hash", "string(sha256)", "algorithm", "string"),
                    Map.of("timestamp_token", "string", "signed_at", "date-time", "certificate_serial", "string"),
                    "真实 CA 对接：RFC 3161 TSA 时间戳 + X.509 证书；替换 handler 为真实 TSA 客户端。"),
            mock("HIE_DOCUMENT_EXCHANGE", "区域平台文档交换", "INTEGRATION_HIE", "模拟区域平台：返回 CDA/FHIR 文档上传、回执和共享状态",
                    "CDA R2 / HL7 FHIR R4 DocumentReference",
                    Map.of("document_id", "string", "content_hash", "string(sha256)", "patient_id", "uuid"),
                    Map.of("exchange_id", "string", "receipt_status", "string", "shared_at", "date-time|null"),
                    "真实区域平台对接：按属地规范上传 CDA R2 或 FHIR 文档；上传使用文档哈希幂等，回执查询只读可重试。"),
            mock("MODEL_PROVIDER", "模型推理 Provider", "MODEL", "模拟外部模型服务：返回确定性推理文本与引用",
                    "OpenAI-compatible /chat/completions",
                    Map.of("model", "string", "messages", "array<message>", "max_tokens", "int"),
                    Map.of("output_text", "string", "citations", "array<string>", "behavior", "string"),
                    "真实模型对接：OpenAI-compatible 或厂商 SDK；替换 handler 为真实模型客户端，保持数据驻留/许可边界。"),
            mock("DEVICE_GATEWAY", "设备网关遥测", "DEVICE", "模拟监护设备网关：返回体征遥测、设备时钟与绑定状态",
                    "HL7 v2 ORU^R01 / 厂商设备网关协议",
                    Map.of("device_id", "string", "patient_id", "uuid"),
                    Map.of("telemetry", "array<metric>", "device_clock_offset_seconds", "int", "bound_patient", "uuid"),
                    "真实设备对接：HL7 v2 或厂商网关协议（MQTT/串口）；替换 handler 为设备网关采集器。"),
            mock("DICTATION_ASR", "语音转写 ASR", "DICTATION", "模拟语音转写服务：返回分句转写与说话人置信度",
                    "语音转写 REST（流式/批量）",
                    Map.of("audio_ref", "string", "language", "string"),
                    Map.of("segments", "array<segment>", "unconfirmed_segments", "int"),
                    "真实语音对接：厂商 ASR REST/WebSocket；替换 handler 为真实转写客户端。"),
            mock("IDP_AUTHENTICATE", "身份认证 IdP", "IDENTITY", "模拟 IdP：返回认证令牌、MFA 状态与会话任期",
                    "OIDC Authorization Code + PKCE",
                    Map.of("subject", "uuid", "credentials", "object"),
                    Map.of("authenticated", "bool", "mfa", "string", "token_expires_in_seconds", "int"),
                    "真实 IdP 对接：OIDC/OAuth2 + MFA；替换 handler 为真实 IdP 客户端（生产由 OIDC 会话注入，此接口仅联调用）。"),
            mock("SCAN_CAPTURE", "纸质病案扫描", "ARCHIVE_SCAN", "模拟扫描仪：返回图像页、OCR 文本与完整性校验",
                    "TWAIN / ISIS 扫描接口",
                    Map.of("batch_id", "string", "pages", "array<page>"),
                    Map.of("pages", "array<page>", "integrity", "string"),
                    "真实扫描对接：TWAIN/ISIS 或扫描仪 SDK；替换 handler 为扫描设备驱动。"),
            mock("MALWARE_SCAN", "恶意文件扫描", "SECURITY_AV", "模拟杀毒引擎：返回干净/检出与签名证据",
                    "ClamAV INSTREAM (TCP 3310)",
                    Map.of("content_ref", "string", "content_hash", "string(sha256)", "media_type", "string"),
                    Map.of("verdict", "string", "signature", "string|null", "engine", "string"),
                    "真实杀毒对接：ClamAV INSTREAM 协议直连 3310（或商业杀毒网关）；替换 handler 为真实杀毒客户端，检出即隔离阻断入档。"),
            mock("CDA_VALIDATION", "CDA 结构校验", "DOCUMENT_CDA", "模拟 CDA R2 结构/语义校验服务：返回逐文书校验结论与问题清单",
                    "CDA R2 结构校验服务 (HTTP)",
                    Map.of("document_id", "string", "content_hash", "string(sha256)", "document_type", "string"),
                    Map.of("document_type", "string", "structural_valid", "boolean",
                            "semantic_ok", "boolean", "checks", "array<string>", "engine", "string"),
                    "真实 CDA 校验对接：配置 cda-validation-endpoint 的结构校验服务；替换 handler 为真实校验客户端，结构/语义失败即隔离不归档。"),
            mock("STORAGE_PRESERVE", "病案长期保存", "ARCHIVE_STORAGE", "模拟长期保存：返回存储位置、内容哈希与保存期限",
                    "S3 / OSS 对象存储 API",
                    Map.of("content_ref", "string", "content_hash", "string"),
                    Map.of("storage_ref", "string", "content_hash", "string", "retention_years", "int"),
                    "真实存储对接：S3/OSS 兼容对象存储；替换 handler 为对象存储客户端，保持 WORM/保留期策略。"),
            mock("PATHOLOGY_DIAGNOSE", "病理诊断", "PATHOLOGY", "模拟病理系统：返回取材、制片与诊断状态",
                    "病理 LIS 报告接口",
                    Map.of("specimen_id", "string", "patient_id", "uuid"),
                    Map.of("stages", "array<stage>", "diagnosis_status", "string"),
                    "真实病理对接：病理科 LIS 报告接口；替换 handler 为病理系统客户端。"),
            mock("ANESTHESIA_EVENT", "麻醉事件轴", "ANESTHESIA", "模拟麻醉系统：返回事件轴、用药与复苏去向",
                    "AIMS 麻醉信息系统接口",
                    Map.of("encounter_id", "uuid"),
                    Map.of("event_axis", "array<event>", "recovery_disposition", "string", "monitoring", "string"),
                    "真实麻醉对接：AIMS 麻醉信息系统接口；替换 handler 为 AIMS 客户端。"),
            mock("THERAPY_EXECUTE", "治疗执行", "THERAPY", "模拟治疗系统：返回排程、核对与不良事件",
                    "治疗执行系统 REST",
                    Map.of("therapy_id", "string", "patient_id", "uuid"),
                    Map.of("verification", "object", "adverse_event", "string|null", "status", "string"),
                    "真实治疗对接：治疗执行系统 REST；替换 handler 为治疗系统客户端，保持双人核对/不良事件语义。"),
            mock("DIRECT_REPORT_GATEWAY", "国家传染病/院感直报网关", "REPORT_GATEWAY", "模拟传染病与医院感染直报平台：返回报告卡号、回执、时限和更正关系",
                    "国家/省级传染病与院感直报接口（属地规范）",
                    Map.of("event_id", "string", "case_category", "string", "reporting_policy_code", "string"),
                    Map.of("report_card_no", "string", "receipt_no", "string|null", "external_report_state", "string",
                            "report_deadline_at", "date-time", "correction_of", "string|null"),
                    "真实直报对接：按属地疾控/院感直报规范报送；替换 handler 为直报网关客户端，保持 2/24 小时时限、报告卡号、回执、更正与失败重放语义。"),
            mock("EMPI_PATIENT_LOOKUP", "患者主索引 EMPI 查询", "EMPI", "模拟患者主索引：按登记号/身份证返回候选患者、匹配度与疑似重复",
                    "EMPI / 患者主索引查询接口",
                    Map.of("patient_id", "uuid", "person_code", "string"),
                    Map.of("candidates", "array<candidate>", "best_match", "object", "possible_duplicate", "boolean"),
                    "真实 EMPI 对接：患者主索引/MPI 查询与合并候选；替换 handler 为 EMPI 客户端，疑似重复必须人工确认，不以算法分直接合并患者。"));

    List<MockInterfaceWire> list() {
        return INTERFACES;
    }

    private static MockInterfaceWire mock(
            String code, String name, String type, String desc,
            String standardInterface, Map<String, Object> requestSchema,
            Map<String, Object> responseSchema, String integrationDoc) {
        Map<String, Object> requestProperties = new java.util.LinkedHashMap<>();
        requestSchema.forEach((field, descriptor) -> requestProperties.put(field, Map.of(
                "type", jsonType(String.valueOf(descriptor)), "description", String.valueOf(descriptor))));
        requestProperties.put("profile_key", Map.of("type", "string", "description", "已批准并发布的工作台配置键"));
        requestProperties.put("simulation_scenario", Map.of(
                "type", "string", "enum", List.of("SUCCESS", "DEGRADED", "UNAVAILABLE")));
        requestProperties.put("record_count", Map.of(
                "type", "integer", "minimum", 12, "maximum", 200, "default", 36));
        Map<String, Object> enrichedRequestSchema = Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "title", code + " 仿真请求",
                "type", "object",
                "additionalProperties", false,
                "required", List.of("profile_key", "simulation_scenario"),
                "properties", requestProperties);
        Map<String, Object> responseProperties = new java.util.LinkedHashMap<>();
        responseSchema.forEach((field, descriptor) -> responseProperties.put(field, Map.of(
                "type", jsonType(String.valueOf(descriptor)), "description", String.valueOf(descriptor))));
        responseProperties.put("data_profile", Map.of("type", "object", "description", "三级医院、院区、标准和合成数据声明"));
        responseProperties.put("business_records", Map.of("type", "array", "items", Map.of("type", "object")));
        responseProperties.put("record_summary", Map.of("type", "object"));
        responseProperties.put("safety_agent", Map.of("type", "object", "description", "规则型安全 Agent 的 PASS/REVIEW/BLOCK 结论"));
        responseProperties.put("execution", Map.of("type", "object", "description", "持久运行、配置版本、幂等与证据标识"));
        Map<String, Object> enrichedResponseSchema = Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "title", code + " 仿真响应",
                "type", "object",
                "required", List.of("data_profile", "business_records", "record_summary", "safety_agent", "execution"),
                "properties", responseProperties);
        return new MockInterfaceWire(code, name, type, desc,
                standardInterface, enrichedRequestSchema, enrichedResponseSchema, integrationDoc);
    }

    private static String jsonType(String descriptor) {
        String value = descriptor.toLowerCase();
        if (value.startsWith("array") || value.endsWith("[]")) return "array";
        if (value.contains("int")) return "integer";
        if (value.contains("decimal") || value.contains("number")) return "number";
        if (value.contains("bool")) return "boolean";
        if (value.contains("object")) return "object";
        return "string";
    }

    public MockInvocationResultWire invoke(String code, Map<String, Object> payload) {
        String scenario = str(payload, "simulation_scenario", "SUCCESS").toUpperCase();
        if (!List.of("SUCCESS", "DEGRADED", "UNAVAILABLE").contains(scenario)) {
            throw new MockInterfaceException("MOCK_SCENARIO_INVALID", 422,
                    "simulation_scenario 必须为 SUCCESS、DEGRADED 或 UNAVAILABLE");
        }
        if ("UNAVAILABLE".equals(scenario)) {
            throw new MockInterfaceException("MOCK_DEPENDENCY_UNAVAILABLE", 503,
                    "合成外部依赖不可用；页面必须保留人工降级路径，且不得写入临床事实");
        }
        Map<String, Object> response = dataGenerator.generate(code, payload, simulationTime(payload));
        if ("DEGRADED".equals(scenario)) {
            response.put("_simulation", Map.of(
                    "scenario", "DEGRADED",
                    "completeness", "PARTIAL",
                    "reason", "合成上游延迟，结果仅供流程演练，禁止据此自动执行临床动作"));
        }
        String deterministicKey = stableToken(code, payload, "request");
        return new MockInvocationResultWire(
                code, UUID.fromString(deterministicKey), simulationTime(payload),
                MockInvocationResultWire.ScenarioValue.valueOf(scenario),
                code + ":" + deterministicKey.substring(0, 8), response,
                "DEGRADED".equals(scenario)
                        ? "降级合成场景 · 结果不完整，必须人工复核，不进入真实临床事实"
                        : "确定性合成数据 · 相同输入产生相同输出 · 不进入真实临床事实");
    }

    private String str(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private Instant simulationTime(Map<String, Object> payload) {
        String referenceTime = str(payload, "simulation_reference_time", "");
        if (!referenceTime.isBlank()) {
            try {
                return Instant.parse(referenceTime);
            } catch (RuntimeException invalid) {
                throw new MockInterfaceException("MOCK_SIMULATION_TIME_INVALID", 422,
                        "simulation_reference_time 必须是 ISO-8601 时间");
            }
        }
        String token = stableToken("CLOCK", payload, "produced-at");
        long seconds = Long.parseUnsignedLong(token.substring(0, 8), 16) % 86_400L;
        return SIMULATION_EPOCH.plusSeconds(seconds);
    }

    private String stableToken(String code, Map<String, Object> payload, String salt) {
        String canonical = code + "|" + salt + "|" + canonical(payload);
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            StringBuilder builder = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : entries) {
                builder.append(entry.getKey()).append(':').append(canonical(entry.getValue())).append(';');
            }
            return builder.append('}').toString();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonical).reduce("[", (left, right) -> left + right + ",") + "]";
        }
        return String.valueOf(value);
    }
}
