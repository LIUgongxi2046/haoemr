package org.haoemr.specialtysupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.haoemr.contracts.DepartmentSupportAssessmentPutRequestWire;
import org.haoemr.contracts.DepartmentSupportAssessmentWire;
import org.haoemr.platform.CapabilityPackAvailabilityService;
import org.haoemr.platform.CapabilityPackAvailabilityService.Availability;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class SpecialtySupportService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9_.-]{2,96}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final CapabilityPackAvailabilityService capabilityAvailability;

    SpecialtySupportService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            CapabilityPackAvailabilityService capabilityAvailability) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.capabilityAvailability = capabilityAvailability;
    }

    List<DepartmentSupportAssessmentWire> list(ClinicalIdentity identity, UUID facilityId) {
        return jdbc.sql(baseSelect() + """
                where assessment.tenant_id = :tenant and assessment.facility_id = :facility
                order by department.display_name, assessment.clinical_scope_code
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .query((rs, row) -> map(rs)).list();
    }

    DepartmentSupportAssessmentWire get(
            ClinicalIdentity identity, UUID facilityId, UUID departmentId, String scope) {
        String normalizedScope = requireCode(scope, "clinical scope");
        return jdbc.sql(baseSelect() + """
                where assessment.tenant_id = :tenant and assessment.facility_id = :facility
                  and assessment.department_id = :department
                  and assessment.clinical_scope_code = :scope
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .param("department", departmentId).param("scope", normalizedScope)
                .query((rs, row) -> map(rs)).optional()
                .orElseThrow(() -> new SpecialtySupportException(
                        "SUPPORT_ASSESSMENT_NOT_FOUND", 404, "No support assessment exists for this department scope"));
    }

    DepartmentSupportAssessmentWire put(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID facilityId,
            UUID departmentId,
            String scope,
            DepartmentSupportAssessmentPutRequestWire request) {
        String normalizedScope = requireCode(scope, "clinical scope");
        List<String> gates = normalizeGates(request.missingSafetyGates());
        validateRequestedSupport(request, gates);
        return transactions.execute(status -> {
            requireActiveDepartment(identity.tenantId(), request.organizationId(), facilityId, departmentId);
            requireActiveRole(identity, request.organizationId(), facilityId);
            requireCompatiblePack(identity.tenantId(), request.packReleaseId());
            Availability availability = requireReleasedCapabilityForPositiveSupport(
                    identity.tenantId(), normalizedScope, request);
            String requestHash = sha256(facilityId + "|" + departmentId + "|" + normalizedScope + "|"
                    + request.supportLevel() + "|" + request.packReleaseId() + "|"
                    + request.evidenceBundleHash() + "|" + gates + "|" + request.expiresAt()
                    + "|" + request.expectedRowVersion());
            beginCommand(identity, idempotencyKey, requestHash);

            Existing current = jdbc.sql("""
                    select department_support_assessment_id, row_version
                    from department_support_assessment
                    where tenant_id = :tenant and facility_id = :facility
                      and department_id = :department and clinical_scope_code = :scope
                    for update
                    """).param("tenant", identity.tenantId()).param("facility", facilityId)
                    .param("department", departmentId).param("scope", normalizedScope)
                    .query((rs, row) -> new Existing(
                            rs.getObject("department_support_assessment_id", UUID.class), rs.getLong("row_version")))
                    .optional().orElse(null);

            UUID assessmentId;
            long nextVersion;
            if (current == null) {
                if (request.expectedRowVersion() != 0) {
                    throw versionConflict();
                }
                assessmentId = UUID.randomUUID();
                nextVersion = 1;
                jdbc.sql("""
                        insert into department_support_assessment(
                          tenant_id, department_support_assessment_id, facility_id, department_id,
                          clinical_scope_code, support_level, pack_release_id, evidence_bundle_hash,
                          missing_safety_gates, assessed_by, assessed_at, expires_at, row_version)
                        values (:tenant, :assessment, :facility, :department, :scope, :level,
                          :pack, :evidence, cast(:gates as text[]), :actor, now(), :expires, 1)
                        """).param("tenant", identity.tenantId()).param("assessment", assessmentId)
                        .param("facility", facilityId).param("department", departmentId).param("scope", normalizedScope)
                        .param("level", request.supportLevel().name()).param("pack", request.packReleaseId())
                        .param("evidence", request.evidenceBundleHash()).param("gates", postgresTextArray(gates))
                        .param("actor", identity.userId()).param("expires", instant(request.expiresAt())).update();
            } else {
                if (current.rowVersion() != request.expectedRowVersion()) {
                    throw versionConflict();
                }
                assessmentId = current.id();
                nextVersion = current.rowVersion() + 1;
                int updated = jdbc.sql("""
                        update department_support_assessment
                        set support_level = :level, pack_release_id = :pack,
                          evidence_bundle_hash = :evidence, missing_safety_gates = cast(:gates as text[]),
                          assessed_by = :actor, assessed_at = now(), expires_at = :expires,
                          row_version = row_version + 1
                        where tenant_id = :tenant and department_support_assessment_id = :assessment
                          and row_version = :expected
                        """).param("level", request.supportLevel().name()).param("pack", request.packReleaseId())
                        .param("evidence", request.evidenceBundleHash()).param("gates", postgresTextArray(gates))
                        .param("actor", identity.userId()).param("expires", instant(request.expiresAt()))
                        .param("tenant", identity.tenantId()).param("assessment", assessmentId)
                        .param("expected", request.expectedRowVersion()).update();
                if (updated != 1) {
                    throw versionConflict();
                }
            }
            appendAuditAndOutbox(identity, assessmentId, nextVersion, facilityId, departmentId, normalizedScope,
                    "SPECIALTY_SUPPORT_ASSESSED", "DepartmentSupportAssessed", availability);
            completeCommand(identity, idempotencyKey, assessmentId);
            return get(identity, facilityId, departmentId, normalizedScope);
        });
    }

    void delete(
            ClinicalIdentity identity, String idempotencyKey, UUID organizationId,
            UUID facilityId, UUID departmentId, String scope, long expectedRowVersion) {
        String normalizedScope = requireCode(scope, "clinical scope");
        if (expectedRowVersion < 1) throw versionConflict();
        transactions.executeWithoutResult(status -> {
            requireActiveDepartment(identity.tenantId(), organizationId, facilityId, departmentId);
            requireActiveRole(identity, organizationId, facilityId);
            beginCommand(identity, idempotencyKey,
                    sha256("DELETE|" + facilityId + "|" + departmentId + "|" + normalizedScope + "|" + expectedRowVersion));
            Existing current = jdbc.sql("""
                    select department_support_assessment_id, row_version
                    from department_support_assessment
                    where tenant_id = :tenant and facility_id = :facility
                      and department_id = :department and clinical_scope_code = :scope
                    for update
                    """).param("tenant", identity.tenantId()).param("facility", facilityId)
                    .param("department", departmentId).param("scope", normalizedScope)
                    .query((rs, row) -> new Existing(
                            rs.getObject("department_support_assessment_id", UUID.class), rs.getLong("row_version")))
                    .optional().orElseThrow(() -> new SpecialtySupportException(
                            "SUPPORT_ASSESSMENT_NOT_FOUND", 404, "No support assessment exists for this department scope"));
            if (current.rowVersion() != expectedRowVersion) throw versionConflict();
            appendAuditAndOutbox(identity, current.id(), current.rowVersion() + 1,
                    facilityId, departmentId, normalizedScope,
                    "SPECIALTY_SUPPORT_REMOVED", "DepartmentSupportRemoved", null);
            int deleted = jdbc.sql("""
                    delete from department_support_assessment
                    where tenant_id = :tenant and department_support_assessment_id = :assessment
                      and row_version = :version
                    """).param("tenant", identity.tenantId()).param("assessment", current.id())
                    .param("version", expectedRowVersion).update();
            if (deleted != 1) throw versionConflict();
            completeCommand(identity, idempotencyKey, current.id());
        });
    }

    private void validateRequestedSupport(DepartmentSupportAssessmentPutRequestWire request, List<String> gates) {
        boolean positive = isPositiveSupport(request);
        if (positive && request.packReleaseId() == null) {
            throw new SpecialtySupportException(
                    "PACK_REQUIRED", 409, "A positive support declaration requires an active specialty pack release");
        }
        if (positive && (request.evidenceBundleHash() == null || !HASH.matcher(request.evidenceBundleHash()).matches())) {
            throw new SpecialtySupportException(
                    "SAFETY_GATE_MISSING", 409, "A positive support declaration requires a verified evidence bundle hash");
        }
        if (positive && !gates.isEmpty()) {
            throw new SpecialtySupportException(
                    "SAFETY_GATE_MISSING", 409, "A positive support declaration cannot retain missing safety gates");
        }
        if (positive && (request.expiresAt() == null || !request.expiresAt().isAfter(Instant.now()))) {
            throw new SpecialtySupportException(
                    "EVIDENCE_EXPIRED", 409, "A positive support declaration requires a future evidence expiry time");
        }
        if (request.evidenceBundleHash() != null && !HASH.matcher(request.evidenceBundleHash()).matches()) {
            throw new SpecialtySupportException("INVALID_EVIDENCE_HASH", 400, "Evidence hash must be lowercase SHA-256");
        }
        if (request.expectedRowVersion() < 0) {
            throw new SpecialtySupportException("INVALID_EXPECTED_VERSION", 400, "Expected row version cannot be negative");
        }
    }

    private void requireActiveDepartment(UUID tenantId, UUID organizationId, UUID facilityId, UUID departmentId) {
        long count = jdbc.sql("""
                select count(*) from clinical_department department
                join facility on facility.tenant_id = department.tenant_id
                  and facility.facility_id = department.facility_id
                where department.tenant_id = :tenant and department.facility_id = :facility
                  and department.department_id = :department and department.status = 'ACTIVE'
                  and facility.organization_id = :organization and facility.status = 'ACTIVE'
                """).param("tenant", tenantId).param("organization", organizationId)
                .param("facility", facilityId).param("department", departmentId).query(Long.class).single();
        if (count != 1) {
            throw new SpecialtySupportException(
                    "DEPARTMENT_SCOPE_DENIED", 403, "The department is not active in the requested facility");
        }
    }

    private void requireActiveRole(ClinicalIdentity identity, UUID organizationId, UUID facilityId) {
        String roles = "{" + identity.roleAssignmentIds().stream().map(UUID::toString)
                .reduce((left, right) -> left + "," + right).orElse("") + "}";
        long count = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user and organization_id = :organization
                  and (facility_id is null or facility_id = :facility)
                  and role_assignment_id = any(cast(:roles as uuid[]))
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("organization", organizationId).param("facility", facilityId).param("roles", roles)
                .query(Long.class).single();
        if (count < 1) {
            throw new SpecialtySupportException("APPROVER_SCOPE_DENIED", 403, "No active role can assess this facility");
        }
    }

    private void requireCompatiblePack(UUID tenantId, UUID packReleaseId) {
        if (packReleaseId == null) {
            return;
        }
        long count = jdbc.sql("""
                select count(*) from specialty_pack_release
                where tenant_id = :tenant and specialty_pack_release_id = :pack
                  and lifecycle_status = 'ACTIVE'
                """).param("tenant", tenantId).param("pack", packReleaseId).query(Long.class).single();
        if (count != 1) {
            throw new SpecialtySupportException(
                    "PACK_INCOMPATIBLE", 409, "Only an active compatible specialty pack can support a declaration");
        }
    }

    private Availability requireReleasedCapabilityForPositiveSupport(
            UUID tenantId, String scope, DepartmentSupportAssessmentPutRequestWire request) {
        if (!isPositiveSupport(request)) {
            return null;
        }
        String module = capabilityAvailability.requiredModuleForSpecialtyScope(scope).orElse(null);
        if (module == null) {
            return null;
        }
        return capabilityAvailability.findActiveForSpecialtyScope(tenantId, scope)
                .orElseThrow(() -> new SpecialtySupportException(
                        "CAPABILITY_MODULE_NOT_ACTIVE", 409,
                        "A positive support declaration requires an active approved capability composition for " + module));
    }

    private void beginCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new SpecialtySupportException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'SPECIALTY_SUPPORT_PUT', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new SpecialtySupportException("IDEMPOTENCY_REPLAY", 409, "This command key has already been used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String key, UUID assessmentId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = 'SPECIALTY_SUPPORT_PUT'
                  and idempotency_key = :key
                """).param("resource", assessmentId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void appendAuditAndOutbox(
            ClinicalIdentity identity, UUID assessmentId, long version,
            UUID facilityId, UUID departmentId, String scope,
            String actionCode, String eventType, Availability availability) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + actionCode + "|"
                + assessmentId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action,
                  'DEPARTMENT_SUPPORT_ASSESSMENT', :assessment, :trace, :previous, :hash,
                  jsonb_build_object('facility_id', :facility, 'department_id', :department,
                    'clinical_scope_code', :scope, 'row_version', :version)
                    || cast(:capability as jsonb))
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("action", actionCode)
                .param("assessment", assessmentId).param("trace", trace).param("previous", previousHash)
                .param("hash", eventHash).param("facility", facilityId).param("department", departmentId)
                .param("scope", scope).param("version", version)
                .param("capability", capabilityEvidence(availability)).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DEPARTMENT_SUPPORT_ASSESSMENT', :assessment, :version,
                  :event_type, 1,
                  jsonb_build_object('facility_id', :facility, 'department_id', :department,
                    'clinical_scope_code', :scope) || cast(:capability as jsonb))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("event_type", eventType)
                .param("assessment", assessmentId).param("version", version).param("facility", facilityId)
                .param("department", departmentId).param("scope", scope)
                .param("capability", capabilityEvidence(availability)).update();
    }

    private DepartmentSupportAssessmentWire map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String storedLevel = rs.getString("effective_support_level");
        List<String> gates = new ArrayList<>(List.of((String[]) rs.getArray("missing_safety_gates").getArray()));
        if (rs.getBoolean("evidence_expired") && !gates.contains("EVIDENCE_EXPIRED")) {
            gates.add("EVIDENCE_EXPIRED");
        }
        OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
        return new DepartmentSupportAssessmentWire(
                rs.getObject("department_support_assessment_id", UUID.class),
                rs.getObject("facility_id", UUID.class), rs.getObject("department_id", UUID.class),
                rs.getString("clinical_scope_code"),
                DepartmentSupportAssessmentWire.SupportLevelValue.valueOf(storedLevel),
                rs.getObject("pack_release_id", UUID.class), rs.getString("evidence_bundle_hash"), gates,
                rs.getObject("assessed_by", UUID.class), rs.getObject("assessed_at", OffsetDateTime.class).toInstant(),
                expires == null ? null : expires.toInstant(), rs.getLong("row_version"));
    }

    private static String baseSelect() {
        return """
                select assessment.department_support_assessment_id, assessment.facility_id,
                  assessment.department_id, assessment.clinical_scope_code,
                  case when assessment.expires_at <= now()
                    and assessment.support_level in ('GENERAL_AVAILABLE','BASIC_CLOSED_LOOP')
                    then 'PACK_PENDING' else assessment.support_level end as effective_support_level,
                  assessment.pack_release_id, assessment.evidence_bundle_hash,
                  assessment.missing_safety_gates, assessment.assessed_by, assessment.assessed_at,
                  assessment.expires_at, assessment.row_version,
                  (assessment.expires_at <= now()) as evidence_expired
                from department_support_assessment assessment
                join clinical_department department on department.tenant_id = assessment.tenant_id
                  and department.facility_id = assessment.facility_id
                  and department.department_id = assessment.department_id
                """;
    }

    private static List<String> normalizeGates(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> requireCode(value, "safety gate")).distinct().sorted().toList();
    }

    private static boolean isPositiveSupport(DepartmentSupportAssessmentPutRequestWire request) {
        return request.supportLevel()
                == DepartmentSupportAssessmentPutRequestWire.SupportLevelValue.GENERAL_AVAILABLE
                || request.supportLevel()
                == DepartmentSupportAssessmentPutRequestWire.SupportLevelValue.BASIC_CLOSED_LOOP;
    }

    private static String capabilityEvidence(Availability availability) {
        if (availability == null) {
            return "{}";
        }
        return """
                {"capability_module":"%s","capability_pack_id":"%s",
                "capability_release_id":"%s","capability_composition_id":"%s",
                "capability_composition_row_version":%d}
                """.formatted(
                availability.moduleCode(), availability.capabilityPackId(), availability.releaseId(),
                availability.compositionConfigId(), availability.compositionRowVersion()).replaceAll("\\s+", "");
    }

    private static String requireCode(String value, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!CODE.matcher(normalized).matches()) {
            throw new SpecialtySupportException("INVALID_SCOPE_CODE", 400, "Invalid " + label + " code");
        }
        return normalized;
    }

    private static OffsetDateTime instant(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String postgresTextArray(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static SpecialtySupportException versionConflict() {
        return new SpecialtySupportException(
                "SUPPORT_VERSION_CONFLICT", 409, "The department support assessment changed; reload before saving");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Existing(UUID id, long rowVersion) {}
}
