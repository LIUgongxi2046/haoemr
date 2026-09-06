package org.haoemr.patient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.haoemr.security.AuthorizationDecisionService;
import org.haoemr.security.AuthorizationDecisionService.AuthorizationContext;
import org.haoemr.security.AuthorizationDecisionService.DepartmentWardScope;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
final class PatientTimelineService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatientTimelineService.class);
    private static final Set<String> SOURCES = Set.of("ENCOUNTER", "DOCUMENT", "DIAGNOSIS", "ORDER", "RESULT", "TASK", "DEVICE");
    private static final Comparator<TimelineItemWire> DESCENDING = Comparator
            .comparing(TimelineItemWire::occurredAt).reversed()
            .thenComparing(TimelineItemWire::itemType)
            .thenComparing(item -> item.resourceId().toString());

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AuthorizationDecisionService authorization;
    private final Environment environment;

    PatientTimelineService(JdbcClient jdbc, TransactionTemplate transactions,
            AuthorizationDecisionService authorization, Environment environment) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.authorization = authorization;
        this.environment = environment;
    }

    PatientTimelineWire load(ClinicalIdentity identity, UUID organizationId, UUID facilityId,
            UUID patientId, Instant from, Instant to, Set<String> requestedTypes,
            Set<String> requestedStatuses, String cursor, int requestedLimit,
            List<String> syntheticFailedSources) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw invalid("Timeline end time must be after the start time");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        Set<String> types = normalizeTypes(requestedTypes);
        Set<String> statusesFilter = normalizeStatuses(requestedStatuses);
        Set<String> aliases = aliases(identity.tenantId(), patientId).stream()
                .map(UUID::toString).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> injectedFailures = environment.acceptsProfiles(Profiles.of("dev-synthetic"))
                ? normalizeSyntheticFailures(syntheticFailedSources) : Set.of();
        List<TimelineItemWire> items = new ArrayList<>();
        List<TimelineSourceStatusWire> statuses = new ArrayList<>();
        for (String source : SOURCES.stream().sorted().toList()) {
            if (!types.contains(source)) continue;
            readSource(source, injectedFailures, statuses, items,
                    () -> querySource(source, identity.tenantId(), organizationId, facilityId,
                            aliases, from, to));
        }
        List<TimelineItemWire> authorized = new ArrayList<>();
        int denied = 0;
        for (TimelineItemWire item : items) {
            if (!statusesFilter.isEmpty() && !statusesFilter.contains(item.status().toUpperCase(Locale.ROOT))) continue;
            if (allowed(identity, organizationId, item)) authorized.add(item); else denied++;
        }
        authorized.sort(DESCENDING);
        TimelineCursor decodedCursor = decodeCursor(cursor);
        if (decodedCursor != null) {
            authorized = authorized.stream().filter(item -> afterCursor(item, decodedCursor)).toList();
        }
        List<TimelineItemWire> visibleItems = authorized;
        List<TimelineSourceStatusWire> publicStatuses = statuses.stream().map(status ->
                "AVAILABLE".equals(status.state())
                        ? new TimelineSourceStatusWire(status.source(), status.state(),
                                (int) visibleItems.stream().filter(item -> status.source().equals(item.itemType())).count(),
                                status.errorCode(), status.retryable(), status.asOf())
                        : status).toList();
        boolean hasMore = visibleItems.size() > limit;
        List<TimelineItemWire> page = visibleItems.stream().limit(limit).toList();
        String nextCursor = hasMore && !page.isEmpty() ? encodeCursor(page.getLast()) : null;
        String completeness = publicStatuses.stream().anyMatch(status -> "PARTIAL".equals(status.state()))
                ? "PARTIAL" : "COMPLETE";
        String watermark = sha256(identity.tenantId() + "|" + patientId + "|" + aliases + "|"
                + page.stream().map(item -> item.itemType() + ":" + item.resourceId() + ":" + item.rowVersion()).toList()
                + "|" + publicStatuses);
        audit(identity, patientId, page.size(), denied, completeness);
        return new PatientTimelineWire(patientId, aliases.stream().map(UUID::fromString).toList(),
                completeness, watermark, Instant.now(), publicStatuses, page, nextCursor);
    }

    private void readSource(String source, Set<String> injectedFailures,
            List<TimelineSourceStatusWire> statuses, List<TimelineItemWire> items,
            Supplier<List<TimelineItemWire>> reader) {
        Instant asOf = Instant.now();
        try {
            if (injectedFailures.contains(source)) throw new SyntheticTimelineSourceException(source);
            List<TimelineItemWire> loaded = reader.get();
            items.addAll(loaded);
            statuses.add(new TimelineSourceStatusWire(source, "AVAILABLE", loaded.size(), null, false, asOf));
        } catch (DataAccessException | SyntheticTimelineSourceException failure) {
            if (failure instanceof DataAccessException) {
                LOGGER.warn("Patient timeline source query failed: source={}", source, failure);
            }
            statuses.add(new TimelineSourceStatusWire(source, "PARTIAL", 0,
                    failure instanceof SyntheticTimelineSourceException ? "SYNTHETIC_SOURCE_FAILURE" : "SOURCE_QUERY_FAILED",
                    true, asOf));
        }
    }

    private List<TimelineItemWire> querySource(String source, UUID tenantId, UUID organizationId,
            UUID facilityId, Set<String> aliases, Instant from, Instant to) {
        return switch (source) {
            case "ENCOUNTER" -> encounters(tenantId, organizationId, facilityId, aliases, from, to);
            case "DOCUMENT" -> documents(tenantId, organizationId, facilityId, aliases, from, to);
            case "DIAGNOSIS" -> diagnoses(tenantId, facilityId, aliases, from, to);
            case "ORDER" -> orders(tenantId, facilityId, aliases, from, to);
            case "RESULT" -> results(tenantId, facilityId, aliases, from, to);
            case "TASK" -> tasks(tenantId, facilityId, aliases, from, to);
            case "DEVICE" -> deviceEvents(tenantId, facilityId, aliases, from, to);
            default -> List.of();
        };
    }

    private List<TimelineItemWire> encounters(UUID tenant, UUID organization, UUID facility,
            Set<String> aliases, Instant from, Instant to) {
        return jdbc.sql("""
                select encounter_id as resource_id, patient_id, encounter_id, facility_id,
                  started_at as occurred_at, status, encounter_type,
                  status as encounter_status, source_system, row_version
                from encounter where tenant_id = :tenant and organization_id = :organization
                  and facility_id = :facility and patient_id = any(cast(:aliases as uuid[]))
                  and (cast(:from_time as timestamptz) is null or started_at >= cast(:from_time as timestamptz))
                  and (cast(:to_time as timestamptz) is null or started_at < cast(:to_time as timestamptz))
                """).param("tenant", tenant).param("organization", organization).param("facility", facility)
                .param("aliases", uuidArray(aliases)).param("from_time", utc(from)).param("to_time", utc(to))
                .query((rs, row) -> item(rs, "ENCOUNTER", rs.getString("encounter_type") + " 就诊",
                        "就诊状态 " + rs.getString("encounter_status"), rs.getString("source_system"),
                        "/outpatient", null)).list();
    }

    private List<TimelineItemWire> documents(UUID tenant, UUID organization, UUID facility,
            Set<String> aliases, Instant from, Instant to) {
        return jdbc.sql("""
                select document.document_id as resource_id, document.patient_id, document.encounter_id,
                  encounter.facility_id, version.created_at as occurred_at, version.status,
                  document.document_type_code, version.sections::text as summary,
                  version.version_no, document.row_version
                from clinical_document document
                join encounter on encounter.tenant_id = document.tenant_id and encounter.encounter_id = document.encounter_id
                join clinical_document_version version on version.tenant_id = document.tenant_id
                  and version.document_version_id = document.current_version_id
                where document.tenant_id = :tenant and encounter.organization_id = :organization
                  and encounter.facility_id = :facility and document.patient_id = any(cast(:aliases as uuid[]))
                  and (cast(:from_time as timestamptz) is null or version.created_at >= cast(:from_time as timestamptz))
                  and (cast(:to_time as timestamptz) is null or version.created_at < cast(:to_time as timestamptz))
                """).param("tenant", tenant).param("organization", organization).param("facility", facility)
                .param("aliases", uuidArray(aliases)).param("from_time", utc(from)).param("to_time", utc(to))
                .query((rs, row) -> item(rs, "DOCUMENT", rs.getString("document_type_code"),
                        "当前版本 v" + rs.getInt("version_no"), "HAOEMR",
                        "/record-versions", rs.getInt("version_no"))).list();
    }

    private List<TimelineItemWire> diagnoses(UUID tenant, UUID facility, Set<String> aliases,
            Instant from, Instant to) {
        return jdbc.sql("""
                select diagnosis.diagnosis_id as resource_id, diagnosis.patient_id, diagnosis.encounter_id,
                  diagnosis.facility_id, version.effective_at as occurred_at, diagnosis.lifecycle_status as status,
                  version.diagnosis_text, version.code, version.version_no, diagnosis.row_version
                from clinical_diagnosis diagnosis
                join clinical_diagnosis_version version on version.tenant_id = diagnosis.tenant_id
                  and version.diagnosis_version_id = diagnosis.current_version_id
                where diagnosis.tenant_id = :tenant and diagnosis.facility_id = :facility
                  and diagnosis.patient_id = any(cast(:aliases as uuid[]))
                  and (cast(:from_time as timestamptz) is null or version.effective_at >= cast(:from_time as timestamptz))
                  and (cast(:to_time as timestamptz) is null or version.effective_at < cast(:to_time as timestamptz))
                """).param("tenant", tenant).param("facility", facility).param("aliases", uuidArray(aliases))
                .param("from_time", utc(from)).param("to_time", utc(to))
                .query((rs, row) -> item(rs, "DIAGNOSIS", rs.getString("diagnosis_text"),
                        rs.getString("code"), "ICD-10-CN", "/opd-diagnosis", rs.getInt("version_no"))).list();
    }

    private List<TimelineItemWire> orders(UUID tenant, UUID facility, Set<String> aliases,
            Instant from, Instant to) {
        return jdbc.sql("""
                select clinical_order.order_id as resource_id, clinical_order.patient_id,
                  clinical_order.encounter_id, clinical_order.facility_id,
                  clinical_order.created_at as occurred_at, clinical_order.status,
                  string_agg(item.display_name, '、' order by item.display_name) as item_names,
                  clinical_order.row_version
                from clinical_order join clinical_order_item item
                  on item.tenant_id = clinical_order.tenant_id and item.order_id = clinical_order.order_id
                where clinical_order.tenant_id = :tenant and clinical_order.facility_id = :facility
                  and clinical_order.patient_id = any(cast(:aliases as uuid[]))
                  and (cast(:from_time as timestamptz) is null or clinical_order.created_at >= cast(:from_time as timestamptz))
                  and (cast(:to_time as timestamptz) is null or clinical_order.created_at < cast(:to_time as timestamptz))
                group by clinical_order.tenant_id, clinical_order.order_id
                """).param("tenant", tenant).param("facility", facility).param("aliases", uuidArray(aliases))
                .param("from_time", utc(from)).param("to_time", utc(to))
                .query((rs, row) -> item(rs, "ORDER", "临床医嘱", rs.getString("item_names"),
                        "HAOEMR", "/opd-orders", null)).list();
    }

    private List<TimelineItemWire> results(UUID tenant, UUID facility, Set<String> aliases,
            Instant from, Instant to) {
        return jdbc.sql("""
                select result.result_id as resource_id, result.patient_id, result.encounter_id,
                  result.facility_id, version.reported_at as occurred_at, version.report_status as status,
                  result.report_type, version.conclusion, result.source_system,
                  version.version_no, result.row_version
                from clinical_result result join clinical_result_version version
                  on version.tenant_id = result.tenant_id and version.result_version_id = result.current_version_id
                where result.tenant_id = :tenant and result.facility_id = :facility
                  and result.patient_id = any(cast(:aliases as uuid[]))
                  and (cast(:from_time as timestamptz) is null or version.reported_at >= cast(:from_time as timestamptz))
                  and (cast(:to_time as timestamptz) is null or version.reported_at < cast(:to_time as timestamptz))
                """).param("tenant", tenant).param("facility", facility).param("aliases", uuidArray(aliases))
                .param("from_time", utc(from)).param("to_time", utc(to))
                .query((rs, row) -> item(rs, "RESULT", rs.getString("report_type") + " 报告",
                        rs.getString("conclusion"), rs.getString("source_system"),
                        "/opd-results", rs.getInt("version_no"))).list();
    }

    private List<TimelineItemWire> tasks(UUID tenant, UUID facility, Set<String> aliases,
            Instant from, Instant to) {
        return jdbc.sql("""
                select task_id as resource_id, patient_id, encounter_id, facility_id,
                  created_at as occurred_at, state as status, title, business_state,
                  source_type, source_route, row_version
                from clinical_task where tenant_id = :tenant and facility_id = :facility
                  and patient_id = any(cast(:aliases as uuid[]))
                  and (cast(:from_time as timestamptz) is null or created_at >= cast(:from_time as timestamptz))
                  and (cast(:to_time as timestamptz) is null or created_at < cast(:to_time as timestamptz))
                """).param("tenant", tenant).param("facility", facility).param("aliases", uuidArray(aliases))
                .param("from_time", utc(from)).param("to_time", utc(to))
                .query((rs, row) -> item(rs, "TASK", rs.getString("title"),
                        rs.getString("business_state"), rs.getString("source_type"),
                        rs.getString("source_route"), null)).list();
    }

    /** Device telemetry is a read-only clinical projection: it never creates or alters a vital-sign record. */
    private List<TimelineItemWire> deviceEvents(UUID tenant, UUID facility, Set<String> aliases,
            Instant from, Instant to) {
        return jdbc.sql("""
                with bound_device as (
                  select binding.device_code, binding.patient_id, encounter.encounter_id, encounter.facility_id,
                    binding.bound_at, binding.unbound_at
                  from device_patient_binding binding
                  join encounter on encounter.tenant_id = binding.tenant_id
                    and encounter.encounter_id = binding.encounter_id
                    and encounter.patient_id = binding.patient_id
                    and encounter.facility_id = :facility
                  where binding.tenant_id = :tenant and binding.patient_id = any(cast(:aliases as uuid[]))
                )
                select observation.observation_id as resource_id, bound_device.patient_id as patient_id,
                  bound_device.encounter_id, bound_device.facility_id, observation.observed_at as occurred_at,
                  observation.quality as status, observation.device_code, observation.metric,
                  observation.metric_value, observation.metric_unit, observation.alarm_level, observation.trace_id,
                  observation.row_version, 'OBSERVATION' as event_kind, null::text as alert_summary
                from device_observation observation
                join bound_device on bound_device.device_code = observation.device_code
                  and observation.observed_at >= bound_device.bound_at
                  and (bound_device.unbound_at is null or observation.observed_at < bound_device.unbound_at)
                where observation.tenant_id = :tenant
                  and (cast(:from_time as timestamptz) is null or observation.observed_at >= cast(:from_time as timestamptz))
                  and (cast(:to_time as timestamptz) is null or observation.observed_at < cast(:to_time as timestamptz))
                union all
                select alert.device_alert_id as resource_id, bound_device.patient_id as patient_id,
                  bound_device.encounter_id, bound_device.facility_id, alert.opened_at as occurred_at,
                  alert.state as status, alert.device_code, alert.metric, observation.metric_value,
                  observation.metric_unit, alert.alarm_level, observation.trace_id, alert.row_version,
                  'ALERT' as event_kind, alert.summary as alert_summary
                from device_alert alert
                join device_observation observation on observation.tenant_id = alert.tenant_id
                  and observation.observation_id = alert.observation_id
                join bound_device on bound_device.device_code = alert.device_code
                  and alert.opened_at >= bound_device.bound_at
                  and (bound_device.unbound_at is null or alert.opened_at < bound_device.unbound_at)
                where alert.tenant_id = :tenant
                  and (cast(:from_time as timestamptz) is null or alert.opened_at >= cast(:from_time as timestamptz))
                  and (cast(:to_time as timestamptz) is null or alert.opened_at < cast(:to_time as timestamptz))
                """).param("tenant", tenant).param("facility", facility).param("aliases", uuidArray(aliases))
                .param("from_time", utc(from)).param("to_time", utc(to))
                .query((rs, row) -> item(rs, "DEVICE",
                        "ALERT".equals(rs.getString("event_kind"))
                                ? "设备告警 · " + rs.getString("device_code") + " · " + rs.getString("metric")
                                : "设备遥测 · " + rs.getString("device_code") + " · " + rs.getString("metric"),
                        "ALERT".equals(rs.getString("event_kind")) ? rs.getString("alert_summary")
                                : rs.getBigDecimal("metric_value") + " " + rs.getString("metric_unit")
                                        + " · 告警等级 " + rs.getString("alarm_level"),
                        "DEVICE_GATEWAY_SIMULATED · trace=" + rs.getString("trace_id"),
                        "/device-monitoring", null)).list();
    }

    private TimelineItemWire item(ResultSet rs, String type, String title, String summary,
            String sourceSystem, String route, Integer versionNo) throws SQLException {
        return new TimelineItemWire(type, rs.getObject("resource_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                rs.getObject("facility_id", UUID.class), instant(rs.getObject("occurred_at", OffsetDateTime.class)),
                rs.getString("status"), title, summary, sourceSystem, route, versionNo,
                rs.getLong("row_version"));
    }

    private boolean allowed(ClinicalIdentity identity, UUID organizationId, TimelineItemWire item) {
        if (!authorization.hasPublishedPolicy(identity.tenantId(), item.itemType(), "READ")) return true;
        DepartmentWardScope scope = authorization.resolveScope(
                identity, organizationId, item.facilityId(), item.encounterId());
        return authorization.evaluate(identity, new AuthorizationContext(item.itemType(), "READ",
                organizationId, item.facilityId(), scope.departmentId(), scope.wardId(), item.patientId(),
                item.encounterId(), "PATIENT_TIMELINE", item.status())).allowed();
    }

    private List<UUID> aliases(UUID tenantId, UUID patientId) {
        List<UUID> values = jdbc.sql("""
                select patient_id from patient where tenant_id = :tenant
                  and (patient_id = :patient or merged_into_patient_id = :patient)
                order by patient_id
                """).param("tenant", tenantId).param("patient", patientId).query(UUID.class).list();
        if (values.isEmpty()) throw denied("Patient timeline is not available in this tenant");
        return values;
    }

    private void audit(ClinicalIdentity identity, UUID patientId, int itemCount, int deniedCount,
            String completeness) {
        transactions.executeWithoutResult(ignored -> {
            jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                    .param("tenant", identity.tenantId()).query(UUID.class).single();
            String previous = jdbc.sql("""
                    select event_hash from audit_event where tenant_id = :tenant
                    order by occurred_at desc, audit_event_id desc limit 1
                    """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
            UUID auditId = UUID.randomUUID(); String trace = UUID.randomUUID().toString();
            String hash = sha256(identity.tenantId() + "|" + auditId + "|PATIENT_TIMELINE_VIEWED|"
                    + patientId + "|" + trace + "|" + previous);
            jdbc.sql("""
                    insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id,
                      action_code, resource_type, resource_id, patient_ref_hash, trace_id,
                      previous_hash, event_hash, details)
                    values (:tenant, :audit, now(), :actor, 'PATIENT_TIMELINE_VIEWED', 'PATIENT',
                      :patient, :patient_hash, :trace, :previous, :hash,
                      jsonb_build_object('item_count', :item_count, 'redacted_count', :denied_count,
                        'completeness', :completeness))
                    """).param("tenant", identity.tenantId()).param("audit", auditId)
                    .param("actor", identity.userId()).param("patient", patientId)
                    .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                    .param("trace", trace).param("previous", previous).param("hash", hash)
                    .param("item_count", itemCount).param("denied_count", deniedCount)
                    .param("completeness", completeness).update();
        });
    }

    private Set<String> normalizeTypes(Set<String> requested) {
        if (requested == null || requested.isEmpty()) return SOURCES;
        Set<String> normalized = requested.stream().map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        if (!SOURCES.containsAll(normalized)) throw invalid("Timeline resource type filter is not valid");
        return normalized;
    }

    private Set<String> normalizeSyntheticFailures(List<String> requested) {
        Set<String> normalized = requested.stream().map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        if (!SOURCES.containsAll(normalized)) throw invalid("Synthetic failed timeline source is not valid");
        return normalized;
    }

    private Set<String> normalizeStatuses(Set<String> requested) {
        if (requested == null || requested.isEmpty()) return Set.of();
        Set<String> normalized = requested.stream().map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        if (normalized.size() != requested.size()) throw invalid("Timeline status filter is not valid");
        return normalized;
    }

    private boolean afterCursor(TimelineItemWire item, TimelineCursor cursor) {
        if (item.occurredAt().isBefore(cursor.occurredAt())) return true;
        if (item.occurredAt().isAfter(cursor.occurredAt())) return false;
        int type = item.itemType().compareTo(cursor.itemType());
        return type > 0 || (type == 0 && item.resourceId().toString().compareTo(cursor.resourceId().toString()) > 0);
    }

    private String encodeCursor(TimelineItemWire item) {
        String value = item.occurredAt() + "|" + item.itemType() + "|" + item.resourceId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private TimelineCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 3);
            if (parts.length != 3 || !SOURCES.contains(parts[1])) throw new IllegalArgumentException();
            return new TimelineCursor(Instant.parse(parts[0]), parts[1], UUID.fromString(parts[2]));
        } catch (RuntimeException invalid) {
            throw invalid("Timeline cursor is not valid");
        }
    }

    private static String uuidArray(Set<String> values) { return "{" + String.join(",", values) + "}"; }
    private static OffsetDateTime utc(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value.toInstant(); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static PatientIdentityException invalid(String message) {
        return new PatientIdentityException("PATIENT_TIMELINE_REQUEST_INVALID", 400, message);
    }
    private static PatientIdentityException denied(String message) {
        return new PatientIdentityException("PATIENT_TIMELINE_SCOPE_DENIED", 403, message);
    }

    record PatientTimelineWire(UUID patientId, List<UUID> patientAliasIds, String completeness,
            String dataWatermark, Instant generatedAt, List<TimelineSourceStatusWire> sourceStatuses,
            List<TimelineItemWire> items, String nextCursor) {}
    record TimelineSourceStatusWire(String source, String state, int loadedCount,
            String errorCode, boolean retryable, Instant asOf) {}
    record TimelineItemWire(String itemType, UUID resourceId, UUID patientId, UUID encounterId,
            UUID facilityId, Instant occurredAt, String status, String title, String summary,
            String sourceSystem, String sourceRoute, Integer versionNo, long rowVersion) {}
    private record TimelineCursor(Instant occurredAt, String itemType, UUID resourceId) {}
    private static final class SyntheticTimelineSourceException extends RuntimeException {
        SyntheticTimelineSourceException(String source) { super(source); }
    }
}
