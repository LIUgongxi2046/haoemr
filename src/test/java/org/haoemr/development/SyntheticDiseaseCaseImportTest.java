package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev-synthetic")
final class SyntheticDiseaseCaseImportTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void importsFiftyCompleteDiseaseCasesAcrossAllThreeClinicalDomains() {
        Map<String, Long> domainCounts = jdbc.sql("""
                select encounter_type, count(*)
                from encounter
                where tenant_id = :tenant and source_system = 'SYNTHETIC-50'
                group by encounter_type
                """)
                .param("tenant", SyntheticDataImporter.TENANT_ID)
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getString(1), resultSet.getLong(2)))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(domainCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "OUTPATIENT", 20L,
                "EMERGENCY", 15L,
                "INPATIENT", 15L));
        assertThat(count("""
                select count(distinct patient.display_name)
                from patient
                join encounter on encounter.tenant_id = patient.tenant_id
                  and encounter.patient_id = patient.patient_id
                where encounter.tenant_id = :tenant and encounter.source_system = 'SYNTHETIC-50'
                  and patient.display_name ~ '^[一-龥]{2,4}$'
                  and patient.display_name !~ '(合成|测试|患者)'
                """)).isEqualTo(50);
        assertThat(count("""
                select count(distinct version.code)
                from clinical_diagnosis diagnosis
                join encounter on encounter.tenant_id = diagnosis.tenant_id
                  and encounter.encounter_id = diagnosis.encounter_id
                join clinical_diagnosis_version version on version.tenant_id = diagnosis.tenant_id
                  and version.diagnosis_id = diagnosis.diagnosis_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(50);
        assertThat(count("""
                select count(*) from clinical_document document
                join encounter on encounter.tenant_id = document.tenant_id
                  and encounter.encounter_id = document.encounter_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(65);
        assertThat(count("""
                select count(*) from clinical_order orders
                join encounter on encounter.tenant_id = orders.tenant_id
                  and encounter.encounter_id = orders.encounter_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(50);
        assertThat(count("""
                select count(*) from clinical_result result
                join encounter on encounter.tenant_id = result.tenant_id
                  and encounter.encounter_id = result.encounter_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(50);
        assertThat(count("""
                select count(*) from waiting_queue_entry queue
                join appointment on appointment.tenant_id = queue.tenant_id
                  and appointment.appointment_id = queue.appointment_id
                join encounter on encounter.tenant_id = appointment.tenant_id
                  and encounter.encounter_id = appointment.encounter_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(20);
        assertThat(count("""
                select count(*) from emergency_triage_assessment triage
                join encounter on encounter.tenant_id = triage.tenant_id
                  and encounter.encounter_id = triage.encounter_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(15);
        assertThat(count("""
                select count(*) from emergency_nursing_note note
                join encounter on encounter.tenant_id = note.tenant_id
                  and encounter.encounter_id = note.encounter_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(15);
        assertThat(count("""
                select count(*) from emergency_observation observation
                join encounter on encounter.tenant_id = observation.tenant_id
                  and encounter.encounter_id = observation.encounter_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(15);
        assertThat(count("""
                select count(*) from inpatient_admission admission
                join encounter on encounter.tenant_id = admission.tenant_id
                  and encounter.encounter_id = admission.encounter_id
                where encounter.source_system = 'SYNTHETIC-50'
                """)).isEqualTo(15);
        assertThat(count("""
                select count(*)
                from encounter
                where tenant_id = :tenant and source_system = 'SYNTHETIC-50'
                  and not exists (
                    select 1 from clinical_diagnosis diagnosis
                    where diagnosis.tenant_id = encounter.tenant_id
                      and diagnosis.encounter_id = encounter.encounter_id)
                """)).isZero();
    }

    @Test
    void importsSystemAdministrationFixturesForEveryDatabaseBackedWorkbench() {
        assertThat(count("""
                select count(distinct dictionary_code) from dictionary_item
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(14);
        assertThat(count("""
                select count(*) from dictionary_item
                where tenant_id = :tenant and status = 'ACTIVE'
                  and item_name like '% / %'
                """)).isGreaterThanOrEqualTo(50);
        assertThat(count("""
                select count(*) from dictionary_item
                where tenant_id = :tenant
                  and dictionary_code in ('GENDER','ENCOUNTER_TYPE','ALLERGY_SEVERITY','LAB_UNIT',
                    'BLOOD_TYPE','RH_TYPE','ADMISSION_SOURCE','DISCHARGE_DISPOSITION','TRIAGE_LEVEL',
                    'DOCUMENT_STATUS','CREDENTIAL_TYPE','MARITAL_STATUS','PAYMENT_TYPE','CONSENT_STATUS','BED_CLASS')
                  and (substring(dictionary_item_id::text from 15 for 1) not in ('1','2','3','4','5','6','7','8')
                    or lower(substring(dictionary_item_id::text from 20 for 1)) not in ('8','9','a','b'))
                """)).isZero();
        assertThat(count("""
                select count(*) from authorization_policy
                where tenant_id = :tenant and status = 'PUBLISHED'
                """)).isGreaterThanOrEqualTo(18);
        assertThat(count("""
                select count(distinct effect) from authorization_policy
                where tenant_id = :tenant and status = 'PUBLISHED'
                """)).isEqualTo(2);
        assertThat(configurationCount("MASTER_DATA")).isGreaterThanOrEqualTo(14);
        assertThat(configurationCount("PARAMETER")).isGreaterThanOrEqualTo(13);
        assertThat(configurationCount("JOB")).isGreaterThanOrEqualTo(9);
        assertThat(configurationCount("ROLE_CATALOG")).isGreaterThanOrEqualTo(18);
        assertThat(count("""
                select count(*) from clinical_document_template template
                join clinical_document_template_version version
                  on version.tenant_id = template.tenant_id and version.template_id = template.template_id
                where template.tenant_id = :tenant and template.lifecycle_status = 'ACTIVE'
                  and version.status = 'PUBLISHED'
                """)).isGreaterThanOrEqualTo(16);
    }

    @Test
    void importsFallbackKnowledgeAfterProvisioningTheSyntheticTenant() {
        assertThat(count("""
                select count(*) from pathway_knowledge
                where tenant_id = :tenant and pathway_code between 'PATH-001' and 'PATH-059'
                """)).isEqualTo(59);
        assertThat(count("""
                select count(*) from pathway_knowledge_version version
                join pathway_knowledge knowledge
                  on knowledge.tenant_id = version.tenant_id
                 and knowledge.pathway_knowledge_id = version.pathway_knowledge_id
                where knowledge.tenant_id = :tenant
                  and knowledge.pathway_code between 'PATH-001' and 'PATH-059'
                """)).isEqualTo(59);
        assertThat(count("""
                select count(*) from pathway_knowledge_stage stage
                join pathway_knowledge_version version
                  on version.tenant_id = stage.tenant_id
                 and version.pathway_version_id = stage.pathway_version_id
                join pathway_knowledge knowledge
                  on knowledge.tenant_id = version.tenant_id
                 and knowledge.pathway_knowledge_id = version.pathway_knowledge_id
                where knowledge.tenant_id = :tenant
                  and knowledge.pathway_code between 'PATH-001' and 'PATH-059'
                """)).isEqualTo(59);
    }

    @Test
    void reconcilesEveryTenantScopedMigrationCatalogAfterProvisioning() {
        assertThat(count("""
                select count(*) from inpatient_document_rule
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(16);
        assertThat(count("""
                select count(*) from agent_registry
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(14);
        assertThat(count("""
                select count(*) from skill_registry
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(24);
        assertThat(count("""
                select count(*) from tool_registry
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(38);
        assertThat(count("""
                select count(*) from agent_dependency
                where tenant_id = :tenant
                """)).isGreaterThanOrEqualTo(99);
        assertThat(count("""
                select count(*) from pathway_knowledge_version
                where tenant_id = :tenant
                """)).isPositive();
        assertThat(count("""
                select count(*) from pathway_knowledge_stage
                where tenant_id = :tenant
                """)).isPositive();
        assertThat(count("""
                select count(*) from pathway_knowledge_task
                where tenant_id = :tenant
                """)).isPositive();
    }

    @Test
    void importsACompleteDataCenterOverviewWithComputedCoreFacts() {
        assertThat(count("""
                select count(*) from config_item
                where tenant_id = :tenant and status = 'ACTIVE'
                  and config_type in (
                    'INTEGRATION_CONNECTOR', 'INTEGRATION_INCIDENT',
                    'DEVICE_CATALOG', 'RESEARCH_PROJECT')
                """)).isGreaterThanOrEqualTo(38);
        assertThat(count("""
                select count(*) from source_system_inventory where tenant_id = :tenant
                """)).isGreaterThanOrEqualTo(5);
        assertThat(count("""
                select count(*) from historical_migration_batch where tenant_id = :tenant
                """)).isGreaterThanOrEqualTo(6);
        assertThat(count("""
                select count(*) from data_quality_rule
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(6);
        assertThat(count("""
                select count(*) from data_quality_evaluation where tenant_id = :tenant
                """)).isGreaterThanOrEqualTo(16);
        assertThat(count("""
                select count(*) from research_cohort where tenant_id = :tenant
                """)).isGreaterThanOrEqualTo(5);
        assertThat(count("""
                select count(*) from research_dataset_request where tenant_id = :tenant
                """)).isGreaterThanOrEqualTo(4);
        assertThat(count("""
                select count(*) from metric_snapshot snapshot
                where snapshot.tenant_id = :tenant and snapshot.metric_type = 'DATA_CENTER'
                  and snapshot.status = 'FINAL'
                  and jsonb_exists_all(snapshot.dimension, array['source', 'formula', 'scope'])
                  and (
                    (snapshot.metric_name = '患者主档案' and snapshot.metric_value = (
                      select count(*) from patient where tenant_id = :tenant)) or
                    (snapshot.metric_name = '就诊事实' and snapshot.metric_value = (
                      select count(*) from encounter where tenant_id = :tenant)) or
                    (snapshot.metric_name = '已签署病历' and snapshot.metric_value = (
                      select count(*) from clinical_document
                      where tenant_id = :tenant and status = 'SIGNED')) or
                    (snapshot.metric_name = '医嘱事实' and snapshot.metric_value = (
                      select count(*) from clinical_order where tenant_id = :tenant))
                  )
                """)).isEqualTo(4);
    }

    @Test
    void importsCompleteTertiaryHospitalOrganizationAndWorkforceConfiguration() {
        assertThat(count("""
                select count(*) from facility
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(3);
        assertThat(count("""
                select count(*) from clinical_department
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(40);
        assertThat(count("""
                select count(*) from clinical_department
                where tenant_id = :tenant and status = 'ACTIVE'
                  and unit_type in ('DEPARTMENT', 'MEDICAL_TECH', 'ADMINISTRATIVE')
                """)).isGreaterThanOrEqualTo(40);
        assertThat(count("""
                select count(*) from clinical_ward
                where tenant_id = :tenant and status = 'ACTIVE'
                """)).isGreaterThanOrEqualTo(18);
        assertThat(count("""
                select count(*) from clinical_bed
                where tenant_id = :tenant and status = 'ACTIVE'
                  and bed_label ~ '.+-.+床$'
                """)).isGreaterThanOrEqualTo(180);
        assertThat(count("""
                select count(*) from workforce_person
                where tenant_id = :tenant and status = 'ACTIVE'
                  and person_code like 'JC-%' and display_name like '% / %'
                """)).isGreaterThanOrEqualTo(20);
        assertThat(count("""
                select count(*) from workforce_assignment assignment
                join workforce_person person on person.tenant_id = assignment.tenant_id
                  and person.person_id = assignment.person_id
                where assignment.tenant_id = :tenant and assignment.status = 'ACTIVE'
                  and person.person_code like 'JC-%'
                  and assignment.organization_id is not null
                  and assignment.facility_id is not null
                  and assignment.department_id is not null
                """)).isGreaterThanOrEqualTo(20);
        assertThat(count("""
                select count(*) from practitioner_credential credential
                join workforce_person person on person.tenant_id = credential.tenant_id
                  and person.person_id = credential.person_id
                where credential.tenant_id = :tenant and credential.status = 'ACTIVE'
                  and person.person_code like 'JC-%'
                  and credential.valid_until > now()
                """)).isGreaterThanOrEqualTo(15);
        assertThat(count("""
                select count(*) from audit_event
                where tenant_id = :tenant
                  and details ->> 'source' = 'dev-synthetic-tertiary-hospital'
                """)).isEqualTo(8);
    }

    @Test
    void importsCompleteBusinessConfigurationFixturesWithVersionHistory() {
        assertThat(count("""
                select count(*) from config_item
                where tenant_id = :tenant and (
                  (config_type = 'WORKFLOW' and config_key = 'syn-workflow-closed-loop-v1'
                    and jsonb_exists_all(payload, array['nodes', 'edges', 'protected_nodes', 'timeout_policy'])) or
                  (config_type = 'FORM_TEMPLATE' and config_key = 'syn-medical-record-v1'
                    and jsonb_exists_all(payload, array['fields', 'groups', 'terminology_mapping', 'print_template'])) or
                  (config_type = 'RULE' and config_key = 'syn-clinical-safety-v1'
                    and jsonb_exists_all(payload, array['conditions', 'actions', 'rule_layer', 'sample_case'])) or
                  (config_type = 'SCOPE' and config_key = 'syn-role-scope-v1'
                    and jsonb_exists_all(payload, array['roles', 'data_scopes', 'separation_of_duties', 'temporary_grant_hours'])))
                """)).isEqualTo(4);
        assertThat(count("""
                select count(distinct item.config_id) from config_item_revision revision
                join config_item item on item.tenant_id = revision.tenant_id
                  and item.config_id = revision.config_id
                where item.tenant_id = :tenant and item.config_key in (
                  'syn-workflow-closed-loop-v1', 'syn-medical-record-v1',
                  'syn-clinical-safety-v1', 'syn-role-scope-v1')
                """)).isEqualTo(4);
        assertThat(count("""
                select count(*) from capability_pack
                where tenant_id = :tenant and pack_code in (
                  'SYN-CORE-CLINICAL', 'SYN-TERTIARY-HOSPITAL', 'SYN-CARDIOLOGY')
                """)).isEqualTo(3);
        assertThat(count("""
                select count(*) from capability_pack_release
                where tenant_id = :tenant and release_id in (
                  '018f0000-0000-7000-8000-00000000c311'::uuid,
                  '018f0000-0000-7000-8000-00000000c312'::uuid,
                  '018f0000-0000-7000-8000-00000000c313'::uuid)
                """)).isEqualTo(3);
        assertThat(count("""
                select count(*) from department_support_assessment
                where tenant_id = :tenant and department_support_assessment_id in (
                  '018f0000-0000-7000-8000-00000000c411'::uuid,
                  '018f0000-0000-7000-8000-00000000c412'::uuid,
                  '018f0000-0000-7000-8000-00000000c413'::uuid,
                  '018f0000-0000-7000-8000-00000000c414'::uuid)
                """)).isEqualTo(4);
    }

    private long configurationCount(String configType) {
        return jdbc.sql("""
                select count(*) from config_item
                where tenant_id = :tenant and config_type = :type
                """)
                .param("tenant", SyntheticDataImporter.TENANT_ID)
                .param("type", configType)
                .query(Long.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql)
                .param("tenant", SyntheticDataImporter.TENANT_ID)
                .query(Long.class)
                .single();
    }
}
