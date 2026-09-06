package org.haoemr.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ReleaseMetricSnapshotCreateRequestWire;
import org.haoemr.contracts.ReleaseMetricSnapshotWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ReleaseMetricSnapshotApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ReleaseMetricSnapshotService snapshots;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ReleaseMetricSnapshotWire record(String metricType, int value, String source, LocalDate date) {
        return snapshots.record(identity(), "metric-" + UUID.randomUUID(),
                new ReleaseMetricSnapshotCreateRequestWire(organization, facility,
                        ReleaseMetricSnapshotCreateRequestWire.MetricTypeValue.valueOf(metricType),
                        value, source, date));
    }

    @Test
    void givenSnapshot_whenRecording_thenRecorded() {
        String source = "GITHUB-" + UUID.randomUUID().toString().substring(0, 8);
        ReleaseMetricSnapshotWire recorded = record("STARS", 42, source, LocalDate.now());
        assertThat(recorded.metricType()).isEqualTo(ReleaseMetricSnapshotWire.MetricTypeValue.STARS);
        assertThat(recorded.metricValue()).isEqualTo(42);

        List<ReleaseMetricSnapshotWire> listed = snapshots.listRecords(
                identity(), ReleaseMetricSnapshotWire.MetricTypeValue.STARS);
        assertThat(listed).extracting(ReleaseMetricSnapshotWire::snapshotId).contains(recorded.snapshotId());
    }

    @Test
    void givenDuplicateSnapshot_whenRecording_thenRejected() {
        String source = "NPM-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDate date = LocalDate.now();
        record("DOWNLOADS", 100, source, date);
        assertThatThrownBy(() -> record("DOWNLOADS", 101, source, date))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenNegativeMetricValue_whenRecording_thenRejected() {
        assertThatThrownBy(() -> record("DOWNLOADS", -1, "NPM", LocalDate.now()))
                .isInstanceOf(ReleaseMetricSnapshotException.class)
                .satisfies(e -> assertThat(((ReleaseMetricSnapshotException) e).code())
                        .isEqualTo("RELEASE_METRIC_REQUEST_INVALID"));
    }
}
