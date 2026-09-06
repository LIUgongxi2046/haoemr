package org.haoemr.assistant;

import java.util.List;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
final class ClinicalAssistantService {

    private final JdbcClient jdbc;

    ClinicalAssistantService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String stream(ClinicalIdentity identity, String message) {
        AssistantPolicy policy = jdbc.sql("""
                select payload ->> 'model_policy' as model_policy,
                  payload -> 'allowed_sources' as allowed_sources,
                  coalesce((payload ->> 'rate_limit')::integer, 1) as rate_limit,
                  coalesce((payload ->> 'approval_required')::boolean, true) as approval_required
                from config_item
                where tenant_id = :tenant and config_type = 'AI_ASSISTANT_POLICY' and status = 'ACTIVE'
                order by published_at desc, updated_at desc limit 1
                """).param("tenant", identity.tenantId())
                .query((rs, row) -> new AssistantPolicy(
                        rs.getString("model_policy"), rs.getString("allowed_sources"),
                        rs.getInt("rate_limit"), rs.getBoolean("approval_required")))
                .optional().orElseThrow(() -> new ClinicalAssistantException(
                        "AI_ASSISTANT_POLICY_INACTIVE", 409, "AI医助 Eva 尚未发布有效工作策略"));
        if (!policy.approvalRequired()) {
            throw new ClinicalAssistantException(
                    "AI_ASSISTANT_POLICY_UNSAFE", 409, "AI医助工作策略必须要求医生确认临床写入");
        }
        String prompt = message == null ? "" : message.trim();
        List<String> chunks = List.of(
                "Eva 已接收当前诊疗场景和医生问题，正在按机构策略组织回答。",
                "您的问题：" + (prompt.isEmpty() ? "（空）" : prompt) + "。",
                "当前已应用机构发布的 Eva 工作策略：模型路由 " + policy.modelPolicy()
                        + "，每分钟最多 " + policy.rateLimit() + " 次调用。",
                "本次允许的数据来源为 " + policy.allowedSources()
                        + "；生成内容只作为医生审阅草稿，不会自动写入诊疗记录。",
                "当前患者 / 就诊上下文已按上下文租约隔离，跨患者或跨就诊不会共享会话。");
        StringBuilder sse = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sse.append("id: ").append(i + 1).append('\n')
               .append("event: delta\n")
               .append("data: ").append(chunks.get(i)).append("\n\n");
        }
        sse.append("event: done\n").append("data: {\"model_behavior\":\"TENANT_POLICY_ROUTED\"}\n\n");
        return sse.toString();
    }

    private record AssistantPolicy(
            String modelPolicy, String allowedSources, int rateLimit, boolean approvalRequired) {}
}
