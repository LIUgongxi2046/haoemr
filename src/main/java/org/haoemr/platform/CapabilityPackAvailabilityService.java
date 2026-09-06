package org.haoemr.platform;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Resolves released compositions that back modeled specialty support. */
@Service
public final class CapabilityPackAvailabilityService {

    private static final Map<String, String> SPECIALTY_MODULE_BY_SCOPE = Map.of(
            "CARDIOLOGY", "SPECIALTY_CARDIOLOGY",
            "PEDIATRICS", "SPECIALTY_PEDIATRICS",
            "MENTAL_HEALTH", "SPECIALTY_MENTAL_HEALTH",
            "ONCOLOGY", "SPECIALTY_ONCOLOGY",
            "OBSTETRICS", "SPECIALTY_OBSTETRICS");

    private final JdbcClient jdbc;

    public CapabilityPackAvailabilityService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A value is returned only while the capability pack, its active release,
     * and its approved active composition all still authorize the module.
     */
    public Optional<Availability> findActiveForSpecialtyScope(UUID tenantId, String scopeCode) {
        String module = SPECIALTY_MODULE_BY_SCOPE.get(scopeCode);
        if (module == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select composition.config_id, composition.row_version,
                  pack.capability_pack_id, release.release_id
                from config_item composition
                join capability_pack pack on pack.tenant_id = composition.tenant_id
                  and composition.payload->>'capability_pack_id' = pack.capability_pack_id::text
                join capability_pack_release release on release.tenant_id = pack.tenant_id
                  and release.capability_pack_id = pack.capability_pack_id
                where composition.tenant_id = :tenant
                  and composition.config_type = 'CAPABILITY_PACK_COMPOSITION'
                  and composition.status = 'ACTIVE'
                  and composition.validation_state = 'VALID'
                  and composition.approval_state = 'APPROVED'
                  and pack.status = 'ACTIVE'
                  and release.lifecycle_status = 'ACTIVE'
                  and jsonb_exists(composition.payload->'selected_modules', :module)
                order by composition.published_at desc, composition.config_id
                limit 1
                for share of composition, pack, release
                """).param("tenant", tenantId).param("module", module)
                .query((rs, row) -> new Availability(
                        module,
                        rs.getObject("capability_pack_id", UUID.class),
                        rs.getObject("release_id", UUID.class),
                        rs.getObject("config_id", UUID.class),
                        rs.getLong("row_version")))
                .optional();
    }

    public Optional<String> requiredModuleForSpecialtyScope(String scopeCode) {
        return Optional.ofNullable(SPECIALTY_MODULE_BY_SCOPE.get(scopeCode));
    }

    public record Availability(
            String moduleCode,
            UUID capabilityPackId,
            UUID releaseId,
            UUID compositionConfigId,
            long compositionRowVersion) { }
}
