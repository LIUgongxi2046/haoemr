package org.haoemr.metrics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.MetricSnapshotRecordRequestWire;
import org.haoemr.contracts.MetricSnapshotWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class MetricSnapshotService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    MetricSnapshotService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    List<MetricSnapshotWire> list(ClinicalIdentity identity, String metricType) {
        StringBuilder sql = new StringBuilder("""
                select snapshot_id, metric_type, metric_name, metric_value, unit, dimension::text,
                       period, status, row_version, computed_at, created_at
                from metric_snapshot where tenant_id = :tenant
                """);
        if (metricType != null && !metricType.isBlank()) sql.append(" and metric_type = :type");
        sql.append(" order by computed_at desc, snapshot_id desc limit 500");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (metricType != null && !metricType.isBlank()) spec = spec.param("type", metricType);
        return spec.query((rs, row) -> wire(rs)).list();
    }

    MetricSnapshotWire record(ClinicalIdentity identity, MetricSnapshotRecordRequestWire request) {
        return transactions.execute(status -> {
            UUID snapshotId = UUID.randomUUID();
            jdbc.sql("""
                    insert into metric_snapshot(
                      tenant_id, snapshot_id, metric_type, metric_name, metric_value, unit, dimension, period)
                    values (:tenant, :snapshot, :type, :name, :value, :unit, cast(:dimension as jsonb), :period)
                    """).param("tenant", identity.tenantId()).param("snapshot", snapshotId)
                    .param("type", request.metricType()).param("name", request.metricName())
                    .param("value", request.metricValue()).param("unit", request.unit())
                    .param("dimension", json(request.dimension() == null ? Map.of() : request.dimension()))
                    .param("period", request.period()).update();
            appendEvidence(identity, "METRIC_SNAPSHOT_RECORDED", request.metricType(), snapshotId);
            return item(identity.tenantId(), snapshotId);
        });
    }

    /** 从已登记的事实表按页面语义计算快照；公式和来源随快照固化，禁止自由 SQL。 */
    List<MetricSnapshotWire> compute(ClinicalIdentity identity, String metricType) {
        return transactions.execute(status -> {
            List<MetricDefinition> definitions = definitions(identity, metricType);
            if ("RESEARCH_STATS".equals(metricType)) {
                String calculationId = UUID.randomUUID().toString();
                definitions = definitions.stream().map(definition -> {
                    Map<String, Object> dimension = new java.util.LinkedHashMap<>(definition.dimension());
                    dimension.put("calculation_id", calculationId);
                    dimension.put("calculation_version", "research-stats-v2");
                    return new MetricDefinition(definition.name(), definition.value(), definition.unit(), dimension);
                }).toList();
            }
            List<MetricSnapshotWire> computed = new java.util.ArrayList<>();
            for (MetricDefinition definition : definitions) {
                computed.add(item(identity.tenantId(), insertSnapshot(identity, metricType, definition)));
            }
            return computed;
        });
    }

    private List<MetricDefinition> definitions(ClinicalIdentity identity, String metricType) {
        UUID tenantId = identity.tenantId();
        return switch (metricType) {
            case "DATA_CENTER" -> List.of(
                    metric("患者主档案", count(tenantId, "patient", null), "人", "patient", "count(patient_id)"),
                    metric("就诊事实", count(tenantId, "encounter", null), "次", "encounter", "count(encounter_id)"),
                    metric("已签署病历", count(tenantId, "clinical_document", "status = 'SIGNED'"), "份", "clinical_document", "count where status=SIGNED"),
                    metric("医嘱事实", count(tenantId, "clinical_order", null), "条", "clinical_order", "count(order_id)"));
            case "RESEARCH" -> List.of(
                    metric("活动科研队列", count(tenantId, "research_cohort", "status = 'ACTIVE'"), "个", "research_cohort", "count where status=ACTIVE"),
                    metric("队列成员", count(tenantId, "research_cohort_member", null), "人次", "research_cohort_member", "count immutable members"),
                    metric("待审批数据申请", count(tenantId, "research_dataset_request", "status = 'REQUESTED'"), "项", "research_dataset_request", "count where status=REQUESTED"),
                    metric("已导出数据申请", count(tenantId, "research_dataset_request", "status = 'EXPORTED'"), "项", "research_dataset_request", "count where status=EXPORTED"));
            case "RESEARCH_STATS" -> {
                ResearchSnapshot sourceSnapshot = latestMaterializedResearchSnapshot(tenantId);
                double members = sourceSnapshot == null ? 0 : sourceSnapshot.memberCount();
                double cohorts = count(tenantId, "research_cohort", null);
                List<MetricDefinition> stats = new java.util.ArrayList<>(List.of(
                        metric("队列快照", count(tenantId, "research_cohort_snapshot", null), "份", "research_cohort_snapshot", "count immutable snapshots"),
                        metric("纳入成员", members, "人", "research_cohort_snapshot_member", "members in latest materialized snapshot"),
                        metric("平均队列规模", cohorts == 0 ? 0 : members / cohorts, "人/队列", "research_cohort_snapshot_member + research_cohort", "latest snapshot member count / cohort count"),
                        metric("已输出研究集", count(tenantId, "research_dataset_request", "status = 'EXPORTED'"), "份", "research_dataset_request", "count where status=EXPORTED")));
                stats.addAll(researchAgeDistribution(tenantId, sourceSnapshot));
                stats.add(researchQuarterTrend(tenantId));
                yield stats;
            }
            case "DEPARTMENT_QC" -> List.of(
                    metric("质控运行", count(tenantId, "document_quality_run", null)
                                    + qualityOperationCount(tenantId, "config_type = 'DEPARTMENT_QC_CASE'"),
                            "次", "document_quality_run + config_item", "immutable runs + registered department QC cases"),
                    metric("阻断运行", count(tenantId, "document_quality_run", "outcome = 'BLOCKED'")
                                    + qualityOperationCount(tenantId, "config_type = 'DEPARTMENT_QC_CASE' and payload->>'severity' = 'BLOCKING' and payload->>'workflow_status' <> 'CLOSED'"),
                            "次", "document_quality_run + config_item", "blocked runs + open BLOCKING QC cases"),
                    metric("开放问题", count(tenantId, "quality_finding", "state in ('OPEN','ACKNOWLEDGED')")
                                    + qualityOperationCount(tenantId, "config_type = 'DEPARTMENT_QC_CASE' and payload->>'workflow_status' <> 'CLOSED'"),
                            "项", "quality_finding + config_item", "open findings + non-terminal QC cases"),
                    metric("已整改问题", count(tenantId, "quality_finding", "state = 'RESOLVED'")
                                    + qualityOperationCount(tenantId, "config_type = 'DEPARTMENT_QC_CASE' and payload->>'workflow_status' = 'CLOSED'"),
                            "项", "quality_finding + config_item", "resolved findings + closed QC cases"));
            case "QUALITY_CENTER" -> {
                double runs = count(tenantId, "document_quality_run", null);
                double passed = count(tenantId, "document_quality_run", "outcome = 'PASSED'");
                yield List.of(
                        metric("病历质控通过率", runs == 0 ? 0 : passed * 100d / runs, "%", "document_quality_run", "PASSED / all runs * 100"),
                        metric("阻断问题", count(tenantId, "quality_finding", "severity = 'BLOCKING' and state in ('OPEN','ACKNOWLEDGED')")
                                        + qualityOperationCount(tenantId, "payload->>'severity' = 'BLOCKING' and coalesce(payload->>'workflow_status','') not in ('CLOSED','VERIFIED','REVOKED')"),
                                "项", "quality_finding + config_item", "open BLOCKING findings and quality operations"),
                        metric("质控警告", count(tenantId, "quality_finding", "severity = 'WARNING' and state in ('OPEN','ACKNOWLEDGED')")
                                        + qualityOperationCount(tenantId, "payload->>'severity' = 'WARNING' and coalesce(payload->>'workflow_status','') not in ('CLOSED','VERIFIED','REVOKED')"),
                                "项", "quality_finding + config_item", "open WARNING findings and quality operations"),
                        metric("整改闭环", count(tenantId, "quality_finding", "state = 'RESOLVED'")
                                        + qualityOperationCount(tenantId, "payload->>'workflow_status' in ('CLOSED','VERIFIED','REVOKED')"),
                                "项", "quality_finding + config_item", "resolved findings + terminal quality operations"));
            }
            default -> throw new MetricSnapshotException(
                    "METRIC_TYPE_UNSUPPORTED", 422, "未登记的指标目录：" + metricType);
        };
    }

    private double count(UUID tenantId, String table, String predicate) {
        String sql = "select count(*) from " + table + " where tenant_id = :tenant"
                + (predicate == null ? "" : " and " + predicate);
        return jdbc.sql(sql).param("tenant", tenantId).query(Long.class).single().doubleValue();
    }

    private double qualityOperationCount(UUID tenantId, String predicate) {
        String qualityTypes = "config_type in ('QUALITY_INITIATIVE','DEPARTMENT_QC_CASE','QUALITY_RATING_EVIDENCE','INFECTION_CONTROL_CASE','CLINICAL_CREDENTIAL_GRANT')";
        return count(tenantId, "config_item", "status <> 'ARCHIVED' and " + qualityTypes + " and " + predicate);
    }

    private MetricDefinition metric(String name, double value, String unit, String source, String formula) {
        return new MetricDefinition(name, value, unit, Map.of("source", source, "formula", formula, "scope", "TENANT"));
    }

    private ResearchSnapshot latestMaterializedResearchSnapshot(UUID tenantId) {
        return jdbc.sql("""
                select research_cohort_snapshot_id, member_count, computed_at
                from research_cohort_snapshot
                where tenant_id = :tenant and member_snapshot_materialized = true
                order by computed_at desc, research_cohort_snapshot_id desc limit 1
                """).param("tenant", tenantId).query((rs, row) -> new ResearchSnapshot(
                        rs.getObject("research_cohort_snapshot_id", UUID.class), rs.getInt("member_count"),
                        rs.getObject("computed_at", OffsetDateTime.class).toInstant()))
                .optional().orElse(null);
    }

    private List<MetricDefinition> researchAgeDistribution(UUID tenantId, ResearchSnapshot snapshot) {
        List<String> labels = List.of("0-17", "18-44", "45-64", "65+");
        List<MetricDefinition> result = new java.util.ArrayList<>();
        double total = snapshot == null ? 0 : snapshot.memberCount();
        for (int index = 0; index < labels.size(); index++) {
            int lower = switch (index) { case 0 -> 0; case 1 -> 18; case 2 -> 45; default -> 65; };
            Integer upper = switch (index) {
                case 0 -> 17;
                case 1 -> 44;
                case 2 -> 64;
                default -> null;
            };
            String sql = "select count(*) from research_cohort_snapshot_member where tenant_id=:tenant and research_cohort_snapshot_id=:snapshot and (:snapshot_year - birth_year) >= :lower" + (upper == null ? "" : " and (:snapshot_year - birth_year) <= :upper");
            JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenant", tenantId)
                    .param("snapshot", snapshot == null ? new UUID(0, 0) : snapshot.snapshotId())
                    .param("snapshot_year", snapshot == null ? LocalDate.now().getYear() : snapshot.computedAt().atOffset(java.time.ZoneOffset.UTC).getYear())
                    .param("lower", lower);
            if (upper != null) spec = spec.param("upper", upper);
            double value = spec.query(Long.class).single();
            Map<String, Object> dimension = new java.util.LinkedHashMap<>(Map.of(
                    "source", "research_cohort_snapshot_member", "formula", "snapshot year - frozen birth year",
                    "scope", "TENANT", "group", "AGE_DISTRIBUTION", "label", labels.get(index), "order", index,
                    "rate", total == 0 ? 0 : value * 100d / total,
                    "cohort_version", snapshot == null ? "no materialized snapshot" : "snapshot:" + snapshot.snapshotId(),
                    "indicator_version", "research-stats-v2"));
            if (snapshot != null) dimension.put("data_cutoff", snapshot.computedAt().toString());
            result.add(new MetricDefinition("年龄分层 " + labels.get(index), value, "人", dimension));
        }
        return result;
    }

    private MetricDefinition researchQuarterTrend(UUID tenantId) {
        List<String> labels = new java.util.ArrayList<>(); List<Double> values = new java.util.ArrayList<>();
        for (int offset = 4; offset >= 0; offset--) {
            LocalDate start = LocalDate.now().minusMonths(offset * 3L).withMonth(((LocalDate.now().minusMonths(offset * 3L).getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
            LocalDate end = start.plusMonths(3);
            double added = jdbc.sql("""
                    select count(*) from research_cohort_snapshot_member m
                    join research_cohort_snapshot s on s.tenant_id = m.tenant_id
                      and s.research_cohort_snapshot_id = m.research_cohort_snapshot_id
                    where m.tenant_id = :tenant and s.member_snapshot_materialized = true
                      and s.computed_at >= :start and s.computed_at < :end
                    """).param("tenant", tenantId).param("start", start.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)).param("end", end.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)).query(Long.class).single();
            labels.add(start.getYear() + "Q" + ((start.getMonthValue() - 1) / 3 + 1)); values.add(added);
        }
        return new MetricDefinition("队列成员季度物化", values.stream().mapToDouble(Double::doubleValue).sum(), "人次", Map.of(
                "source", "research_cohort_snapshot_member", "formula", "quarterly materialized snapshot member count", "scope", "TENANT",
                "group", "TREND", "labels", labels, "values", values, "cohort_version", "materialized snapshot ledger",
                "indicator_version", "research-stats-v2"));
    }

    private UUID insertSnapshot(ClinicalIdentity identity, String metricType, MetricDefinition definition) {
        UUID snapshotId = UUID.randomUUID();
        jdbc.sql("""
                insert into metric_snapshot(
                  tenant_id, snapshot_id, metric_type, metric_name, metric_value, unit, dimension, period)
                values (:tenant, :snapshot, :type, :name, :value, :unit,
                  cast(:dimension as jsonb), :period)
                """).param("tenant", identity.tenantId()).param("snapshot", snapshotId)
                .param("type", metricType).param("name", definition.name())
                .param("value", definition.value()).param("unit", definition.unit())
                .param("dimension", json(definition.dimension()))
                .param("period", LocalDate.now()).update();
        appendEvidence(identity, "METRIC_SNAPSHOT_COMPUTED", metricType, snapshotId);
        return snapshotId;
    }

    private record MetricDefinition(String name, double value, String unit, Map<String, Object> dimension) {}
    private record ResearchSnapshot(UUID snapshotId, int memberCount, Instant computedAt) {}

    private MetricSnapshotWire item(UUID tenantId, UUID snapshotId) {
        return jdbc.sql("""
                select snapshot_id, metric_type, metric_name, metric_value, unit, dimension::text,
                       period, status, row_version, computed_at, created_at
                from metric_snapshot where tenant_id = :tenant and snapshot_id = :snapshot
                """).param("tenant", tenantId).param("snapshot", snapshotId)
                .query((rs, row) -> wire(rs))
                .optional().orElseThrow(() -> new MetricSnapshotException(
                        "METRIC_SNAPSHOT_NOT_FOUND", 404, "指标快照不存在"));
    }

    private MetricSnapshotWire wire(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MetricSnapshotWire(
                rs.getObject("snapshot_id", UUID.class),
                rs.getString("metric_type"),
                rs.getString("metric_name"),
                rs.getBigDecimal("metric_value") == null ? null : rs.getBigDecimal("metric_value").doubleValue(),
                rs.getString("unit"),
                dimension(rs.getString("dimension")),
                rs.getObject("period", LocalDate.class),
                MetricSnapshotWire.StatusValue.valueOf(rs.getString("status")),
                rs.getLong("row_version"),
                rs.getObject("computed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private void appendEvidence(ClinicalIdentity identity, String action, String metricType, UUID snapshotId) {
        String previousHash = jdbc.sql(
                "select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|" + snapshotId
                + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, 'METRIC_SNAPSHOT', :resource,
                  :trace, :previous, :hash, jsonb_build_object('metric_type', :metric_type))
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", snapshotId)
                .param("trace", trace).param("previous", previousHash).param("hash", eventHash)
                .param("metric_type", metricType).update();
    }

    private Map<String, Object> dimension(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new MetricSnapshotException("METRIC_DIMENSION_INVALID", 500, "存储的维度载荷无效");
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception invalid) {
            throw new MetricSnapshotException("METRIC_DIMENSION_INVALID", 400, "维度载荷不可序列化");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
