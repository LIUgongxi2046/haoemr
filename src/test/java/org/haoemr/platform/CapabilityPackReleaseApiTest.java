package org.haoemr.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.CapabilityPackReleaseCreateRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseRollbackRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseTransitionRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class CapabilityPackReleaseApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private CapabilityPackReleaseService releases;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedPack() {
        UUID packId = UUID.randomUUID();
        jdbc.sql("""
                insert into capability_pack(tenant_id, capability_pack_id, pack_code, pack_name, status)
                values (cast(:tenant as uuid), :pack, :code, '能力包', 'ACTIVE')
                """).param("tenant", TENANT).param("pack", packId)
                .param("code", "PKG-" + UUID.randomUUID().toString().substring(0, 12)).update();
        return packId;
    }

    private CapabilityPackReleaseWire create(UUID packId, String version) {
        return releases.create(identity(), "create-" + UUID.randomUUID(),
                new CapabilityPackReleaseCreateRequestWire(organization, facility, packId, version, Instant.now()));
    }

    private CapabilityPackReleaseWire startCanary(UUID releaseId, long expectedRowVersion) {
        return releases.startCanary(identity(), "canary-" + UUID.randomUUID(), releaseId,
                new CapabilityPackReleaseTransitionRequestWire(organization, facility, expectedRowVersion));
    }

    private CapabilityPackReleaseWire promote(UUID releaseId, long expectedRowVersion) {
        return releases.promote(identity(), "promote-" + UUID.randomUUID(), releaseId,
                new CapabilityPackReleaseTransitionRequestWire(organization, facility, expectedRowVersion));
    }

    private CapabilityPackReleaseWire rollback(UUID releaseId, long expectedRowVersion, String reason) {
        return releases.rollback(identity(), "rollback-" + UUID.randomUUID(), releaseId,
                new CapabilityPackReleaseRollbackRequestWire(organization, facility, expectedRowVersion, reason));
    }

    @Test
    void givenActivePack_whenCreatingRelease_thenDraft() {
        CapabilityPackReleaseWire release = create(seedPack(), "v1.0.0");
        assertThat(release.lifecycleStatus()).isEqualTo(CapabilityPackReleaseWire.LifecycleStatusValue.DRAFT);
        assertThat(release.canaryStartedAt()).isNull();
    }

    @Test
    void givenDraft_whenStartingCanary_thenCanary() {
        CapabilityPackReleaseWire draft = create(seedPack(), "v1.0.0");
        CapabilityPackReleaseWire canary = startCanary(draft.releaseId(), draft.rowVersion());
        assertThat(canary.lifecycleStatus()).isEqualTo(CapabilityPackReleaseWire.LifecycleStatusValue.CANARY);
        assertThat(canary.canaryStartedAt()).isNotNull();
    }

    @Test
    void givenCanary_whenPromoting_thenActive() {
        CapabilityPackReleaseWire draft = create(seedPack(), "v1.0.0");
        CapabilityPackReleaseWire canary = startCanary(draft.releaseId(), draft.rowVersion());
        CapabilityPackReleaseWire active = promote(canary.releaseId(), canary.rowVersion());
        assertThat(active.lifecycleStatus()).isEqualTo(CapabilityPackReleaseWire.LifecycleStatusValue.ACTIVE);
        assertThat(active.promotedAt()).isNotNull();
    }

    @Test
    void givenCanary_whenRollingBack_thenRolledBack() {
        CapabilityPackReleaseWire draft = create(seedPack(), "v1.0.0");
        CapabilityPackReleaseWire canary = startCanary(draft.releaseId(), draft.rowVersion());
        CapabilityPackReleaseWire rolledBack = rollback(canary.releaseId(), canary.rowVersion(), "灰度失败回退");
        assertThat(rolledBack.lifecycleStatus()).isEqualTo(CapabilityPackReleaseWire.LifecycleStatusValue.ROLLED_BACK);
        assertThat(rolledBack.rollbackReason()).isEqualTo("灰度失败回退");
    }

    @Test
    void givenDraft_whenPromotingDirectly_thenRejected() {
        CapabilityPackReleaseWire draft = create(seedPack(), "v1.0.0");
        assertThatThrownBy(() -> promote(draft.releaseId(), draft.rowVersion()))
                .isInstanceOf(CapabilityPackReleaseException.class)
                .satisfies(e -> assertThat(((CapabilityPackReleaseException) e).code())
                        .isEqualTo("CAPABILITY_PACK_RELEASE_STATE_INVALID"));
    }

    @Test
    void givenStaleVersion_whenStartingCanary_thenRejected() {
        CapabilityPackReleaseWire draft = create(seedPack(), "v1.0.0");
        assertThatThrownBy(() -> startCanary(draft.releaseId(), 99L))
                .isInstanceOf(CapabilityPackReleaseException.class)
                .satisfies(e -> assertThat(((CapabilityPackReleaseException) e).code())
                        .isEqualTo("CAPABILITY_PACK_RELEASE_VERSION_CONFLICT"));
    }

    @Test
    void givenTwoReleases_whenPromotingSecond_thenFirstRetired() {
        UUID packId = seedPack();
        CapabilityPackReleaseWire firstDraft = create(packId, "v1.0.0");
        CapabilityPackReleaseWire firstCanary = startCanary(firstDraft.releaseId(), firstDraft.rowVersion());
        promote(firstCanary.releaseId(), firstCanary.rowVersion());

        CapabilityPackReleaseWire secondDraft = create(packId, "v2.0.0");
        CapabilityPackReleaseWire secondCanary = startCanary(secondDraft.releaseId(), secondDraft.rowVersion());
        CapabilityPackReleaseWire secondActive = promote(secondCanary.releaseId(), secondCanary.rowVersion());
        assertThat(secondActive.lifecycleStatus()).isEqualTo(CapabilityPackReleaseWire.LifecycleStatusValue.ACTIVE);

        List<CapabilityPackReleaseWire> listed = releases.listReleases(identity(), packId);
        assertThat(listed).extracting(CapabilityPackReleaseWire::lifecycleStatus)
                .containsExactlyInAnyOrder(
                        CapabilityPackReleaseWire.LifecycleStatusValue.ACTIVE,
                        CapabilityPackReleaseWire.LifecycleStatusValue.RETIRED);
    }

    @Test
    void givenRelease_whenTampered_thenDatabaseRejectsMutation() {
        CapabilityPackReleaseWire draft = create(seedPack(), "v1.0.0");
        assertThatThrownBy(() -> jdbc.sql("""
                update capability_pack_release set release_version = 'forged'
                where tenant_id = cast(:tenant as uuid) and release_id = :release
                """).param("tenant", TENANT).param("release", draft.releaseId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
