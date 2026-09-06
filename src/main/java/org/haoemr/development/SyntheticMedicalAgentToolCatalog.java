package org.haoemr.development;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Migrations precede the first synthetic tenant; install its versioned read-tool catalog after import. */
final class SyntheticMedicalAgentToolCatalog {
    private SyntheticMedicalAgentToolCatalog() {}
    @SuppressWarnings("unchecked")
    static void install(JdbcClient jdbc, UUID tenantId) {
        try (var input = SyntheticMedicalAgentToolCatalog.class.getResourceAsStream("/medical-agents/tools-v1.json")) {
            Map<String, Object> catalog = new ObjectMapper().readValue(input, Map.class);
            for (var tool : (List<Map<String, String>>) catalog.get("tools")) {
                jdbc.sql("""
                        insert into tool_registry(tenant_id,tool_registry_id,tool_code,tool_name,tool_version,tool_type,status)
                        values (:tenant,:id,:code,:name,'1.0.0','DATABASE_QUERY','ACTIVE')
                        on conflict(tenant_id,tool_code,tool_version) do nothing
                        """).param("tenant",tenantId).param("id",UUID.randomUUID())
                        .param("code",tool.get("code")).param("name",tool.get("name")).update();
            }
            var dependencies = (Map<String, List<String>>) catalog.get("dependencies");
            for (var agent : dependencies.entrySet()) for (String tool : agent.getValue()) {
                jdbc.sql("""
                        insert into agent_dependency(tenant_id,agent_dependency_id,agent_registry_id,dependency_type,dependency_code)
                        select tenant_id,:id,agent_registry_id,'TOOL',:tool from agent_registry
                        where tenant_id=:tenant and agent_code=:agent and status='ACTIVE'
                        on conflict(tenant_id,agent_registry_id,dependency_type,dependency_code) do nothing
                        """).param("tenant",tenantId).param("id",UUID.randomUUID())
                        .param("agent",agent.getKey()).param("tool",tool).update();
            }
        } catch (Exception failure) { throw new IllegalStateException("Cannot install synthetic medical-agent tools",failure); }
    }
}
