package org.haoemr.agent;

import java.util.Map;
import java.util.UUID;

interface ClinicalModelProvider {

    boolean supports(String providerCode);

    Map<String, Object> generate(DraftPrompt prompt);

    record DraftPrompt(UUID tenantId, String providerCode, String modelCode,
            Map<String, Object> currentSections, int maxOutputTokens) {
        DraftPrompt(Map<String, Object> currentSections, int maxOutputTokens) {
            this(null, null, null, currentSections, maxOutputTokens);
        }
    }
}
