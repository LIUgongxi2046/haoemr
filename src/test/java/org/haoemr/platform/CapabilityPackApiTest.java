package org.haoemr.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.CapabilityPackDeactivateRequestWire;
import org.haoemr.contracts.CapabilityPackDefineRequestWire;
import org.haoemr.contracts.CapabilityPackWire;
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
final class CapabilityPackApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private CapabilityPackService packs;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private CapabilityPackWire define(String packCode, String inheritsFrom) {
        return packs.define(identity(), "pack-" + UUID.randomUUID(),
                new CapabilityPackDefineRequestWire(organization, facility, packCode,
                        "能力包-" + packCode, inheritsFrom));
    }

    @Test
    void givenPack_whenDefiningAndListing_thenActivePackRecorded() {
        String base = "PACK-" + UUID.randomUUID().toString().substring(0, 8);
        CapabilityPackWire defined = define(base, null);
        assertThat(defined.status()).isEqualTo(CapabilityPackWire.StatusValue.ACTIVE);
        assertThat(defined.inheritsFrom()).isNull();

        String child = "PACK-" + UUID.randomUUID().toString().substring(0, 8);
        CapabilityPackWire childPack = define(child, base);
        assertThat(childPack.inheritsFrom()).isEqualTo(base);

        List<CapabilityPackWire> listed = packs.listPacks(identity(), "ACTIVE");
        assertThat(listed).extracting(CapabilityPackWire::capabilityPackId).contains(defined.capabilityPackId());
    }

    @Test
    void givenSelfInheritance_whenDefining_thenRejected() {
        String code = "PACK-" + UUID.randomUUID().toString().substring(0, 8);
        assertThatThrownBy(() -> define(code, code))
                .isInstanceOf(CapabilityPackException.class)
                .satisfies(e -> assertThat(((CapabilityPackException) e).code())
                        .isEqualTo("CAPABILITY_PACK_REQUEST_INVALID"));
    }

    @Test
    void givenActivePack_whenDeactivating_thenInactive() {
        String code = "PACK-" + UUID.randomUUID().toString().substring(0, 8);
        CapabilityPackWire defined = define(code, null);
        CapabilityPackWire deactivated = packs.deactivate(identity(), "deact-" + UUID.randomUUID(),
                defined.capabilityPackId(), new CapabilityPackDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(CapabilityPackWire.StatusValue.INACTIVE);
    }

    @Test
    void givenActivePack_whenEditing_thenNameAndInheritanceArePersisted() {
        String base = "PACK-" + UUID.randomUUID().toString().substring(0, 8);
        CapabilityPackWire parent = define(base, null);
        String childCode = "PACK-" + UUID.randomUUID().toString().substring(0, 8);
        CapabilityPackWire child = define(childCode, null);

        CapabilityPackWire updated = packs.update(identity(), "edit-" + UUID.randomUUID(),
                child.capabilityPackId(), new CapabilityPackDefineRequestWire(
                        organization, facility, childCode, "急诊闭环能力包", parent.packCode()));

        assertThat(updated.packName()).isEqualTo("急诊闭环能力包");
        assertThat(updated.inheritsFrom()).isEqualTo(parent.packCode());
    }

    @Test
    void givenPackIdentity_whenTampered_thenDatabaseRejectsMutation() {
        String code = "PACK-" + UUID.randomUUID().toString().substring(0, 8);
        CapabilityPackWire defined = define(code, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update capability_pack set pack_code = 'TAMPERED'
                where tenant_id = cast(:tenant as uuid) and capability_pack_id = :pack
                """).param("tenant", TENANT).param("pack", defined.capabilityPackId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
