package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DevelopmentAcceptanceSettingsUpdateRequestWire;
import org.haoemr.contracts.DevelopmentAcceptanceSettingsWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DevelopmentAcceptanceSettingsServiceTest {

    @Autowired
    private DevelopmentAcceptanceSettingsService settings;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void givenSystemAdministrator_whenUpdating_thenSettingIsVersionedAndAudited() {
        ClinicalIdentity administrator = identityFor("SYSTEM_ADMIN");
        DevelopmentAcceptanceSettingsWire before = settings.get(administrator);

        DevelopmentAcceptanceSettingsWire updated = settings.update(administrator,
                new DevelopmentAcceptanceSettingsUpdateRequestWire(
                        !before.inpatientRoleSimulationEnabled(), before.rowVersion()));

        assertThat(updated.inpatientRoleSimulationEnabled()).isEqualTo(!before.inpatientRoleSimulationEnabled());
        assertThat(updated.rowVersion()).isEqualTo(before.rowVersion() + 1);
        assertThat(updated.updatedBy()).isEqualTo(administrator.userId());
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = :tenant
                  and action_code = 'DEVELOPMENT_ACCEPTANCE_SETTINGS_UPDATED'
                """).param("tenant", administrator.tenantId()).query(Long.class).single()).isGreaterThan(0);

        assertThatThrownBy(() -> settings.update(administrator,
                new DevelopmentAcceptanceSettingsUpdateRequestWire(false, before.rowVersion())))
                .isInstanceOf(DevelopmentAcceptanceSettingsException.class)
                .satisfies(error -> assertThat(((DevelopmentAcceptanceSettingsException) error).code())
                        .isEqualTo("DEVELOPMENT_ACCEPTANCE_VERSION_CONFLICT"));
    }

    @Test
    void givenNonSystemAdministrator_whenReading_thenAccessIsDenied() {
        ClinicalIdentity clinician = identityFor("CLINICIAN");
        assertThatThrownBy(() -> settings.get(clinician))
                .isInstanceOf(DevelopmentAcceptanceSettingsException.class)
                .satisfies(error -> assertThat(((DevelopmentAcceptanceSettingsException) error).code())
                        .isEqualTo("DEVELOPMENT_ACCEPTANCE_ADMIN_REQUIRED"));
    }

    private ClinicalIdentity identityFor(String roleCode) {
        return jdbc.sql("""
                select tenant_id, user_id, role_assignment_id from role_assignment
                where role_code = :role and status = 'ACTIVE'
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                order by tenant_id, user_id, role_assignment_id limit 1
                """).param("role", roleCode).query((rs, row) -> new ClinicalIdentity(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class),
                        List.of(rs.getObject("role_assignment_id", UUID.class))))
                .single();
    }
}
