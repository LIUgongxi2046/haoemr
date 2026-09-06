package org.haoemr.patient;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class PatientTimelineApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID TARGET = UUID.fromString("018f0000-0000-7000-8000-000000000001");

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final UUID aliasPatient = UUID.randomUUID();
    private final UUID aliasEncounter = UUID.randomUUID();
    private final UUID denyPolicy = UUID.randomUUID();
    private final UUID deviceObservation = UUID.randomUUID();
    private final UUID deviceAlert = UUID.randomUUID();
    private final UUID deviceBinding = UUID.randomUUID();
    private final String deviceCode = "TIMELINE-DEVICE-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void seedMergedAliasWithHistoricalEncounter() {
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date,
                  status, merged_into_patient_id)
                values (:tenant, :patient, '合并前历史档案', '1', date '1978-04-16', 'MERGED', :target)
                """).param("tenant", TENANT).param("patient", aliasPatient).param("target", TARGET).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, ended_at, source_system, source_key)
                values (:tenant, :encounter, :patient, :organization, :facility,
                  'EMERGENCY', 'FINISHED', now() - interval '5 minutes', now() - interval '1 minute',
                  'TIMELINE-ALIAS-TEST', :source_key)
                """).param("tenant", TENANT).param("encounter", aliasEncounter).param("patient", aliasPatient)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", aliasEncounter.toString()).update();
    }

    @AfterEach
    void cleanupAlias() {
        jdbc.sql("delete from device_alert where tenant_id = :tenant and device_alert_id = :alert")
                .param("tenant", TENANT).param("alert", deviceAlert).update();
        jdbc.sql("delete from device_observation where tenant_id = :tenant and observation_id = :observation")
                .param("tenant", TENANT).param("observation", deviceObservation).update();
        jdbc.sql("delete from device_patient_binding where tenant_id = :tenant and device_patient_binding_id = :binding")
                .param("tenant", TENANT).param("binding", deviceBinding).update();
        jdbc.sql("delete from device_status where tenant_id = :tenant and device_code = :device")
                .param("tenant", TENANT).param("device", deviceCode).update();
        jdbc.sql("delete from authorization_policy where tenant_id = :tenant and policy_id = :policy")
                .param("tenant", TENANT).param("policy", denyPolicy).update();
        jdbc.sql("alter table encounter_state_event disable trigger encounter_state_event_immutable").update();
        try {
            jdbc.sql("delete from encounter_state_event where tenant_id = :tenant and encounter_id = :encounter")
                    .param("tenant", TENANT).param("encounter", aliasEncounter).update();
        } finally {
            jdbc.sql("alter table encounter_state_event enable trigger encounter_state_event_immutable").update();
        }
        jdbc.sql("delete from encounter where tenant_id = :tenant and encounter_id = :encounter")
                .param("tenant", TENANT).param("encounter", aliasEncounter).update();
        jdbc.sql("delete from patient where tenant_id = :tenant and patient_id = :patient")
                .param("tenant", TENANT).param("patient", aliasPatient).update();
    }

    @Test
    void givenPublishedItemDenyPolicy_whenTimelineLoads_thenUnauthorizedBodiesAndCountsAreNotDisclosed()
            throws Exception {
        jdbc.sql("""
                insert into authorization_policy(tenant_id, policy_id, policy_code, policy_name, version_no,
                  effect, status, resource_type, action_code, purpose_codes, emergency_override_allowed,
                  priority, valid_from, created_by, approved_by, published_at)
                values (:tenant, :policy, :code, '患者时间线访问拒绝测试策略', 1, 'DENY', 'PUBLISHED', 'ENCOUNTER', 'READ',
                  array['PATIENT_TIMELINE'], false, 9000, now() - interval '1 minute',
                  :creator, :approver, now())
                """).param("tenant", TENANT).param("policy", denyPolicy)
                .param("code", "TIMELINE-DENY-" + denyPolicy).param("creator", USER)
                .param("approver", UUID.fromString("018f0000-0000-7000-8000-00000000aa06")).update();
        HttpResponse<String> response = timeline(TARGET, TARGET, "?types=ENCOUNTER&limit=100",
                issueLease(TARGET), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("items")).isEmpty();
        assertThat(body.path("source_statuses").get(0).path("loaded_count").intValue()).isZero();
        assertThat(body.toString()).doesNotContain(aliasEncounter.toString(), "TIMELINE-ALIAS-TEST");
        assertThat(jdbc.sql("""
                select (details ->> 'redacted_count')::integer from audit_event
                where tenant_id = :tenant and action_code = 'PATIENT_TIMELINE_VIEWED'
                  and resource_id = :patient order by occurred_at desc limit 1
                """).param("tenant", TENANT).param("patient", TARGET).query(Integer.class).single())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void givenAuthorizedCanonicalPatient_whenTimelineLoads_thenAliasesSourcesFiltersAndAuditRemainExplicit()
            throws Exception {
        Lease lease = issueLease(TARGET);
        HttpResponse<String> response = timeline(TARGET, TARGET,
                "?types=ENCOUNTER,DOCUMENT,DIAGNOSIS,ORDER,RESULT,TASK&limit=100", lease, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("completeness").stringValue()).isEqualTo("COMPLETE");
        assertThat(body.path("source_statuses")).hasSize(6);
        assertThat(body.path("source_statuses").toString()).doesNotContain("PARTIAL", "SOURCE_QUERY_FAILED");
        assertThat(body.path("patient_alias_ids").toString()).contains(TARGET.toString(), aliasPatient.toString());
        assertThat(body.path("items").toString()).contains(aliasEncounter.toString(), "TIMELINE-ALIAS-TEST");
        assertThat(body.path("data_watermark").stringValue()).hasSize(64);

        HttpResponse<String> statusFiltered = timeline(TARGET, TARGET,
                "?types=ENCOUNTER&statuses=FINISHED&limit=100", lease, null);
        assertThat(statusFiltered.statusCode()).isEqualTo(200);
        JsonNode filtered = objectMapper.readTree(statusFiltered.body());
        assertThat(filtered.path("source_statuses")).hasSize(1);
        assertThat(filtered.path("items")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(filtered.path("items").toString()).doesNotContain("IN_PROGRESS", "PLANNED");

        JsonNode firstPage = objectMapper.readTree(timeline(TARGET, TARGET,
                "?types=ENCOUNTER&limit=1", issueLease(TARGET), null).body());
        assertThat(firstPage.path("items")).hasSize(1);
        assertThat(firstPage.path("next_cursor").stringValue()).isNotBlank();

        HttpResponse<String> next = timeline(TARGET, TARGET,
                "?types=ENCOUNTER&limit=1&cursor="
                        + firstPage.path("next_cursor").stringValue(), issueLease(TARGET), null);
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(next.body()).path("items")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = :tenant
                  and action_code = 'PATIENT_TIMELINE_VIEWED' and resource_id = :patient
                """).param("tenant", TENANT).param("patient", TARGET).query(Long.class).single())
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void givenPartialSourceFailureAndMismatchedContext_whenTimelineLoads_thenFailureIsVisibleAndScopeFailsClosed()
            throws Exception {
        Lease lease = issueLease(TARGET);
        HttpResponse<String> partial = timeline(TARGET, TARGET, "?limit=20", lease, "RESULT,TASK");
        assertThat(partial.statusCode()).as(partial.body()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(partial.body());
        assertThat(body.path("completeness").stringValue()).isEqualTo("PARTIAL");
        assertThat(body.path("source_statuses").toString())
                .contains("SYNTHETIC_SOURCE_FAILURE", "RESULT", "TASK", "AVAILABLE");
        assertThat(body.path("items").isArray()).isTrue();

        HttpResponse<String> malformedCursor = timeline(TARGET, TARGET, "?cursor=not-a-cursor", lease, null);
        assertThat(malformedCursor.statusCode()).isEqualTo(400);
        assertThat(malformedCursor.body()).contains("PATIENT_TIMELINE_REQUEST_INVALID");

        HttpResponse<String> mismatch = timeline(TARGET, aliasPatient, "?limit=10", lease, null);
        assertThat(mismatch.statusCode()).isEqualTo(403);
        assertThat(mismatch.body()).doesNotContain("合并前历史档案", aliasEncounter.toString());
    }

    @Test
    void givenBoundDeviceTelemetryAndAlert_whenTimelineLoads_thenProjectionHasTraceableSimulatedSource()
            throws Exception {
        jdbc.sql("""
                insert into device_status(
                  tenant_id, device_code, online_status, calibration_status, alarm_state)
                values (:tenant, :device, 'ONLINE', 'VALID', 'MEDIUM')
                """)
                .param("tenant", TENANT).param("device", deviceCode).update();
        jdbc.sql("""
                insert into device_patient_binding(
                  tenant_id, device_patient_binding_id, device_code, patient_id, encounter_id,
                  bound_by, bound_at, verification_method)
                values (:tenant, :binding, :device, :patient,
                  (select encounter_id from encounter where tenant_id = :tenant and patient_id = :patient
                    and facility_id = :facility order by started_at desc, encounter_id desc limit 1),
                  :user, now() - interval '1 minute', '测试双标识核验')
                """)
                .param("tenant", TENANT).param("binding", deviceBinding).param("device", deviceCode).param("patient", TARGET)
                .param("facility", FACILITY).param("user", USER).update();
        jdbc.sql("""
                insert into device_observation(tenant_id, observation_id, device_code, trace_id, metric, metric_value, metric_unit, quality, alarm_level, observed_at)
                values (:tenant, :observation, :device, 'simulated-device-trace', 'SPO2', 91.0, '%', 'VERIFIED', 'MEDIUM', now())
                """)
                .param("tenant", TENANT).param("observation", deviceObservation).param("device", deviceCode).update();
        jdbc.sql("""
                insert into device_alert(tenant_id, device_alert_id, device_code, observation_id, metric, alarm_level, state, summary)
                values (:tenant, :alert, :device, :observation, 'SPO2', 'MEDIUM', 'OPEN', 'SPO2 低于阈值')
                """)
                .param("tenant", TENANT).param("alert", deviceAlert).param("device", deviceCode).param("observation", deviceObservation).update();
        HttpResponse<String> response = timeline(TARGET, TARGET, "?types=DEVICE&limit=20", issueLease(TARGET), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains(deviceObservation.toString(), deviceAlert.toString(), "设备遥测", "设备告警", "DEVICE_GATEWAY_SIMULATED", "simulated-device-trace");
    }

    private Lease issueLease(UUID patient) throws Exception {
        HttpResponse<String> response = http.send(base("/api/v1/context-leases")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"organization_id":"%s","facility_id":"%s","patient_id":"%s",
                         "purpose_code":"PATIENT_TIMELINE"}
                        """.formatted(ORGANIZATION, FACILITY, patient))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(response.body());
        return new Lease(json.path("lease_id").stringValue(), json.path("authorization_watermark").stringValue());
    }

    private HttpResponse<String> timeline(UUID pathPatient, UUID headerPatient, String query,
            Lease lease, String syntheticFailures) throws Exception {
        HttpRequest.Builder request = base("/api/v1/patients/" + pathPatient + "/timeline" + query)
                .header("X-Context-Lease-Id", lease.id())
                .header("X-Authorization-Watermark", lease.watermark())
                .header("X-Organization-Context", ORGANIZATION.toString())
                .header("X-Facility-Context", FACILITY.toString())
                .header("X-Patient-Context", headerPatient.toString());
        if (syntheticFailures != null) {
            request.header("X-HaoEMR-Synthetic-Failed-Timeline-Sources", syntheticFailures);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer dev-synthetic-token")
                .header("X-HaoEMR-Tenant-Id", TENANT.toString())
                .header("X-HaoEMR-User-Id", USER.toString())
                .header("X-HaoEMR-Role-Assignment-Ids", ROLE.toString());
    }

    private record Lease(String id, String watermark) {}
}
