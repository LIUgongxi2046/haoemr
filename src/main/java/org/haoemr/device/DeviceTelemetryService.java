package org.haoemr.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.DeviceObservationWire;
import org.haoemr.contracts.DeviceAlertTransitionRequestWire;
import org.haoemr.contracts.DeviceAlertWire;
import org.haoemr.contracts.DeviceStatusWire;
import org.haoemr.contracts.DeviceStatusBindingRequestWire;
import org.haoemr.contracts.DeviceStatusUnbindingRequestWire;
import org.haoemr.contracts.DeviceTelemetryCollectRequestWire;
import org.haoemr.contracts.DeviceTelemetryCollectResultWire;
import org.haoemr.contracts.MockInvocationResultWire;
import org.haoemr.mock.MockInterfaceService;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class DeviceTelemetryService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final MockInterfaceService mocks;

    DeviceTelemetryService(
            JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper,
            MockInterfaceService mocks) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.mocks = mocks;
    }

    List<DeviceObservationWire> listObservations(ClinicalIdentity identity, String deviceCode) {
        StringBuilder sql = new StringBuilder("""
                select observation_id from device_observation where tenant_id = :tenant
                """);
        if (deviceCode != null && !deviceCode.isBlank()) sql.append(" and device_code = :device");
        sql.append(" order by observed_at desc, observation_id desc limit 500");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (deviceCode != null && !deviceCode.isBlank()) spec = spec.param("device", deviceCode.trim());
        List<UUID> ids = spec.query(UUID.class).list();
        return ids.stream().map(id -> observation(identity.tenantId(), id)).toList();
    }

    List<DeviceStatusWire> listStatuses(ClinicalIdentity identity, String deviceCode) {
        StringBuilder sql = new StringBuilder("""
                select device_code from device_status where tenant_id = :tenant
                """);
        if (deviceCode != null && !deviceCode.isBlank()) sql.append(" and device_code = :device");
        sql.append(" order by device_code limit 500");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (deviceCode != null && !deviceCode.isBlank()) spec = spec.param("device", deviceCode.trim());
        List<String> codes = spec.query(String.class).list();
        return codes.stream().map(code -> status(identity.tenantId(), code)).toList();
    }

    List<DeviceAlertWire> listAlerts(ClinicalIdentity identity, String deviceCode, boolean includeClosed) {
        StringBuilder sql = new StringBuilder("""
                select device_alert_id from device_alert where tenant_id = :tenant
                """);
        if (deviceCode != null && !deviceCode.isBlank()) sql.append(" and device_code = :device");
        if (!includeClosed) sql.append(" and state <> 'CLOSED'");
        sql.append(" order by case alarm_level when 'HIGH' then 2 else 1 end desc, opened_at, device_alert_id limit 500");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (deviceCode != null && !deviceCode.isBlank()) spec = spec.param("device", deviceCode.trim());
        return spec.query(UUID.class).list().stream().map(id -> alert(identity.tenantId(), id)).toList();
    }

    DeviceAlertWire transitionAlert(
            ClinicalIdentity identity, String idempotencyKey, UUID alertId, DeviceAlertTransitionRequestWire request) {
        if (request.expectedRowVersion() == null || request.action() == null || request.note() == null
                || request.note().trim().length() < 2 || request.note().length() > 1000) {
            throw invalid("expected_row_version, action and a 2-1000 character note are required");
        }
        return transactions.execute(status -> {
            AlertHead current = jdbc.sql("""
                    select device_code, state, row_version from device_alert
                    where tenant_id = :tenant and device_alert_id = :alert for update
                    """).param("tenant", identity.tenantId()).param("alert", alertId)
                    .query((rs, row) -> new AlertHead(rs.getString("device_code"), rs.getString("state"),
                            rs.getLong("row_version"))).optional().orElseThrow(DeviceTelemetryService::contextDenied);
            if (current.rowVersion() != request.expectedRowVersion()) {
                throw new DeviceException("DEVICE_ALERT_VERSION_CONFLICT", 409, "告警已被其他操作者处理，请刷新后重试");
            }
            String action = request.action().name();
            boolean permitted = switch (current.state()) {
                case "OPEN" -> List.of("ACKNOWLEDGE", "ESCALATE", "CLOSE").contains(action);
                case "ACKNOWLEDGED" -> List.of("ESCALATE", "CLOSE").contains(action);
                case "ESCALATED" -> "CLOSE".equals(action);
                default -> false;
            };
            if (!permitted) {
                throw new DeviceException("DEVICE_ALERT_STATE_INVALID", 409, "当前告警状态不允许该操作");
            }
            beginCommand(identity, "DEVICE_ALERT_" + action, idempotencyKey,
                    sha256(alertId + "|" + request.expectedRowVersion() + "|" + action + "|" + request.note().trim()));
            String update = switch (action) {
                case "ACKNOWLEDGE" -> """
                        update device_alert set state = 'ACKNOWLEDGED', acknowledged_by = :actor,
                          acknowledged_at = now(), row_version = row_version + 1
                        where tenant_id = :tenant and device_alert_id = :alert and row_version = :version
                        """;
                case "ESCALATE" -> """
                        update device_alert set state = 'ESCALATED', escalated_by = :actor,
                          escalated_at = now(), row_version = row_version + 1
                        where tenant_id = :tenant and device_alert_id = :alert and row_version = :version
                        """;
                default -> """
                        update device_alert set state = 'CLOSED', closed_by = :actor, closed_at = now(),
                          resolution_note = :note, row_version = row_version + 1
                        where tenant_id = :tenant and device_alert_id = :alert and row_version = :version
                        """;
            };
            int changed = jdbc.sql(update).param("tenant", identity.tenantId()).param("alert", alertId)
                    .param("actor", identity.userId()).param("note", request.note().trim())
                    .param("version", request.expectedRowVersion()).update();
            if (changed != 1) throw new DeviceException("DEVICE_ALERT_VERSION_CONFLICT", 409, "告警已被其他操作者处理，请刷新后重试");
            recomputeAlarmState(identity.tenantId(), current.deviceCode());
            appendAlertEvidence(identity, alertId, "DEVICE_ALERT_" + action, "DeviceAlert" + title(action));
            completeCommand(identity, "DEVICE_ALERT_" + action, idempotencyKey, alertId);
            return alert(identity.tenantId(), alertId);
        });
    }

    DeviceTelemetryCollectResultWire collect(
            ClinicalIdentity identity, String idempotencyKey, DeviceTelemetryCollectRequestWire request) {
        String deviceCode = requireText(request.deviceCode(), 2, "device_code");
        requireActiveDevice(identity.tenantId(), deviceCode);
        String scenario = request.simulationScenario() == null ? "SUCCESS" : request.simulationScenario().name();
        int recordCount = request.recordCount() == null ? 36 : request.recordCount();
        if (recordCount < 12 || recordCount > 200) {
            throw invalid("record_count must be between 12 and 200");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "DEVICE_TELEMETRY_COLLECT", idempotencyKey, sha256(deviceCode + "|" + scenario));
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("simulation_scenario", scenario);
            payload.put("record_count", recordCount);
            payload.put("device_id", deviceCode);
            payload.put("simulation_reference_time", Instant.now().toString());
            MockInvocationResultWire generated = mocks.invoke("DEVICE_GATEWAY", payload);
            List<Map<String, Object>> records = businessRecords(generated.payload());
            List<DeviceObservationWire> observations = new ArrayList<>(records.size());
            for (Map<String, Object> record : records) {
                UUID observationId = UUID.randomUUID();
                jdbc.sql("""
                        insert into device_observation(
                          tenant_id, observation_id, device_code, trace_id, metric, metric_value,
                          metric_unit, quality, alarm_level, observed_at)
                        values (:tenant, :observation, :device, :trace, :metric, :value,
                          :unit, :quality, :alarm, :observed)
                        """).param("tenant", identity.tenantId()).param("observation", observationId)
                        .param("device", deviceCode).param("trace", generated.requestId().toString())
                        .param("metric", text(record, "metric")).param("value", doubleValue(record))
                        .param("unit", text(record, "unit")).param("quality", text(record, "quality", "VERIFIED"))
                        .param("alarm", text(record, "alarm_level", "NONE"))
                        .param("observed", instantValue(record, "observed_at", generated.producedAt()).atOffset(ZoneOffset.UTC)).update();
                appendEvidence(identity, observationId, "DEVICE_OBSERVATION", "DEVICE_OBSERVATION_RECORDED", "DeviceObservationRecorded");
                if (rank(text(record, "alarm_level", "NONE")) > 1) {
                    createAlert(identity, observationId, deviceCode, text(record, "metric"),
                            text(record, "alarm_level"), text(record, "metric") + " 异常："
                                    + doubleValue(record) + " " + text(record, "unit"));
                }
                observations.add(observation(identity.tenantId(), observationId));
            }
            DeviceStatusWire deviceStatus = recomputeStatus(identity, deviceCode, scenario, records, generated.producedAt());
            completeCommand(identity, "DEVICE_TELEMETRY_COLLECT", idempotencyKey, deviceCode);
            return new DeviceTelemetryCollectResultWire(observations, deviceStatus);
        });
    }

    DeviceStatusWire bindStatus(ClinicalIdentity identity, String idempotencyKey, String deviceCode,
            DeviceStatusBindingRequestWire request) {
        String code = requireText(deviceCode, 2, "device_code");
        if (request.patientId() == null || request.encounterId() == null || request.expectedRowVersion() == null
                || request.verificationMethod() == null || request.verificationMethod().trim().length() < 2) {
            throw invalid("patient_id, encounter_id, expected_row_version and verification_method are required");
        }
        return transactions.execute(ignored -> {
            DeviceStatusWire current = status(identity.tenantId(), code);
            if (current.rowVersion() != request.expectedRowVersion()) {
                throw new DeviceException("DEVICE_STATUS_VERSION_CONFLICT", 409, "设备状态已变更，请刷新后重试");
            }
            boolean validEncounter = jdbc.sql("""
                    select exists(select 1 from encounter where tenant_id = :tenant and encounter_id = :encounter
                      and patient_id = :patient and facility_id = :facility and status in ('ARRIVED','IN_PROGRESS','SUSPENDED'))
                    """).param("tenant", identity.tenantId()).param("encounter", request.encounterId())
                    .param("patient", request.patientId()).param("facility", request.facilityId()).query(Boolean.class).single();
            if (!validEncounter) throw new DeviceException("DEVICE_BINDING_ENCOUNTER_INVALID", 409,
                    "设备只能绑定到当前院区内处于服务中的同一患者就诊");
            boolean alreadyBound = jdbc.sql("""
                    select exists(select 1 from device_patient_binding
                      where tenant_id = :tenant and device_code = :device and unbound_at is null)
                    """).param("tenant", identity.tenantId()).param("device", code).query(Boolean.class).single();
            if (alreadyBound) throw new DeviceException("DEVICE_ALREADY_BOUND", 409,
                    "设备已有生效中的患者绑定，请先完成解绑并保留原因");
            beginCommand(identity, "DEVICE_STATUS_BIND", idempotencyKey,
                    sha256(code + "|" + request.patientId() + "|" + request.encounterId() + "|" + request.expectedRowVersion()));
            UUID bindingId = UUID.randomUUID();
            jdbc.sql("""
                    insert into device_patient_binding(
                      tenant_id, device_patient_binding_id, device_code, patient_id, encounter_id,
                      bound_by, verification_method)
                    values (:tenant, :binding, :device, :patient, :encounter, :actor, :method)
                    """).param("tenant", identity.tenantId()).param("binding", bindingId).param("device", code)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("actor", identity.userId()).param("method", request.verificationMethod().trim()).update();
            int changed = jdbc.sql("""
                    update device_status set bound_patient_id = :patient, bound_encounter_id = :encounter,
                      bound_by = :actor, bound_at = now(), binding_verification_method = :method,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and device_code = :device and row_version = :version
                    """).param("tenant", identity.tenantId()).param("device", code).param("patient", request.patientId())
                    .param("encounter", request.encounterId()).param("actor", identity.userId())
                    .param("method", request.verificationMethod().trim()).param("version", request.expectedRowVersion()).update();
            if (changed != 1) throw new DeviceException("DEVICE_STATUS_VERSION_CONFLICT", 409, "设备状态已变更，请刷新后重试");
            appendEvidence(identity, bindingId, "DEVICE_PATIENT_BINDING", "DEVICE_STATUS_BOUND", "DeviceStatusBound");
            completeCommand(identity, "DEVICE_STATUS_BIND", idempotencyKey, bindingId);
            return status(identity.tenantId(), code);
        });
    }

    DeviceStatusWire unbindStatus(ClinicalIdentity identity, String idempotencyKey, String deviceCode,
            DeviceStatusUnbindingRequestWire request) {
        String code = requireText(deviceCode, 2, "device_code");
        if (request.expectedRowVersion() == null || request.reason() == null
                || request.reason().trim().length() < 2 || request.reason().length() > 1000) {
            throw invalid("expected_row_version and a 2-1000 character reason are required");
        }
        return transactions.execute(ignored -> {
            DeviceStatusWire current = status(identity.tenantId(), code);
            if (current.rowVersion() != request.expectedRowVersion()) {
                throw new DeviceException("DEVICE_STATUS_VERSION_CONFLICT", 409, "设备状态已变更，请刷新后重试");
            }
            BindingHead active = jdbc.sql("""
                    select binding.device_patient_binding_id, binding.patient_id, binding.encounter_id
                    from device_patient_binding binding
                    join encounter on encounter.tenant_id = binding.tenant_id
                      and encounter.encounter_id = binding.encounter_id
                    where binding.tenant_id = :tenant and binding.device_code = :device
                      and binding.unbound_at is null and encounter.facility_id = :facility
                    for update
                    """).param("tenant", identity.tenantId()).param("device", code).param("facility", request.facilityId())
                    .query((rs, row) -> new BindingHead(rs.getObject("device_patient_binding_id", UUID.class),
                            rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class)))
                    .optional().orElseThrow(() -> new DeviceException("DEVICE_NOT_BOUND", 409,
                            "设备在当前院区没有可解绑的生效绑定"));
            beginCommand(identity, "DEVICE_STATUS_UNBIND", idempotencyKey,
                    sha256(code + "|" + active.bindingId() + "|" + request.expectedRowVersion() + "|" + request.reason().trim()));
            int bindingChanged = jdbc.sql("""
                    update device_patient_binding
                    set unbound_by = :actor, unbound_at = now(), unbinding_reason = :reason,
                      row_version = row_version + 1
                    where tenant_id = :tenant and device_patient_binding_id = :binding and unbound_at is null
                    """).param("tenant", identity.tenantId()).param("binding", active.bindingId())
                    .param("actor", identity.userId()).param("reason", request.reason().trim()).update();
            if (bindingChanged != 1) throw new DeviceException("DEVICE_BINDING_VERSION_CONFLICT", 409,
                    "设备绑定已被其他操作者处理，请刷新后重试");
            int statusChanged = jdbc.sql("""
                    update device_status
                    set bound_patient_id = null, bound_encounter_id = null, bound_by = null, bound_at = null,
                      binding_verification_method = null, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and device_code = :device and row_version = :version
                    """).param("tenant", identity.tenantId()).param("device", code)
                    .param("version", request.expectedRowVersion()).update();
            if (statusChanged != 1) throw new DeviceException("DEVICE_STATUS_VERSION_CONFLICT", 409, "设备状态已变更，请刷新后重试");
            appendEvidence(identity, active.bindingId(), "DEVICE_PATIENT_BINDING", "DEVICE_STATUS_UNBOUND", "DeviceStatusUnbound");
            completeCommand(identity, "DEVICE_STATUS_UNBIND", idempotencyKey, active.bindingId());
            return status(identity.tenantId(), code);
        });
    }

    private DeviceStatusWire recomputeStatus(
            ClinicalIdentity identity, String deviceCode, String scenario,
            List<Map<String, Object>> records, Instant fallback) {
        Instant latest = records.stream()
                .map(record -> instantValue(record, "observed_at", fallback))
                .max(Comparator.naturalOrder()).orElse(fallback);
        Map<String, Object> newest = records.stream()
                .max(Comparator.comparing(record -> instantValue(record, "observed_at", fallback))).orElse(Map.of());
        int clockOffset = Math.max(Math.abs(intValue(newest, "device_clock_offset_seconds")),
                records.stream().mapToInt(record -> Math.abs(intValue(record, "device_clock_offset_seconds"))).max().orElse(0));
        String calibration = text(newest, "calibration_status", "VALID");
        String alarm = activeAlarmState(identity.tenantId(), deviceCode);
        String online = "DEGRADED".equals(scenario) ? "DEGRADED" : "ONLINE";
        boolean exists = jdbc.sql("select 1 from device_status where tenant_id = :tenant and device_code = :device")
                .param("tenant", identity.tenantId()).param("device", deviceCode)
                .query(Integer.class).optional().isPresent();
        if (exists) {
            jdbc.sql("""
                    update device_status
                    set online_status = :online, clock_offset_seconds = :clock, last_observed_at = :observed,
                        calibration_status = :calibration, alarm_state = :alarm, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and device_code = :device
                    """).param("online", online).param("clock", clockOffset)
                    .param("observed", latest.atOffset(ZoneOffset.UTC))
                    .param("calibration", calibration).param("alarm", alarm)
                    .param("tenant", identity.tenantId()).param("device", deviceCode).update();
        } else {
            jdbc.sql("""
                    insert into device_status(
                      tenant_id, device_code, online_status, clock_offset_seconds,
                      last_observed_at, calibration_status, alarm_state)
                    values (:tenant, :device, :online, :clock, :observed, :calibration, :alarm)
                    """).param("tenant", identity.tenantId()).param("device", deviceCode)
                    .param("online", online).param("clock", clockOffset)
                    .param("observed", latest.atOffset(ZoneOffset.UTC))
                    .param("calibration", calibration).param("alarm", alarm).update();
        }
        return status(identity.tenantId(), deviceCode);
    }

    private void createAlert(
            ClinicalIdentity identity, UUID observationId, String deviceCode, String metric, String level, String summary) {
        UUID alertId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                insert into device_alert(
                  tenant_id, device_alert_id, device_code, observation_id, metric, alarm_level, state, summary)
                select :tenant, :alert, :device, :metric_source, :metric, :level, 'OPEN', :summary
                where not exists (
                  select 1 from device_alert where tenant_id = :tenant and device_code = :device
                    and metric = :metric and state <> 'CLOSED')
                """).param("tenant", identity.tenantId()).param("alert", alertId).param("device", deviceCode)
                .param("metric_source", observationId).param("metric", metric).param("level", level)
                .param("summary", summary).update();
        if (inserted == 1) appendAlertEvidence(identity, alertId, "DEVICE_ALERT_OPENED", "DeviceAlertOpened");
    }

    private String activeAlarmState(UUID tenantId, String deviceCode) {
        return jdbc.sql("""
                select coalesce(max(case alarm_level when 'HIGH' then 3 when 'MEDIUM' then 2 else 1 end), 1)
                from device_alert where tenant_id = :tenant and device_code = :device and state <> 'CLOSED'
                """).param("tenant", tenantId).param("device", deviceCode).query(Integer.class).single() == 3 ? "HIGH"
                : jdbc.sql("""
                        select count(*) from device_alert where tenant_id = :tenant and device_code = :device
                          and state <> 'CLOSED'
                        """).param("tenant", tenantId).param("device", deviceCode).query(Long.class).single() > 0 ? "MEDIUM" : "NONE";
    }

    private void recomputeAlarmState(UUID tenantId, String deviceCode) {
        jdbc.sql("""
                update device_status set alarm_state = :alarm, row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and device_code = :device
                """).param("tenant", tenantId).param("device", deviceCode)
                .param("alarm", activeAlarmState(tenantId, deviceCode)).update();
    }

    private static int rank(String value) {
        return switch (value) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private void requireActiveDevice(UUID tenantId, String deviceCode) {
        boolean active = jdbc.sql("""
                select 1 from device
                where tenant_id = :tenant and device_code = :device and status = 'ACTIVE'
                """).param("tenant", tenantId).param("device", deviceCode)
                .query(Integer.class).optional().isPresent();
        if (!active) {
            throw new DeviceException("DEVICE_NOT_ACTIVE", 409, "设备不存在或未处于 ACTIVE 状态");
        }
    }

    private DeviceObservationWire observation(UUID tenantId, UUID observationId) {
        return jdbc.sql("""
                select observation_id, device_code, trace_id, metric, metric_value, metric_unit,
                  quality, alarm_level, observed_at, row_version, created_at
                from device_observation where tenant_id = :tenant and observation_id = :observation
                """).param("tenant", tenantId).param("observation", observationId)
                .query((rs, row) -> new DeviceObservationWire(
                        rs.getObject("observation_id", UUID.class), rs.getString("device_code"),
                        rs.getString("trace_id"), rs.getString("metric"),
                        rs.getBigDecimal("metric_value").doubleValue(), rs.getString("metric_unit"),
                        DeviceObservationWire.QualityValue.valueOf(rs.getString("quality")),
                        DeviceObservationWire.AlarmLevelValue.valueOf(rs.getString("alarm_level")),
                        rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(DeviceTelemetryService::contextDenied);
    }

    private DeviceAlertWire alert(UUID tenantId, UUID alertId) {
        return jdbc.sql("""
                select device_alert_id, device_code, observation_id, metric, alarm_level, state, summary,
                  opened_at, acknowledged_by, acknowledged_at, escalated_by, escalated_at, closed_by,
                  closed_at, resolution_note, row_version
                from device_alert where tenant_id = :tenant and device_alert_id = :alert
                """).param("tenant", tenantId).param("alert", alertId)
                .query((rs, row) -> new DeviceAlertWire(
                        rs.getObject("device_alert_id", UUID.class), rs.getString("device_code"),
                        rs.getObject("observation_id", UUID.class), rs.getString("metric"),
                        DeviceAlertWire.AlarmLevelValue.valueOf(rs.getString("alarm_level")),
                        DeviceAlertWire.StateValue.valueOf(rs.getString("state")), rs.getString("summary"),
                        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("acknowledged_by", UUID.class),
                        instant(rs.getObject("acknowledged_at", OffsetDateTime.class)),
                        rs.getObject("escalated_by", UUID.class),
                        instant(rs.getObject("escalated_at", OffsetDateTime.class)),
                        rs.getObject("closed_by", UUID.class),
                        instant(rs.getObject("closed_at", OffsetDateTime.class)),
                        rs.getString("resolution_note"), rs.getLong("row_version")))
                .optional().orElseThrow(DeviceTelemetryService::contextDenied);
    }

    private DeviceStatusWire status(UUID tenantId, String deviceCode) {
        return jdbc.sql("""
                select device_code, online_status, clock_offset_seconds, bound_patient_id,
                  last_observed_at, calibration_status, alarm_state, row_version, updated_at
                from device_status where tenant_id = :tenant and device_code = :device
                """).param("tenant", tenantId).param("device", deviceCode)
                .query((rs, row) -> new DeviceStatusWire(
                        rs.getString("device_code"),
                        DeviceStatusWire.OnlineStatusValue.valueOf(rs.getString("online_status")),
                        rs.getInt("clock_offset_seconds"),
                        rs.getObject("bound_patient_id", UUID.class),
                        rs.getObject("last_observed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("last_observed_at", OffsetDateTime.class).toInstant(),
                        DeviceStatusWire.CalibrationStatusValue.valueOf(rs.getString("calibration_status")),
                        DeviceStatusWire.AlarmStateValue.valueOf(rs.getString("alarm_state")),
                        rs.getLong("row_version"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(DeviceTelemetryService::contextDenied);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> businessRecords(Map<String, Object> payload) {
        Object records = payload.get("business_records");
        if (!(records instanceof List<?> list)) {
            throw new DeviceException("DEVICE_MOCK_PAYLOAD_INVALID", 502, "设备网关未返回业务记录");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return result;
    }

    private String text(Map<String, Object> record, String key, String fallback) {
        Object value = record.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String text(Map<String, Object> record, String key) {
        return text(record, key, "");
    }

    private double doubleValue(Map<String, Object> record) {
        Object value = record.get("value");
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private int intValue(Map<String, Object> record, String key) {
        Object value = record.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Instant instantValue(Map<String, Object> record, String key, Instant fallback) {
        String value = text(record, key);
        if (value.isEmpty()) return fallback;
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw invalid("A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new DeviceException("IDEMPOTENCY_REPLAY", 409, "该命令键已使用");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, Object resource) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', cast(:resource as text))
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", String.valueOf(resource)).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendAlertEvidence(ClinicalIdentity identity, UUID alertId, String action, String eventType) {
        appendEvidence(identity, alertId, "DEVICE_ALERT", action, eventType);
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID resourceId, String resourceType, String action, String eventType) {
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + resourceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, :resource_type, :resource,
                  :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource_type", resourceType)
                .param("resource", resourceId)
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, :aggregate_type, :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate_type", resourceType).param("aggregate", resourceId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " is required");
        }
        return value.trim();
    }

    private static DeviceException invalid(String message) {
        return new DeviceException("DEVICE_REQUEST_INVALID", 400, message);
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    private static String title(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase();
    }

    static DeviceException contextDenied() {
        return new DeviceException("CONTEXT_NOT_PERMITTED", 403, "请求的设备上下文不允许访问");
    }

    private record AlertHead(String deviceCode, String state, long rowVersion) {}

    private record BindingHead(UUID bindingId, UUID patientId, UUID encounterId) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
