package org.haoemr.development;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DevelopmentAcceptanceSettingsUpdateRequestWire;
import org.haoemr.contracts.DevelopmentAcceptanceSettingsWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DevelopmentAcceptanceSettingsService {
    private static final List<String> SYSTEM_ADMIN_ROLE = List.of("SYSTEM_ADMIN");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DevelopmentAcceptanceSettingsService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DevelopmentAcceptanceSettingsWire get(ClinicalIdentity identity) {
        requireSystemAdministrator(identity);
        return find(identity.tenantId());
    }

    DevelopmentAcceptanceSettingsWire update(
            ClinicalIdentity identity, DevelopmentAcceptanceSettingsUpdateRequestWire command) {
        requireSystemAdministrator(identity);
        if (command == null || command.expectedRowVersion() == null || command.expectedRowVersion() < 0) {
            throw invalid("expected_row_version must be zero or a current non-negative version");
        }
        return transactions.execute(status -> {
            DevelopmentAcceptanceSettingsWire current = find(identity.tenantId());
            if (!current.rowVersion().equals(command.expectedRowVersion())) throw conflict();
            long nextVersion = current.rowVersion() + 1;
            int updated = jdbc.sql("""
                    insert into development_acceptance_setting(
                      tenant_id, inpatient_role_simulation_enabled, updated_by, updated_at, row_version)
                    values (:tenant, :enabled, :actor, now(), :version)
                    on conflict (tenant_id) do update set
                      inpatient_role_simulation_enabled = excluded.inpatient_role_simulation_enabled,
                      updated_by = excluded.updated_by, updated_at = excluded.updated_at,
                      row_version = excluded.row_version
                    where development_acceptance_setting.row_version = :expected
                    """).param("tenant", identity.tenantId())
                    .param("enabled", command.inpatientRoleSimulationEnabled())
                    .param("actor", identity.userId()).param("version", nextVersion)
                    .param("expected", command.expectedRowVersion()).update();
            if (updated != 1) throw conflict();
            DevelopmentAcceptanceSettingsWire result = find(identity.tenantId());
            appendAudit(identity, result);
            return result;
        });
    }

    private DevelopmentAcceptanceSettingsWire find(UUID tenantId) {
        return jdbc.sql("""
                select inpatient_role_simulation_enabled, row_version, updated_by, updated_at
                from development_acceptance_setting where tenant_id = :tenant
                """).param("tenant", tenantId).query((rs, row) -> new DevelopmentAcceptanceSettingsWire(
                        rs.getBoolean("inpatient_role_simulation_enabled"), rs.getLong("row_version"),
                        rs.getObject("updated_by", UUID.class),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional().orElse(new DevelopmentAcceptanceSettingsWire(false, 0L, null, null));
    }

    private void requireSystemAdministrator(ClinicalIdentity identity) {
        long allowed = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id = any(cast(:roles as uuid[])) and role_code in (:role_codes)
                  and status = 'ACTIVE' and valid_from <= now() and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", uuidArray(identity.roleAssignmentIds())).param("role_codes", SYSTEM_ADMIN_ROLE)
                .query(Long.class).single();
        if (allowed == 0) throw denied("Development acceptance settings are limited to system administrators");
    }

    private void appendAudit(ClinicalIdentity identity, DevelopmentAcceptanceSettingsWire setting) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID audit = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String resource = "development-acceptance-settings";
        String hash = sha256(identity.tenantId() + "|" + audit + "|DEVELOPMENT_ACCEPTANCE_SETTINGS_UPDATED|"
                + resource + "|" + trace + "|" + (previous == null ? "GENESIS" : previous));
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, 'DEVELOPMENT_ACCEPTANCE_SETTINGS_UPDATED',
                  'DEVELOPMENT_ACCEPTANCE_SETTINGS', :resource, :trace, :previous, :hash,
                  jsonb_build_object('inpatient_role_simulation_enabled', :enabled, 'row_version', :version))
                """).param("tenant", identity.tenantId()).param("audit", audit).param("actor", identity.userId())
                .param("resource", UUID.nameUUIDFromBytes(resource.getBytes(StandardCharsets.UTF_8)))
                .param("trace", trace).param("previous", previous).param("hash", hash)
                .param("enabled", setting.inpatientRoleSimulationEnabled()).param("version", setting.rowVersion()).update();
    }

    private static DevelopmentAcceptanceSettingsException invalid(String message) {
        return new DevelopmentAcceptanceSettingsException("DEVELOPMENT_ACCEPTANCE_REQUEST_INVALID", 400, message);
    }
    private static DevelopmentAcceptanceSettingsException denied(String message) {
        return new DevelopmentAcceptanceSettingsException("DEVELOPMENT_ACCEPTANCE_ADMIN_REQUIRED", 403, message);
    }
    private static DevelopmentAcceptanceSettingsException conflict() {
        return new DevelopmentAcceptanceSettingsException("DEVELOPMENT_ACCEPTANCE_VERSION_CONFLICT", 409,
                "The development acceptance settings changed; refresh and retry");
    }
    private static String uuidArray(List<UUID> values) {
        return "{" + (values == null ? List.<UUID>of() : values).stream().map(UUID::toString)
                .reduce((left, right) -> left + "," + right).orElse("") + "}";
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
