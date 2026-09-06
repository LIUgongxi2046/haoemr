package org.haoemr.administration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class WorkgroupAdministrationService {
    private static final Set<String> ADMIN_ROLES = Set.of("SYSTEM_ADMIN", "CLINICAL_ADMIN", "AUTHORIZATION_ADMIN");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    WorkgroupAdministrationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    List<WorkgroupWire> list(ClinicalIdentity identity) {
        requireAdministrator(identity);
        return jdbc.sql("""
                select group_item.workgroup_id, group_item.workgroup_code, group_item.display_name,
                  group_item.purpose, group_item.organization_id, group_item.facility_id,
                  group_item.department_id, group_item.owner_person_id, owner.display_name owner_name,
                  group_item.status, group_item.effective_from, group_item.effective_until,
                  group_item.row_version, group_item.created_at, group_item.updated_at
                from administration_workgroup group_item
                join workforce_person owner on owner.tenant_id = group_item.tenant_id
                  and owner.person_id = group_item.owner_person_id
                where group_item.tenant_id = :tenant
                order by group_item.status, group_item.display_name, group_item.workgroup_id
                """).param("tenant", identity.tenantId()).query((rs, row) -> {
                    UUID workgroupId = rs.getObject("workgroup_id", UUID.class);
                    return new WorkgroupWire(workgroupId, rs.getString("workgroup_code"),
                            rs.getString("display_name"), rs.getString("purpose"),
                            rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                            rs.getObject("department_id", UUID.class), rs.getObject("owner_person_id", UUID.class),
                            rs.getString("owner_name"), rs.getString("status"),
                            instant(rs.getObject("effective_from", OffsetDateTime.class)),
                            instant(rs.getObject("effective_until", OffsetDateTime.class)),
                            rs.getLong("row_version"), members(identity.tenantId(), workgroupId),
                            instant(rs.getObject("created_at", OffsetDateTime.class)),
                            instant(rs.getObject("updated_at", OffsetDateTime.class)));
                }).list();
    }

    WorkgroupWire create(ClinicalIdentity identity, String idempotencyKey, WorkgroupCreateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.workgroupId() == null || blank(request.workgroupCode())
                || blank(request.displayName()) || blank(request.purpose()) || request.organizationId() == null
                || request.ownerPersonId() == null || request.effectiveFrom() == null
                || (request.effectiveUntil() != null && !request.effectiveUntil().isAfter(request.effectiveFrom()))) {
            invalid("工作组编码、名称、用途、组织范围、责任人与有效期必须完整");
        }
        return transactions.execute(status -> {
            begin(identity, "ADMIN_WORKGROUP_CREATE", idempotencyKey, sha256(request.toString()));
            requireScopeAndPerson(identity.tenantId(), request.organizationId(), request.facilityId(),
                    request.departmentId(), request.ownerPersonId());
            try {
                jdbc.sql("""
                        insert into administration_workgroup(
                          tenant_id, workgroup_id, workgroup_code, display_name, purpose,
                          organization_id, facility_id, department_id, owner_person_id,
                          status, effective_from, effective_until, created_by)
                        values (:tenant, :workgroup, :code, :name, :purpose, :organization,
                          :facility, :department, :owner, 'ACTIVE', :effective_from, :effective_until, :actor)
                        """).param("tenant", identity.tenantId()).param("workgroup", request.workgroupId())
                        .param("code", request.workgroupCode().trim().toUpperCase())
                        .param("name", request.displayName().trim()).param("purpose", request.purpose().trim())
                        .param("organization", request.organizationId()).param("facility", request.facilityId())
                        .param("department", request.departmentId()).param("owner", request.ownerPersonId())
                        .param("effective_from", utc(request.effectiveFrom()))
                        .param("effective_until", utc(request.effectiveUntil())).param("actor", identity.userId()).update();
            } catch (DataIntegrityViolationException conflict) {
                throw new AdministrationRuntimeException("WORKGROUP_CONFLICT", 409, "工作组编码或组织责任人数据冲突");
            }
            appendAudit(identity, request.workgroupId(), "ADMIN_WORKGROUP_CREATED");
            complete(identity, "ADMIN_WORKGROUP_CREATE", idempotencyKey, request.workgroupId(), 201);
            return find(identity, request.workgroupId());
        });
    }

    WorkgroupWire addMember(ClinicalIdentity identity, UUID workgroupId, String idempotencyKey,
            WorkgroupMemberCreateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.memberId() == null || request.personId() == null
                || blank(request.roleCode()) || blank(request.responsibility()) || request.effectiveFrom() == null
                || (request.effectiveUntil() != null && !request.effectiveUntil().isAfter(request.effectiveFrom()))) {
            invalid("成员、组内角色、职责与有效期必须完整");
        }
        return transactions.execute(status -> {
            begin(identity, "ADMIN_WORKGROUP_MEMBER_ADD", idempotencyKey, sha256(workgroupId + "|" + request));
            WorkgroupWire group = find(identity, workgroupId);
            if (!"ACTIVE".equals(group.status())) conflict("只能向有效工作组添加成员");
            requireScopeAndPerson(identity.tenantId(), group.organizationId(), group.facilityId(),
                    group.departmentId(), request.personId());
            try {
                jdbc.sql("""
                        insert into administration_workgroup_member(
                          tenant_id, member_id, workgroup_id, person_id, role_code, responsibility,
                          status, effective_from, effective_until, created_by)
                        values (:tenant, :member, :workgroup, :person, :role, :responsibility,
                          'ACTIVE', :effective_from, :effective_until, :actor)
                        """).param("tenant", identity.tenantId()).param("member", request.memberId())
                        .param("workgroup", workgroupId).param("person", request.personId())
                        .param("role", request.roleCode().trim().toUpperCase())
                        .param("responsibility", request.responsibility().trim())
                        .param("effective_from", utc(request.effectiveFrom()))
                        .param("effective_until", utc(request.effectiveUntil())).param("actor", identity.userId()).update();
            } catch (DataIntegrityViolationException conflict) {
                throw new AdministrationRuntimeException("WORKGROUP_MEMBER_CONFLICT", 409, "成员已承担相同组内角色或人员范围无效");
            }
            jdbc.sql("update administration_workgroup set row_version = row_version + 1, updated_at = now() where tenant_id = :tenant and workgroup_id = :workgroup")
                    .param("tenant", identity.tenantId()).param("workgroup", workgroupId).update();
            appendAudit(identity, request.memberId(), "ADMIN_WORKGROUP_MEMBER_ADDED");
            complete(identity, "ADMIN_WORKGROUP_MEMBER_ADD", idempotencyKey, request.memberId(), 201);
            return find(identity, workgroupId);
        });
    }

    WorkgroupWire endMember(ClinicalIdentity identity, UUID workgroupId, UUID memberId,
            String idempotencyKey, EndRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedVersion() < 1 || blank(request.reason())) invalid("必须提供当前版本和结束原因");
        return transactions.execute(status -> {
            begin(identity, "ADMIN_WORKGROUP_MEMBER_END", idempotencyKey, sha256(memberId + "|" + request));
            int updated = jdbc.sql("""
                    update administration_workgroup_member set status = 'INACTIVE',
                      effective_until = coalesce(effective_until, greatest(now(), effective_from + interval '1 second')),
                      responsibility = responsibility || '；结束原因：' || :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and workgroup_id = :workgroup and member_id = :member
                      and status = 'ACTIVE' and row_version = :version
                    """).param("reason", request.reason().trim()).param("tenant", identity.tenantId())
                    .param("workgroup", workgroupId).param("member", memberId)
                    .param("version", request.expectedVersion()).update();
            if (updated != 1) conflict("成员已结束或数据库版本发生变化");
            jdbc.sql("update administration_workgroup set row_version = row_version + 1, updated_at = now() where tenant_id = :tenant and workgroup_id = :workgroup")
                    .param("tenant", identity.tenantId()).param("workgroup", workgroupId).update();
            appendAudit(identity, memberId, "ADMIN_WORKGROUP_MEMBER_ENDED");
            complete(identity, "ADMIN_WORKGROUP_MEMBER_END", idempotencyKey, memberId, 200);
            return find(identity, workgroupId);
        });
    }

    WorkgroupWire deactivate(ClinicalIdentity identity, UUID workgroupId, String idempotencyKey, EndRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedVersion() < 1 || blank(request.reason())) invalid("必须提供当前版本和停用原因");
        return transactions.execute(status -> {
            begin(identity, "ADMIN_WORKGROUP_DEACTIVATE", idempotencyKey, sha256(workgroupId + "|" + request));
            long members = jdbc.sql("select count(*) from administration_workgroup_member where tenant_id = :tenant and workgroup_id = :workgroup and status = 'ACTIVE'")
                    .param("tenant", identity.tenantId()).param("workgroup", workgroupId).query(Long.class).single();
            if (members > 0) conflict("请先结束全部有效成员后再停用工作组");
            int updated = jdbc.sql("""
                    update administration_workgroup set status = 'INACTIVE',
                      effective_until = coalesce(effective_until, greatest(now(), effective_from + interval '1 second')),
                      purpose = purpose || '；停用原因：' || :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and workgroup_id = :workgroup and status = 'ACTIVE'
                      and row_version = :version
                    """).param("reason", request.reason().trim()).param("tenant", identity.tenantId())
                    .param("workgroup", workgroupId).param("version", request.expectedVersion()).update();
            if (updated != 1) conflict("工作组已停用或数据库版本发生变化");
            appendAudit(identity, workgroupId, "ADMIN_WORKGROUP_DEACTIVATED");
            complete(identity, "ADMIN_WORKGROUP_DEACTIVATE", idempotencyKey, workgroupId, 200);
            return find(identity, workgroupId);
        });
    }

    private List<WorkgroupMemberWire> members(UUID tenantId, UUID workgroupId) {
        return jdbc.sql("""
                select member.member_id, member.person_id, person.display_name person_name,
                  member.role_code, member.responsibility, member.status, member.effective_from,
                  member.effective_until, member.row_version
                from administration_workgroup_member member
                join workforce_person person on person.tenant_id = member.tenant_id and person.person_id = member.person_id
                where member.tenant_id = :tenant and member.workgroup_id = :workgroup
                order by member.status, person.display_name, member.member_id
                """).param("tenant", tenantId).param("workgroup", workgroupId)
                .query((rs, row) -> new WorkgroupMemberWire(
                        rs.getObject("member_id", UUID.class), rs.getObject("person_id", UUID.class),
                        rs.getString("person_name"), rs.getString("role_code"), rs.getString("responsibility"),
                        rs.getString("status"), instant(rs.getObject("effective_from", OffsetDateTime.class)),
                        instant(rs.getObject("effective_until", OffsetDateTime.class)), rs.getLong("row_version")))
                .list();
    }

    private WorkgroupWire find(ClinicalIdentity identity, UUID workgroupId) {
        return list(identity).stream().filter(item -> item.workgroupId().equals(workgroupId)).findFirst()
                .orElseThrow(() -> new AdministrationRuntimeException("WORKGROUP_NOT_FOUND", 404, "工作组不存在"));
    }

    private void requireScopeAndPerson(UUID tenantId, UUID organizationId, UUID facilityId,
            UUID departmentId, UUID personId) {
        long count = jdbc.sql("""
                select count(*) from workforce_person person
                join organization organization on organization.tenant_id = person.tenant_id
                  and organization.organization_id = :organization and organization.status = 'ACTIVE'
                left join facility facility on facility.tenant_id = organization.tenant_id
                  and facility.organization_id = organization.organization_id
                  and facility.facility_id = cast(:facility as uuid)
                left join clinical_department department on department.tenant_id = organization.tenant_id
                  and department.facility_id = facility.facility_id
                  and department.department_id = cast(:department as uuid)
                where person.tenant_id = :tenant and person.person_id = :person and person.status = 'ACTIVE'
                  and (cast(:facility as uuid) is null or facility.status = 'ACTIVE')
                  and (cast(:department as uuid) is null or department.status = 'ACTIVE')
                """).param("organization", organizationId).param("facility", facilityId)
                .param("department", departmentId).param("tenant", tenantId).param("person", personId)
                .query(Long.class).single();
        if (count != 1) conflict("工作组组织范围或人员无效");
    }

    private void requireAdministrator(ClinicalIdentity identity) {
        if (identity.roleAssignmentIds().isEmpty()) denied();
        long count = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:roles) and role_code in (:codes) and status = 'ACTIVE'
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", identity.roleAssignmentIds()).param("codes", ADMIN_ROLES).query(Long.class).single();
        if (count == 0) denied();
    }

    private void begin(ClinicalIdentity identity, String scope, String key, String hash) {
        if (blank(key)) invalid("Idempotency-Key 不能为空");
        int inserted = jdbc.sql("""
                insert into idempotency_record(tenant_id, command_scope, idempotency_key,
                  request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", hash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) conflict("该工作组操作已提交，请勿重复执行");
    }

    private void complete(ClinicalIdentity identity, String scope, String key, UUID resourceId, int status) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", status).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendAudit(ClinicalIdentity identity, UUID resourceId, String action) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID audit = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String hash = sha256(identity.tenantId() + "|" + audit + "|" + action + "|" + resourceId + "|"
                + trace + "|" + (previous == null ? "GENESIS" : previous));
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id,
                  action_code, resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ADMIN_WORKGROUP',
                  :resource, :trace, :previous, :hash)
                """).param("tenant", identity.tenantId()).param("audit", audit)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("trace", trace).param("previous", previous).param("hash", hash).update();
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static OffsetDateTime utc(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static void invalid(String message) { throw new AdministrationRuntimeException("WORKGROUP_REQUEST_INVALID", 400, message); }
    private static void conflict(String message) { throw new AdministrationRuntimeException("WORKGROUP_VERSION_CONFLICT", 409, message); }
    private static void denied() { throw new AdministrationRuntimeException("ADMIN_SCOPE_DENIED", 403, "没有工作组管理权限"); }

    record WorkgroupCreateRequest(UUID workgroupId, String workgroupCode, String displayName, String purpose,
            UUID organizationId, UUID facilityId, UUID departmentId, UUID ownerPersonId,
            Instant effectiveFrom, Instant effectiveUntil) {}
    record WorkgroupMemberCreateRequest(UUID memberId, UUID personId, String roleCode,
            String responsibility, Instant effectiveFrom, Instant effectiveUntil) {}
    record EndRequest(long expectedVersion, String reason) {}
    record WorkgroupMemberWire(UUID memberId, UUID personId, String personName, String roleCode,
            String responsibility, String status, Instant effectiveFrom, Instant effectiveUntil, long rowVersion) {}
    record WorkgroupWire(UUID workgroupId, String workgroupCode, String displayName, String purpose,
            UUID organizationId, UUID facilityId, UUID departmentId, UUID ownerPersonId, String ownerName,
            String status, Instant effectiveFrom, Instant effectiveUntil, long rowVersion,
            List<WorkgroupMemberWire> members, Instant createdAt, Instant updatedAt) {}
}
