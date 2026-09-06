package org.haoemr.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.DeviceCatalogCreateRequestWire;
import org.haoemr.contracts.DeviceCatalogDeactivateRequestWire;
import org.haoemr.contracts.DeviceCatalogWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class DeviceCatalogApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DeviceCatalogService devices;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenSeededDevices_whenListingActive_thenDomainDevicesReturned() {
        List<DeviceCatalogWire> listed = devices.listDevices(identity(), "ACTIVE");
        assertThat(listed).extracting(DeviceCatalogWire::deviceCode)
                .contains("card-monitor-01", "icu-vent-07", "pump-a-118", "ct-01");
    }

    @Test
    void givenNewDevice_whenCreating_thenActiveRecorded() {
        String code = "DEV-" + UUID.randomUUID().toString().substring(0, 8);
        DeviceCatalogWire created = devices.create(identity(), "create-" + UUID.randomUUID(),
                new DeviceCatalogCreateRequestWire(organization, facility, code, "测试监护仪",
                        DeviceCatalogCreateRequestWire.DeviceTypeValue.MONITOR, null, "心血管内科一病区", null, null, null, 0, null));
        assertThat(created.status()).isEqualTo(DeviceCatalogWire.StatusValue.ACTIVE);
        assertThat(created.deviceCode()).isEqualTo(code);
    }

    @Test
    void givenActiveDevice_whenDeactivating_thenInactive() {
        String code = "DEV-" + UUID.randomUUID().toString().substring(0, 8);
        DeviceCatalogWire created = devices.create(identity(), "create-" + UUID.randomUUID(),
                new DeviceCatalogCreateRequestWire(organization, facility, code, "测试输注泵",
                        DeviceCatalogCreateRequestWire.DeviceTypeValue.INFUSION_PUMP, null, null, null, null, null, 0, null));
        DeviceCatalogWire deactivated = devices.deactivate(identity(), "deact-" + UUID.randomUUID(),
                created.deviceId(), new DeviceCatalogDeactivateRequestWire(organization, facility));
        assertThat(deactivated.status()).isEqualTo(DeviceCatalogWire.StatusValue.INACTIVE);
    }
}
