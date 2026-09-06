package org.haoemr.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ReleaseDownloadEventCreateRequestWire;
import org.haoemr.contracts.ReleaseDownloadEventWire;
import org.haoemr.contracts.ReleaseDownloadValidCountWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ReleaseDownloadEventApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ReleaseDownloadEventService downloads;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private static String fingerprint() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private ReleaseDownloadEventWire record(String channel, String userAgent, String fingerprint) {
        return downloads.record(identity(), "dl-" + UUID.randomUUID(),
                new ReleaseDownloadEventCreateRequestWire(organization, facility,
                        ReleaseDownloadEventCreateRequestWire.ChannelValue.valueOf(channel),
                        "203.0.113.7", userAgent, fingerprint, Instant.now()));
    }

    @Test
    void givenHumanDownload_whenRecording_thenNotRobot() {
        ReleaseDownloadEventWire recorded = record("GITHUB", "Mozilla/5.0 (Windows NT 10.0)", fingerprint());
        assertThat(recorded.isRobot()).isFalse();
    }

    @Test
    void givenBotDownload_whenRecording_thenRobot() {
        ReleaseDownloadEventWire recorded = record("WEBSITE", "Mozilla/5.0 (compatible; Googlebot/2.1)", fingerprint());
        assertThat(recorded.isRobot()).isTrue();
    }

    @Test
    void givenBlankAgent_whenRecording_thenRobot() {
        ReleaseDownloadEventWire recorded = record("PACKAGE_REGISTRY", null, fingerprint());
        assertThat(recorded.isRobot()).isTrue();
    }

    @Test
    void givenDuplicateValidDownload_whenRecording_thenRejected() {
        String shared = fingerprint();
        record("GITHUB", "Mozilla/5.0 (Windows NT 10.0)", shared);
        assertThatThrownBy(() -> record("GITHUB", "Mozilla/5.0 (Macintosh)", shared))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenInvalidFingerprint_whenRecording_thenRejected() {
        assertThatThrownBy(() -> record("GITHUB", "Mozilla/5.0", "not-a-hash"))
                .isInstanceOf(ReleaseDownloadEventException.class)
                .satisfies(e -> assertThat(((ReleaseDownloadEventException) e).code())
                        .isEqualTo("INVALID_FINGERPRINT_HASH"));
    }

    @Test
    void givenDownloads_whenCountingValid_thenExcludesRobots() {
        String channel = "DOCKER_HUB";
        long before = downloads.validCount(identity(), channel).validCount();
        record(channel, "Mozilla/5.0 (Windows NT 10.0)", fingerprint());
        record(channel, "curl/8.0", fingerprint());
        record(channel, "Mozilla/5.0 (compatible; bingbot/2.0)", fingerprint());

        ReleaseDownloadValidCountWire count = downloads.validCount(identity(), channel);
        assertThat(count.validCount()).isEqualTo(before + 1);
    }
}
