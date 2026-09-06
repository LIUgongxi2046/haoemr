package org.haoemr.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("dev-synthetic")
@Transactional
final class OrganizationWorkforceSchemaTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");

    @Autowired
    private JdbcClient jdbc;

    @Test
    void givenExistingAccounts_whenV23IsApplied_thenPeopleAndWorkforceAssignmentsAreSeparatedAndBound() {
        MapRow binding = jdbc.sql("""
                select account.user_id, account.person_id, person.display_name,
                  role.role_assignment_id, workforce.source_role_assignment_id
                from app_user account
                join workforce_person person on person.tenant_id = account.tenant_id
                  and person.person_id = account.person_id
                join role_assignment role on role.tenant_id = account.tenant_id
                  and role.user_id = account.user_id and role.person_id = account.person_id
                join workforce_assignment workforce on workforce.tenant_id = role.tenant_id
                  and workforce.source_role_assignment_id = role.role_assignment_id
                where account.tenant_id = :tenant and account.user_id = :user
                  and role.role_assignment_id = :role
                """).param("tenant", TENANT).param("user", USER).param("role", ROLE)
                .query((rs, row) -> new MapRow(
                        rs.getObject("user_id", UUID.class), rs.getObject("person_id", UUID.class),
                        rs.getString("display_name"), rs.getObject("role_assignment_id", UUID.class),
                        rs.getObject("source_role_assignment_id", UUID.class)))
                .single();

        assertThat(binding.userId()).isEqualTo(USER);
        assertThat(binding.personId()).isEqualTo(USER);
        assertThat(binding.displayName()).isNotBlank();
        assertThat(binding.sourceRoleId()).isEqualTo(binding.roleId());
    }

    @Test
    void givenOrganizationDescendants_whenCreatingACycle_thenDatabaseRejectsIt() {
        UUID parentOrg = UUID.randomUUID();
        UUID childOrg = UUID.randomUUID();
        jdbc.sql("insert into organization(tenant_id, organization_id, organization_code, display_name, status) values (:tenant, :id, :code, 'Parent', 'ACTIVE')")
                .param("tenant", TENANT).param("id", parentOrg).param("code", "ORG-" + parentOrg).update();
        jdbc.sql("insert into organization(tenant_id, organization_id, organization_code, display_name, status, parent_organization_id) values (:tenant, :id, :code, 'Child', 'ACTIVE', :parent)")
                .param("tenant", TENANT).param("id", childOrg).param("code", "ORG-" + childOrg)
                .param("parent", parentOrg).update();

        assertThatThrownBy(() -> jdbc.sql("update organization set parent_organization_id = :child where tenant_id = :tenant and organization_id = :parent")
                .param("child", childOrg).param("tenant", TENANT).param("parent", parentOrg).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void givenDepartmentDescendants_whenCreatingACycle_thenDatabaseRejectsIt() {
        UUID parentDepartment = UUID.randomUUID();
        UUID childDepartment = UUID.randomUUID();
        jdbc.sql("insert into clinical_department(tenant_id, facility_id, department_id, department_code, display_name, status) values (:tenant, :facility, :id, :code, 'Parent department', 'ACTIVE')")
                .param("tenant", TENANT).param("facility", FACILITY).param("id", parentDepartment)
                .param("code", "DEPT-" + parentDepartment).update();
        jdbc.sql("insert into clinical_department(tenant_id, facility_id, department_id, department_code, display_name, status, parent_department_id) values (:tenant, :facility, :id, :code, 'Child department', 'ACTIVE', :parent)")
                .param("tenant", TENANT).param("facility", FACILITY).param("id", childDepartment)
                .param("code", "DEPT-" + childDepartment).param("parent", parentDepartment).update();

        assertThatThrownBy(() -> jdbc.sql("update clinical_department set parent_department_id = :child where tenant_id = :tenant and facility_id = :facility and department_id = :parent")
                .param("child", childDepartment).param("tenant", TENANT).param("facility", FACILITY)
                .param("parent", parentDepartment).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void givenARoleAssignment_whenItIsEnded_thenTheSeparateWorkforceAssignmentIsEndedInTheSameStatement() {
        jdbc.sql("update role_assignment set status = 'EXPIRED', valid_until = now() where tenant_id = :tenant and role_assignment_id = :role")
                .param("tenant", TENANT).param("role", ROLE).update();

        String workforceStatus = jdbc.sql("select status from workforce_assignment where tenant_id = :tenant and source_role_assignment_id = :role")
                .param("tenant", TENANT).param("role", ROLE).query(String.class).single();

        assertThat(workforceStatus).isEqualTo("ENDED");
    }

    @Test
    void givenASignatureSnapshot_whenAccountAndPersonNamesChange_thenHistoricalSignerNameDoesNotChange() {
        UUID patient = jdbc.sql("select patient_id from patient where tenant_id = :tenant order by patient_id limit 1")
                .param("tenant", TENANT).query(UUID.class).single();
        UUID encounter = jdbc.sql("select encounter_id from encounter where tenant_id = :tenant and patient_id = :patient order by encounter_id limit 1")
                .param("tenant", TENANT).param("patient", patient).query(UUID.class).single();
        String originalName = jdbc.sql("select display_name from workforce_person where tenant_id = :tenant and person_id = :person")
                .param("tenant", TENANT).param("person", USER).query(String.class).single();
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID signature = UUID.randomUUID();
        jdbc.sql("insert into clinical_document(tenant_id, document_id, patient_id, encounter_id, document_type_code, template_version_id, status, created_by) values (:tenant, :document, :patient, :encounter, 'WS445.2.OUTPATIENT_RECORD', (select version.template_version_id from clinical_document_template template join clinical_document_template_version version on version.tenant_id = template.tenant_id and version.template_id = template.template_id where template.tenant_id = :tenant and template.document_type_code = 'WS445.2.OUTPATIENT_RECORD' and version.status = 'PUBLISHED' order by version.version_no desc limit 1), 'DRAFT', :user)")
                .param("tenant", TENANT).param("document", document).param("patient", patient)
                .param("encounter", encounter).param("user", USER).update();
        jdbc.sql("insert into clinical_document_version(tenant_id, document_id, document_version_id, version_no, status, sections, content_hash, author_user_id) values (:tenant, :document, :version, 1, 'DRAFT', '{}'::jsonb, :hash, :user)")
                .param("tenant", TENANT).param("document", document).param("version", version)
                .param("hash", "a".repeat(64)).param("user", USER).update();
        jdbc.sql("update clinical_document set current_version_id = :version where tenant_id = :tenant and document_id = :document")
                .param("version", version).param("tenant", TENANT).param("document", document).update();
        jdbc.sql("insert into signature_evidence(tenant_id, signature_id, document_id, document_version_id, signer_user_id, signature_role, signature_status, content_hash, signed_at) values (:tenant, :signature, :document, :version, :user, 'AUTHOR', 'PENDING_CA_EVIDENCE', :hash, now())")
                .param("tenant", TENANT).param("signature", signature).param("document", document)
                .param("version", version).param("user", USER).param("hash", "a".repeat(64)).update();

        jdbc.sql("update app_user set display_name = 'Changed account label' where tenant_id = :tenant and user_id = :user")
                .param("tenant", TENANT).param("user", USER).update();
        jdbc.sql("update workforce_person set display_name = 'Changed person name' where tenant_id = :tenant and person_id = :person")
                .param("tenant", TENANT).param("person", USER).update();

        String snapshot = jdbc.sql("select signer_display_name from signature_evidence where tenant_id = :tenant and signature_id = :signature")
                .param("tenant", TENANT).param("signature", signature).query(String.class).single();
        assertThat(snapshot).isEqualTo(originalName);
    }

    private record MapRow(UUID userId, UUID personId, String displayName, UUID roleId, UUID sourceRoleId) {}
}
