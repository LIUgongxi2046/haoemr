package org.haoemr.organization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class OrganizationAdministrationService {

    private static final List<String> ADMIN_ROLES = List.of("SYSTEM_ADMIN", "CLINICAL_ADMIN");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    OrganizationAdministrationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    List<OrganizationUnitWire> list(ClinicalIdentity identity) {
        requireAdministrator(identity);
        return jdbc.sql("""
                select 'ORGANIZATION' as unit_type, organization_id as unit_id,
                  parent_organization_id as parent_unit_id, organization_code as unit_code,
                  display_name, status, effective_from, effective_until, row_version
                from organization where tenant_id = :tenant
                  and organization_code not like 'ACC-%'
                union all
                select 'FACILITY', facility_id, organization_id, facility_code,
                  display_name, status, effective_from, effective_until, row_version
                from facility where tenant_id = :tenant
                  and facility_code not like 'ACC-%'
                union all
                select 'DEPARTMENT', department_id, coalesce(parent_department_id, facility_id),
                  department_code, display_name, status, effective_from, effective_until, row_version
                from clinical_department where tenant_id = :tenant
                  and department_code !~ '^(ACC-|DEP-|WARD-FROM-DEPT-|WARD-TO-DEPT-)'
                union all
                select 'WARD', ward_id, department_id, ward_code,
                  display_name, status, effective_from, effective_until, row_version
                from clinical_ward where tenant_id = :tenant
                  and ward_code !~ '^(ACC-|WARD-[0-9a-f]{8}$|WARD-FROM-|WARD-TO-)'
                union all
                select 'BED', bed_id, ward_id, bed_label,
                  bed_label, status, effective_from, effective_until, row_version
                from clinical_bed where tenant_id = :tenant
                  and bed_label !~ '^(ACC-|B-[0-9a-f]{8}$|SYN-|TEST-)'
                order by unit_type, display_name, unit_id
                """).param("tenant", identity.tenantId())
                .query((rs, row) -> new OrganizationUnitWire(
                        UnitType.valueOf(rs.getString("unit_type")), rs.getObject("unit_id", UUID.class),
                        rs.getObject("parent_unit_id", UUID.class), rs.getString("unit_code"),
                        rs.getString("display_name"), rs.getString("status"),
                        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("effective_until", OffsetDateTime.class)), rs.getLong("row_version")))
                .list();
    }

    OrganizationUnitWire create(
            ClinicalIdentity identity,
            String idempotencyKey,
            OrganizationUnitCreateRequest request) {
        requireAdministrator(identity);
        validateCreate(request);
        return transactions.execute(status -> {
            String type = request.unitType().name();
            String requestHash = sha256(type + "|" + request.unitId() + "|" + request.parentUnitId()
                    + "|" + request.organizationId() + "|" + request.facilityId() + "|"
                    + request.departmentId() + "|" + request.unitCode().trim() + "|"
                    + request.displayName().trim() + "|" + request.effectiveFrom() + "|"
                    + request.effectiveUntil());
            begin(identity, "ORGANIZATION_UNIT_CREATE", idempotencyKey, requestHash);
            try {
                switch (request.unitType()) {
                    case ORGANIZATION -> insertOrganization(identity, request);
                    case FACILITY -> insertFacility(identity, request);
                    case DEPARTMENT -> insertDepartment(identity, request);
                    case WARD -> insertWard(identity, request);
                    case BED -> insertBed(identity, request);
                }
            } catch (DataIntegrityViolationException invalidHierarchy) {
                throw new OrganizationAdministrationException(
                        "ORGANIZATION_HIERARCHY_INVALID", 409,
                        "The unit conflicts with the current hierarchy, code or effective period");
            }
            appendEvidence(identity, "ORGANIZATION_UNIT_CREATED", request.unitType(), request.unitId(), 1);
            complete(identity, "ORGANIZATION_UNIT_CREATE", idempotencyKey, request.unitId());
            return find(identity.tenantId(), request.unitType(), request.unitId());
        });
    }

    OrganizationUnitWire deactivate(
            ClinicalIdentity identity,
            String idempotencyKey,
            UnitType unitType,
            UUID unitId,
            OrganizationUnitDeactivateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedRowVersion() < 1 || request.effectiveUntil() == null
                || request.effectiveUntil().isAfter(Instant.now().plusSeconds(300))) {
            throw new OrganizationAdministrationException(
                    "ORGANIZATION_UNIT_REQUEST_INVALID", 400,
                    "Expected row version and a current or past effective-until time are required");
        }
        return transactions.execute(status -> {
            String requestHash = sha256(unitType + "|" + unitId + "|" + request.expectedRowVersion()
                    + "|" + request.effectiveUntil() + "|" + request.reason());
            begin(identity, "ORGANIZATION_UNIT_DEACTIVATE", idempotencyKey, requestHash);
            requireNoActiveChildren(identity.tenantId(), unitType, unitId);
            String table = table(unitType);
            String idColumn = idColumn(unitType);
            int updated = jdbc.sql("update " + table + " set status = 'INACTIVE', effective_until = :until, "
                            + "row_version = row_version + 1, updated_at = now() "
                            + "where tenant_id = :tenant and " + idColumn + " = :id and status = 'ACTIVE' "
                            + "and row_version = :expected and effective_from < :until")
                    .param("until", utc(request.effectiveUntil())).param("tenant", identity.tenantId())
                    .param("id", unitId).param("expected", request.expectedRowVersion()).update();
            if (updated != 1) {
                throw new OrganizationAdministrationException(
                        "ORGANIZATION_UNIT_VERSION_CONFLICT", 409,
                        "The unit changed, is already inactive or has an invalid effective period");
            }
            appendEvidence(
                    identity, "ORGANIZATION_UNIT_DEACTIVATED", unitType, unitId,
                    request.expectedRowVersion() + 1);
            complete(identity, "ORGANIZATION_UNIT_DEACTIVATE", idempotencyKey, unitId);
            return find(identity.tenantId(), unitType, unitId);
        });
    }

    private void requireAdministrator(ClinicalIdentity identity) {
        if (identity.roleAssignmentIds().isEmpty()) deny();
        long allowed = jdbc.sql("""
                select count(*) from role_assignment assignment
                where assignment.tenant_id = :tenant and assignment.user_id = :user
                  and assignment.role_assignment_id in (:roles)
                  and assignment.role_code in (:admin_roles)
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= now()
                  and (assignment.valid_until is null or assignment.valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", identity.roleAssignmentIds()).param("admin_roles", ADMIN_ROLES)
                .query(Long.class).single();
        if (allowed == 0) deny();
    }

    private static void deny() {
        throw new OrganizationAdministrationException(
                "ADMIN_SCOPE_DENIED", 403, "The requested administration scope is not permitted");
    }

    private static void validateCreate(OrganizationUnitCreateRequest request) {
        if (request == null || request.unitType() == null || request.unitId() == null
                || request.unitCode() == null || request.unitCode().isBlank() || request.unitCode().length() > 96
                || request.displayName() == null || request.displayName().isBlank()
                || request.displayName().length() > 256 || request.effectiveFrom() == null
                || (request.effectiveUntil() != null && !request.effectiveUntil().isAfter(request.effectiveFrom()))) {
            throw new OrganizationAdministrationException(
                    "ORGANIZATION_UNIT_REQUEST_INVALID", 400,
                    "Unit type, id, code, display name and a valid effective period are required");
        }
    }

    private void insertOrganization(ClinicalIdentity identity, OrganizationUnitCreateRequest request) {
        jdbc.sql("""
                insert into organization(
                  tenant_id, organization_id, organization_code, display_name, status,
                  parent_organization_id, organization_type, effective_from, effective_until)
                values (:tenant, :id, :code, :name, 'ACTIVE', :parent, :subtype, :effective_from, :effective_until)
                """).param("tenant", identity.tenantId()).param("id", request.unitId())
                .param("code", request.unitCode().trim()).param("name", request.displayName().trim())
                .param("parent", request.parentUnitId())
                .param("subtype", subtype(request.subtype(), "HEALTHCARE_ORGANIZATION"))
                .param("effective_from", utc(request.effectiveFrom())).param("effective_until", utc(request.effectiveUntil()))
                .update();
    }

    private void insertFacility(ClinicalIdentity identity, OrganizationUnitCreateRequest request) {
        require(request.organizationId(), "organization_id");
        jdbc.sql("""
                insert into facility(
                  tenant_id, organization_id, facility_id, facility_code, display_name,
                  timezone, status, effective_from, effective_until)
                values (:tenant, :organization, :id, :code, :name, :timezone,
                  'ACTIVE', :effective_from, :effective_until)
                """).param("tenant", identity.tenantId()).param("organization", request.organizationId())
                .param("id", request.unitId()).param("code", request.unitCode().trim())
                .param("name", request.displayName().trim())
                .param("timezone", subtype(request.subtype(), "Asia/Shanghai"))
                .param("effective_from", utc(request.effectiveFrom())).param("effective_until", utc(request.effectiveUntil()))
                .update();
    }

    private void insertDepartment(ClinicalIdentity identity, OrganizationUnitCreateRequest request) {
        require(request.facilityId(), "facility_id");
        jdbc.sql("""
                insert into clinical_department(
                  tenant_id, facility_id, department_id, department_code, display_name,
                  status, parent_department_id, unit_type, effective_from, effective_until)
                values (:tenant, :facility, :id, :code, :name, 'ACTIVE', :parent,
                  :subtype, :effective_from, :effective_until)
                """).param("tenant", identity.tenantId()).param("facility", request.facilityId())
                .param("id", request.unitId()).param("code", request.unitCode().trim())
                .param("name", request.displayName().trim()).param("parent", request.parentUnitId())
                .param("subtype", subtype(request.subtype(), "DEPARTMENT"))
                .param("effective_from", utc(request.effectiveFrom())).param("effective_until", utc(request.effectiveUntil()))
                .update();
    }

    private void insertWard(ClinicalIdentity identity, OrganizationUnitCreateRequest request) {
        require(request.facilityId(), "facility_id");
        require(request.departmentId(), "department_id");
        jdbc.sql("""
                insert into clinical_ward(
                  tenant_id, facility_id, department_id, ward_id, ward_code, display_name,
                  status, effective_from, effective_until)
                values (:tenant, :facility, :department, :id, :code, :name,
                  'ACTIVE', :effective_from, :effective_until)
                """).param("tenant", identity.tenantId()).param("facility", request.facilityId())
                .param("department", request.departmentId()).param("id", request.unitId())
                .param("code", request.unitCode().trim()).param("name", request.displayName().trim())
                .param("effective_from", utc(request.effectiveFrom())).param("effective_until", utc(request.effectiveUntil()))
                .update();
    }

    private void insertBed(ClinicalIdentity identity, OrganizationUnitCreateRequest request) {
        require(request.parentUnitId(), "ward_id");
        jdbc.sql("""
                insert into clinical_bed(
                  tenant_id, bed_id, ward_id, bed_label, status, effective_from, effective_until)
                values (:tenant, :id, :ward, :label, 'ACTIVE', :effective_from, :effective_until)
                """).param("tenant", identity.tenantId()).param("id", request.unitId())
                .param("ward", request.parentUnitId()).param("label", request.unitCode().trim())
                .param("effective_from", utc(request.effectiveFrom())).param("effective_until", utc(request.effectiveUntil()))
                .update();
    }

    private void requireNoActiveChildren(UUID tenantId, UnitType unitType, UUID unitId) {
        String sql = switch (unitType) {
            case ORGANIZATION -> "select count(*) from facility where tenant_id = :tenant and organization_id = :id and status = 'ACTIVE'";
            case FACILITY -> "select count(*) from clinical_department where tenant_id = :tenant and facility_id = :id and status = 'ACTIVE'";
            case DEPARTMENT -> "select (select count(*) from clinical_department where tenant_id = :tenant and parent_department_id = :id and status = 'ACTIVE') + (select count(*) from clinical_ward where tenant_id = :tenant and department_id = :id and status = 'ACTIVE')";
            case WARD -> "select count(*) from clinical_bed where tenant_id = :tenant and ward_id = :id and status = 'ACTIVE'";
            case BED -> null;
        };
        if (sql != null && jdbc.sql(sql).param("tenant", tenantId).param("id", unitId)
                .query(Long.class).single() > 0) {
            throw new OrganizationAdministrationException(
                    "ORGANIZATION_UNIT_HAS_ACTIVE_CHILDREN", 409,
                    "Active child units must be ended before this unit can be deactivated");
        }
    }

    private OrganizationUnitWire find(UUID tenantId, UnitType unitType, UUID unitId) {
        return listByType(tenantId, unitType, unitId).stream().filter(item -> item.unitId().equals(unitId))
                .findFirst().orElseThrow(() -> new OrganizationAdministrationException(
                        "ORGANIZATION_UNIT_NOT_FOUND", 404, "The organization unit was not found"));
    }

    private List<OrganizationUnitWire> listByType(UUID tenantId, UnitType unitType, UUID unitId) {
        String table = table(unitType);
        String idColumn = idColumn(unitType);
        String parentExpression = switch (unitType) {
            case ORGANIZATION -> "parent_organization_id";
            case FACILITY -> "organization_id";
            case DEPARTMENT -> "coalesce(parent_department_id, facility_id)";
            case WARD -> "department_id";
            case BED -> "ward_id";
        };
        String codeColumn = switch (unitType) {
            case ORGANIZATION -> "organization_code";
            case FACILITY -> "facility_code";
            case DEPARTMENT -> "department_code";
            case WARD -> "ward_code";
            case BED -> "bed_label";
        };
        String displayColumn = unitType == UnitType.BED ? "bed_label" : "display_name";
        return jdbc.sql("select " + idColumn + " as unit_id, " + parentExpression + " as parent_unit_id, "
                        + codeColumn + " as unit_code, " + displayColumn + " as display_name, status, "
                        + "effective_from, effective_until, row_version from " + table
                        + " where tenant_id = :tenant and " + idColumn + " = :id")
                .param("tenant", tenantId).param("id", unitId)
                .query((rs, row) -> new OrganizationUnitWire(
                        unitType, rs.getObject("unit_id", UUID.class), rs.getObject("parent_unit_id", UUID.class),
                        rs.getString("unit_code"), rs.getString("display_name"), rs.getString("status"),
                        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("effective_until", OffsetDateTime.class)), rs.getLong("row_version")))
                .list();
    }

    private void begin(ClinicalIdentity identity, String scope, String key, String hash) {
        if (key == null || key.isBlank()) {
            throw new OrganizationAdministrationException(
                    "IDEMPOTENCY_KEY_REQUIRED", 400, "Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", hash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new OrganizationAdministrationException(
                    "IDEMPOTENCY_REPLAY", 409, "This administration command was already submitted");
        }
    }

    private void complete(ClinicalIdentity identity, String scope, String key, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, String action, UnitType type, UUID resourceId, long version) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|" + resourceId
                + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, 'ORGANIZATION_UNIT', :resource,
                  :trace, :previous, :hash, jsonb_build_object('unit_type', :unit_type))
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("trace", trace).param("previous", previousHash).param("hash", eventHash)
                .param("unit_type", type.name()).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'ORGANIZATION_UNIT', :resource, :version,
                  :event_type, 1, jsonb_build_object('unit_type', :unit_type))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("resource", resourceId).param("version", version).param("event_type", action)
                .param("unit_type", type.name()).update();
    }

    private static String table(UnitType type) {
        return switch (type) {
            case ORGANIZATION -> "organization";
            case FACILITY -> "facility";
            case DEPARTMENT -> "clinical_department";
            case WARD -> "clinical_ward";
            case BED -> "clinical_bed";
        };
    }

    private static String idColumn(UnitType type) {
        return switch (type) {
            case ORGANIZATION -> "organization_id";
            case FACILITY -> "facility_id";
            case DEPARTMENT -> "department_id";
            case WARD -> "ward_id";
            case BED -> "bed_id";
        };
    }

    private static String subtype(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static void require(UUID value, String field) {
        if (value == null) {
            throw new OrganizationAdministrationException(
                    "ORGANIZATION_UNIT_REQUEST_INVALID", 400, field + " is required for this unit type");
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    enum UnitType { ORGANIZATION, FACILITY, DEPARTMENT, WARD, BED }

    record OrganizationUnitCreateRequest(
            UnitType unitType,
            UUID unitId,
            UUID parentUnitId,
            UUID organizationId,
            UUID facilityId,
            UUID departmentId,
            String unitCode,
            String displayName,
            String subtype,
            Instant effectiveFrom,
            Instant effectiveUntil) {}

    record OrganizationUnitDeactivateRequest(
            long expectedRowVersion,
            Instant effectiveUntil,
            String reason) {}

    record OrganizationUnitWire(
            UnitType unitType,
            UUID unitId,
            UUID parentUnitId,
            String unitCode,
            String displayName,
            String status,
            Instant effectiveFrom,
            Instant effectiveUntil,
            long rowVersion) {}
}
