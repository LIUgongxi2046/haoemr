package org.haoemr.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.MetricSnapshotRecordRequestWire;
import org.haoemr.contracts.MetricSnapshotWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MetricSnapshotApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private MetricSnapshotService metrics;

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(UUID.fromString(TENANT), UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenMetric_whenRecordingAndListing_thenSnapshotRecorded() {
        String type = "DATA_CENTER";
        MetricSnapshotWire recorded = metrics.record(identity(), new MetricSnapshotRecordRequestWire(
                type, "在院患者数", 1234.0, "人", null, null));
        assertThat(recorded.metricType()).isEqualTo(type);
        assertThat(recorded.status()).isEqualTo(MetricSnapshotWire.StatusValue.DRAFT);

        List<MetricSnapshotWire> listed = metrics.list(identity(), type);
        assertThat(listed).extracting(MetricSnapshotWire::snapshotId).contains(recorded.snapshotId());
    }

    @Test
    void givenResearchMembers_whenComputingResearchStats_thenAgeAndQuarterlyAggregatesAreSnapshotBacked() {
        List<MetricSnapshotWire> computed = metrics.compute(identity(), "RESEARCH_STATS");

        assertThat(computed).extracting(MetricSnapshotWire::metricName)
                .contains("队列快照", "纳入成员", "平均队列规模", "已输出研究集", "队列成员季度物化");
        assertThat(computed)
                .filteredOn(item -> "AGE_DISTRIBUTION".equals(item.dimension().get("group")))
                .hasSizeGreaterThanOrEqualTo(4)
                .allSatisfy(item -> {
                    assertThat(item.dimension()).containsEntry("source", "research_cohort_snapshot_member");
                    assertThat(item.dimension()).containsEntry("scope", "TENANT");
                });
        MetricSnapshotWire trend = computed.stream()
                .filter(item -> "TREND".equals(item.dimension().get("group")))
                .findFirst().orElseThrow();
        assertThat(trend.dimension()).containsEntry("source", "research_cohort_snapshot_member")
                .containsEntry("cohort_version", "materialized snapshot ledger");
        assertThat((List<?>) trend.dimension().get("labels")).hasSize(5);
        assertThat((List<?>) trend.dimension().get("values")).hasSize(5);
    }
}
