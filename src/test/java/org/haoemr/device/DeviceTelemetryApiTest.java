package org.haoemr.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.DeviceObservationWire;
import org.haoemr.contracts.DeviceAlertTransitionRequestWire;
import org.haoemr.contracts.DeviceAlertWire;
import org.haoemr.contracts.DeviceStatusWire;
import org.haoemr.contracts.DeviceStatusBindingRequestWire;
import org.haoemr.contracts.DeviceStatusUnbindingRequestWire;
import org.haoemr.contracts.DeviceTelemetryCollectRequestWire;
import org.haoemr.contracts.DeviceTelemetryCollectResultWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class DeviceTelemetryApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DeviceTelemetryService telemetry;
    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenActiveDevice_whenCollecting_thenObservationsAndStatusPersisted() {
        Instant collectedAfter = Instant.now().minusSeconds(1);
        DeviceTelemetryCollectResultWire result = telemetry.collect(identity(), "collect-" + UUID.randomUUID(),
                new DeviceTelemetryCollectRequestWire(organization, facility, "card-monitor-01",
                        DeviceTelemetryCollectRequestWire.SimulationScenarioValue.SUCCESS, 24));

        assertThat(result.observations()).hasSize(24);
        assertThat(result.observations()).allMatch(observation ->
                observation.deviceCode().equals("card-monitor-01"));
        assertThat(result.status().deviceCode()).isEqualTo("card-monitor-01");
        assertThat(result.status().onlineStatus()).isEqualTo(DeviceStatusWire.OnlineStatusValue.ONLINE);
        assertThat(result.observations()).allMatch(observation -> observation.observedAt().isAfter(collectedAfter));
    }

    @Test
    void givenCollectedTelemetry_whenListingObservations_thenFilteredByDevice() {
        telemetry.collect(identity(), "collect-" + UUID.randomUUID(),
                new DeviceTelemetryCollectRequestWire(organization, facility, "icu-vent-07",
                        DeviceTelemetryCollectRequestWire.SimulationScenarioValue.DEGRADED, 12));

        List<DeviceObservationWire> observations = telemetry.listObservations(identity(), "icu-vent-07");
        assertThat(observations).isNotEmpty();
        assertThat(observations).allMatch(observation -> observation.deviceCode().equals("icu-vent-07"));

        List<DeviceStatusWire> statuses = telemetry.listStatuses(identity(), "icu-vent-07");
        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).onlineStatus()).isEqualTo(DeviceStatusWire.OnlineStatusValue.DEGRADED);
    }

    @Test
    void givenRaisedTelemetryAlarm_whenHandled_thenStateAndActorEvidenceArePersisted() {
        telemetry.collect(identity(), "collect-alert-" + UUID.randomUUID(),
                new DeviceTelemetryCollectRequestWire(organization, facility, "card-monitor-01",
                        DeviceTelemetryCollectRequestWire.SimulationScenarioValue.SUCCESS, 24));
        DeviceAlertWire opened = telemetry.listAlerts(identity(), "card-monitor-01", false).stream()
                .findFirst().orElseThrow();
        assertThat(opened.state()).isEqualTo(DeviceAlertWire.StateValue.OPEN);

        DeviceAlertWire acknowledged = telemetry.transitionAlert(identity(), "ack-alert-" + UUID.randomUUID(),
                opened.deviceAlertId(), new DeviceAlertTransitionRequestWire(
                        organization, facility, opened.rowVersion(),
                        DeviceAlertTransitionRequestWire.ActionValue.ACKNOWLEDGE, "已复核患者和设备状态，正在处置"));
        assertThat(acknowledged.state()).isEqualTo(DeviceAlertWire.StateValue.ACKNOWLEDGED);
        assertThat(acknowledged.acknowledgedBy()).isEqualTo(UUID.fromString(USER));
        assertThatThrownBy(() -> telemetry.transitionAlert(identity(), "repeat-ack-" + UUID.randomUUID(),
                acknowledged.deviceAlertId(), new DeviceAlertTransitionRequestWire(
                        organization, facility, acknowledged.rowVersion(),
                        DeviceAlertTransitionRequestWire.ActionValue.ACKNOWLEDGE, "禁止重复确认同一告警")))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("当前告警状态不允许");

        DeviceAlertWire closed = telemetry.transitionAlert(identity(), "close-alert-" + UUID.randomUUID(),
                acknowledged.deviceAlertId(), new DeviceAlertTransitionRequestWire(
                        organization, facility, acknowledged.rowVersion(),
                        DeviceAlertTransitionRequestWire.ActionValue.CLOSE, "已完成床旁复核并恢复正常范围"));
        assertThat(closed.state()).isEqualTo(DeviceAlertWire.StateValue.CLOSED);
        assertThat(closed.closedBy()).isEqualTo(UUID.fromString(USER));
        assertThat(telemetry.listAlerts(identity(), "card-monitor-01", false))
                .noneMatch(item -> item.deviceAlertId().equals(closed.deviceAlertId()));
    }

    @Test
    void givenUnknownDevice_whenCollecting_thenRejected() {
        assertThatThrownBy(() -> telemetry.collect(identity(), "collect-" + UUID.randomUUID(),
                new DeviceTelemetryCollectRequestWire(organization, facility, "not-a-device", null, null)))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("设备不存在");
    }

    @Test
    void givenVerifiedBinding_whenUnbound_thenHistoricalBindingAndEvidenceAreRetained() {
        long bindingAuditCountBefore = jdbc.sql("""
                select count(*) from audit_event where tenant_id = :tenant
                  and action_code in ('DEVICE_STATUS_BOUND', 'DEVICE_STATUS_UNBOUND')
                """).param("tenant", tenant).query(Long.class).single();
        DeviceTelemetryCollectResultWire collected = telemetry.collect(identity(), "collect-bind-" + UUID.randomUUID(),
                new DeviceTelemetryCollectRequestWire(organization, facility, "card-monitor-01",
                        DeviceTelemetryCollectRequestWire.SimulationScenarioValue.SUCCESS, 12));
        EncounterHead encounter = jdbc.sql("""
                select patient_id, encounter_id from encounter
                where tenant_id = :tenant and facility_id = :facility
                  and status in ('ARRIVED', 'IN_PROGRESS', 'SUSPENDED')
                order by started_at desc, encounter_id desc limit 1
                """).param("tenant", tenant).param("facility", facility)
                .query((rs, row) -> new EncounterHead(rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class))).single();

        DeviceStatusWire bound = telemetry.bindStatus(identity(), "bind-" + UUID.randomUUID(), "card-monitor-01",
                new DeviceStatusBindingRequestWire(organization, facility, encounter.patientId(), encounter.encounterId(),
                        collected.status().rowVersion(), "腕带与设备序列号双标识核验"));
        assertThat(bound.boundPatientId()).isEqualTo(encounter.patientId());
        assertThat(jdbc.sql("""
                select count(*) from device_patient_binding where tenant_id = :tenant
                  and device_code = 'card-monitor-01' and patient_id = :patient and unbound_at is null
                """).param("tenant", tenant).param("patient", encounter.patientId()).query(Long.class).single()).isOne();

        DeviceStatusWire unbound = telemetry.unbindStatus(identity(), "unbind-" + UUID.randomUUID(), "card-monitor-01",
                new DeviceStatusUnbindingRequestWire(organization, facility, bound.rowVersion(), "患者离开监测区域，完成设备回收"));
        assertThat(unbound.boundPatientId()).isNull();
        assertThat(jdbc.sql("""
                select count(*) from device_patient_binding where tenant_id = :tenant
                  and device_code = 'card-monitor-01' and patient_id = :patient and unbound_at is not null
                """).param("tenant", tenant).param("patient", encounter.patientId()).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = :tenant
                  and action_code in ('DEVICE_STATUS_BOUND', 'DEVICE_STATUS_UNBOUND')
                """).param("tenant", tenant).query(Long.class).single()).isEqualTo(bindingAuditCountBefore + 2);
    }

    private record EncounterHead(UUID patientId, UUID encounterId) {}
}
