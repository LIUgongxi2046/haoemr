package org.haoemr.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Generates deterministic, de-identified tertiary-hospital business batches for mock adapters. */
@Component
final class TertiaryMockBusinessDataGenerator {

    static final int DEFAULT_RECORD_COUNT = 36;
    static final int MIN_RECORD_COUNT = 12;
    static final int MAX_RECORD_COUNT = 200;
    static final String GENERATOR_VERSION = "tertiary-business-cn-v3";

    private static final List<String> CAMPUSES = List.of("本部院区", "东院区", "感染病院区");
    private static final List<String> DEPARTMENTS = List.of(
            "急诊医学科", "心血管内科", "呼吸与危重症医学科", "神经内科", "消化内科",
            "肾内科", "内分泌科", "血液内科", "肿瘤科", "普通外科", "骨科", "神经外科",
            "妇产科", "儿科", "重症医学科", "麻醉科", "病理科", "医学影像科", "检验科",
            "康复医学科", "病案管理科", "信息中心");
    private static final List<TestDefinition> LAB_TESTS = List.of(
            new TestDefinition("WBC", "白细胞计数", "10^9/L", 3.5, 9.5, 1.0, 30.0),
            new TestDefinition("HGB", "血红蛋白", "g/L", 115, 150, 50.0, 200.0),
            new TestDefinition("PLT", "血小板计数", "10^9/L", 125, 350, 20.0, 1000.0),
            new TestDefinition("K", "血钾", "mmol/L", 3.5, 5.3, 2.5, 6.5),
            new TestDefinition("NA", "血钠", "mmol/L", 137, 147, 120.0, 160.0),
            new TestDefinition("CREA", "肌酐", "μmol/L", 44, 106, null, 707.0),
            new TestDefinition("ALT", "丙氨酸氨基转移酶", "U/L", 7, 40, null, null),
            new TestDefinition("CRP", "C 反应蛋白", "mg/L", 0, 8, null, null),
            new TestDefinition("TNI", "高敏肌钙蛋白 I", "ng/L", 0, 34.2, null, null),
            new TestDefinition("DD", "D-二聚体", "mg/L FEU", 0, 0.5, null, null),
            new TestDefinition("GLU", "葡萄糖", "mmol/L", 3.9, 6.1, 2.8, 22.2),
            new TestDefinition("PCT", "降钙素原", "ng/mL", 0, 0.05, null, null));

    Map<String, Object> generate(String code, Map<String, Object> payload, Instant producedAt) {
        int count = recordCount(payload);
        SplittableRandom random = new SplittableRandom(seed(code, payload));
        List<Map<String, Object>> records = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            records.add(generateRecord(code, payload, producedAt, index, count, random.split()));
        }

        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("data_profile", dataProfile(count, records, payload));
        response.put("business_records", records);
        response.put("record_summary", recordSummary(records));
        addCompatibilityProjection(code, payload, producedAt, response, records);
        return response;
    }

    private Map<String, Object> generateRecord(
            String code, Map<String, Object> payload, Instant producedAt,
            int index, int count, SplittableRandom random) {
        LinkedHashMap<String, Object> record = baseRecord(code, payload, producedAt, index, count);
        switch (code) {
            case "LIS_RESULTS" -> labRecord(record, producedAt, index, random);
            case "PACS_IMAGES" -> imagingRecord(record, index, random);
            case "HIS_INSURANCE" -> settlementRecord(record, index, random);
            case "CA_TIMESTAMP" -> signatureRecord(record, payload, producedAt, index);
            case "HIE_DOCUMENT_EXCHANGE" -> exchangeRecord(record, payload, index, random);
            case "MODEL_PROVIDER" -> modelRecord(record, index, random);
            case "DEVICE_GATEWAY" -> deviceRecord(record, payload, producedAt, index, random);
            case "DICTATION_ASR" -> dictationRecord(record, index, random);
            case "IDP_AUTHENTICATE" -> authenticationRecord(record, payload, index, random);
            case "SCAN_CAPTURE" -> scanRecord(record, index, random);
            case "MALWARE_SCAN" -> malwareRecord(record, payload, producedAt, index, random);
            case "CDA_VALIDATION" -> cdaValidationRecord(record, payload, producedAt, index);
            case "STORAGE_PRESERVE" -> preservationRecord(record, payload, index, random);
            case "PATHOLOGY_DIAGNOSE" -> pathologyRecord(record, index, random);
            case "ANESTHESIA_EVENT" -> anesthesiaRecord(record, producedAt, index, random);
            case "THERAPY_EXECUTE" -> therapyRecord(record, index, random);
            case "DIRECT_REPORT_GATEWAY" -> directReportRecord(record, payload, producedAt, index, random);
            case "EMPI_PATIENT_LOOKUP" -> empiLookupRecord(record, payload, producedAt, index, random);
            default -> throw new MockInterfaceException("MOCK_INTERFACE_UNKNOWN", 404, "未知模拟接口：" + code);
        }
        return record;
    }

    private LinkedHashMap<String, Object> baseRecord(
            String code, Map<String, Object> payload, Instant producedAt,
            int index, int count) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("business_id", stableUuid(code, payload, "business-" + index).toString());
        boolean requestSubject = payload.containsKey("patient_id") || payload.containsKey("encounter_id");
        record.put("patient_id", requestSubject
                ? str(payload, "patient_id", stableUuid(code, payload, "patient-0").toString())
                : stableUuid(code, payload, "patient-" + (index % Math.max(12, count / 2))).toString());
        record.put("encounter_id", requestSubject
                ? str(payload, "encounter_id", stableUuid(code, payload, "encounter-0").toString())
                : stableUuid(code, payload, "encounter-" + index).toString());
        record.put("request_subject_match", requestSubject);
        record.put("campus", CAMPUSES.get(index % CAMPUSES.size()));
        record.put("department", DEPARTMENTS.get(index % DEPARTMENTS.size()));
        record.put("business_time", producedAt.minusSeconds((long) (count - index) * 300L).toString());
        return record;
    }

    private void labRecord(Map<String, Object> record, Instant producedAt, int index, SplittableRandom random) {
        TestDefinition test = LAB_TESTS.get(index % LAB_TESTS.size());
        boolean criticalCandidate = index > 0 && index % 17 == 0
                && (test.criticalLow() != null || test.criticalHigh() != null);
        boolean highCritical = criticalCandidate && test.criticalHigh() != null
                && (index % 2 == 0 || test.criticalLow() == null);
        boolean critical = criticalCandidate;
        double value = critical
                ? highCritical
                    ? test.criticalHigh() * 1.03
                    : Math.max(0, test.criticalLow() * 0.97)
                : test.low() + random.nextDouble() * (test.high() - test.low());
        record.put("specimen_id", "SPM-" + compactId(record, index));
        record.put("test_code", test.code());
        record.put("test_name", test.name());
        record.put("value", decimal(value, value < 10 ? 2 : 1));
        record.put("unit", test.unit());
        record.put("reference_range", decimal(test.low(), 1) + "–" + decimal(test.high(), 1));
        record.put("flag", critical ? (highCritical ? "HH" : "LL") : "N");
        record.put("critical", critical);
        record.put("critical_policy_code", "JC-LAB-CRITICAL-2026-01");
        record.put("critical_threshold", critical
                ? decimal(highCritical ? test.criticalHigh() : test.criticalLow(), value < 10 ? 2 : 1)
                : null);
        record.put("critical_recheck_status", critical ? "RECHECK_CONFIRMED" : "NOT_APPLICABLE");
        record.put("critical_reporter", critical ? "SYNTHETIC-LAB-TECH" : null);
        record.put("critical_receiver", critical ? "SYNTHETIC-RESPONSIBLE-CLINICIAN" : null);
        record.put("critical_acknowledged_at", critical ? producedAt.minusSeconds(60).toString() : null);
        record.put("critical_handling", critical ? "合成演练：复核后电话报告并由临床接收确认" : null);
        record.put("critical_closed_loop_status", critical ? "SIMULATION_CLOSED" : "NOT_APPLICABLE");
        record.put("status", index % 13 == 0 ? "PENDING_REVIEW" : "CONFIRMED");
        record.put("observed_at", producedAt.minusSeconds((long) index * 420L).toString());
    }

    private void imagingRecord(Map<String, Object> record, int index, SplittableRandom random) {
        List<String> modalities = List.of("CT", "MR", "DR", "US", "PET-CT", "DSA");
        List<String> bodyParts = List.of("CHEST", "HEAD", "ABDOMEN", "SPINE", "PELVIS", "CARDIAC");
        String modality = modalities.get(index % modalities.size());
        record.put("study_uid", "1.2.826.0.1.3680043.10." + compactId(record, index));
        record.put("accession_no", "ACC-" + compactId(record, index));
        record.put("modality", modality);
        record.put("body_part", bodyParts.get((index + random.nextInt(bodyParts.size())) % bodyParts.size()));
        record.put("series_count", 2 + random.nextInt(7));
        record.put("image_count", 32 + random.nextInt(780));
        record.put("report_status", index % 9 == 0 ? "DRAFT" : index % 7 == 0 ? "PRELIMINARY" : "FINAL");
        record.put("priority", index % 11 == 0 ? "EMERGENCY" : "ROUTINE");
    }

    private void settlementRecord(Map<String, Object> record, int index, SplittableRandom random) {
        List<String> categories = List.of("诊疗项目", "医疗服务项目", "药品", "医用耗材", "检验", "检查", "床位", "手术");
        BigDecimal amount = BigDecimal.valueOf(18 + random.nextDouble() * 2480).setScale(2, RoundingMode.HALF_UP);
        String insuranceType = index % 3 == 0 ? "URRBMI" : "UEBMI";
        BigDecimal ratio = BigDecimal.valueOf("UEBMI".equals(insuranceType) ? 0.80 : 0.70);
        String category = categories.get(index % categories.size());
        record.put("claim_item_id", "CLMI-" + compactId(record, index));
        record.put("item_code", "SYN-NHSA-" + String.format("%06d", 100000 + index));
        record.put("local_item_code", "JC-" + String.format("%06d", 200000 + index));
        record.put("code_system", "国家医疗保障信息业务编码标准（合成仿真）");
        record.put("item_name", category + "仿真项目 " + String.format("%03d", index + 1));
        record.put("category", category);
        record.put("insurance_type", insuranceType);
        record.put("policy_region", "江城市城镇基本医疗保险仿真政策");
        record.put("catalog_eligibility", index % 11 == 0 ? "LIMITED_PAYMENT_REVIEW" : "ELIGIBLE");
        record.put("amount", amount.toPlainString());
        record.put("reimbursement_ratio", ratio);
        record.put("reimbursed_amount", amount.multiply(ratio).setScale(2, RoundingMode.HALF_UP).toPlainString());
        record.put("settlement_status", index > 0 && (index % 19 == 0 || index % 11 == 0) ? "MANUAL_REVIEW" : "SETTLED");
        record.put("reconciliation_status", index % 19 == 0 ? "PENDING_RECONCILIATION" : "RECONCILED");
    }

    private void signatureRecord(Map<String, Object> record, Map<String, Object> payload, Instant producedAt, int index) {
        record.put("document_id", str(payload, "document_id", "DOC-" + compactId(record, index)));
        record.put("content_hash", str(payload, "content_hash", "sha256:" + stableUuid("CA", payload, "content-" + index).toString().replace("-", "")));
        record.put("timestamp_token", "TSA-" + compactId(record, index));
        record.put("certificate_serial", "CA-SYN-" + compactId(record, index));
        record.put("signed_at", producedAt.minusSeconds((long) index * 180L).toString());
        record.put("algorithm", "SHA256withRSA");
        record.put("verification_status", "SYNTHETIC_NOT_LEGAL_EVIDENCE");
        record.put("certificate_chain_verified", false);
        record.put("revocation_checked", false);
        record.put("legal_effect", false);
    }

    private void exchangeRecord(Map<String, Object> record, Map<String, Object> payload, int index, SplittableRandom random) {
        List<String> documentTypes = List.of("门诊病历", "住院病案首页", "出院记录", "检验报告", "影像报告", "手术记录");
        List<String> statuses = List.of("ACKNOWLEDGED", "ACKNOWLEDGED", "ACKNOWLEDGED", "PENDING_ACK", "RETRYING");
        record.put("document_id", str(payload, "document_id", "CDA-" + compactId(record, index)));
        record.put("document_type", documentTypes.get(index % documentTypes.size()));
        record.put("exchange_id", "HIE-" + compactId(record, index));
        record.put("content_hash", str(payload, "content_hash", "sha256:" + compactId(record, index).toLowerCase()));
        record.put("receipt_status", index == 0 ? "PENDING_ACK" : statuses.get((index + random.nextInt(statuses.size())) % statuses.size()));
        record.put("destination", index % 3 == 0 ? "省级健康信息平台" : "市级区域卫生平台");
        record.put("idempotency_key", "HIE-CDA-" + compactId(record, index));
    }

    private void modelRecord(Map<String, Object> record, int index, SplittableRandom random) {
        List<String> tasks = List.of("就诊摘要", "病历质控", "出院计划", "结果追踪", "用药重整", "MDT 资料准备");
        String task = tasks.get(index % tasks.size());
        record.put("inference_id", "INF-" + compactId(record, index));
        record.put("task", task);
        record.put("model_route", index % 5 == 0 ? "clinical-reasoning-backup" : "clinical-summary-primary");
        record.put("output_text", task + "候选结果已生成，包含 " + (3 + random.nextInt(8)) + " 条可定位证据，必须由授权人员确认。");
        record.put("citation_count", 3 + random.nextInt(8));
        record.put("grounding_score", decimal(0.86 + random.nextDouble() * 0.13, 3));
        record.put("review_status", index % 7 == 0 ? "PENDING_HUMAN_REVIEW" : "REVIEWED");
    }

    private void deviceRecord(
            Map<String, Object> record, Map<String, Object> payload, Instant producedAt,
            int index, SplittableRandom random) {
        List<String> metrics = List.of("HR", "SpO2", "NIBP_SYS", "NIBP_DIA", "RESP", "TEMP", "ETCO2");
        String metric = metrics.get(index % metrics.size());
        double value = switch (metric) {
            case "HR" -> 58 + random.nextInt(78);
            case "SpO2" -> 88 + random.nextInt(13);
            case "NIBP_SYS" -> 82 + random.nextInt(88);
            case "NIBP_DIA" -> 48 + random.nextInt(62);
            case "RESP" -> 10 + random.nextInt(20);
            case "TEMP" -> 35.5 + random.nextDouble() * 4.2;
            default -> 25 + random.nextInt(25);
        };
        record.put("device_id", str(payload, "device_id", "IOMT-" + String.format("%04d", 100 + index % 60)));
        record.put("metric", metric);
        record.put("value", decimal(value, metric.equals("TEMP") ? 1 : 0));
        record.put("unit", metric.equals("SpO2") ? "%" : metric.equals("TEMP") ? "°C" : metric.startsWith("NIBP") || metric.equals("ETCO2") ? "mmHg" : metric.equals("HR") ? "bpm" : "rpm");
        record.put("quality", index % 23 == 0 ? "SUSPECT" : "VERIFIED");
        record.put("alarm_level", index % 17 == 0 ? "HIGH" : index % 11 == 0 ? "MEDIUM" : "NONE");
        record.put("device_clock_offset_seconds", Math.floorMod(index, 9));
        record.put("calibration_status", index % 29 == 0 ? "DUE_REVIEW" : "VALID");
        record.put("binding_verified", true);
        record.put("alarm_ack_status", index % 17 == 0 ? "PENDING_HUMAN_ACK" : "NOT_REQUIRED");
        // A collected simulated gateway batch represents readings received at this
        // collection instant. Do not backdate a newly collected batch before a
        // just-completed patient binding, or it would be incorrectly excluded
        // from the same live monitoring session.
        record.put("observed_at", producedAt.toString());
    }

    private void dictationRecord(Map<String, Object> record, int index, SplittableRandom random) {
        List<String> clinicalPhrases = List.of(
                "患者主诉活动后胸闷，休息后可缓解。", "既往有高血压病史，规律服药。",
                "查体双肺呼吸音清，未闻及明显干湿啰音。", "建议结合检验和影像结果进一步评估。",
                "已向患者说明候选内容需医生逐句确认。", "过敏史、用药史和家族史尚需再次核对。",
                "本次记录来自合成音频，不可直接进入正式病历。", "复诊计划和红旗症状已完成患者教育。"
        );
        record.put("segment_no", index + 1);
        record.put("speaker", index % 6 == 0 ? "患者" : "医生");
        record.put("text", clinicalPhrases.get((index + random.nextInt(clinicalPhrases.size())) % clinicalPhrases.size()));
        record.put("confidence", decimal(0.84 + random.nextDouble() * 0.15, 3));
        record.put("confirmation_status", index % 9 == 0 ? "UNCONFIRMED" : "CONFIRMED");
        record.put("audio_offset_ms", index * 4_500L);
    }

    private void authenticationRecord(Map<String, Object> record, Map<String, Object> payload, int index, SplittableRandom random) {
        List<String> roles = List.of("门诊医师", "住院医师", "主任医师", "责任护士", "药师", "技师", "病案管理员", "系统管理员");
        record.remove("patient_id");
        record.remove("encounter_id");
        record.put("subject", str(payload, "subject", stableUuid("IDP", payload, "subject-" + index).toString()));
        record.put("role", roles.get(index % roles.size()));
        record.put("mfa", index > 0 && index % 13 == 0 ? "STEP_UP_REQUIRED" : "VERIFIED");
        record.put("authentication_method", index % 4 == 0 ? "PASSKEY_MFA" : "OIDC_PKCE_MFA");
        record.put("session_minutes", 15 + random.nextInt(46));
        record.put("risk_level", index > 0 && index % 17 == 0 ? "HIGH" : "NORMAL");
        record.put("authenticated", index == 0 || index % 17 != 0);
    }

    private void scanRecord(Map<String, Object> record, int index, SplittableRandom random) {
        List<String> documentTypes = List.of("病案首页", "入院记录", "手术记录", "麻醉记录", "护理记录", "出院记录", "知情同意书", "检验报告");
        record.put("batch_id", "SCAN-BATCH-" + compactId(record, index));
        record.put("page", index + 1);
        record.put("document_type", documentTypes.get(index % documentTypes.size()));
        record.put("image_ref", "scan://" + compactId(record, index).toLowerCase() + "/page-" + (index + 1));
        record.put("ocr_excerpt", documentTypes.get(index % documentTypes.size()) + " · 合成页码 " + (index + 1) + " · OCR 置信度已记录");
        record.put("ocr_confidence", decimal(0.88 + random.nextDouble() * 0.11, 3));
        record.put("checksum", "sha256:" + compactId(record, index).toLowerCase());
        record.put("integrity", index > 0 && index % 29 == 0 ? "REVIEW_REQUIRED" : "VERIFIED");
    }

    private void malwareRecord(
            Map<String, Object> record, Map<String, Object> payload, Instant producedAt,
            int index, SplittableRandom random) {
        List<String> signatures = List.of("Eicar-Test-Signature", "Win.Test.EICAR_HDB-1",
                "PUA.Test.EICAR", "Heuristics.Suspicious.Memory");
        List<String> mediaTypes = List.of("application/pdf", "image/jpeg", "application/msword",
                "application/zip", "text/plain");
        boolean infected = index > 0 && index % 29 == 0;
        String contentHash = str(payload, "content_hash",
                "sha256:" + compactId(record, index).toLowerCase());
        record.put("content_ref", str(payload, "content_ref",
                "synthetic://file/" + compactId(record, index).toLowerCase()));
        record.put("content_hash", contentHash);
        record.put("media_type", str(payload, "media_type", mediaTypes.get(index % mediaTypes.size())));
        record.put("verdict", infected ? "FOUND" : "CLEAN");
        record.put("signature", infected
                ? signatures.get((index / 29 + random.nextInt(signatures.size())) % signatures.size())
                : null);
        record.put("engine", "haoemr-synthetic-clamav-v1");
        record.put("scan_evidence", infected
                ? "stream: " + record.get("signature") + " FOUND"
                : "stream: OK");
        record.put("action", infected ? "ISOLATE_AND_BLOCK" : "ALLOW");
        record.put("scanned_at", producedAt.minusSeconds((long) index * 90L).toString());
    }

    private void cdaValidationRecord(
            Map<String, Object> record, Map<String, Object> payload, Instant producedAt, int index) {
        List<String> documentTypes = List.of("门诊病历", "住院病案首页", "出院记录", "入院记录", "检验报告", "手术记录", "护理记录");
        boolean structuralInvalid = index > 0 && index % 37 == 0;
        boolean semanticWarning = !structuralInvalid && index > 0 && index % 13 == 0;
        record.put("document_id", str(payload, "document_id", "CDA-" + compactId(record, index)));
        record.put("content_hash", str(payload, "content_hash",
                "sha256:" + compactId(record, index).toLowerCase()));
        record.put("document_type", str(payload, "document_type",
                documentTypes.get(index % documentTypes.size())));
        List<String> checks = new ArrayList<>(List.of("XML well-formed",
                "Root ClinicalDocument present", "document type/status 枚举合法", "必填章节完整"));
        if (structuralInvalid) {
            checks.set(3, "必填章节缺失：主诉/现病史 section 未找到");
        } else if (semanticWarning) {
            checks.add("语义告警：valueSet 编码系统无法解析（不影响结构）");
        }
        record.put("checks", checks);
        record.put("structural_valid", !structuralInvalid);
        record.put("semantic_ok", !structuralInvalid && !semanticWarning);
        record.put("validation_status", structuralInvalid ? "STRUCTURE_INVALID"
                : semanticWarning ? "SEMANTIC_WARNING" : "VALID");
        record.put("engine", "haoemr-synthetic-cda-validator-v1");
        record.put("validated_at", producedAt.minusSeconds((long) index * 60L).toString());
    }

    private void preservationRecord(Map<String, Object> record, Map<String, Object> payload, int index, SplittableRandom random) {
        List<String> formats = List.of("CDA-R2", "PDF-A/3", "DICOM", "FHIR-NDJSON");
        record.put("content_ref", str(payload, "content_ref", "synthetic://archive/" + compactId(record, index).toLowerCase()));
        record.put("storage_ref", "worm://archive/" + compactId(record, index).toLowerCase());
        record.put("content_hash", str(payload, "content_hash", "sha256:" + compactId(record, index).toLowerCase()));
        record.put("format", formats.get(index % formats.size()));
        record.put("size_bytes", 64_000L + random.nextLong(80_000_000L));
        String recordType = index % 3 == 0 ? "OUTPATIENT_EMERGENCY_RECORD" : "INPATIENT_MEDICAL_RECORD";
        record.put("record_type", recordType);
        record.put("retention_years", "OUTPATIENT_EMERGENCY_RECORD".equals(recordType) ? 15 : 30);
        record.put("retention_basis", "OUTPATIENT_EMERGENCY_RECORD".equals(recordType)
                ? "自患者最后一次就诊之日起不少于15年"
                : "自患者最后一次出院之日起不少于30年");
        record.put("legal_hold", index % 41 == 0);
        record.put("destruction_approval_status", "NOT_DUE");
        record.put("sealed", true);
        record.put("restore_verification", index % 12 == 0 ? "SAMPLED_OK" : "NOT_DUE");
    }

    private void pathologyRecord(Map<String, Object> record, int index, SplittableRandom random) {
        List<String> specimens = List.of("胃黏膜活检", "乳腺肿物", "肺叶切除标本", "结直肠息肉", "淋巴结", "宫颈组织", "肝穿刺组织");
        List<String> statuses = List.of("RECEIVED", "GROSSING", "PROCESSING", "SLIDE_READY", "PENDING_REVIEW", "SIGNED");
        record.put("specimen_id", "PATH-" + compactId(record, index));
        record.put("specimen_type", specimens.get(index % specimens.size()));
        record.put("block_count", 1 + random.nextInt(12));
        record.put("slide_count", 2 + random.nextInt(28));
        record.put("diagnosis_status", statuses.get((index + random.nextInt(statuses.size())) % statuses.size()));
        record.put("frozen_section", index % 11 == 0);
        record.put("turnaround_hours", 6 + random.nextInt(114));
    }

    private void anesthesiaRecord(Map<String, Object> record, Instant producedAt, int index, SplittableRandom random) {
        List<String> events = List.of("术前核查", "麻醉诱导", "气道建立", "切皮", "生命体征复测", "镇痛给药", "苏醒评估", "PACU 交接");
        List<String> drugs = List.of("无", "丙泊酚", "罗库溴铵", "舒芬太尼", "七氟烷", "右美托咪定");
        record.put("event_no", index + 1);
        record.put("event", events.get(index % events.size()));
        record.put("drug", drugs.get((index + random.nextInt(drugs.size())) % drugs.size()));
        record.put("dose_verified", index % 5 != 0);
        record.put("monitoring", index > 0 && index % 19 == 0 ? "TRANSIENT_ALERT" : "STABLE");
        record.put("at", producedAt.minusSeconds((long) index * 180L).toString());
        record.put("recovery_disposition", index % 14 == 0 ? "ICU" : "PACU");
    }

    private void therapyRecord(Map<String, Object> record, int index, SplittableRandom random) {
        List<String> modalities = List.of("物理治疗", "作业治疗", "言语治疗", "放射治疗", "血液净化", "高压氧治疗", "疼痛介入");
        record.put("therapy_id", "THER-" + compactId(record, index));
        record.put("modality", modalities.get(index % modalities.size()));
        record.put("scheduled_slot", String.format("%02d:%02d", 8 + index % 10, (index % 4) * 15));
        record.put("patient_checked", true);
        record.put("order_checked", true);
        boolean dualSign = index == 0 || index % 8 != 0;
        record.put("dual_sign", dualSign);
        record.put("status", dualSign ? "COMPLETED" : "PENDING_SECOND_CHECK");
        record.put("adverse_event", index > 0 && index % 31 == 0 ? "MILD_REACTION_REPORTED" : "NONE");
        record.put("duration_minutes", 20 + random.nextInt(101));
    }

    private void directReportRecord(
            Map<String, Object> record, Map<String, Object> payload, Instant producedAt,
            int index, SplittableRandom random) {
        List<String> categories = List.of("HAI_CASE", "HAI_OUTBREAK", "NOTIFIABLE_DISEASE", "HAI_CASE");
        List<String> policies = List.of("REPORT-POLICY-HAI-24H", "REPORT-POLICY-NOTIFIABLE-2H");
        boolean notifiableUrgent = index % 3 == 0;
        record.put("event_id", str(payload, "event_id", "EVT-" + compactId(record, index)));
        record.put("case_category", categories.get(index % categories.size()));
        record.put("reporting_policy_code", notifiableUrgent ? policies.get(1) : policies.get(0));
        record.put("report_deadline_at", producedAt
                .plusSeconds(notifiableUrgent ? 2L * 3600 : 24L * 3600)
                .toString());
        record.put("report_card_no", "RC-" + compactId(record, index));
        record.put("receipt_no", index % 5 == 0 ? null : "RCPT-" + compactId(record, index));
        record.put("external_report_state", index % 7 == 0 ? "PENDING_RETRY"
                : index % 11 == 0 ? "CORRECTED" : "ACKNOWLEDGED");
        record.put("correction_of", index % 11 == 0
                ? "RC-" + compactId(record, Math.max(0, index - 1)) : null);
        record.put("retry_count", index % 7 == 0 ? 1 + random.nextInt(3) : 0);
        record.put("reported_at", producedAt.minusSeconds((long) index * 600L).toString());
    }

    private void empiLookupRecord(Map<String, Object> record, Map<String, Object> payload, Instant producedAt, int index, SplittableRandom random) {
        List<String> sourceSystems = List.of("HIS", "门诊挂号", "住院入区", "区域共享", "体检系统");
        record.put("person_code", str(payload, "person_code", "P-" + compactId(record, index)));
        record.put("candidate_id", "CAND-" + compactId(record, index));
        record.put("identity_status", index % 17 == 0 ? "PENDING_VERIFICATION" : "ACTIVE");
        record.put("match_score", decimal(0.72 + random.nextDouble() * 0.27, 2));
        record.put("possible_duplicate", index > 0 && index % 13 == 0);
        record.put("source_system", sourceSystems.get(index % sourceSystems.size()));
        record.put("record_authority", index % 9 == 0 ? "AUTHORITATIVE" : "AUXILIARY");
        record.put("matched_at", producedAt.minusSeconds((long) index * 300L).toString());
        record.put("merge_required", index > 0 && index % 13 == 0);
    }

    private void addCompatibilityProjection(
            String code, Map<String, Object> payload, Instant producedAt,
            Map<String, Object> response, List<Map<String, Object>> records) {
        Map<String, Object> first = records.getFirst();
        switch (code) {
            case "LIS_RESULTS" -> {
                response.put("patient_id", first.get("patient_id"));
                response.put("results", records);
                response.put("critical_values", records.stream().filter(item -> List.of("HH", "LL").contains(item.get("flag")))
                        .map(item -> item.get("test_name") + " " + item.get("value") + " " + item.get("unit") + " 已触发危急值闭环")
                        .toList());
            }
            case "PACS_IMAGES" -> {
                response.put("studies", records);
                copy(response, first, "study_uid", "modality", "body_part", "report_status");
                response.put("series", records.stream().limit(8).map(item -> Map.of(
                        "study_uid", item.get("study_uid"), "series_count", item.get("series_count"),
                        "images", item.get("image_count"), "status", item.get("report_status"))).toList());
            }
            case "HIS_INSURANCE" -> {
                response.put("claim_id", "CLM-" + compactId(first, 0));
                response.put("items", records);
                BigDecimal reimbursed = records.stream().map(item -> new BigDecimal(String.valueOf(item.get("reimbursed_amount"))))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                response.put("reimbursed_total", reimbursed.setScale(2, RoundingMode.HALF_UP).toPlainString());
                response.put("settlement_status", records.stream().anyMatch(item -> "MANUAL_REVIEW".equals(item.get("settlement_status"))) ? "PARTIAL_REVIEW" : "SETTLED");
            }
            case "CA_TIMESTAMP" -> {
                response.put("signatures", records);
                copy(response, first, "timestamp_token", "signed_at", "certificate_serial", "content_hash", "algorithm");
                response.put("evidence_ref", "evidence:tsa:" + compactId(first, 0).toLowerCase());
            }
            case "HIE_DOCUMENT_EXCHANGE" -> {
                response.put("exchanges", records);
                copy(response, first, "document_id", "exchange_id", "content_hash", "receipt_status");
                response.put("shared_at", "ACKNOWLEDGED".equals(first.get("receipt_status")) ? producedAt.toString() : null);
                response.put("clinical_impact", "区域回执不影响院内病历签署；未确认记录进入幂等重试队列");
            }
            case "MODEL_PROVIDER" -> {
                response.put("outputs", records);
                response.put("model", first.get("model_route"));
                response.put("output_text", first.get("output_text"));
                response.put("citations", records.stream().limit(6).map(item -> "evidence:" + item.get("business_id")).toList());
                response.put("behavior", "DETERMINISTIC_SEEDED_SYNTHETIC");
            }
            case "DEVICE_GATEWAY" -> {
                response.put("telemetry", records);
                response.put("device_clock_offset_seconds", Math.floorMod(seed(code, payload), 9));
                response.put("bound_patient", first.get("patient_id"));
            }
            case "DICTATION_ASR" -> {
                response.put("segments", records);
                response.put("unconfirmed_segments", records.stream().filter(item -> "UNCONFIRMED".equals(item.get("confirmation_status"))).count());
            }
            case "IDP_AUTHENTICATE" -> {
                response.put("authentication_events", records);
                copy(response, first, "authenticated", "subject", "mfa");
                response.put("token_expires_in_seconds", 900);
                response.put("roles", records.stream().map(item -> String.valueOf(item.get("role"))).distinct().toList());
            }
            case "SCAN_CAPTURE" -> {
                response.put("batch_id", first.get("batch_id"));
                response.put("pages", records);
                response.put("integrity", records.stream().anyMatch(item -> "REVIEW_REQUIRED".equals(item.get("integrity"))) ? "REVIEW_REQUIRED" : "VERIFIED");
            }
            case "MALWARE_SCAN" -> {
                response.put("scans", records);
                boolean infected = records.stream().anyMatch(item -> "FOUND".equals(item.get("verdict")));
                response.put("verdict", infected ? "INFECTED" : "CLEAN");
                response.put("signature", records.stream().map(item -> item.get("signature"))
                        .filter(java.util.Objects::nonNull).findFirst().orElse(null));
                response.put("engine", first.get("engine"));
                response.put("batch_verdict", infected ? "ISOLATE_AND_BLOCK" : "ALLOW");
            }
            case "CDA_VALIDATION" -> {
                response.put("documents", records);
                copy(response, first, "document_type", "engine");
                boolean allStructural = records.stream().allMatch(item -> Boolean.TRUE.equals(item.get("structural_valid")));
                boolean allSemantic = records.stream().allMatch(item -> Boolean.TRUE.equals(item.get("semantic_ok")));
                response.put("structural_valid", allStructural);
                response.put("semantic_ok", allSemantic);
                response.put("checks", records.stream()
                        .flatMap(item -> ((List<?>) item.get("checks")).stream())
                        .filter(value -> String.valueOf(value).contains("缺失") || String.valueOf(value).contains("告警"))
                        .distinct().toList());
            }
            case "STORAGE_PRESERVE" -> {
                response.put("objects", records);
                copy(response, first, "storage_ref", "content_hash", "retention_years", "format", "sealed");
            }
            case "PATHOLOGY_DIAGNOSE" -> {
                response.put("specimens", records);
                response.put("specimen_id", first.get("specimen_id"));
                response.put("stages", records.stream().limit(6).map(item -> Map.of(
                        "specimen_id", item.get("specimen_id"), "status", item.get("diagnosis_status"),
                        "turnaround_hours", item.get("turnaround_hours"))).toList());
                response.put("diagnosis_status", first.get("diagnosis_status"));
            }
            case "ANESTHESIA_EVENT" -> {
                response.put("event_axis", records);
                response.put("recovery_disposition", first.get("recovery_disposition"));
                response.put("monitoring", records.stream().anyMatch(item -> "TRANSIENT_ALERT".equals(item.get("monitoring"))) ? "ALERT_REVIEWED" : "STABLE");
            }
            case "THERAPY_EXECUTE" -> {
                response.put("executions", records);
                response.put("therapy_id", first.get("therapy_id"));
                response.put("verification", Map.of(
                        "patient_checked", first.get("patient_checked"), "order_checked", first.get("order_checked"),
                        "dual_sign", first.get("dual_sign")));
                response.put("adverse_event", first.get("adverse_event"));
                response.put("status", first.get("status"));
            }
            case "DIRECT_REPORT_GATEWAY" -> {
                response.put("reports", records);
                copy(response, first, "event_id", "case_category", "reporting_policy_code",
                        "report_deadline_at", "report_card_no", "external_report_state");
                response.put("receipt_no", first.get("receipt_no"));
                boolean hasPending = records.stream().anyMatch(item -> "PENDING_RETRY".equals(item.get("external_report_state")));
                boolean hasCorrected = records.stream().anyMatch(item -> "CORRECTED".equals(item.get("external_report_state")));
                response.put("acknowledgement_state", hasCorrected ? "CORRECTED"
                        : hasPending ? "PENDING_ACK" : "ACKNOWLEDGED");
                response.put("clinical_impact", "回执不影响院内感染防控；PENDING_RETRY 保留事件并幂等重试，不伪造国家平台已接收");
            }
            case "EMPI_PATIENT_LOOKUP" -> {
                response.put("candidates", records);
                response.put("best_match", first);
                boolean duplicate = records.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("possible_duplicate")));
                response.put("possible_duplicate", duplicate);
                response.put("merge_required", duplicate);
                response.put("clinical_impact", "疑似重复与合并候选必须人工确认，不以算法分直接合并患者；查询结果不替代本院患者主索引授权");
            }
            default -> throw new MockInterfaceException("MOCK_INTERFACE_UNKNOWN", 404, "未知模拟接口：" + code);
        }
    }

    private Map<String, Object> dataProfile(
            int count, List<Map<String, Object>> records, Map<String, Object> payload) {
        Map<String, Object> runtime = valueMap(payload.get("_runtime_profile"));
        LinkedHashMap<String, Object> profile = new LinkedHashMap<>();
        profile.put("hospital_level", runtime.getOrDefault("hospital_level", "三级甲等"));
        profile.put("organization", runtime.getOrDefault("organization", "江城大学附属医院（业务仿真）"));
        profile.put("organization_code", runtime.getOrDefault("organization_code", "SYNTHETIC-ORGANIZATION"));
        profile.put("facility_code", runtime.getOrDefault("facility_code", "SYNTHETIC-FACILITY"));
        profile.put("campuses", CAMPUSES);
        profile.put("business_domains", List.of("门急诊", "住院", "医技", "手术麻醉", "病案", "运营与集成"));
        profile.put("generation_scope", "TERTIARY_HOSPITAL_OPERATIONAL_BATCH");
        profile.put("generation_method", "DETERMINISTIC_SEEDED");
        profile.put("generator_version", GENERATOR_VERSION);
        profile.put("record_count", count);
        profile.put("department_count", records.stream().map(item -> item.get("department")).distinct().count());
        profile.put("patient_count", records.stream().map(item -> item.get("patient_id")).filter(java.util.Objects::nonNull).distinct().count());
        profile.put("encounter_count", records.stream().map(item -> item.get("encounter_id")).filter(java.util.Objects::nonNull).distinct().count());
        profile.put("synthetic", true);
        profile.put("contains_real_phi", false);
        profile.put("china_standard_profile", runtime.getOrDefault("china_standard_profile", Map.of(
                "hospital_platform", "WS/T 846.1-846.11—2024",
                "hospital_platform_function", "WS/T 847—2024")));
        profile.put("production_adapter_state", "SYNTHETIC_ONLY");
        return profile;
    }

    private Map<String, Object> recordSummary(List<Map<String, Object>> records) {
        LinkedHashSet<Object> departments = new LinkedHashSet<>();
        LinkedHashSet<Object> campuses = new LinkedHashSet<>();
        for (Map<String, Object> record : records) {
            departments.add(record.get("department"));
            campuses.add(record.get("campus"));
        }
        return Map.of(
                "total", records.size(),
                "patients", records.stream().map(item -> item.get("patient_id")).filter(java.util.Objects::nonNull).distinct().count(),
                "encounters", records.stream().map(item -> item.get("encounter_id")).filter(java.util.Objects::nonNull).distinct().count(),
                "departments", List.copyOf(departments),
                "campuses", List.copyOf(campuses),
                "generated_from_request_seed", true);
    }

    private int recordCount(Map<String, Object> payload) {
        Object raw = payload.getOrDefault("record_count", DEFAULT_RECORD_COUNT);
        try {
            int count = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
            if (count < MIN_RECORD_COUNT || count > MAX_RECORD_COUNT) {
                throw new NumberFormatException("out of range");
            }
            return count;
        } catch (NumberFormatException exception) {
            throw new MockInterfaceException("MOCK_RECORD_COUNT_INVALID", 422,
                    "record_count 必须为 " + MIN_RECORD_COUNT + "–" + MAX_RECORD_COUNT + " 的整数");
        }
    }

    private long seed(String code, Map<String, Object> payload) {
        UUID uuid = stableUuid(code, payload, "dataset-seed");
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }

    private UUID stableUuid(String code, Map<String, Object> payload, String salt) {
        return UUID.nameUUIDFromBytes((code + "|" + salt + "|" + canonical(payload)).getBytes(StandardCharsets.UTF_8));
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
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            for (Object item : iterable) {
                builder.append(canonical(item)).append(';');
            }
            return builder.append(']').toString();
        }
        return String.valueOf(value);
    }

    private String compactId(Map<String, Object> record, int index) {
        String businessId = String.valueOf(record.get("business_id")).replace("-", "").toUpperCase();
        return businessId.substring(0, 10) + String.format("%03d", index);
    }

    private String str(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private void copy(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            target.put(key, source.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> valueMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private record TestDefinition(
            String code, String name, String unit, double low, double high,
            Double criticalLow, Double criticalHigh) {}
}
