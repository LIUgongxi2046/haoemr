package org.haoemr.development;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record SyntheticDataset(String datasetVersion, boolean synthetic, List<String> cases) {

    public static SyntheticDataset parse(ObjectMapper objectMapper, String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!root.path("synthetic").asBoolean(false)) {
            throw new IllegalArgumentException("Only explicitly synthetic datasets may be imported");
        }
        var caseIds = new ArrayList<String>();
        for (JsonNode item : root.path("cases")) {
            caseIds.add(item.path("case_id").stringValue());
        }
        return new SyntheticDataset(root.path("dataset_version").stringValue(), true, List.copyOf(caseIds));
    }
}
