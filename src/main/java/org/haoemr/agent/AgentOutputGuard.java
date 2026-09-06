package org.haoemr.agent;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class AgentOutputGuard {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "signature", "signed_at", "sign_document", "tool_call", "execute_sql",
            "write_clinical_fact", "tenant_id", "authorization_watermark");

    void validate(Map<String, Object> payload) {
        if (payload == null || !(payload.get("sections") instanceof Map<?, ?>)) {
            throw new AgentRunException("AI_OUTPUT_SCHEMA_INVALID", 422, "The model output did not match the proposal schema");
        }
        rejectForbiddenKeys(payload);
    }

    private void rejectForbiddenKeys(Map<?, ?> value) {
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            String key = String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_KEYS.contains(key)) {
                throw new AgentRunException("AI_FORBIDDEN_ACTION", 422, "AI output contains a forbidden action or authority field");
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                rejectForbiddenKeys(nested);
            }
        }
    }
}
