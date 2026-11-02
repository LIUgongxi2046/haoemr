package org.haoemr.model;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class SyntheticDemoProcessingPolicy {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final List<String> SCOPES = List.of("RECORDS", "ORDERS", "RESULTS", "TASKS", "CONFIGURATION");

    private SyntheticDemoProcessingPolicy() {}

    public static void requireEnvironment(Environment environment, UUID tenantId, List<String> scopes) {
        if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"dev-synthetic"})
                || !TENANT.equals(tenantId) || scopes == null || scopes.isEmpty()
                || !SCOPES.containsAll(scopes)) {
            throw denied();
        }
    }

    public static void requireDataset(JdbcClient jdbc, UUID tenantId, UUID patientId, UUID encounterId) {
        boolean synthetic = jdbc.sql("""
                select exists(select 1 from encounter where tenant_id = :tenant)
                  and not exists(select 1 from encounter where tenant_id = :tenant
                    and (source_system is null or source_system not in ('SYNTHETIC', 'SYNTHETIC-50')))
                """).param("tenant", tenantId).query(Boolean.class).single();
        if (!synthetic) throw denied();
        if (patientId != null || encounterId != null) {
            boolean matched = jdbc.sql("""
                    select exists(select 1 from encounter where tenant_id = :tenant
                      and patient_id = :patient and encounter_id = :encounter
                      and source_system in ('SYNTHETIC', 'SYNTHETIC-50'))
                    """).param("tenant", tenantId).param("patient", patientId)
                    .param("encounter", encounterId).query(Boolean.class).single();
            if (!matched) throw denied();
        }
    }

    public static void requireProvider(String provider, String endpoint) {
        if (!"DEEPSEEK".equals(provider) || !"https://api.deepseek.com".equals(endpoint)) throw denied();
    }

    private static ModelDeploymentException denied() {
        return new ModelDeploymentException("SYNTHETIC_DEMO_PROCESSING_NOT_PERMITTED", 409,
                "模拟演示授权仅适用于 dev-synthetic 环境中的项目模拟数据和已授权 DeepSeek 服务");
    }
}
