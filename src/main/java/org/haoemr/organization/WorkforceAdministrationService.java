package org.haoemr.organization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class WorkforceAdministrationService {

    private static final List<String> ADMIN_ROLES = List.of("SYSTEM_ADMIN", "CLINICAL_ADMIN");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    WorkforceAdministrationService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    List<WorkforceIdentityWire> list(ClinicalIdentity identity) {
        requireAdministrator(identity);
        return jdbc.sql("""
                select person.person_id, person.person_code, person.display_name as person_display_name,
                  person.status as person_status, person.row_version as person_row_version,
                  account.user_id, account.external_subject, account.status as account_status,
                  account.row_version as account_row_version,
                  role.role_assignment_id, role.role_code, role.status as role_status,
                  role.valid_from, role.valid_until, role.row_version as role_row_version,
                  workforce.organization_id, workforce.facility_id, workforce.department_id,
                  workforce.ward_id, workforce.position_code,
                  (select count(*) from practitioner_credential credential
                    where credential.tenant_id = person.tenant_id and credential.person_id = person.person_id
                      and credential.status = 'ACTIVE' and credential.valid_from <= now()
                      and (credential.valid_until is null or credential.valid_until > now())) as active_credential_count
                from workforce_person person
                left join app_user account on account.tenant_id = person.tenant_id
                  and account.person_id = person.person_id
                left join role_assignment role on role.tenant_id = account.tenant_id
                  and role.user_id = account.user_id and role.person_id = person.person_id
                left join workforce_assignment workforce on workforce.tenant_id = role.tenant_id
                  and workforce.source_role_assignment_id = role.role_assignment_id
                where person.tenant_id = :tenant
                  and not (
                    person.person_code like 'MIGRATED-%'
                    and (person.display_name = '合成上级审签用户' or person.display_name like '归档测试-%')
                  )
                order by person.display_name, account.user_id, role.role_assignment_id
                """).param("tenant", identity.tenantId())
                .query((rs, row) -> new WorkforceIdentityWire(
                        rs.getObject("person_id", UUID.class), rs.getString("person_code"),
                        rs.getString("person_display_name"), rs.getString("person_status"),
                        rs.getLong("person_row_version"), rs.getObject("user_id", UUID.class),
                        rs.getString("external_subject"), rs.getString("account_status"),
                        rs.getLong("account_row_version"), rs.getObject("role_assignment_id", UUID.class),
                        rs.getString("role_code"), rs.getString("role_status"),
                        instant(rs.getObject("valid_from", OffsetDateTime.class)),
                        instant(rs.getObject("valid_until", OffsetDateTime.class)),
                        rs.getLong("role_row_version"), rs.getObject("organization_id", UUID.class),
                        rs.getObject("facility_id", UUID.class), rs.getObject("department_id", UUID.class),
                        rs.getObject("ward_id", UUID.class), rs.getString("position_code"),
                        rs.getLong("active_credential_count")))
                .list();
    }

    WorkforceIdentityWire onboard(
            ClinicalIdentity identity,
            String idempotencyKey,
            WorkforceOnboardingRequest request) {
        requireAdministrator(identity);
        validateOnboarding(request);
        return transactions.execute(status -> {
            String requestHash = sha256(request.personId() + "|" + request.personCode() + "|"
                    + request.displayName() + "|" + request.userId() + "|" + request.externalSubject()
                    + "|" + request.roleAssignmentId() + "|" + request.roleCode() + "|"
                    + request.organizationId() + "|" + request.facilityId() + "|"
                    + request.departmentId() + "|" + request.wardId() + "|" + request.validFrom()
                    + "|" + request.validUntil() + "|" + request.credentialType() + "|"
                    + request.registrationNumber());
            begin(identity, "WORKFORCE_ONBOARD", idempotencyKey, requestHash);
            requireActiveScope(identity.tenantId(), request);
            try {
                jdbc.sql("""
                        insert into workforce_person(
                          tenant_id, person_id, person_code, display_name, status, effective_from)
                        values (:tenant, :person, :code, :name, 'ACTIVE', :valid_from)
                        """).param("tenant", identity.tenantId()).param("person", request.personId())
                        .param("code", request.personCode().trim()).param("name", request.displayName().trim())
                        .param("valid_from", utc(request.validFrom())).update();
                jdbc.sql("""
                        insert into app_user(
                          tenant_id, user_id, person_id, external_subject, display_name, status)
                        values (:tenant, :user, :person, :subject, :name, 'ACTIVE')
                        """).param("tenant", identity.tenantId()).param("user", request.userId())
                        .param("person", request.personId()).param("subject", request.externalSubject().trim())
                        .param("name", request.displayName().trim()).update();
                jdbc.sql("""
                        insert into workforce_person_name_history(
                          tenant_id, person_name_history_id, person_id, version_no, display_name,
                          valid_from, change_reason, changed_by)
                        values (:tenant, :history, :person, 1, :name, :valid_from,
                          'Initial workforce onboarding', :actor)
                        """).param("tenant", identity.tenantId()).param("history", UUID.randomUUID())
                        .param("person", request.personId()).param("name", request.displayName().trim())
                        .param("valid_from", utc(request.validFrom())).param("actor", identity.userId()).update();
                jdbc.sql("""
                        insert into role_assignment(
                          tenant_id, role_assignment_id, user_id, person_id, organization_id,
                          facility_id, role_code, valid_from, valid_until, status)
                        values (:tenant, :role, :user, :person, :organization,
                          :facility, :role_code, :valid_from, :valid_until, 'ACTIVE')
                        """).param("tenant", identity.tenantId()).param("role", request.roleAssignmentId())
                        .param("user", request.userId()).param("person", request.personId())
                        .param("organization", request.organizationId()).param("facility", request.facilityId())
                        .param("role_code", request.roleCode().trim().toUpperCase())
                        .param("valid_from", utc(request.validFrom())).param("valid_until", utc(request.validUntil()))
                        .update();
                jdbc.sql("""
                        update workforce_assignment
                        set department_id = :department, ward_id = :ward,
                          position_code = :position, row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and source_role_assignment_id = :role
                        """).param("department", request.departmentId()).param("ward", request.wardId())
                        .param("position", request.positionCode().trim().toUpperCase())
                        .param("tenant", identity.tenantId()).param("role", request.roleAssignmentId()).update();
                insertCredential(identity.tenantId(), request);
            } catch (DataIntegrityViolationException conflict) {
                throw new OrganizationAdministrationException(
                        "WORKFORCE_IDENTITY_CONFLICT", 409,
                        "The person, account, role, credential or organization scope conflicts with current data");
            }
            appendEvidence(identity, "WORKFORCE_PERSON_ONBOARDED", request.personId(), 1);
            complete(identity, "WORKFORCE_ONBOARD", idempotencyKey, request.personId());
            return find(identity, request.personId(), request.userId(), request.roleAssignmentId());
        });
    }

    WorkforceIdentityWire disableAccount(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID userId,
            AccountDeactivateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedRowVersion() < 1 || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 1000) {
            throw invalid("Expected account row version and reason are required");
        }
        return transactions.execute(status -> {
            begin(identity, "WORKFORCE_ACCOUNT_DISABLE", idempotencyKey,
                    sha256(userId + "|" + request.expectedRowVersion() + "|" + request.reason().trim()));
            int updated = jdbc.sql("""
                    update app_user set status = 'DISABLED', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and user_id = :user and status <> 'DISABLED'
                      and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("user", userId)
                    .param("expected", request.expectedRowVersion()).update();
            if (updated != 1) conflict("The account changed or is already disabled");
            IdentityKey key = identityKey(identity.tenantId(), userId, null);
            appendEvidence(identity, "WORKFORCE_ACCOUNT_DISABLED", key.personId(), request.expectedRowVersion() + 1);
            complete(identity, "WORKFORCE_ACCOUNT_DISABLE", idempotencyKey, userId);
            return find(identity, key.personId(), userId, key.roleId());
        });
    }

    WorkforceIdentityWire endRole(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID roleAssignmentId,
            RoleEndRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedRowVersion() < 1 || request.effectiveUntil() == null
                || request.reason() == null || request.reason().isBlank()
                || request.effectiveUntil().isAfter(Instant.now().plusSeconds(300))) {
            throw invalid("Expected role row version, end time and reason are required");
        }
        return transactions.execute(status -> {
            begin(identity, "WORKFORCE_ROLE_END", idempotencyKey,
                    sha256(roleAssignmentId + "|" + request.expectedRowVersion() + "|"
                            + request.effectiveUntil() + "|" + request.reason().trim()));
            IdentityKey key = identityKey(identity.tenantId(), null, roleAssignmentId);
            int updated = jdbc.sql("""
                    update role_assignment
                    set status = 'EXPIRED', valid_until = :until,
                      row_version = row_version + 1
                    where tenant_id = :tenant and role_assignment_id = :role
                      and status = 'ACTIVE' and row_version = :expected and valid_from < :until
                    """).param("until", utc(request.effectiveUntil())).param("tenant", identity.tenantId())
                    .param("role", roleAssignmentId).param("expected", request.expectedRowVersion()).update();
            if (updated != 1) conflict("The role assignment changed or cannot end at the requested time");
            appendEvidence(identity, "WORKFORCE_ROLE_ENDED", key.personId(), request.expectedRowVersion() + 1);
            complete(identity, "WORKFORCE_ROLE_END", idempotencyKey, roleAssignmentId);
            return find(identity, key.personId(), key.userId(), roleAssignmentId);
        });
    }

    WorkforceIdentityWire assignRole(
            ClinicalIdentity identity,
            String idempotencyKey,
            RoleAssignmentCreateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.roleAssignmentId() == null || request.userId() == null
                || request.personId() == null || request.organizationId() == null || request.facilityId() == null
                || request.roleCode() == null || request.roleCode().isBlank()
                || request.positionCode() == null || request.positionCode().isBlank()
                || request.validFrom() == null
                || (request.validUntil() != null && !request.validUntil().isAfter(request.validFrom()))
                || (request.wardId() != null && request.departmentId() == null)) {
            throw invalid("Complete account, person, role, organization scope, position and validity are required");
        }
        return transactions.execute(status -> {
            begin(identity, "WORKFORCE_ROLE_ASSIGN", idempotencyKey, sha256(request.toString()));
            requireActiveScope(identity.tenantId(), new WorkforceOnboardingRequest(
                    request.personId(), "EXISTING", "EXISTING", request.userId(), "EXISTING",
                    request.roleAssignmentId(), request.roleCode(), request.positionCode(),
                    request.organizationId(), request.facilityId(), request.departmentId(), request.wardId(),
                    request.validFrom(), request.validUntil(), null, null, null, null, Map.of()));
            long account = jdbc.sql("""
                    select count(*) from app_user where tenant_id = :tenant and user_id = :user
                      and person_id = :person and status = 'ACTIVE'
                    """).param("tenant", identity.tenantId()).param("user", request.userId())
                    .param("person", request.personId()).query(Long.class).single();
            if (account != 1) throw new OrganizationAdministrationException(
                    "WORKFORCE_ACCOUNT_NOT_ACTIVE", 409, "The person account is not active");
            try {
                jdbc.sql("""
                        insert into role_assignment(
                          tenant_id, role_assignment_id, user_id, person_id, organization_id,
                          facility_id, role_code, valid_from, valid_until, status)
                        values (:tenant, :role, :user, :person, :organization, :facility,
                          :role_code, :valid_from, :valid_until, 'ACTIVE')
                        """).param("tenant", identity.tenantId()).param("role", request.roleAssignmentId())
                        .param("user", request.userId()).param("person", request.personId())
                        .param("organization", request.organizationId()).param("facility", request.facilityId())
                        .param("role_code", request.roleCode().trim().toUpperCase())
                        .param("valid_from", utc(request.validFrom())).param("valid_until", utc(request.validUntil()))
                        .update();
                jdbc.sql("""
                        update workforce_assignment set department_id = :department, ward_id = :ward,
                          position_code = :position, row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and source_role_assignment_id = :role
                        """).param("department", request.departmentId()).param("ward", request.wardId())
                        .param("position", request.positionCode().trim().toUpperCase())
                        .param("tenant", identity.tenantId()).param("role", request.roleAssignmentId()).update();
            } catch (DataIntegrityViolationException conflict) {
                throw new OrganizationAdministrationException(
                        "WORKFORCE_ROLE_CONFLICT", 409, "The role assignment conflicts with current identity or scope data");
            }
            appendEvidence(identity, "WORKFORCE_ROLE_ASSIGNED", request.personId(), 1);
            complete(identity, "WORKFORCE_ROLE_ASSIGN", idempotencyKey, request.roleAssignmentId());
            return find(identity, request.personId(), request.userId(), request.roleAssignmentId());
        });
    }

    private void insertCredential(UUID tenantId, WorkforceOnboardingRequest request) {
        if (request.credentialId() == null) return;
        jdbc.sql("""
                insert into practitioner_credential(
                  tenant_id, credential_id, person_id, credential_type, registration_number,
                  issuing_authority, practice_scope, status, valid_from, valid_until)
                values (:tenant, :credential, :person, :type, :number, :authority,
                  cast(:scope as jsonb), 'ACTIVE', :valid_from, :valid_until)
                """).param("tenant", tenantId).param("credential", request.credentialId())
                .param("person", request.personId()).param("type", request.credentialType())
                .param("number", request.registrationNumber().trim())
                .param("authority", request.issuingAuthority().trim())
                .param("scope", json(request.practiceScope()))
                .param("valid_from", utc(request.validFrom())).param("valid_until", utc(request.validUntil()))
                .update();
    }

    private void requireActiveScope(UUID tenantId, WorkforceOnboardingRequest request) {
        long scope = jdbc.sql("""
                select count(*)
                from organization organization
                join facility facility on facility.tenant_id = organization.tenant_id
                  and facility.organization_id = organization.organization_id
                left join clinical_department department on department.tenant_id = facility.tenant_id
                  and department.facility_id = facility.facility_id and department.department_id = :department
                left join clinical_ward ward on ward.tenant_id = facility.tenant_id
                  and ward.facility_id = facility.facility_id and ward.department_id = department.department_id
                  and ward.ward_id = :ward
                where organization.tenant_id = :tenant and organization.organization_id = :organization
                  and facility.facility_id = :facility
                  and organization.status = 'ACTIVE' and organization.effective_from <= now()
                  and (organization.effective_until is null or organization.effective_until > now())
                  and facility.status = 'ACTIVE' and facility.effective_from <= now()
                  and (facility.effective_until is null or facility.effective_until > now())
                  and (:department is null or (department.status = 'ACTIVE' and department.effective_from <= now()
                    and (department.effective_until is null or department.effective_until > now())))
                  and (:ward is null or (ward.status = 'ACTIVE' and ward.effective_from <= now()
                    and (ward.effective_until is null or ward.effective_until > now())))
                """).param("department", request.departmentId()).param("ward", request.wardId())
                .param("tenant", tenantId).param("organization", request.organizationId())
                .param("facility", request.facilityId()).query(Long.class).single();
        if (scope != 1) {
            throw new OrganizationAdministrationException(
                    "WORKFORCE_SCOPE_INVALID", 409, "The workforce organization scope is not currently active");
        }
    }

    private WorkforceIdentityWire find(ClinicalIdentity identity, UUID personId, UUID userId, UUID roleId) {
        return list(identity).stream()
                .filter(item -> item.personId().equals(personId)
                        && (userId == null || userId.equals(item.userId()))
                        && (roleId == null || roleId.equals(item.roleAssignmentId())))
                .findFirst().orElseThrow(() -> new OrganizationAdministrationException(
                        "WORKFORCE_IDENTITY_NOT_FOUND", 404, "The workforce identity was not found"));
    }

    private IdentityKey identityKey(UUID tenantId, UUID userId, UUID roleId) {
        String predicate = roleId == null ? "account.user_id = :id" : "role.role_assignment_id = :id";
        String sql = """
                select account.person_id, account.user_id, role.role_assignment_id
                from app_user account
                left join role_assignment role on role.tenant_id = account.tenant_id
                  and role.user_id = account.user_id and role.person_id = account.person_id
                where account.tenant_id = :tenant and
                """ + predicate + " order by role.role_assignment_id limit 1";
        return jdbc.sql(sql)
                .param("tenant", tenantId).param("id", roleId == null ? userId : roleId)
                .query((rs, row) -> new IdentityKey(
                        rs.getObject("person_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("role_assignment_id", UUID.class)))
                .optional().orElseThrow(() -> new OrganizationAdministrationException(
                        "WORKFORCE_IDENTITY_NOT_FOUND", 404, "The workforce identity was not found"));
    }

    private void requireAdministrator(ClinicalIdentity identity) {
        if (identity.roleAssignmentIds().isEmpty()) deny();
        long allowed = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user and role_assignment_id in (:roles)
                  and role_code in (:admin_roles) and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", identity.roleAssignmentIds()).param("admin_roles", ADMIN_ROLES)
                .query(Long.class).single();
        if (allowed == 0) deny();
    }

    private static void validateOnboarding(WorkforceOnboardingRequest request) {
        if (request == null || request.personId() == null || request.personCode() == null
                || request.personCode().isBlank() || request.displayName() == null || request.displayName().isBlank()
                || request.userId() == null || request.externalSubject() == null || request.externalSubject().isBlank()
                || request.roleAssignmentId() == null || request.roleCode() == null || request.roleCode().isBlank()
                || request.positionCode() == null || request.positionCode().isBlank()
                || request.organizationId() == null || request.facilityId() == null || request.validFrom() == null
                || (request.validUntil() != null && !request.validUntil().isAfter(request.validFrom()))
                || (request.wardId() != null && request.departmentId() == null)
                || (request.credentialId() != null && (request.credentialType() == null
                    || request.registrationNumber() == null || request.registrationNumber().isBlank()
                    || request.issuingAuthority() == null || request.issuingAuthority().isBlank()))) {
            throw invalid("Complete person, account, role, organization scope and credential fields are required");
        }
    }

    private void begin(ClinicalIdentity identity, String scope, String key, String hash) {
        if (key == null || key.isBlank()) throw invalid("Idempotency-Key is required");
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", hash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) conflict("This workforce command was already submitted");
    }

    private void complete(ClinicalIdentity identity, String scope, String key, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, String action, UUID personId, long version) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID audit = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String hash = sha256(identity.tenantId() + "|" + audit + "|" + action + "|" + personId + "|"
                + trace + "|" + (previous == null ? "GENESIS" : previous));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'WORKFORCE_PERSON',
                  :person, :trace, :previous, :hash)
                """).param("tenant", identity.tenantId()).param("audit", audit)
                .param("actor", identity.userId()).param("action", action).param("person", personId)
                .param("trace", trace).param("previous", previous).param("hash", hash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'WORKFORCE_PERSON', :person, :version,
                  :event_type, 1, jsonb_build_object('person_id', :person))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("person", personId).param("version", version).param("event_type", action).update();
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception invalid) {
            throw invalid("Credential practice scope must be valid JSON");
        }
    }

    private static void deny() {
        throw new OrganizationAdministrationException(
                "ADMIN_SCOPE_DENIED", 403, "The requested administration scope is not permitted");
    }

    private static OrganizationAdministrationException invalid(String message) {
        return new OrganizationAdministrationException("WORKFORCE_REQUEST_INVALID", 400, message);
    }

    private static void conflict(String message) {
        throw new OrganizationAdministrationException("WORKFORCE_VERSION_CONFLICT", 409, message);
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record WorkforceOnboardingRequest(
            UUID personId,
            String personCode,
            String displayName,
            UUID userId,
            String externalSubject,
            UUID roleAssignmentId,
            String roleCode,
            String positionCode,
            UUID organizationId,
            UUID facilityId,
            UUID departmentId,
            UUID wardId,
            Instant validFrom,
            Instant validUntil,
            UUID credentialId,
            String credentialType,
            String registrationNumber,
            String issuingAuthority,
            Map<String, Object> practiceScope) {}

    record AccountDeactivateRequest(long expectedRowVersion, String reason) {}

    record RoleEndRequest(long expectedRowVersion, Instant effectiveUntil, String reason) {}

    record RoleAssignmentCreateRequest(
            UUID roleAssignmentId,
            UUID userId,
            UUID personId,
            String roleCode,
            String positionCode,
            UUID organizationId,
            UUID facilityId,
            UUID departmentId,
            UUID wardId,
            Instant validFrom,
            Instant validUntil) {}

    record WorkforceIdentityWire(
            UUID personId,
            String personCode,
            String personDisplayName,
            String personStatus,
            long personRowVersion,
            UUID userId,
            String externalSubject,
            String accountStatus,
            long accountRowVersion,
            UUID roleAssignmentId,
            String roleCode,
            String roleStatus,
            Instant roleValidFrom,
            Instant roleValidUntil,
            long roleRowVersion,
            UUID organizationId,
            UUID facilityId,
            UUID departmentId,
            UUID wardId,
            String positionCode,
            long activeCredentialCount) {}

    private record IdentityKey(UUID personId, UUID userId, UUID roleId) {}
}
