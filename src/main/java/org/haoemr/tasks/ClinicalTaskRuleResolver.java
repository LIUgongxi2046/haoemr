package org.haoemr.tasks;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Resolves an approved task rule and freezes the effective SLA on every created task. */
@Service
public final class ClinicalTaskRuleResolver {
    private final JdbcClient jdbc;

    public ClinicalTaskRuleResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ResolvedTaskRule resolve(
            UUID tenantId, String taskType, String careDomain, String defaultRisk, Duration defaultDue) {
        List<String> aliases = aliases(taskType);
        String pgAliases = "{" + aliases.stream().map(value -> "\"" + value.replace("\"", "") + "\"")
                .reduce((left, right) -> left + "," + right).orElse("") + "}";
        String normalizedDomain = normalizeDomain(careDomain);
        ResolvedTaskRule configured = jdbc.sql("""
                select config_id, row_version, payload::text,
                  payload->>'risk_level' as risk_level,
                  (payload->>'due_minutes')::bigint as due_minutes,
                  (payload->>'escalation_minutes')::bigint as escalation_minutes
                from config_item
                where tenant_id = :tenant and config_type = 'CLINICAL_TASK_RULE'
                  and status = 'ACTIVE' and validation_state = 'VALID'
                  and approval_state = 'APPROVED'
                  and coalesce((payload->>'enabled')::boolean, true)
                  and payload->>'task_type' = any(cast(:aliases as text[]))
                  and (not jsonb_exists(payload, 'applies_to')
                    or jsonb_exists(payload->'applies_to', :domain))
                order by published_at desc, row_version desc, config_id desc limit 1
                """).param("tenant", tenantId).param("aliases", pgAliases).param("domain", normalizedDomain)
                .query((rs, row) -> {
                    long dueMinutes = rs.getLong("due_minutes");
                    long escalationMinutes = rs.getLong("escalation_minutes");
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    return new ResolvedTaskRule(
                            rs.getObject("config_id", UUID.class), rs.getLong("row_version"),
                            rs.getString("risk_level"), now.plusMinutes(dueMinutes),
                            now.plusMinutes(Math.max(0, dueMinutes - escalationMinutes)),
                            rs.getString("payload"));
                }).optional().orElse(null);
        if (configured != null) return configured;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ResolvedTaskRule(null, null, defaultRisk, now.plus(defaultDue), null, "{}");
    }

    private static List<String> aliases(String taskType) {
        return switch (taskType) {
            case "CRITICAL_VALUE_RECEIPT", "CRITICAL_VALUE_DISPOSITION" ->
                    List.of(taskType, "CRITICAL_VALUE", "危急值处置");
            case "CONSULTATION_RESPONSE" -> List.of(taskType, "EMERGENCY_CONSULTATION", "急会诊响应");
            default -> List.of(taskType);
        };
    }

    private static String normalizeDomain(String careDomain) {
        return switch (careDomain) {
            case "OUTPATIENT" -> "门诊";
            case "EMERGENCY" -> "急诊";
            case "INPATIENT" -> "住院";
            default -> careDomain;
        };
    }

    public record ResolvedTaskRule(
            UUID configId, Long configVersion, String riskLevel, OffsetDateTime dueAt,
            OffsetDateTime escalationAt, String snapshotJson) {}
}
