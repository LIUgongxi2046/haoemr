package org.haoemr.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PromptReleasePublishRequestWire;
import org.haoemr.contracts.PromptReleaseRetireRequestWire;
import org.haoemr.contracts.PromptReleaseWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class PromptReleaseApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private PromptReleaseService prompts;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private PromptReleaseWire publish(String promptCode, String version, String content) {
        return prompts.publish(identity(), "prompt-" + UUID.randomUUID(),
                new PromptReleasePublishRequestWire(organization, facility, promptCode, version,
                        "临床摘要提示词", content, Instant.now()));
    }

    @Test
    void givenPrompt_whenPublishingAndListing_thenActiveReleaseRecorded() {
        String promptCode = "PROMPT-" + UUID.randomUUID().toString().substring(0, 8);
        PromptReleaseWire published = publish(promptCode, "v1",
                "你是临床助手，请基于以下病历生成结构化摘要并保留证据引用。");
        assertThat(published.status()).isEqualTo(PromptReleaseWire.StatusValue.ACTIVE);
        assertThat(published.releaseVersion()).isEqualTo("v1");

        List<PromptReleaseWire> listed = prompts.listReleases(identity(), promptCode);
        assertThat(listed).extracting(PromptReleaseWire::promptReleaseId).contains(published.promptReleaseId());
    }

    @Test
    void givenNewVersion_whenPublishing_thenPreviousRetired() {
        String promptCode = "PROMPT-" + UUID.randomUUID().toString().substring(0, 8);
        PromptReleaseWire v1 = publish(promptCode, "v1", "第一版提示词内容，要求输出摘要");
        PromptReleaseWire v2 = publish(promptCode, "v2", "第二版提示词内容，要求输出摘要并标注来源");

        List<PromptReleaseWire> listed = prompts.listReleases(identity(), promptCode);
        assertThat(listed).extracting(PromptReleaseWire::status)
                .contains(PromptReleaseWire.StatusValue.ACTIVE, PromptReleaseWire.StatusValue.RETIRED);
        assertThat(v2.status()).isEqualTo(PromptReleaseWire.StatusValue.ACTIVE);

        long activeCount = jdbc.sql("""
                select count(*) from prompt_release
                where tenant_id = cast(:tenant as uuid) and prompt_code = :prompt and status = 'ACTIVE'
                """).param("tenant", TENANT).param("prompt", promptCode).query(Long.class).single();
        assertThat(activeCount).isEqualTo(1);
        assertThat(v1.status()).isEqualTo(PromptReleaseWire.StatusValue.ACTIVE);
    }

    @Test
    void givenActiveRelease_whenRetiring_thenRetired() {
        String promptCode = "PROMPT-" + UUID.randomUUID().toString().substring(0, 8);
        PromptReleaseWire published = publish(promptCode, "v1", "待退休提示词内容");
        PromptReleaseWire retired = prompts.retire(identity(), "retire-" + UUID.randomUUID(),
                published.promptReleaseId(), new PromptReleaseRetireRequestWire(organization, facility));
        assertThat(retired.status()).isEqualTo(PromptReleaseWire.StatusValue.RETIRED);
        assertThat(retired.effectiveTo()).isNotNull();
    }

    @Test
    void givenReleaseContent_whenTampered_thenDatabaseRejectsMutation() {
        String promptCode = "PROMPT-" + UUID.randomUUID().toString().substring(0, 8);
        PromptReleaseWire published = publish(promptCode, "v1", "不可篡改的提示词内容");
        assertThatThrownBy(() -> jdbc.sql("""
                update prompt_release set content = '篡改'
                where tenant_id = cast(:tenant as uuid) and prompt_release_id = :release
                """).param("tenant", TENANT).param("release", published.promptReleaseId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
