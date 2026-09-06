package org.haoemr.development;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("dev-synthetic")
final class SyntheticDataImporter implements ApplicationRunner {

    static final UUID TENANT_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    static final UUID ORGANIZATION_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    static final UUID FACILITY_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    static final UUID USER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    static final UUID ROLE_ASSIGNMENT_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    static final UUID ADMIN_ROLE_ASSIGNMENT_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa09");
    /** 演示账号林伟附加的病案岗任期，使 linwei 可开箱演示病案资产中心（不改任何授权规则）。 */
    static final UUID DEMO_MEDICAL_RECORDS_ROLE_ASSIGNMENT_ID =
            UUID.fromString("018f0000-0000-7000-8000-00000000aa1a");
    static final UUID COLLABORATOR_USER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa06");
    static final UUID COLLABORATOR_ROLE_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa07");
    static final UUID CONFIG_AUTHOR_ROLE_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa18");
    static final UUID CONFIG_APPROVER_ROLE_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa19");
    static final UUID SYNTHETIC_DEPARTMENT_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa08");
    static final UUID SYNTHETIC_WARD_ID = UUID.fromString("018f0000-0000-7000-8000-00000000bb01");
    static final UUID SYNTHETIC_BED_ID = UUID.fromString("018f0000-0000-7000-8000-00000000bb02");
    static final UUID SYNTHETIC_FREE_BED_ID = UUID.fromString("018f0000-0000-7000-8000-00000000bb09");
    static final UUID SYNTHETIC_ADMISSION_ID = UUID.fromString("018f0000-0000-7000-8000-00000000bb03");
    static final UUID ATTENDING_USER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa10");
    static final UUID ATTENDING_ROLE_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa11");
    static final UUID CHIEF_USER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa12");
    static final UUID CHIEF_ROLE_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa13");
    static final UUID MEDICAL_RECORDS_USER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa14");
    static final UUID MEDICAL_RECORDS_ROLE_ID = UUID.fromString("018f0000-0000-7000-8000-00000000aa15");
    static final UUID HEART_FAILURE_PATHWAY_ID = UUID.fromString("018f0000-0000-7000-8000-00000000cc01");
    static final UUID HEART_FAILURE_PATHWAY_V1_ID = UUID.fromString("018f0000-0000-7000-8000-00000000cc02");

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Path datasetPath;
    private final Path diseaseCaseCatalogPath;
    private final Path dataCenterDatasetPath;
    private final String loginPassword;

    SyntheticDataImporter(
            JdbcClient jdbc,
            DataSource dataSource,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            @Value("${haoemr.synthetic-dataset:samples/data/synthetic-clinical-golden-v1.json}") String datasetPath,
            @Value("${haoemr.synthetic-disease-cases:samples/data/synthetic-50-disease-cases-v1.json}")
            String diseaseCaseCatalogPath,
            @Value("${haoemr.tertiary-data-center-dataset:samples/data/tertiary-data-center-business-v2.json}")
            String dataCenterDatasetPath,
            @Value("${haoemr.dev-login-password:HaoEMR-dev!}") String loginPassword) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.datasetPath = Path.of(datasetPath);
        this.diseaseCaseCatalogPath = Path.of(diseaseCaseCatalogPath);
        this.dataCenterDatasetPath = Path.of(dataCenterDatasetPath);
        this.loginPassword = loginPassword;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String json = Files.readString(datasetPath, StandardCharsets.UTF_8);
        SyntheticDataset.parse(objectMapper, json);
        JsonNode root = objectMapper.readTree(json);
        String diseaseCaseJson = Files.readString(diseaseCaseCatalogPath, StandardCharsets.UTF_8);
        SyntheticDiseaseCaseCatalog diseaseCases = SyntheticDiseaseCaseCatalog.parse(objectMapper, diseaseCaseJson);
        String dataCenterJson = Files.readString(dataCenterDatasetPath, StandardCharsets.UTF_8);
        TertiaryDataCenterDataset dataCenter = TertiaryDataCenterDataset.parse(objectMapper, dataCenterJson);
        transactions.executeWithoutResult(status -> importRoot(root, diseaseCases, dataCenter));
    }

    private void importRoot(
            JsonNode root,
            SyntheticDiseaseCaseCatalog diseaseCases,
            TertiaryDataCenterDataset dataCenter) {
        upsertInfrastructure();
        reconcileTenantScopedMigrationSeeds();
        upsertAdministrationFixtures();
        upsertSpecialtyReferencePatients();
        upsertClinicalPathwayDefinitions();
        upsertConfigurationFixtures();
        upsertSystemAdministrationRuntimeFixtures();
        upsertAiAgentConfigurationFixtures();
        upsertBusinessConfigurationFixtures();
        upsertDocumentTemplates(root);
        for (JsonNode item : root.path("cases")) {
            upsertCase(item);
        }
        upsertSpecialtyReferencePatients();
        upsertOutpatientClinicalFixture();
        upsertEmergencyFixture();
        upsertInpatientFixture();
        upsertTertiaryInpatientWorkspaceFixtures();
        upsertDiseaseCases(diseaseCases.cases());
        upsertTertiaryExecutionCenterFixtures();
        refreshPlaceholderPatientProfiles();
        upsertTertiaryDataCenterFixtures();
        upsertTertiaryDataCenterDataset(dataCenter);
        upsertTertiaryTaskPathwayFixtures();
        verifyDataCenterBootstrap();
    }

    /** Run after the ready-event operational importers, which add the final order facts. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    void finalizeDataCenterMetricSnapshots() {
        transactions.executeWithoutResult(status -> {
            seedInitialDataCenterMetricSnapshotsWhenEmpty();
            verifyDataCenterMetricSnapshots();
        });
    }

    /**
     * Flyway initializes an empty database before the dev-synthetic tenant exists. Any migration
     * that selects from tenant therefore succeeds without installing its tenant-scoped rows. Keep
     * all equivalent idempotent application seeds together and reconcile them immediately after
     * tenant provisioning, then fail fast if a catalog is still incomplete.
     */
    private void reconcileTenantScopedMigrationSeeds() {
        seedFallbackKnowledgeWhenEmpty();
        upsertInpatientDocumentRules();
        upsertAgentCatalog();
        upsertAiPlatformCatalog();
        SyntheticMedicalAgentToolCatalog.install(jdbc, TENANT_ID);
        verifyTenantScopedMigrationSeeds();
    }

    private void verifyTenantScopedMigrationSeeds() {
        requireTenantSeedRows(
                "inpatient document rules",
                "select count(*) from inpatient_document_rule where tenant_id = :tenant",
                16);
        requireTenantSeedRows(
                "agent registry",
                "select count(*) from agent_registry where tenant_id = :tenant",
                14);
        requireTenantSeedRows(
                "skill registry",
                "select count(*) from skill_registry where tenant_id = :tenant",
                24);
        requireTenantSeedRows(
                "tool registry",
                "select count(*) from tool_registry where tenant_id = :tenant",
                38);
        requireTenantSeedRows(
                "agent dependencies",
                "select count(*) from agent_dependency where tenant_id = :tenant",
                99);
        requireTenantSeedRows(
                "pathway knowledge",
                "select count(*) from pathway_knowledge where tenant_id = :tenant",
                1);
        requireTenantSeedRows(
                "pathway knowledge versions",
                "select count(*) from pathway_knowledge_version where tenant_id = :tenant",
                1);
        requireTenantSeedRows(
                "pathway knowledge stages",
                "select count(*) from pathway_knowledge_stage where tenant_id = :tenant",
                1);
        requireTenantSeedRows(
                "pathway knowledge tasks",
                "select count(*) from pathway_knowledge_task where tenant_id = :tenant",
                1);
    }

    private void requireTenantSeedRows(String catalog, String sql, long minimum) {
        long actual = jdbc.sql(sql)
                .param("tenant", TENANT_ID)
                .query(Long.class)
                .single();
        if (actual < minimum) {
            throw new IllegalStateException(
                    "Incomplete dev-synthetic tenant seed for " + catalog
                            + ": expected at least " + minimum + " rows but found " + actual);
        }
    }

    /**
     * Populate the first data-center snapshot from the imported facts. These are calculated values,
     * not display fixtures; subsequent user-triggered calculations remain immutable history rows.
     */
    private void seedInitialDataCenterMetricSnapshotsWhenEmpty() {
        long existing = jdbc.sql("""
                select count(*) from metric_snapshot
                where tenant_id = :tenant and metric_type = 'DATA_CENTER'
                """).param("tenant", TENANT_ID).query(Long.class).single();
        if (existing > 0) {
            return;
        }
        jdbc.sql("""
                insert into metric_snapshot(
                  tenant_id, snapshot_id, metric_type, metric_name, metric_value, unit,
                  dimension, period, status, computed_at)
                select :tenant, seed.snapshot_id::uuid, 'DATA_CENTER', seed.metric_name,
                  seed.metric_value, seed.unit,
                  jsonb_build_object(
                    'source', seed.source_table,
                    'formula', seed.formula,
                    'scope', 'TENANT',
                    'fixture_source', 'dev-synthetic-computed-v1'),
                  current_date, 'FINAL', now()
                from (values
                  ('018f0000-0000-7000-8000-00000000d811', 'patient', 'count(patient_id)',
                    '患者主档案', (
                      select count(*)::numeric from patient where tenant_id = :tenant), '人'),
                  ('018f0000-0000-7000-8000-00000000d812', 'encounter', 'count(encounter_id)',
                    '就诊事实', (
                      select count(*)::numeric from encounter where tenant_id = :tenant), '次'),
                  ('018f0000-0000-7000-8000-00000000d813', 'clinical_document',
                    'count where status=SIGNED', '已签署病历', (
                      select count(*)::numeric from clinical_document
                      where tenant_id = :tenant and status = 'SIGNED'), '份'),
                  ('018f0000-0000-7000-8000-00000000d814', 'clinical_order', 'count(order_id)',
                    '医嘱事实', (
                      select count(*)::numeric from clinical_order where tenant_id = :tenant), '条')
                ) as seed(snapshot_id, source_table, formula, metric_name, metric_value, unit)
                on conflict (tenant_id, snapshot_id) do nothing
                """).param("tenant", TENANT_ID).update();
    }

    private void verifyDataCenterBootstrap() {
        requireTenantSeedRows(
                "active integration connectors",
                """
                select count(*) from config_item where tenant_id = :tenant
                  and config_type = 'INTEGRATION_CONNECTOR' and status = 'ACTIVE'
                """,
                10);
        requireTenantSeedRows(
                "active device catalog",
                """
                select count(*) from config_item where tenant_id = :tenant
                  and config_type = 'DEVICE_CATALOG' and status = 'ACTIVE'
                """,
                12);
        requireTenantSeedRows(
                "active research projects",
                """
                select count(*) from config_item where tenant_id = :tenant
                  and config_type = 'RESEARCH_PROJECT' and status = 'ACTIVE'
                """,
                8);
        requireTenantSeedRows(
                "active integration incidents",
                """
                select count(*) from config_item where tenant_id = :tenant
                  and config_type = 'INTEGRATION_INCIDENT' and status = 'ACTIVE'
                """,
                8);
        requireTenantSeedRows(
                "source systems",
                "select count(*) from source_system_inventory where tenant_id = :tenant",
                5);
        requireTenantSeedRows(
                "migration batches",
                "select count(*) from historical_migration_batch where tenant_id = :tenant",
                6);
        requireTenantSeedRows(
                "active data-quality rules",
                """
                select count(*) from data_quality_rule
                where tenant_id = :tenant and status = 'ACTIVE'
                """,
                6);
        requireTenantSeedRows(
                "data-quality evaluations",
                "select count(*) from data_quality_evaluation where tenant_id = :tenant",
                16);
        requireTenantSeedRows(
                "research cohorts",
                "select count(*) from research_cohort where tenant_id = :tenant",
                5);
        requireTenantSeedRows(
                "research dataset requests",
                "select count(*) from research_dataset_request where tenant_id = :tenant",
                4);
    }

    private void verifyDataCenterMetricSnapshots() {
        requireTenantSeedRows(
                "data-center metric snapshots",
                """
                select count(distinct metric_name) from metric_snapshot
                where tenant_id = :tenant and metric_type = 'DATA_CENTER'
                  and metric_name in ('患者主档案', '就诊事实', '已签署病历', '医嘱事实')
                """,
                4);
    }

    /**
     * Flyway runs before the dev-synthetic importer provisions its tenant. The
     * V355/V360 seed statements intentionally join tenant, so a brand-new
     * database records those migrations as successful while inserting no
     * knowledge rows. Re-run the idempotent seeds after tenant provisioning,
     * but only for an entirely empty knowledge catalog so a restored or
     * externally imported corpus is never mixed with fallback fixtures.
     */
    private void seedFallbackKnowledgeWhenEmpty() {
        long knowledgeCount = jdbc.sql("select count(*) from pathway_knowledge")
                .query(Long.class)
                .single();
        if (knowledgeCount > 0) {
            return;
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/V355__seed_pathway_knowledge.sql"));
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/V360__seed_pathway_knowledge_content.sql"));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void upsertEmergencyFixture() {
        UUID patientId = UUID.fromString("018f0000-0000-7000-8000-000000000003");
        UUID encounterId = UUID.fromString("018f0000-0000-7000-8000-000000000103");

        jdbc.sql("""
                insert into emergency_triage_assessment(
                  tenant_id, triage_assessment_id, patient_id, encounter_id, facility_id,
                  triage_level, chief_complaint, triaged_at, immediate_action_required, status)
                values (:tenant, '018f0000-0000-7000-8000-00000000e101'::uuid, :patient, :encounter,
                  :facility, 'LEVEL_1', '突发胸骨后压榨样疼痛伴大汗、低血压，院前心电图提示ST段抬高',
                  now() - interval '46 minutes', true, 'ACTIVE')
                on conflict (tenant_id, triage_assessment_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into emergency_observation(
                  tenant_id, observation_id, patient_id, encounter_id, facility_id,
                  observation_started_at, disposition, status)
                values (:tenant, '018f0000-0000-7000-8000-00000000e102'::uuid, :patient, :encounter,
                  :facility, now() - interval '38 minutes', 'PENDING', 'OBSERVING')
                on conflict (tenant_id, observation_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into emergency_resuscitation(
                  tenant_id, resuscitation_id, patient_id, encounter_id, facility_id,
                  started_at, ended_at, outcome, status)
                values (:tenant, '018f0000-0000-7000-8000-00000000e103'::uuid, :patient, :encounter,
                  :facility, now() - interval '42 minutes', now() - interval '24 minutes', 'ROSC', 'COMPLETED')
                on conflict (tenant_id, resuscitation_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into emergency_nursing_note(
                  tenant_id, note_id, patient_id, encounter_id, facility_id,
                  assessment, intervention, risk_flag, recorded_at)
                select :tenant, seed.note_id::uuid, :patient, :encounter, :facility,
                  seed.assessment, seed.intervention, seed.risk_flag, seed.recorded_at
                from (values
                  ('018f0000-0000-7000-8000-00000000e111',
                   '胸痛持续，BP 86/54 mmHg，HR 112 次/分，SpO2 93%，皮肤湿冷，休克风险高',
                   '建立双静脉通路、持续心电监护，遵医嘱给予抗血小板及升压支持，启动导管室绿色通道',
                   true, now() - interval '41 minutes'),
                  ('018f0000-0000-7000-8000-00000000e112',
                   '复苏后意识清楚，BP 102/68 mmHg，胸痛评分由8分降至3分，仍需严密监护',
                   '复测十二导联心电图，复核肌钙蛋白危急值，通知心内科会诊并记录家属沟通',
                   true, now() - interval '22 minutes'),
                  ('018f0000-0000-7000-8000-00000000e113',
                   '转运前生命体征暂时稳定，桡动脉搏动可触及，穿刺部位皮肤完整',
                   '完成转运核查表、药物与管路交接，除颤仪及抢救药品随行',
                   false, now() - interval '8 minutes')
                ) as seed(note_id, assessment, intervention, risk_flag, recorded_at)
                on conflict (tenant_id, note_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into emergency_preadmission(
                  tenant_id, preadmission_id, facility_id, temporary_identifier, reason, status,
                  registered_patient_id, registered_at)
                select :tenant, seed.preadmission_id::uuid, :facility, seed.temporary_identifier,
                  seed.reason, seed.status, seed.patient_id::uuid, seed.registered_at
                from (values
                  ('018f0000-0000-7000-8000-00000000e121', '急诊临时-胸痛-017',
                   '120送达，无身份证件，疑似急性心肌梗死，先救治后补登', 'UNREGISTERED', null, null),
                  ('018f0000-0000-7000-8000-00000000e122', '急诊临时-创伤-011',
                   '多发伤患者身份核验完成，已关联张慧敏', 'REGISTERED',
                   '018f0000-0000-7000-8000-000000000001', now() - interval '18 minutes')
                ) as seed(preadmission_id, temporary_identifier, reason, status, patient_id, registered_at)
                on conflict (tenant_id, preadmission_id) do nothing
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID).update();

        jdbc.sql("""
                insert into schedule_slot(
                  tenant_id, schedule_slot_id, organization_id, facility_id, department_id,
                  visit_type, slot_date, start_time, end_time, total_capacity, booked_count, status)
                values (:tenant, '018f0000-0000-7000-8000-00000000e131'::uuid, :org, :facility, null,
                  'EMERGENCY', current_date, time '00:00', time '23:59', 60, 3, 'OPEN')
                on conflict (tenant_id, schedule_slot_id) do update set slot_date = current_date,
                  booked_count = 3, status = 'OPEN', updated_at = now()
                """).param("tenant", TENANT_ID).param("org", ORGANIZATION_ID).param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                select :tenant, seed.encounter_id::uuid, seed.patient_id::uuid, :org, :facility,
                  'EMERGENCY', 'ARRIVED', seed.started_at, 'SYNTHETIC', seed.source_key
                from (values
                  ('018f0000-0000-7000-8000-00000000e171', '018f0000-0000-7000-8000-000000000001', now() - interval '30 minutes', 'syn-er-queue-patient-1'),
                  ('018f0000-0000-7000-8000-00000000e172', '018f0000-0000-7000-8000-000000000002', now() - interval '15 minutes', 'syn-er-queue-patient-2')
                ) as seed(encounter_id, patient_id, started_at, source_key)
                on conflict (tenant_id, encounter_id) do update set status = 'ARRIVED', ended_at = null,
                  updated_at = now()
                """).param("tenant", TENANT_ID).param("org", ORGANIZATION_ID).param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into appointment(
                  tenant_id, appointment_id, schedule_slot_id, patient_id, organization_id, facility_id,
                  visit_type, source, status, booked_at, check_in_at, encounter_id)
                select :tenant, seed.appointment_id::uuid,
                  '018f0000-0000-7000-8000-00000000e131'::uuid, seed.patient_id::uuid,
                  :org, :facility, 'EMERGENCY', 'EMERGENCY', 'CHECKED_IN', seed.booked_at,
                  seed.check_in_at, seed.encounter_id::uuid
                from (values
                  ('018f0000-0000-7000-8000-00000000e141', '018f0000-0000-7000-8000-000000000003', now() - interval '48 minutes', now() - interval '47 minutes', '018f0000-0000-7000-8000-000000000103'),
                  ('018f0000-0000-7000-8000-00000000e142', '018f0000-0000-7000-8000-000000000001', now() - interval '31 minutes', now() - interval '30 minutes', '018f0000-0000-7000-8000-00000000e171'),
                  ('018f0000-0000-7000-8000-00000000e143', '018f0000-0000-7000-8000-000000000002', now() - interval '16 minutes', now() - interval '15 minutes', '018f0000-0000-7000-8000-00000000e172')
                ) as seed(appointment_id, patient_id, booked_at, check_in_at, encounter_id)
                on conflict (tenant_id, appointment_id) do update set encounter_id = excluded.encounter_id,
                  status = 'CHECKED_IN', check_in_at = excluded.check_in_at, updated_at = now()
                """).param("tenant", TENANT_ID).param("org", ORGANIZATION_ID).param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into waiting_queue_entry(
                  tenant_id, waiting_queue_entry_id, appointment_id, facility_id, queue_date,
                  sequence_no, status, called_at, called_by)
                select :tenant, seed.entry_id::uuid, seed.appointment_id::uuid, :facility, current_date,
                  seed.sequence_no, seed.status, seed.called_at, seed.called_by::uuid
                from (values
                  ('018f0000-0000-7000-8000-00000000e151', '018f0000-0000-7000-8000-00000000e141', 1, 'IN_CONSULTATION', now() - interval '44 minutes', '018f0000-0000-7000-8000-00000000aa04'),
                  ('018f0000-0000-7000-8000-00000000e152', '018f0000-0000-7000-8000-00000000e142', 2, 'CALLED', now() - interval '3 minutes', '018f0000-0000-7000-8000-00000000aa04'),
                  ('018f0000-0000-7000-8000-00000000e153', '018f0000-0000-7000-8000-00000000e143', 3, 'WAITING', null, null)
                ) as seed(entry_id, appointment_id, sequence_no, status, called_at, called_by)
                on conflict (tenant_id, waiting_queue_entry_id) do update set queue_date = current_date,
                  status = excluded.status, called_at = excluded.called_at, called_by = excluded.called_by,
                  updated_at = now()
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID).update();

        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, ended_at, source_system, source_key)
                values (:tenant, '018f0000-0000-7000-8000-00000000e161'::uuid, :patient, :org, :facility,
                  'OUTPATIENT', 'FINISHED', now() - interval '1 day', now() - interval '23 hours',
                  'SYNTHETIC', 'syn-er-001-prior-opd')
                on conflict (tenant_id, encounter_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("org", ORGANIZATION_ID)
                .param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into encounter_domain_switch(
                  tenant_id, domain_switch_id, patient_id, from_encounter_id, to_encounter_id,
                  from_domain, to_domain, reason, switched_at, switched_by)
                values (:tenant, '018f0000-0000-7000-8000-00000000e162'::uuid, :patient,
                  '018f0000-0000-7000-8000-00000000e161'::uuid, :encounter,
                  'OUTPATIENT', 'EMERGENCY', '胸痛伴低血压，门诊立即升级急诊抢救流程',
                  now() - interval '49 minutes', :user)
                on conflict (tenant_id, domain_switch_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("user", USER_ID).update();
    }

    private void upsertSpecialtyReferencePatients() {
        jdbc.sql("""
                update patient
                set sex_code = 'F', updated_at = now(), row_version = row_version + 1
                where tenant_id = :tenant
                  and patient_id = '018f0000-0000-7000-8000-000000000001'::uuid
                  and sex_code <> 'F'
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into patient(
                  tenant_id, patient_id, display_name, sex_code, birth_date, status, row_version)
                values (:tenant, '018f0000-0000-7000-8000-000000000006'::uuid,
                  '周婉宁',
                  'F', date '1990-05-18', 'ACTIVE', 1)
                on conflict (tenant_id, patient_id) do update
                set display_name = excluded.display_name, updated_at = now(),
                    row_version = patient.row_version + 1
                where patient.display_name is distinct from excluded.display_name
                """).param("tenant", TENANT_ID).update();
    }

    private void upsertAgentCatalog() {
        jdbc.sql("""
                insert into agent_registry(
                  tenant_id, agent_registry_id, agent_code, agent_name, agent_version, status)
                select :tenant, seed.agent_registry_id::uuid, seed.agent_code,
                  seed.agent_name, '1.0.0', 'ACTIVE'
                from (values
                  ('018f0000-0000-7000-8000-00000000ee01', 'ENCOUNTER_SUMMARIZER', '就诊摘要医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee02', 'DOCUMENT_DRAFTER', '文书起草医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee03', 'RECORD_QC', '病历质控医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee04', 'RESULT_FOLLOWUP_COORDINATOR', '结果闭环医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee05', 'CARE_COORDINATOR', '诊疗协同医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee06', 'INFECTION_SURVEILLANCE', '院感监测医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee07', 'INSURANCE_COMPLIANCE', '医保合规医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee08', 'MEDICAL_TECH_SCHEDULING', '医技预约调度医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee09', 'RESEARCH_FOLLOWUP', '科研随访医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee10', 'PATIENT_EDUCATION', '患者宣教医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee11', 'GLYCEMIC_MANAGEMENT', '血糖治理医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee12', 'CARDIOVASCULAR_CARE', '心血管诊疗医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee13', 'TCM_SYNDROME_REVIEW', '中医辨证审方医助团队'),
                  ('018f0000-0000-7000-8000-00000000ee14', 'ICU_RISK_ASSESSMENT', '重症风险研判医助团队')
                ) as seed(agent_registry_id, agent_code, agent_name)
                on conflict (tenant_id, agent_registry_id) do nothing
                """).param("tenant", TENANT_ID).update();
    }

    private void upsertAiPlatformCatalog() {
        jdbc.sql("""
                insert into model_deployment(
                  tenant_id, model_deployment_id, model_code, provider_code, display_name,
                  residency_policy, endpoint_url, api_key_ref, connection_status,
                  status, evaluation_status, row_version)
                select :tenant, seed.model_deployment_id::uuid, seed.model_code, seed.provider_code,
                  seed.display_name, seed.residency_policy, seed.endpoint_url,
                  null, 'NOT_CONFIGURED', 'INACTIVE', 'REJECTED', 1
                from (values
                  ('018f0000-0000-7000-8000-00000000f001', 'DEEPSEEK-V3-CLINICAL-LOCAL', 'DEEPSEEK',
                   'DeepSeek V3 临床综合主模型', 'ON_PREM_ONLY', 'https://ai-gateway.tertiary-hospital.example/v1'),
                  ('018f0000-0000-7000-8000-00000000f002', 'DEEPSEEK-R1-REASONING-LOCAL', 'DEEPSEEK',
                   'DeepSeek R1 疑难病例推理模型', 'ON_PREM_ONLY', 'https://ai-gateway.tertiary-hospital.example/v1'),
                  ('018f0000-0000-7000-8000-00000000f003', 'QWEN3-EMBEDDING-LOCAL', 'QWEN',
                   'Qwen3 医学知识检索向量模型', 'ON_PREM_ONLY', 'https://ai-gateway.tertiary-hospital.example/v1'),
                  ('018f0000-0000-7000-8000-00000000f005', 'QWEN2.5-VL-MEDICAL', 'QWEN',
                   'Qwen2.5-VL 医学影像文档理解模型', 'LOCAL_PREFERRED', 'https://ai-gateway.tertiary-hospital.example/v1'),
                  ('018f0000-0000-7000-8000-00000000f006', 'GLM4-PATIENT-COMMUNICATION', 'GLM',
                   'GLM-4 医患沟通与随访模型', 'LOCAL_PREFERRED', 'https://ai-gateway.tertiary-hospital.example/v1'),
                  ('018f0000-0000-7000-8000-00000000f007', 'BGE-M3-CLINICAL-RERANK', 'QWEN',
                   'BGE-M3 临床术语重排模型', 'ON_PREM_ONLY', 'https://ai-gateway.tertiary-hospital.example/v1')
                ) as seed(model_deployment_id, model_code, provider_code, display_name, residency_policy, endpoint_url)
                on conflict (tenant_id, model_code) do update
                set display_name = excluded.display_name,
                    api_key_ref = null,
                    connection_status = 'NOT_CONFIGURED',
                    status = 'INACTIVE', evaluation_status = 'REJECTED',
                    last_connection_tested_at = null,
                    last_connection_latency_ms = null,
                    last_connection_error_code = 'SYNTHETIC_CONFIGURATION_RETIRED',
                    row_version = model_deployment.row_version + 1, updated_at = now()
                where model_deployment.endpoint_url like 'https://%.example/%'
                  and (model_deployment.api_key_ref is not null
                    or model_deployment.connection_status <> 'NOT_CONFIGURED'
                    or model_deployment.status <> 'INACTIVE'
                    or model_deployment.evaluation_status <> 'REJECTED'
                    or model_deployment.last_connection_tested_at is not null
                    or model_deployment.last_connection_latency_ms is not null
                    or model_deployment.last_connection_error_code is distinct from 'SYNTHETIC_CONFIGURATION_RETIRED')
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into skill_registry(
                  tenant_id, skill_registry_id, skill_code, skill_name, skill_version, status)
                select :tenant, seed.skill_registry_id::uuid, seed.skill_code, seed.skill_name,
                  '1.0.0', 'ACTIVE'
                from (values
                  ('018f0000-0000-7000-8000-00000000f101', 'CONTEXT_LEASE_GUARD', '上下文租约校验'),
                  ('018f0000-0000-7000-8000-00000000f102', 'PATIENT_TIMELINE_RETRIEVAL', '患者时间线检索'),
                  ('018f0000-0000-7000-8000-00000000f103', 'CLINICAL_FACT_SUMMARY', '临床事实摘要'),
                  ('018f0000-0000-7000-8000-00000000f104', 'CLINICAL_DOCUMENT_DRAFT', '临床文书候选起草'),
                  ('018f0000-0000-7000-8000-00000000f105', 'DOCUMENT_CONSISTENCY_REVIEW', '文书一致性审阅'),
                  ('018f0000-0000-7000-8000-00000000f106', 'CRITICAL_RESULT_CONTEXT', '危急值上下文核对'),
                  ('018f0000-0000-7000-8000-00000000f107', 'FOLLOWUP_TASK_PLANNING', '随访任务候选规划'),
                  ('018f0000-0000-7000-8000-00000000f108', 'CONSULT_BRIEF_PREPARATION', '会诊资料候选准备'),
                  ('018f0000-0000-7000-8000-00000000f109', 'MEDICATION_RISK_EXPLANATION', '用药风险解释'),
                  ('018f0000-0000-7000-8000-00000000f10a', 'MINIMUM_NECESSARY_REDACTION', '最小必要数据脱敏'),
                  ('018f0000-0000-7000-8000-00000000f10b', 'SOURCE_REFERENCE_CITATION', '可定位来源引用'),
                  ('018f0000-0000-7000-8000-00000000f10c', 'CANDIDATE_BOUNDARY_VALIDATION', '候选结果边界校验'),
                  ('018f0000-0000-7000-8000-00000000f10d', 'ADMISSION_RISK_SUMMARY', '入院风险摘要'),
                  ('018f0000-0000-7000-8000-00000000f10e', 'EMERGENCY_TRIAGE_CONTEXT', '急诊分诊上下文整理'),
                  ('018f0000-0000-7000-8000-00000000f10f', 'SURGICAL_RISK_BRIEF', '围手术期风险摘要'),
                  ('018f0000-0000-7000-8000-00000000f110', 'MEDICATION_RECONCILIATION', '用药重整'),
                  ('018f0000-0000-7000-8000-00000000f111', 'ANTIBIOTIC_STEWARDSHIP_REVIEW', '抗菌药物管理审阅'),
                  ('018f0000-0000-7000-8000-00000000f112', 'LAB_TREND_ANALYSIS', '检验趋势分析'),
                  ('018f0000-0000-7000-8000-00000000f113', 'IMAGING_REPORT_SUMMARY', '影像报告摘要'),
                  ('018f0000-0000-7000-8000-00000000f114', 'NURSING_HANDOFF_SUMMARY', '护理交接摘要'),
                  ('018f0000-0000-7000-8000-00000000f115', 'DISCHARGE_PLAN_DRAFT', '出院计划候选起草'),
                  ('018f0000-0000-7000-8000-00000000f116', 'MDT_CASE_PREPARATION', 'MDT 病例资料准备'),
                  ('018f0000-0000-7000-8000-00000000f117', 'DRG_RECORD_COMPLETENESS', 'DRG 病案完整性检查'),
                  ('018f0000-0000-7000-8000-00000000f118', 'PRIVACY_DEIDENTIFICATION', '医疗数据脱敏')
                ) as seed(skill_registry_id, skill_code, skill_name)
                on conflict (tenant_id, skill_code, skill_version) do nothing
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into tool_registry(
                  tenant_id, tool_registry_id, tool_code, tool_name, tool_version, tool_type, status)
                select :tenant, seed.tool_registry_id::uuid, seed.tool_code, seed.tool_name,
                  '1.0.0', seed.tool_type, 'ACTIVE'
                from (values
                  ('018f0000-0000-7000-8000-00000000f201', 'PATIENT_SUMMARY_READ', '患者概要只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f202', 'ENCOUNTER_TIMELINE_READ', '就诊时间线只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f203', 'DOCUMENT_VERSION_READ', '病历版本只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f204', 'RESULT_READ', '检查检验结果只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f205', 'CRITICAL_VALUE_READ', '危急值闭环只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f206', 'ORDER_READ', '医嘱与处方只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f207', 'CLINICAL_TASK_READ', '临床任务只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f208', 'CONSULTATION_READ', '会诊协同只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f209', 'CARE_PLAN_READ', '诊疗计划只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f20a', 'DOCUMENT_TEMPLATE_READ', '文书模板只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f20b', 'CLINICAL_RULE_EVALUATE', '临床硬规则只读评估', 'FUNCTION'),
                  ('018f0000-0000-7000-8000-00000000f20c', 'AGENT_EVIDENCE_APPEND', '医助证据链追加工具', 'FUNCTION'),
                  ('018f0000-0000-7000-8000-00000000f20d', 'ALLERGY_READ', '过敏史只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f20e', 'VITAL_SIGN_READ', '生命体征只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f20f', 'LAB_TREND_READ', '检验趋势只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f210', 'IMAGING_REPORT_READ', '影像报告只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f211', 'MEDICATION_ADMIN_READ', '用药执行只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f212', 'NURSING_RECORD_READ', '护理记录只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f213', 'SURGERY_SCHEDULE_READ', '手术排程只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f214', 'ANESTHESIA_RECORD_READ', '麻醉记录只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f215', 'BLOOD_TRANSFUSION_READ', '输血记录只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f216', 'INFECTION_EVENT_READ', '院感事件只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f217', 'PATHOLOGY_REPORT_READ', '病理报告只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f218', 'MDT_RECORD_READ', 'MDT 记录只读工具', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f221', 'CLINICAL_DOCUMENT_READ', '当前就诊文书版本读取', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f222', 'CLINICAL_ORDER_READ', '当前就诊医嘱读取', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f223', 'CLINICAL_RESULT_READ', '当前就诊结果读取', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f225', 'CLINICAL_ATTACHMENT_READ', '当前就诊附件元数据读取', 'DATABASE_QUERY'),
                  ('018f0000-0000-7000-8000-00000000f226', 'BUSINESS_CONFIGURATION_READ', '已发布业务配置与运行时证据读取', 'DATABASE_QUERY')
                ) as seed(tool_registry_id, tool_code, tool_name, tool_type)
                on conflict (tenant_id, tool_code, tool_version) do nothing
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into agent_run_budget(
                  tenant_id, budget_id, budget_code, budget_name, max_tokens,
                  max_duration_seconds, status)
                select :tenant, seed.budget_id::uuid, seed.budget_code, seed.budget_name,
                  seed.max_tokens, seed.max_duration_seconds, 'ACTIVE'
                from (values
                  ('018f0000-0000-7000-8000-00000000f301', 'BUDGET_ENCOUNTER_SUMMARIZER', '就诊摘要医助单次处理上限', 16000::bigint, 90),
                  ('018f0000-0000-7000-8000-00000000f302', 'BUDGET_DOCUMENT_DRAFTER', '文书起草医助单次处理上限', 22000::bigint, 120),
                  ('018f0000-0000-7000-8000-00000000f303', 'BUDGET_RECORD_QC', '病历质控医助单次处理上限', 16000::bigint, 90),
                  ('018f0000-0000-7000-8000-00000000f304', 'BUDGET_RESULT_FOLLOWUP_COORDINATOR', '结果闭环医助单次处理上限', 24000::bigint, 120),
                  ('018f0000-0000-7000-8000-00000000f305', 'BUDGET_CARE_COORDINATOR', '诊疗协同医助单次处理上限', 22000::bigint, 120),
                  ('018f0000-0000-7000-8000-00000000f306', 'BUDGET_INFECTION_SURVEILLANCE', '院感监测医助单次处理上限', 16000::bigint, 90),
                  ('018f0000-0000-7000-8000-00000000f307', 'BUDGET_INSURANCE_COMPLIANCE', '医保合规医助单次处理上限', 20000::bigint, 100),
                  ('018f0000-0000-7000-8000-00000000f308', 'BUDGET_MEDICAL_TECH_SCHEDULING', '医技预约调度医助单次处理上限', 16000::bigint, 80),
                  ('018f0000-0000-7000-8000-00000000f309', 'BUDGET_RESEARCH_FOLLOWUP', '科研随访医助单次处理上限', 16000::bigint, 80),
                  ('018f0000-0000-7000-8000-00000000f310', 'BUDGET_PATIENT_EDUCATION', '患者宣教医助单次处理上限', 12000::bigint, 60),
                  ('018f0000-0000-7000-8000-00000000f311', 'BUDGET_GLYCEMIC_MANAGEMENT', '血糖治理医助单次处理上限', 18000::bigint, 90),
                  ('018f0000-0000-7000-8000-00000000f312', 'BUDGET_CARDIOVASCULAR_CARE', '心血管诊疗医助单次处理上限', 18000::bigint, 90),
                  ('018f0000-0000-7000-8000-00000000f313', 'BUDGET_TCM_SYNDROME_REVIEW', '中医辨证审方医助单次处理上限', 16000::bigint, 90),
                  ('018f0000-0000-7000-8000-00000000f314', 'BUDGET_ICU_RISK_ASSESSMENT', '重症风险研判医助单次处理上限', 18000::bigint, 90)
                ) as seed(budget_id, budget_code, budget_name, max_tokens, max_duration_seconds)
                on conflict (tenant_id, budget_code) do nothing
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into model_evaluation(
                  tenant_id, model_evaluation_id, model_deployment_id, eval_name, score,
                  threshold, status, evaluated_at, evaluated_by)
                select :tenant, seed.model_evaluation_id::uuid, seed.model_deployment_id::uuid,
                  seed.eval_name, seed.score, seed.threshold,
                  case when seed.score >= seed.threshold then 'PASSED' else 'FAILED' end,
                  now() - seed.age_days * interval '1 day', :actor
                from (values
                  ('018f0000-0000-7000-8000-00000000f401', '018f0000-0000-7000-8000-00000000f001', '临床事实一致性与拒答门禁', 0.9730::numeric, 0.9500::numeric, 3),
                  ('018f0000-0000-7000-8000-00000000f402', '018f0000-0000-7000-8000-00000000f002', '复杂推理可追溯性门禁', 0.9620::numeric, 0.9500::numeric, 2),
                  ('018f0000-0000-7000-8000-00000000f403', '018f0000-0000-7000-8000-00000000f003', '医疗语义检索召回率门禁', 0.9810::numeric, 0.9600::numeric, 1)
                ) as seed(model_evaluation_id, model_deployment_id, eval_name, score, threshold, age_days)
                on conflict (tenant_id, model_evaluation_id) do nothing
                """).param("tenant", TENANT_ID).param("actor", USER_ID).update();

        jdbc.sql("""
                insert into agent_dependency(
                  tenant_id, agent_dependency_id, agent_registry_id, dependency_type, dependency_code)
                select :tenant, seed.agent_dependency_id::uuid, seed.agent_registry_id::uuid,
                  seed.dependency_type, seed.dependency_code
                from (values
                  ('018f0000-0000-7000-8000-00000000f501', '018f0000-0000-7000-8000-00000000ee01', 'SKILL', 'CLINICAL_FACT_SUMMARY'),
                  ('018f0000-0000-7000-8000-00000000f502', '018f0000-0000-7000-8000-00000000ee01', 'TOOL', 'ENCOUNTER_TIMELINE_READ'),
                  ('018f0000-0000-7000-8000-00000000f503', '018f0000-0000-7000-8000-00000000ee02', 'SKILL', 'CLINICAL_DOCUMENT_DRAFT'),
                  ('018f0000-0000-7000-8000-00000000f504', '018f0000-0000-7000-8000-00000000ee02', 'TOOL', 'DOCUMENT_TEMPLATE_READ'),
                  ('018f0000-0000-7000-8000-00000000f505', '018f0000-0000-7000-8000-00000000ee03', 'SKILL', 'DOCUMENT_CONSISTENCY_REVIEW'),
                  ('018f0000-0000-7000-8000-00000000f506', '018f0000-0000-7000-8000-00000000ee03', 'TOOL', 'CLINICAL_RULE_EVALUATE'),
                  ('018f0000-0000-7000-8000-00000000f507', '018f0000-0000-7000-8000-00000000ee04', 'SKILL', 'CRITICAL_RESULT_CONTEXT'),
                  ('018f0000-0000-7000-8000-00000000f508', '018f0000-0000-7000-8000-00000000ee04', 'TOOL', 'CRITICAL_VALUE_READ'),
                  ('018f0000-0000-7000-8000-00000000f509', '018f0000-0000-7000-8000-00000000ee05', 'SKILL', 'CONSULT_BRIEF_PREPARATION'),
                  ('018f0000-0000-7000-8000-00000000f50a', '018f0000-0000-7000-8000-00000000ee05', 'TOOL', 'CONSULTATION_READ'),
                  ('018f0000-0000-7000-8000-00000000f50b', '018f0000-0000-7000-8000-00000000ee02', 'TOOL', 'AGENT_EVIDENCE_APPEND')
                ) as seed(agent_dependency_id, agent_registry_id, dependency_type, dependency_code)
                on conflict (tenant_id, agent_registry_id, dependency_type, dependency_code) do nothing
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into agent_run_budget_consumption(
                  tenant_id, consumption_id, budget_id, run_id, tokens_consumed,
                  duration_seconds, recorded_by, recorded_at)
                select :tenant, seed.consumption_id::uuid, seed.budget_id::uuid, seed.run_id::uuid,
                  seed.tokens_consumed, seed.duration_seconds, :actor,
                  now() - seed.age_hours * interval '1 hour'
                from (values
                  ('018f0000-0000-7000-8000-00000000f601', '018f0000-0000-7000-8000-00000000f301', '018f0000-0000-7000-8000-00000000f701', 4280::bigint, 18::bigint, 8),
                  ('018f0000-0000-7000-8000-00000000f602', '018f0000-0000-7000-8000-00000000f302', '018f0000-0000-7000-8000-00000000f702', 7350::bigint, 34::bigint, 6),
                  ('018f0000-0000-7000-8000-00000000f603', '018f0000-0000-7000-8000-00000000f304', '018f0000-0000-7000-8000-00000000f703', 5190::bigint, 22::bigint, 3),
                  ('018f0000-0000-7000-8000-00000000f604', '018f0000-0000-7000-8000-00000000f303', '018f0000-0000-7000-8000-00000000f704', 3860::bigint, 16::bigint, 2),
                  ('018f0000-0000-7000-8000-00000000f605', '018f0000-0000-7000-8000-00000000f305', '018f0000-0000-7000-8000-00000000f705', 6120::bigint, 27::bigint, 1)
                ) as seed(consumption_id, budget_id, run_id, tokens_consumed, duration_seconds, age_hours)
                on conflict (tenant_id, consumption_id) do nothing
                """).param("tenant", TENANT_ID).param("actor", USER_ID).update();
    }

    private void upsertAiAgentConfigurationFixtures() {
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                values (:tenant, '018f0000-0000-7000-8000-00000000c108'::uuid,
                  'AI_ASSISTANT_POLICY', 'xiaonan-clinical-policy-v1', 'AI医助 Eva·三级甲等医院临床工作策略',
                  jsonb_build_object(
                  'schema_version', 1,
                  'description', '覆盖门诊、急诊与住院场景的统一 Eva 工作策略。',
                  'proactive_level', 'REMIND_ONLY',
                  'allowed_sources', jsonb_build_array('DOCUMENT_VERSION', 'OBSERVATION', 'ORDER', 'RESULT', 'RULE'),
                  'model_policy', 'TENANT_ACTIVE_MODEL_WITH_LOCAL_FALLBACK',
                  'rate_limit', 10,
                  'approval_required', true,
                  'main_agent_count', 14,
                  'child_agent_count', 58,
                  'hospital_level', '三级甲等',
                  'facility_name', '江城大学附属医院（仿真）',
                  'campuses', jsonb_build_array('本部院区', '东院区', '感染病院区'),
                  'environment', 'tertiary-hospital-simulation'),
                  'ACTIVE', 1, 1, 'VALID', '[]'::jsonb, 'APPROVED', :approver, now(), :author)
                on conflict (tenant_id, config_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();
        jdbc.sql("""
                update config_item
                set payload = payload || jsonb_build_object(
                  'agents', jsonb_build_array(
                    'ENCOUNTER_SUMMARIZER@1.0.0', 'DOCUMENT_DRAFTER@1.0.0', 'RECORD_QC@1.0.0',
                    'RESULT_FOLLOWUP_COORDINATOR@1.0.0', 'CARE_COORDINATOR@1.0.0',
                    'INFECTION_SURVEILLANCE@1.0.0', 'INSURANCE_COMPLIANCE@1.0.0',
                    'MEDICAL_TECH_SCHEDULING@1.0.0', 'RESEARCH_FOLLOWUP@1.0.0',
                    'PATIENT_EDUCATION@1.0.0', 'GLYCEMIC_MANAGEMENT@1.0.0',
                    'CARDIOVASCULAR_CARE@1.0.0', 'TCM_SYNDROME_REVIEW@1.0.0',
                    'ICU_RISK_ASSESSMENT@1.0.0'),
                  'skills', jsonb_build_array('CONTEXT_LEASE_GUARD@1.0.0', 'SOURCE_REFERENCE_CITATION@1.0.0', 'CANDIDATE_BOUNDARY_VALIDATION@1.0.0'),
                  'tools', jsonb_build_array('ENCOUNTER_TIMELINE_READ@1.0.0', 'DOCUMENT_VERSION_READ@1.0.0', 'AGENT_EVIDENCE_APPEND@1.0.0'),
                  'budget_tokens', 24000,
                  'stop_conditions', '租约失效；患者或就诊切换；超预算；来源不可定位；硬规则阻断',
                  'compensation', '停止未执行节点，保留事件证据，转人工复核'),
                  updated_at = now()
                where tenant_id = :tenant and config_type = 'AGENT_COMPOSITION'
                  and config_key = 'syn-agent-team-v1'
                  and not jsonb_exists(payload, 'agents')
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                select :tenant, seed.config_id::uuid, 'AGENT_EVAL', seed.config_key, seed.display_name,
                  jsonb_build_object(
                    'schema_version', 1,
                    'description', seed.description,
                    'dataset_version', seed.dataset_version,
                    'case_count', seed.case_count,
                    'pass_threshold', seed.pass_threshold,
                    'red_team_profile', '越权上下文、Prompt 注入、无来源临床结论、自动临床副作用、敏感数据外泄',
                    'target_agent', seed.target_agent,
                    'measured_score', seed.measured_score,
                    'release_gate', case when seed.measured_score >= seed.pass_threshold then 'PASSED' else 'BLOCKED' end,
                    'hospital_level', '三级甲等',
                    'facility_name', '江城大学附属医院（仿真）',
                    'environment', 'tertiary-hospital-simulation'),
                  'ACTIVE', 1, 1, 'VALID', '[]'::jsonb, 'APPROVED', :approver, now(), :author
                from (values
                  ('018f0000-0000-7000-8000-00000000f901', 'eval-emergency-triage-v1', '急诊分诊上下文完整性评测', 'ENCOUNTER_SUMMARIZER', '覆盖创伤、胸痛、卒中、高热与特殊人群的分诊事实整理。', 'tertiary-ed-triage-golden-v1', 220, 0.9700::numeric, 0.9820::numeric),
                  ('018f0000-0000-7000-8000-00000000f902', 'eval-admission-risk-v1', '入院风险摘要评测', 'ENCOUNTER_SUMMARIZER', '核验过敏、跌倒、VTE、压疮和营养风险事实覆盖。', 'tertiary-admission-risk-v1', 180, 0.9600::numeric, 0.9760::numeric),
                  ('018f0000-0000-7000-8000-00000000f903', 'eval-surgical-document-v1', '围手术期文书起草评测', 'DOCUMENT_DRAFTER', '覆盖术前讨论、手术记录、麻醉记录与术后计划。', 'tertiary-surgery-document-v1', 200, 0.9700::numeric, 0.9790::numeric),
                  ('018f0000-0000-7000-8000-00000000f904', 'eval-discharge-plan-v1', '出院计划与患者指导评测', 'DOCUMENT_DRAFTER', '核验出院用药、复诊、红旗症状和患者可理解性。', 'tertiary-discharge-plan-v1', 190, 0.9600::numeric, 0.9740::numeric),
                  ('018f0000-0000-7000-8000-00000000f905', 'eval-drg-completeness-v1', 'DRG 病案完整性评测', 'RECORD_QC', '核验主要诊断、主要手术、并发症与时间逻辑缺项。', 'tertiary-drg-qc-v1', 260, 0.9600::numeric, 0.9730::numeric),
                  ('018f0000-0000-7000-8000-00000000f906', 'eval-antibiotic-review-v1', '抗菌药物管理审阅评测', 'RECORD_QC', '核验适应证、微生物证据、肾功能与特殊级使用条件。', 'tertiary-ams-review-v1', 210, 0.9700::numeric, 0.9810::numeric),
                  ('018f0000-0000-7000-8000-00000000f907', 'eval-critical-result-v1', '危急值与重要结果闭环评测', 'RESULT_FOLLOWUP_COORDINATOR', '覆盖检验、影像、病理与心电重要结果通知。', 'tertiary-critical-result-v1', 240, 0.9850::numeric, 0.9900::numeric),
                  ('018f0000-0000-7000-8000-00000000f908', 'eval-pathology-followup-v1', '病理结果随访闭环评测', 'RESULT_FOLLOWUP_COORDINATOR', '核验未回结果跟踪、责任人、截止时间与患者触达。', 'tertiary-pathology-followup-v1', 170, 0.9700::numeric, 0.9780::numeric),
                  ('018f0000-0000-7000-8000-00000000f909', 'eval-mdt-preparation-v1', 'MDT 病例准备与任务协同评测', 'CARE_COORDINATOR', '覆盖肿瘤、疑难、器官移植与多学科病例资料准备。', 'tertiary-mdt-golden-v1', 160, 0.9600::numeric, 0.9720::numeric),
                  ('018f0000-0000-7000-8000-00000000f90a', 'eval-medication-reconciliation-v1', '跨场景用药重整评测', 'CARE_COORDINATOR', '核验门诊、急诊、住院和出院用药的差异及待确认项。', 'tertiary-medication-reconciliation-v1', 230, 0.9700::numeric, 0.9830::numeric),
                  ('018f0000-0000-7000-8000-00000000f90b', 'eval-infection-surveillance-v1', '院感监测候选评测', 'INFECTION_SURVEILLANCE', '核验感染病例线索、聚集暴发与法定传染病直报要素。', 'tertiary-infection-surveillance-v1', 180, 0.9600::numeric, 0.9750::numeric),
                  ('018f0000-0000-7000-8000-00000000f90c', 'eval-insurance-compliance-v1', '医保合规候选评测', 'INSURANCE_COMPLIANCE', '核验 DRG/DIP 编码与费用合理性的可追溯候选。', 'tertiary-insurance-compliance-v1', 190, 0.9600::numeric, 0.9740::numeric),
                  ('018f0000-0000-7000-8000-00000000f90d', 'eval-medical-tech-scheduling-v1', '医技预约调度候选评测', 'MEDICAL_TECH_SCHEDULING', '核验号源规划与设备利用的可追溯候选。', 'tertiary-medical-tech-scheduling-v1', 160, 0.9500::numeric, 0.9680::numeric),
                  ('018f0000-0000-7000-8000-00000000f90e', 'eval-research-followup-v1', '科研随访候选评测', 'RESEARCH_FOLLOWUP', '核验队列入组与结局采集口径的可追溯候选。', 'tertiary-research-followup-v1', 170, 0.9500::numeric, 0.9710::numeric),
                  ('018f0000-0000-7000-8000-00000000f90f', 'eval-patient-education-v1', '患者宣教候选评测', 'PATIENT_EDUCATION', '核验出院带药说明与复诊提醒的可追溯候选。', 'tertiary-patient-education-v1', 150, 0.9500::numeric, 0.9690::numeric),
                  ('018f0000-0000-7000-8000-00000000f910', 'eval-glycemic-management-v1', '血糖治理候选评测', 'GLYCEMIC_MANAGEMENT', '核验住院血糖、胰岛素剂量与低血糖风险候选。', 'tertiary-glycemic-management-v1', 180, 0.9600::numeric, 0.9750::numeric),
                  ('018f0000-0000-7000-8000-00000000f911', 'eval-cardiovascular-care-v1', '心血管诊疗候选评测', 'CARDIOVASCULAR_CARE', '核验胸痛分层、抗凝与心衰随访候选。', 'tertiary-cardiovascular-care-v1', 190, 0.9600::numeric, 0.9740::numeric),
                  ('018f0000-0000-7000-8000-00000000f912', 'eval-tcm-syndrome-review-v1', '中医辨证审方候选评测', 'TCM_SYNDROME_REVIEW', '核验辨证分型、方药思路与配伍禁忌候选。', 'tertiary-tcm-syndrome-review-v1', 170, 0.9500::numeric, 0.9720::numeric),
                  ('018f0000-0000-7000-8000-00000000f913', 'eval-icu-risk-assessment-v1', '重症风险研判候选评测', 'ICU_RISK_ASSESSMENT', '核验脓毒症、恶化风险与交班摘要候选。', 'tertiary-icu-risk-assessment-v1', 180, 0.9600::numeric, 0.9750::numeric)
                ) as seed(config_id, config_key, display_name, target_agent, description,
                  dataset_version, case_count, pass_threshold, measured_score)
                on conflict (tenant_id, config_id) do update
                set display_name = excluded.display_name,
                    payload = excluded.payload,
                    status = 'ACTIVE', validation_state = 'VALID', validation_errors = '[]'::jsonb,
                    approval_state = 'APPROVED', approved_by = excluded.approved_by,
                    published_at = coalesce(config_item.published_at, now()), updated_at = now()
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();
    }

    private void upsertConfigurationFixtures() {
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, created_by)
                select :tenant, seed.config_id::uuid, seed.config_type, seed.config_key,
                  seed.display_name,
                  jsonb_build_object(
                    'schema_version', case when seed.config_type in ('WORKFLOW', 'FORM_TEMPLATE', 'RULE', 'SCOPE') then 2 else 1 end,
                    'description', seed.description,
                    'environment', 'dev-synthetic',
                    'owner', 'HaoEMR 验收组',
                    'effective_scope', jsonb_build_array('合成医院', '全院区', '验收角色'),
                    'controls', jsonb_build_array('双人复核', '审计留痕', '失败关闭', '可回退'),
                    'evidence', jsonb_build_object(
                      'dataset', 'synthetic-clinical-golden-v1',
                      'case_count', 12,
                      'last_verified', '2026-08-25'))
                  || case seed.config_type
                    when 'WORKFLOW' then jsonb_build_object(
                      'nodes', jsonb_build_array(
                        jsonb_build_object('id','start','name','发起申请','type','START','owner','经治医生','minutes',15),
                        jsonb_build_object('id','receive','name','科室接收','type','TASK','owner','目标科室','minutes',30),
                        jsonb_build_object('id','opinion','name','专家意见','type','TASK','owner','会诊专家','minutes',120),
                        jsonb_build_object('id','sign','name','数字签署','type','SIGN','owner','会诊专家','minutes',20,'protected',true),
                        jsonb_build_object('id','audit','name','审计留痕','type','AUDIT','owner','系统','minutes',1,'protected',true),
                        jsonb_build_object('id','complete','name','完成','type','END','owner','系统','minutes',1,'terminal',true,'protected',true)),
                      'edges', jsonb_build_array(
                        jsonb_build_object('from','start','to','receive','condition','申请已提交'),
                        jsonb_build_object('from','receive','to','opinion','condition','科室接收'),
                        jsonb_build_object('from','opinion','to','sign','condition','意见完成'),
                        jsonb_build_object('from','sign','to','audit','condition','签名有效'),
                        jsonb_build_object('from','audit','to','complete','condition','审计成功'),
                        jsonb_build_object('from','receive','to','start','condition','资料不全','compensation',true)),
                      'protected_nodes', jsonb_build_array('sign', 'audit', 'complete'),
                      'timeout_policy', '30 分钟提醒，120 分钟升级科主任，240 分钟升级医务处',
                      'synthetic_case', jsonb_build_object('case_id','SYN-CONSULT-20260826-01','patient','赵明远（合成）'))
                    when 'FORM_TEMPLATE' then jsonb_build_object(
                      'groups', jsonb_build_array(jsonb_build_object('id','history','name','病史采集','columns',2),jsonb_build_object('id','assessment','name','评估与计划','columns',2)),
                      'fields', jsonb_build_array(
                        jsonb_build_object('id','chief_complaint','label','主诉','type','TEXTAREA','group','history','required',true,'terminology','SNOMED-CT'),
                        jsonb_build_object('id','pain_score','label','疼痛评分','type','NUMBER','group','history','required',true,'validation','0 <= value <= 10'),
                        jsonb_build_object('id','risk_level','label','胸痛风险分层','type','CALCULATED','group','assessment','required',true,'protected',true,'calculation','pain_score >= 7 ? HIGH : MEDIUM'),
                        jsonb_build_object('id','diagnosis','label','诊断','type','CODE','group','assessment','required',true,'protected',true,'terminology','ICD-10-CN'),
                        jsonb_build_object('id','signature','label','医生签名','type','SIGNATURE','group','assessment','required',true,'protected',true)),
                      'terminology_mapping', jsonb_build_array(jsonb_build_object('field','diagnosis','system','ICD-10-CN'),jsonb_build_object('field','chief_complaint','system','SNOMED-CT')),
                      'print_template', 'A4-门诊病历-v2',
                      'sample_values', jsonb_build_object('chief_complaint','胸痛 2 小时','pain_score',8,'diagnosis','I20.0 不稳定型心绞痛'))
                    when 'RULE' then jsonb_build_object(
                      'conditions', jsonb_build_array('年龄<14', '药品类型=处方', '体重已记录'),
                      'actions', jsonb_build_array('校验体重剂量', '阻断超范围处方'),
                      'rule_layer', 'MIXED',
                      'rules', jsonb_build_array(
                        jsonb_build_object('id','allergy-block','name','严重过敏处方阻断','layer','PLATFORM_HARD','priority',1000,'condition','严重过敏且成分命中','action','阻断处方并要求替代药','evidence','国家药品不良反应监测规范','exception','禁止例外','enabled',true),
                        jsonb_build_object('id','pediatric-dose','name','儿科体重剂量校验','layer','INSTITUTION_HARD','priority',800,'condition','年龄<14 且已录入体重','action','超出 mg/kg 范围时阻断','evidence','院内儿科用药目录 2026.2','exception','药师与上级医师双签','enabled',true),
                        jsonb_build_object('id','consult-timeout','name','会诊超时升级','layer','REMINDER','priority',500,'condition','会诊等待>=120分钟','action','提醒科主任并升级任务','evidence','医疗核心制度','exception','急救处理中可延后','enabled',true),
                        jsonb_build_object('id','ai-summary','name','AI 病情摘要建议','layer','AI_ADVICE','priority',100,'condition','资料完整度>=80%','action','生成带来源的摘要候选','evidence','clinical-ai-golden-v1','exception','仅供人工确认','enabled',true)),
                      'sample_case', jsonb_build_object('case_id','SYN-RULE-20260826-01','age',6,'weight_kg',20,'allergy','青霉素严重过敏','order','阿莫西林克拉维酸钾'))
                    when 'SCOPE' then jsonb_build_object(
                      'roles', jsonb_build_array('作者', '审批人', '跨科医生'),
                      'data_scopes', jsonb_build_array('本科就诊', '授权患者', '脱敏汇总'),
                      'permissions', jsonb_build_array(
                        jsonb_build_object('role','经治医生','resource','病历草稿','action','读写','scope','本科患者','effect','ALLOW','temporary_hours',0,'sod','签署人不能批准本人更正'),
                        jsonb_build_object('role','会诊医生','resource','病历全文','action','只读','scope','会诊授权患者','effect','ALLOW','temporary_hours',4,'sod','禁止导出'),
                        jsonb_build_object('role','会诊医生','resource','批量导出','action','导出','scope','全部患者','effect','DENY','temporary_hours',0,'sod','保护性拒绝优先'),
                        jsonb_build_object('role','护士长','resource','护理记录','action','审核','scope','本病区','effect','ALLOW','temporary_hours',0,'sod','作者与审核人分离')),
                      'separation_of_duties', '作者!=审批人；跨科医生只读',
                      'temporary_grant_hours', 4)
                    when 'MASTER_DATA' then jsonb_build_object(
                      'code_system', 'HAOEMR-HOSPITAL-MASTER',
                      'hierarchy', jsonb_build_array('医院', '院区', '科室', '病区', '床位'),
                      'effective_period', '2026-01-01/2099-12-31',
                      'import_policy', '编码冲突阻断；已引用数据仅允许停用')
                    when 'PARAMETER' then jsonb_build_object(
                      'value_type', 'OBJECT', 'scope', 'ORGANIZATION',
                      'inheritance', '机构默认，院区可以在安全基线内收紧',
                      'configured_value', '临床系统参数基线 v1',
                      'secret_reference', 'env://HAOEMR_SYSTEM_PARAMETER_BASELINE',
                      'effective_at', '2026-08-25T00:00:00+08:00')
                    when 'JOB' then jsonb_build_object(
                      'schedule', '0 */5 * * * *', 'batch_size', 100,
                      'retry_policy', '只重试失败通知；1m/5m/15m；最多 3 次',
                      'reconciliation_rule', '通知发送、回执、失败和补偿总数必须相等',
                      'notification_channels', jsonb_build_array('站内信', '短信', '邮件', 'Webhook'),
                      'channel_owner', '信息中心运维组')
                    else '{}'::jsonb
                  end,
                  'DRAFT', 1, 1, 'NOT_VALIDATED', '[]'::jsonb, 'DRAFT', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c101', 'WORKFLOW', 'syn-workflow-closed-loop-v1', '门急住全程闭环流程', '覆盖接诊、诊疗、交接、归档与异常补偿的合成流程。'),
                  ('018f0000-0000-7000-8000-00000000c102', 'FORM_TEMPLATE', 'syn-medical-record-v1', '复杂病历结构化模板', '包含主诉、现病史、系统回顾、查体、诊断依据与计划。'),
                  ('018f0000-0000-7000-8000-00000000c103', 'RULE', 'syn-clinical-safety-v1', '临床安全与时限规则', '覆盖过敏、剂量、危急值、超时与会诊升级规则。'),
                  ('018f0000-0000-7000-8000-00000000c104', 'SCOPE', 'syn-role-scope-v1', '院科患者关系数据范围', '按机构、岗位、患者关系和临时授权组合控制。'),
                  ('018f0000-0000-7000-8000-00000000c105', 'AGENT_COMPOSITION', 'syn-agent-team-v1', 'AI医助 Eva 团队编排', '六类医助按诊疗场景协同处理，临床写入均需医生确认。'),
                  ('018f0000-0000-7000-8000-00000000c106', 'AGENT_CONTEXT', 'syn-agent-context-v1', '最小必要临床上下文', '绑定患者、就诊、任务、来源和有效期，切换后立即失效。'),
                  ('018f0000-0000-7000-8000-00000000c109', 'CONFIG_RELEASE', 'syn-config-release-v1', '配置灰度发布批次', '包含差异、验证证据、灰度范围、停止条件和回退点。'),
                  ('018f0000-0000-7000-8000-00000000c110', 'CONFIG_UPGRADE', 'syn-config-upgrade-v1', '配置包升级预演', '覆盖兼容性检查、冲突决议、迁移预演与恢复校验。'),
                  ('018f0000-0000-7000-8000-00000000c111', 'MASTER_DATA', 'hospital-master-data-v1', '医院主数据基线', '包含机构、科室、病区、床位、术语和值集的有效期。'),
                  ('018f0000-0000-7000-8000-00000000c112', 'PARAMETER', 'system-parameters-v1', '临床系统参数基线', '记录作用域、继承、敏感引用、生效时间和回退值。'),
                  ('018f0000-0000-7000-8000-00000000c113', 'JOB', 'notification-job-v1', '危急值通知对账任务', '记录批次、进度、部分成功、失败项重试和 Outbox 对账。'),
                  ('018f0000-0000-7000-8000-00000000c114', 'BACKUP', 'syn-backup-drill-v1', '合成库备份恢复演练', '包含 checksum、RPO/RTO、恢复步骤和完整性报告。'),
                  ('018f0000-0000-7000-8000-00000000c115', 'INSTALL', 'syn-install-healthcheck-v1', '首次安装健康检查', '验证数据库、OIDC、消息、存储和合成数据导入。'),
                  ('018f0000-0000-7000-8000-00000000c116', 'OPERATION', 'syn-operations-v1', '生产运行与停机续运预案', '展示服务健康、事件、维护窗、积压与恢复动作。'),
                  ('018f0000-0000-7000-8000-00000000c117', 'RELEASE_GATE', 'syn-release-gate-v1', 'HaoEMR 发布门禁', '聚合契约、迁移、测试、安全、备份和回滚证据。')
                ) as seed(config_id, config_type, config_key, display_name, description)
                on conflict (tenant_id, config_id) do update
                set display_name = excluded.display_name,
                    payload = excluded.payload,
                    updated_at = now(),
                    row_version = config_item.row_version + 1
                where config_item.display_name is distinct from excluded.display_name
                   or config_item.payload is distinct from excluded.payload
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, created_by)
                select :tenant, seed.config_id::uuid, 'PARAMETER', seed.config_key,
                  seed.display_name,
                  jsonb_build_object(
                    'schema_version', 1, 'description', seed.description,
                    'value_type', seed.value_type, 'scope', seed.scope,
                    'inheritance', seed.inheritance,
                    'configured_value', seed.configured_value,
                    'secret_reference', seed.secret_reference,
                    'effective_at', '2026-08-25T00:00:00+08:00'),
                  'DRAFT', 1, 1, 'NOT_VALIDATED', '[]'::jsonb, 'DRAFT', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c211', 'syn-admin-session-v1', '管理会话时长',
                    '高权管理会话闲置超时', 'INTEGER_MINUTES', 'ORGANIZATION',
                    '全局默认，机构可缩短', '15 分钟闲置超时；8 小时绝对时限', 'env://HAOEMR_ADMIN_SESSION_MINUTES'),
                  ('018f0000-0000-7000-8000-00000000c212', 'syn-record-autosave-v1', '病历自动保存间隔',
                    '结构化病历草稿自动保存间隔', 'INTEGER_SECONDS', 'FACILITY',
                    '院区覆盖机构默认值', '10 秒', 'env://HAOEMR_RECORD_AUTOSAVE_SECONDS'),
                  ('018f0000-0000-7000-8000-00000000c213', 'syn-archive-retention-v1', '住院病历保管年限',
                    '住院病案长期保存保护下限', 'INTEGER_YEARS', 'GLOBAL',
                    '机构不得向下覆盖', '30 年', 'env://HAOEMR_ARCHIVE_RETENTION_YEARS'),
                  ('018f0000-0000-7000-8000-00000000c214', 'syn-research-feature-v1', '科研统计模块开关',
                    '机构级科研统计功能灰度开关', 'BOOLEAN', 'ORGANIZATION',
                    '科室继承机构值', '机构级灰度启用', 'env://HAOEMR_RESEARCH_ENABLED'),
                  ('018f0000-0000-7000-8000-00000000c215', 'syn-pacs-timeout-v1', 'PACS 调阅超时',
                    '影像调阅超时灰度参数', 'INTEGER_MILLISECONDS', 'FACILITY',
                    '院区覆盖机构默认值', '平台 8000ms -> 机构 6000ms -> 本部院区 5000ms', 'env://HAOEMR_PACS_TIMEOUT_MS'),
                  ('018f0000-0000-7000-8000-00000000c216', 'syn-auth-identity-provider-v1', '统一身份源',
                    '生产环境统一身份认证源', 'STRING', 'GLOBAL',
                    '生产启用 OIDC；开发验收使用数据库测试凭据', '江城大学统一身份平台 · OIDC', 'env://HAOEMR_OIDC_ISSUER'),
                  ('018f0000-0000-7000-8000-00000000c217', 'syn-auth-flow-v1', '授权模式',
                    '浏览器登录授权模式', 'STRING', 'GLOBAL',
                    '客户端不得保存用户密码', 'Authorization Code + PKCE', 'env://HAOEMR_OIDC_CLIENT_ID'),
                  ('018f0000-0000-7000-8000-00000000c218', 'syn-auth-mfa-policy-v1', '多因素认证',
                    '高风险场景多因素认证要求', 'STRING', 'ORGANIZATION',
                    '机构只能扩大强制范围', '管理员、院外访问和高风险操作强制 MFA', 'env://HAOEMR_MFA_POLICY'),
                  ('018f0000-0000-7000-8000-00000000c219', 'syn-auth-lockout-policy-v1', '失败锁定',
                    '连续认证失败锁定策略', 'STRING', 'GLOBAL',
                    '全局安全基线', '连续 5 次失败锁定 15 分钟', 'env://HAOEMR_AUTH_LOCKOUT_POLICY'),
                  ('018f0000-0000-7000-8000-00000000c220', 'syn-auth-emergency-policy-v1', '紧急访问',
                    '紧急访问时限与事后复核要求', 'STRING', 'GLOBAL',
                    '机构只能缩短时限', '最小范围 · 最长 60 分钟 · 强制事后复核', 'env://HAOEMR_EMERGENCY_ACCESS_POLICY'),
                  ('018f0000-0000-7000-8000-00000000c230', 'syn-auth-password-policy-v1', '口令强度基线',
                    '开发凭据和应急本地账户的口令复杂度', 'STRING', 'GLOBAL',
                    '生产统一身份源策略优先', '至少 12 位，包含数字、大小写字母和特殊字符', 'env://HAOEMR_PASSWORD_POLICY'),
                  ('018f0000-0000-7000-8000-00000000c231', 'syn-auth-concurrent-session-v1', '并发会话限制',
                    '同一管理账号允许的有效会话上限', 'INTEGER', 'ORGANIZATION',
                    '机构可设置更严格上限', '最多 3 个并发会话', 'env://HAOEMR_MAX_CONCURRENT_SESSIONS'),
                  ('018f0000-0000-7000-8000-00000000c232', 'syn-auth-risk-reauth-v1', '高风险操作再认证',
                    '导出、授权和安全配置变更前重新认证', 'STRING', 'GLOBAL',
                    '所有机构必须启用', '授权、批量导出与紧急访问审批前强制再认证', 'env://HAOEMR_RISK_REAUTH_POLICY')
                ) as seed(config_id, config_key, display_name, description, value_type, scope, inheritance, configured_value, secret_reference)
                on conflict (tenant_id, config_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, created_by)
                select :tenant, seed.config_id::uuid, 'MASTER_DATA', seed.config_key,
                  seed.display_name,
                  jsonb_build_object(
                    'schema_version', 1, 'description', seed.description,
                    'code_system', seed.code_system, 'hierarchy', string_to_array(seed.hierarchy, '>'),
                    'effective_period', '2026-01-01/2099-12-31',
                    'import_policy', seed.import_policy),
                  'DRAFT', 1, 1, 'NOT_VALIDATED', '[]'::jsonb, 'DRAFT', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c201', 'syn-drug-catalog-v1', '药品目录主数据',
                    '药品、剂型、规格与生产厂商目录', 'NMPA-DRUG-CATALOG',
                    '药品>心血管用药>降压药>氨氯地平', '国家本位码唯一；停用不影响历史处方'),
                  ('018f0000-0000-7000-8000-00000000c202', 'syn-lab-catalog-v1', '检验项目主数据',
                    'LIS 检验项目与标准单位映射', 'LOINC-CN-LAB',
                    '检验>生化>电解质>血钾', '单位和参考区间冲突进入人工复核'),
                  ('018f0000-0000-7000-8000-00000000c203', 'syn-device-catalog-v1', '医疗设备主数据',
                    '床旁监护、影像和检验设备目录', 'HAOEMR-DEVICE',
                    '设备>床旁监护>心电监护仪>CARD-MON-01', '设备身份、校准状态与责任科室联合校验'),
                  ('018f0000-0000-7000-8000-00000000c204', 'syn-bed-catalog-v1', '床位与护理单元主数据',
                    '按科室-床号维护病区床位', 'HAOEMR-BED',
                    '心血管内科>心内科一病区>心血管内科-01床', '占用床位禁止停用；历史住院绑定原床位版本'),
                  ('018f0000-0000-7000-8000-00000000c205', 'syn-treatment-catalog-v1', '诊疗项目主数据',
                    '诊疗、护理、手术与物价项目映射', 'HAOEMR-TREATMENT',
                    '诊疗项目>心血管诊疗>冠状动脉介入>冠脉造影', '项目编码与物价版本同步；临床停用需医务处确认'),
                  ('018f0000-0000-7000-8000-00000000c206', 'syn-imaging-catalog-v1', '检查项目与部位主数据',
                    'RIS/PACS 检查、部位、体位与设备能力映射', 'DICOM-RIS-PROCEDURE',
                    '检查>影像>胸部>CT 平扫', '部位与 DICOM 编码冲突进入影像科人工复核'),
                  ('018f0000-0000-7000-8000-00000000c207', 'syn-supply-catalog-v1', '医用耗材主数据',
                    'SPD 耗材、规格、UDI 与供应商资质目录', 'UDI-SPD-SUPPLY',
                    '耗材>心血管介入>血管支架>药物洗脱支架', 'UDI 唯一；供应商资质过期后禁止新增领用'),
                  ('018f0000-0000-7000-8000-00000000c208', 'syn-organization-catalog-v1', '机构科室主数据',
                    '集团、医院、院区、科室与护理单元目录', 'HAOEMR-ORGANIZATION',
                    '江城大学附属医院>本部院区>呼吸与危重症医学科>一病区', '机构编码全院唯一；被业务引用后仅允许停用'),
                  ('018f0000-0000-7000-8000-00000000c209', 'syn-diagnosis-catalog-v1', '疾病诊断主数据',
                    '国家临床版疾病诊断与院内常用词映射', 'ICD-10-CN',
                    '循环系统疾病>高血压病>原发性高血压', '标准编码保持版本快照；院内别名须经病案室审核'),
                  ('018f0000-0000-7000-8000-00000000c20a', 'syn-surgery-catalog-v1', '手术操作主数据',
                    '手术操作、级别、切口和授权范围目录', 'ICD-9-CM-3-CN',
                    '手术>心血管介入>冠状动脉支架置入术', '手术级别与术者授权联动，停用前检查在途申请'),
                  ('018f0000-0000-7000-8000-00000000c20b', 'syn-nursing-catalog-v1', '护理项目主数据',
                    '护理级别、护理操作与评估量表目录', 'HAOEMR-NURSING',
                    '护理>基础护理>生命体征监测>一级护理', '护理项目与执行频次、资质要求联合校验'),
                  ('018f0000-0000-7000-8000-00000000c20c', 'syn-blood-product-catalog-v1', '血液制品主数据',
                    '血液成分、血型、储存条件与输注规则目录', 'HAOEMR-BLOOD',
                    '血液制品>红细胞>悬浮红细胞>A型Rh阳性', '血型相容性与有效期硬校验；历史批次不可覆盖'),
                  ('018f0000-0000-7000-8000-00000000c20d', 'syn-billing-catalog-v1', '医疗服务价格主数据',
                    '诊疗项目、医保支付类别和价格版本映射', 'NHC-MEDICAL-SERVICE',
                    '医疗服务>诊查费>主任医师门诊诊查费', '价格按生效日期版本化，结算后禁止追溯覆盖')
                ) as seed(config_id, config_key, display_name, description, code_system, hierarchy, import_policy)
                on conflict (tenant_id, config_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, created_by)
                select :tenant, seed.config_id::uuid, 'JOB', seed.config_key, seed.display_name,
                  jsonb_build_object(
                    'schema_version', 1, 'description', seed.description,
                    'schedule', seed.schedule, 'batch_size', seed.batch_size,
                    'retry_policy', seed.retry_policy,
                    'reconciliation_rule', seed.reconciliation_rule,
                    'notification_channels', string_to_array(seed.notification_channels, ','),
                    'channel_owner', seed.channel_owner),
                  'DRAFT', 1, 1, 'NOT_VALIDATED', '[]'::jsonb, 'DRAFT', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c221', 'syn-workforce-import-v1', '人员批量导入',
                    '人员、账号与岗位任期批量导入', 'MANUAL', 1650,
                    '仅重试 8 个隔离失败项；成功项保持幂等', '成功 1642，隔离 8，总数必须为 1650', '站内信,邮件', '人力资源处信息员'),
                  ('018f0000-0000-7000-8000-00000000c222', 'syn-lis-master-sync-v1', 'LIS 主数据同步',
                    '检验项目、单位和参考区间同步', '0 0/15 * * * *', 2106,
                    '冲突项转人工，不覆盖当前有效版本', '成功 2089，冲突 17，总数必须为 2106', '站内信,Webhook', '数据治理组'),
                  ('018f0000-0000-7000-8000-00000000c223', 'syn-archive-verify-v1', '病案长期验真',
                    '病案封包哈希与长期保存抽样恢复', '0 0 2 * * *', 186420,
                    '哈希异常立即隔离并创建安全事件', '校验 186420，通过 186420，异常 0', '站内信,短信,邮件', '病案统计室'),
                  ('018f0000-0000-7000-8000-00000000c224', 'syn-permission-review-v1', '权限季度复核通知',
                    '高权、闲置和离岗授权季度复核', '0 0 9 1 */3 *', 64,
                    '未送达通知每日重试，最多 3 日', '应复核 64，已送达 64，待关闭 7', '站内信,短信,邮件', '信息安全组'),
                  ('018f0000-0000-7000-8000-00000000c225', 'syn-identity-sync-v1', '统一身份增量同步',
                    '统一身份平台账号、离岗与锁定状态增量同步', '0 */10 * * * *', 500,
                    '失败账号隔离，下一批仅重试失败项', '拉取数、成功数、隔离数与游标必须闭合', '站内信,Webhook', '信息中心身份组'),
                  ('018f0000-0000-7000-8000-00000000c226', 'syn-terminology-refresh-v1', '国家术语版本更新检查',
                    '疾病诊断、手术操作和检验术语新版本检查', '0 0 3 * * 1', 5000,
                    '差异进入预发布区，不自动覆盖生效版本', '下载、校验、差异和待审批条目总数一致', '站内信,邮件', '数据治理组'),
                  ('018f0000-0000-7000-8000-00000000c227', 'syn-backup-restore-verify-v1', '备份可恢复性校验',
                    '每日备份校验和与隔离环境抽样恢复', '0 30 3 * * *', 1,
                    '恢复失败立即升级，不自动重复覆盖证据', '备份校验、恢复、业务抽检三项均通过', '站内信,短信,电话', '信息中心运维组'),
                  ('018f0000-0000-7000-8000-00000000c228', 'syn-emergency-access-review-v1', '紧急访问事后复核',
                    '聚合到期紧急访问并创建医务与安全复核任务', '0 0 8 * * *', 200,
                    '任务生成失败按访问记录幂等重试', '到期访问数等于已复核、待复核与异常数之和', '站内信,短信,邮件', '医务处与信息安全组')
                ) as seed(config_id, config_key, display_name, description, schedule, batch_size, retry_policy, reconciliation_rule, notification_channels, channel_owner)
                on conflict (tenant_id, config_id) do update set payload = excluded.payload,
                  display_name = excluded.display_name, updated_at = now(), row_version = config_item.row_version + 1
                where config_item.payload is distinct from excluded.payload
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, created_by, approved_by, published_at)
                select :tenant, seed.config_id::uuid, 'ROLE_CATALOG', seed.config_key,
                  seed.display_name,
                  jsonb_build_object(
                    'schema_version', 1, 'object_type', seed.object_type,
                    'parent_role_code', seed.parent_role_code,
                    'permission_summary', seed.permission_summary,
                    'scope', seed.scope, 'owner', seed.owner,
                    'description', seed.description),
                  'ACTIVE', 1, 1, 'VALID', '[]'::jsonb, 'APPROVED', :author, :approver,
                  now() - interval '30 days'
                from (values
                  ('018f0000-0000-7000-8000-00000000c241', 'CLINICIAN', '医生基础角色', 'ROLE', '—',
                    '普通病历查看、草拟与医嘱候选', '全院', '医务处', '临床医师基础权限模板'),
                  ('018f0000-0000-7000-8000-00000000c242', 'ROLE-IP-CARD', '心内科住院医生', 'ROLE', 'CLINICIAN',
                    '心内科住院病历、医嘱与交接班', '心内科病区', '心内科主任', '心内科住院岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c243', 'NURSE', '护士基础角色', 'ROLE', '—',
                    '护理记录、医嘱执行与病区交接', '全院', '护理部', '临床护理基础权限模板'),
                  ('018f0000-0000-7000-8000-00000000c244', 'SYSTEM_ADMIN', '系统管理员', 'ROLE', '—',
                    '发布系统配置、重置账户与管理机构目录', '本机构', '信息中心主任', '高权管理角色，必须季度复核'),
                  ('018f0000-0000-7000-8000-00000000c245', 'GROUP-CV-ONCALL', '心血管会诊值班组', 'WORKGROUP', '—',
                    '跨科会诊、危急值升级与值班任务分派', '全院', '医务处值班中心', '不直接赋权，仅表达协作分派'),
                  ('018f0000-0000-7000-8000-00000000c246', 'ATTENDING_PHYSICIAN', '主治医师', 'ROLE', 'CLINICIAN',
                    '病历审签、医嘱开立与诊疗计划调整', '本科室患者', '医务处', '临床主治医师岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c247', 'CHIEF_PHYSICIAN', '主任医师', 'ROLE', 'ATTENDING_PHYSICIAN',
                    '疑难病例决策、重大手术与三级审签', '全院授权患者', '医务处', '高级临床决策岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c248', 'REGISTERED_NURSE', '注册护士', 'ROLE', 'NURSE',
                    '护理评估、医嘱执行与护理记录', '本病区', '护理部', '临床护理岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c249', 'NURSE_MANAGER', '护士长', 'ROLE', 'REGISTERED_NURSE',
                    '护理排班、质量审核与病区管理', '本病区', '护理部', '病区护理管理岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c24a', 'PHARMACIST', '药师', 'ROLE', '—',
                    '处方审核、调剂与用药监护', '全院处方', '药学部', '药学专业岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c24b', 'LAB_TECHNICIAN', '检验技师', 'ROLE', '—',
                    '检验执行、结果审核与危急值上报', '检验科', '医学检验科', '医学检验专业岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c24c', 'RADIOLOGIST', '影像诊断医师', 'ROLE', 'CLINICIAN',
                    '影像检查审核、报告签发与危急结果上报', '影像科', '医学影像科', '影像诊断岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c24d', 'REGISTRAR', '挂号入院登记员', 'ROLE', '—',
                    '患者登记、预约挂号与入院办理', '门诊与住院登记窗口', '门诊部', '非临床登记岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c24e', 'MEDICAL_RECORDS', '病案管理员', 'ROLE', '—',
                    '病案归档、编码、质控与借阅管理', '全院归档病案', '病案统计室', '病案全生命周期管理角色'),
                  ('018f0000-0000-7000-8000-00000000c24f', 'CLINICAL_ADMIN', '临床业务管理员', 'ROLE', '—',
                    '机构科室、临床规则与业务字典维护', '本机构', '医务处', '临床业务配置管理角色'),
                  ('018f0000-0000-7000-8000-00000000c250', 'SECURITY_AUDITOR', '安全审计员', 'ROLE', '—',
                    '审计检索、权限复核与安全事件调查', '全院审计数据', '信息安全组', '只读安全审计岗位角色'),
                  ('018f0000-0000-7000-8000-00000000c251', 'RESEARCHER', '临床研究人员', 'ROLE', '—',
                    '审批范围内脱敏数据集查看与统计', '已批准研究项目', '科研处', '科研最小必要权限角色'),
                  ('018f0000-0000-7000-8000-00000000c252', 'GROUP-EMERGENCY-RESPONSE', '全院急救响应组', 'WORKGROUP', '—',
                    '院内急救、会诊与危急值升级任务协同', '三院区', '医务处总值班', '跨院区急救协作组，不直接赋予数据权限')
                ) as seed(config_id, config_key, display_name, object_type, parent_role_code,
                  permission_summary, scope, owner, description)
                on conflict (tenant_id, config_id) do update set payload = excluded.payload,
                  display_name = excluded.display_name, updated_at = now(), row_version = config_item.row_version + 1
                where config_item.payload is distinct from excluded.payload
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();
        jdbc.sql("""
                insert into config_item_revision(
                  tenant_id, config_id, revision_no, display_name, payload, schema_version,
                  status, validation_state, validation_errors, approval_state, changed_by,
                  change_reason)
                select item.tenant_id, item.config_id, item.row_version, item.display_name, item.payload,
                  item.schema_version, item.status, item.validation_state, item.validation_errors,
                  item.approval_state, :author, 'dev-synthetic complex acceptance fixture'
                from config_item item
                where item.tenant_id = :tenant and item.config_key like 'syn-%-v1'
                  and not exists (
                    select 1 from config_item_revision revision
                    where revision.tenant_id = item.tenant_id and revision.config_id = item.config_id
                      and revision.revision_no = item.row_version)
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
    }

    private void upsertSystemAdministrationRuntimeFixtures() {
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                select :tenant, seed.config_id::uuid, 'PARAMETER', seed.config_key, seed.display_name,
                  jsonb_build_object(
                    'schema_version', 1, 'description', seed.description, 'value_type', 'INTEGER',
                    'configured_value', seed.configured_value, 'scope', 'GLOBAL',
                    'inheritance', 'FACILITY -> ORGANIZATION -> GLOBAL',
                    'secret_reference', 'env://HAOEMR_PARAMETER_NOT_SECRET',
                    'effective_at', '2026-01-01T00:00:00+08:00'),
                  'ACTIVE', 1, 1, 'VALID', '[]'::jsonb, 'APPROVED', :approver,
                  now() - interval '30 days', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c601', 'auth-max-failed-attempts', '连续登录失败上限', '连续失败达到上限后锁定本地应急账号', '5'),
                  ('018f0000-0000-7000-8000-00000000c602', 'auth-lockout-minutes', '账号锁定分钟数', '锁定时间由发布参数直接控制登录服务', '15'),
                  ('018f0000-0000-7000-8000-00000000c603', 'auth-session-absolute-minutes', '会话绝对时限', '管理与临床会话最长存续分钟数', '480'),
                  ('018f0000-0000-7000-8000-00000000c604', 'auth-session-idle-minutes', '会话闲置时限', '超过闲置分钟数后服务端撤销会话', '15'),
                  ('018f0000-0000-7000-8000-00000000c605', 'auth-max-concurrent-sessions', '并发会话上限', '同一账号允许保留的有效会话数', '3')
                ) as seed(config_id, config_key, display_name, description, configured_value)
                on conflict (tenant_id, config_id) do update set display_name = excluded.display_name,
                  payload = excluded.payload, status = 'ACTIVE', validation_state = 'VALID',
                  validation_errors = '[]'::jsonb, approval_state = 'APPROVED', approved_by = :approver,
                  published_at = coalesce(config_item.published_at, excluded.published_at), updated_at = now()
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                select :tenant, seed.config_id::uuid, 'MASTER_DATA', seed.config_key, seed.display_name,
                  jsonb_build_object(
                    'schema_version', 1, 'description', seed.description, 'code_system', seed.code_system,
                    'hierarchy', string_to_array(seed.hierarchy, '>'),
                    'effective_period', '2026-01-01/2099-12-31',
                    'import_policy', '重复本地编码阻断；标准映射冲突进入治理队列；临床历史引用仅允许停用'),
                  'ACTIVE', 1, 1, 'VALID', '[]'::jsonb, 'APPROVED', :approver,
                  now() - interval '20 days', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c611', 'runtime-diagnosis-catalog-v1', '医保疾病诊断目录 · 当前在效', 'NHSA-DIAGNOSIS-2.0', '诊断>系统章节>病种', '国家医疗保障局医保疾病诊断分类与代码'),
                  ('018f0000-0000-7000-8000-00000000c612', 'runtime-procedure-catalog-v1', '医保手术操作目录 · 当前在效', 'NHSA-PROCEDURE-2.0', '手术操作>系统章节>项目', '国家医疗保障局医保手术操作分类与代码'),
                  ('018f0000-0000-7000-8000-00000000c613', 'runtime-drug-catalog-v1', '药品本位码与院内药品目录 · 当前在效', 'NMPA-DRUG-CODE', '药品>药理分类>通用名>规格', '国家药品监督管理局药品本位码与院内药事目录'),
                  ('018f0000-0000-7000-8000-00000000c614', 'runtime-lab-catalog-v1', '检验项目与单位目录 · 当前在效', 'WS-LAB-LOCAL', '检验>专业组>项目', '卫生行业标准与院内LIS映射'),
                  ('018f0000-0000-7000-8000-00000000c615', 'runtime-department-catalog-v1', '三级医院科室主数据 · 当前在效', 'HOSPITAL-DEPARTMENT', '医院>院区>科室>病区', '江城大学附属医院组织机构主数据')
                ) as seed(config_id, config_key, display_name, code_system, hierarchy, description)
                on conflict (tenant_id, config_id) do update set display_name = excluded.display_name,
                  payload = excluded.payload, status = 'ACTIVE', validation_state = 'VALID',
                  validation_errors = '[]'::jsonb, approval_state = 'APPROVED', approved_by = :approver,
                  published_at = coalesce(config_item.published_at, excluded.published_at), updated_at = now()
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();
        jdbc.sql("""
                insert into master_data_record(
                  tenant_id, record_id, config_id, code_system, national_code, local_code,
                  display_name, category_path, national_version, authoritative_source,
                  mapping_status, status, effective_from, attributes, created_by)
                select :tenant,
                  overlay(overlay(md5('runtime-master:' || seed.code_system || ':' || seed.local_code)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  seed.config_id::uuid, seed.code_system, seed.national_code, seed.local_code,
                  seed.display_name, seed.category_path, seed.national_version, seed.authoritative_source,
                  seed.mapping_status, 'ACTIVE', timestamptz '2026-01-01 00:00:00+08',
                  jsonb_build_object('maintaining_department', seed.owner, 'source', 'DEV_SYNTHETIC_TERTIARY_HOSPITAL'),
                  :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c611','NHSA-DIAGNOSIS-2.0','I25.10','DX-I25.10','冠状动脉粥样硬化性心脏病','诊断>循环系统疾病>缺血性心脏病','医保版2.0','国家医疗保障局','MATCHED','病案统计室'),
                  ('018f0000-0000-7000-8000-00000000c611','NHSA-DIAGNOSIS-2.0','J18.900','DX-J18.900','肺炎，病原体未特指','诊断>呼吸系统疾病>肺炎','医保版2.0','国家医疗保障局','MATCHED','病案统计室'),
                  ('018f0000-0000-7000-8000-00000000c611','NHSA-DIAGNOSIS-2.0','E11.900','DX-E11.900','2型糖尿病','诊断>内分泌疾病>糖尿病','医保版2.0','国家医疗保障局','MATCHED','病案统计室'),
                  ('018f0000-0000-7000-8000-00000000c611','NHSA-DIAGNOSIS-2.0',null,'DX-LOCAL-CHEST-PAIN','胸痛待查','诊断>症状与体征>院内扩展','院内2026.1','江城大学附属医院','LOCAL_ONLY','医务处'),
                  ('018f0000-0000-7000-8000-00000000c612','NHSA-PROCEDURE-2.0','00.6601','OP-PCI-001','经皮冠状动脉球囊扩张术','手术操作>心血管系统>介入治疗','医保版2.0','国家医疗保障局','MATCHED','病案统计室'),
                  ('018f0000-0000-7000-8000-00000000c612','NHSA-PROCEDURE-2.0','88.7200','OP-ECHO-001','经胸超声心动图检查','手术操作>影像检查>超声','医保版2.0','国家医疗保障局','MATCHED','超声医学科'),
                  ('018f0000-0000-7000-8000-00000000c613','NMPA-DRUG-CODE','86900000000001','DRUG-AMLO-5','苯磺酸氨氯地平片 5mg','药品>心血管系统用药>钙通道阻滞剂','国家药品本位码2026','国家药品监督管理局','MATCHED','药学部'),
                  ('018f0000-0000-7000-8000-00000000c613','NMPA-DRUG-CODE','86900000000002','DRUG-ASP-100','阿司匹林肠溶片 100mg','药品>血液系统用药>抗血小板药','国家药品本位码2026','国家药品监督管理局','MATCHED','药学部'),
                  ('018f0000-0000-7000-8000-00000000c613','NMPA-DRUG-CODE',null,'DRUG-COMPOUND-001','院内制剂复方护心颗粒','药品>院内制剂>心血管用药','院内制剂2026.1','江城大学附属医院药学部','UNMATCHED','药学部'),
                  ('018f0000-0000-7000-8000-00000000c614','WS-LAB-LOCAL','718-7','LAB-HGB','血红蛋白测定','检验>临床血液学>血常规','LIS映射2026.2','卫生行业标准与院内LIS','MATCHED','检验科'),
                  ('018f0000-0000-7000-8000-00000000c614','WS-LAB-LOCAL','2160-0','LAB-CREA','血清肌酐测定','检验>临床生化>肾功能','LIS映射2026.2','卫生行业标准与院内LIS','MATCHED','检验科'),
                  ('018f0000-0000-7000-8000-00000000c614','WS-LAB-LOCAL',null,'LAB-HS-TNI-NEW','高敏肌钙蛋白I（新平台）','检验>临床生化>心肌标志物','LIS映射2026.3-候选','院内LIS设备映射','CONFLICT','检验科'),
                  ('018f0000-0000-7000-8000-00000000c615','HOSPITAL-DEPARTMENT',null,'CARDIOLOGY','心血管内科','医院>本部院区>内科系统>心血管内科','组织版本2026.8','江城大学附属医院','LOCAL_ONLY','人力资源部'),
                  ('018f0000-0000-7000-8000-00000000c615','HOSPITAL-DEPARTMENT',null,'EMERGENCY','急诊医学科','医院>本部院区>门急诊系统>急诊医学科','组织版本2026.8','江城大学附属医院','LOCAL_ONLY','人力资源部'),
                  ('018f0000-0000-7000-8000-00000000c615','HOSPITAL-DEPARTMENT',null,'PHARMACY','药学部','医院>本部院区>医技系统>药学部','组织版本2026.8','江城大学附属医院','LOCAL_ONLY','人力资源部')
                ) as seed(config_id, code_system, national_code, local_code, display_name,
                  category_path, national_version, authoritative_source, mapping_status, owner)
                on conflict (tenant_id, code_system, local_code) do update set
                  national_code = excluded.national_code, display_name = excluded.display_name,
                  category_path = excluded.category_path, national_version = excluded.national_version,
                  authoritative_source = excluded.authoritative_source, mapping_status = excluded.mapping_status,
                  attributes = excluded.attributes, status = 'ACTIVE', effective_until = null, updated_at = now()
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                select :tenant, seed.config_id::uuid, 'JOB', seed.config_key, seed.display_name,
                  jsonb_build_object(
                    'schema_version', 1, 'description', seed.description, 'job_kind', seed.job_kind,
                    'schedule', seed.schedule, 'batch_size', 500,
                    'retry_policy', '只重试失败批次；1分钟/5分钟/15分钟；最多3次',
                    'reconciliation_rule', '处理总数必须等于成功数与失败数之和；治理问题单独闭环',
                    'notification_channels', jsonb_build_array('站内信','短信','邮件'),
                    'channel_owner', seed.owner),
                  'ACTIVE', 1, 1, 'VALID', '[]'::jsonb, 'APPROVED', :approver,
                  now() - interval '10 days', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c621','admin-governance-agent-v1','系统管理治理智能体','ADMIN_GOVERNANCE_AGENT','0 30 2 * * *','综合检查审计链、角色冲突、资质、主数据和事务事件','信息中心运维组'),
                  ('018f0000-0000-7000-8000-00000000c622','audit-chain-verify-v1','审计链完整性每日核验','AUDIT_CHAIN_VERIFY','0 0 3 * * *','逐条核对审计事件前序摘要连续性','信息安全组'),
                  ('018f0000-0000-7000-8000-00000000c623','role-conflict-review-v1','特权角色职责冲突复核','ROLE_CONFLICT_REVIEW','0 0 4 * * 1','复核系统管理、审计、授权和配置审批职责分离','信息安全组'),
                  ('018f0000-0000-7000-8000-00000000c624','credential-expiry-review-v1','执业资质到期复核','CREDENTIAL_EXPIRY_REVIEW','0 0 5 * * *','提前30天发现到期或已过期执业资质','医务处'),
                  ('018f0000-0000-7000-8000-00000000c625','master-data-reconcile-v1','主数据标准映射对账','MASTER_DATA_RECONCILIATION','0 0 1 * * *','发现未匹配、冲突和院内扩展编码','主数据管理组'),
                  ('018f0000-0000-7000-8000-00000000c626','notification-reconcile-v1','通知与事务事件对账','NOTIFICATION_RECONCILIATION','0 */5 * * * *','发现超过5分钟未发布的事务事件','信息中心运维组')
                ) as seed(config_id, config_key, display_name, job_kind, schedule, description, owner)
                on conflict (tenant_id, config_id) do update set display_name = excluded.display_name,
                  payload = excluded.payload, status = 'ACTIVE', validation_state = 'VALID',
                  validation_errors = '[]'::jsonb, approval_state = 'APPROVED', approved_by = :approver,
                  published_at = coalesce(config_item.published_at, excluded.published_at), updated_at = now()
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();
        jdbc.sql("""
                insert into administration_job_run(
                  tenant_id, run_id, config_id, job_kind, status, requested_by, idempotency_key,
                  attempt, processed_count, succeeded_count, failed_count, result,
                  error_code, error_message, started_at, finished_at, created_at, updated_at)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000c631','018f0000-0000-7000-8000-00000000c621','ADMIN_GOVERNANCE_AGENT','PARTIAL',:author,'fixture-admin-governance-20260830',1,126,124,2,
                    jsonb_build_object('summary','完成五类系统治理规则检查，2项问题进入人工闭环','finding_count',2,'engine','DETERMINISTIC_RULE_ENGINE'),null,null,now()-interval '1 day 35 minutes',now()-interval '1 day 31 minutes',now()-interval '1 day 36 minutes',now()-interval '1 day 31 minutes'),
                  (:tenant,'018f0000-0000-7000-8000-00000000c632','018f0000-0000-7000-8000-00000000c622','AUDIT_CHAIN_VERIFY','SUCCEEDED',:author,'fixture-audit-chain-20260830',1,8642,8642,0,
                    jsonb_build_object('summary','审计链前序摘要连续，未发现断链','verified_events',8642),null,null,now()-interval '1 day 3 hours',now()-interval '1 day 2 hours 57 minutes',now()-interval '1 day 3 hours 1 minute',now()-interval '1 day 2 hours 57 minutes'),
                  (:tenant,'018f0000-0000-7000-8000-00000000c633','018f0000-0000-7000-8000-00000000c625','MASTER_DATA_RECONCILIATION','FAILED',:author,'fixture-master-reconcile-20260829',3,15280,15220,60,
                    jsonb_build_object('summary','国家编码增量包校验失败，未发布到临床选择器','rollback_applied',true),'SOURCE_PACKAGE_CHECKSUM_MISMATCH','国家医保编码增量包校验和与登记值不一致，任务安全失败并保留当前在效版本',now()-interval '2 days 1 hour',now()-interval '2 days 45 minutes',now()-interval '2 days 1 hour 2 minutes',now()-interval '2 days 45 minutes'),
                  (:tenant,'018f0000-0000-7000-8000-00000000c634','018f0000-0000-7000-8000-00000000c624','CREDENTIAL_EXPIRY_REVIEW','SUCCEEDED',:author,'fixture-credential-review-20260831',1,21,21,0,
                    jsonb_build_object('summary','执业资质有效期复核完成','expiring_within_30_days',0),null,null,now()-interval '4 hours',now()-interval '3 hours 58 minutes',now()-interval '4 hours 1 minute',now()-interval '3 hours 58 minutes')
                on conflict (tenant_id, run_id) do update set
                  config_id = excluded.config_id, job_kind = excluded.job_kind, status = excluded.status,
                  processed_count = excluded.processed_count, succeeded_count = excluded.succeeded_count,
                  failed_count = excluded.failed_count, result = excluded.result, error_code = excluded.error_code,
                  error_message = excluded.error_message, started_at = excluded.started_at,
                  finished_at = excluded.finished_at, updated_at = excluded.updated_at
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into administration_governance_finding(
                  tenant_id, finding_id, run_id, finding_type, severity, resource_type, resource_id,
                  summary, recommendation, evidence, status, resolved_by, resolved_at, created_at, updated_at)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000c661','018f0000-0000-7000-8000-00000000c631','MASTER_DATA_MAPPING_CONFLICT','HIGH','MASTER_DATA_RECORD',null,
                    '检验项目高敏肌钙蛋白I存在新旧平台映射冲突','由检验科与主数据管理组确认设备平台、单位和参考区间后再发布映射',jsonb_build_object('local_code','LAB-HS-TNI-NEW','code_system','WS-LAB-LOCAL','owner','检验科'),'OPEN',null,null,now()-interval '1 day 31 minutes',now()-interval '1 day 31 minutes'),
                  (:tenant,'018f0000-0000-7000-8000-00000000c662','018f0000-0000-7000-8000-00000000c631','WORKGROUP_COVERAGE','MEDIUM','ADMINISTRATION_WORKGROUP',null,
                    '通知任务运维组缺少第二复核人','补充信息安全岗作为任务失败重试和外发通知的独立复核人',jsonb_build_object('workgroup_code','WG-SECURITY-GOVERNANCE','required_role','SECOND_REVIEWER'),'RESOLVED',:approver,now()-interval '20 hours',now()-interval '1 day 31 minutes',now()-interval '20 hours')
                on conflict (tenant_id, finding_id) do update set
                  severity = excluded.severity, summary = excluded.summary,
                  recommendation = excluded.recommendation, evidence = excluded.evidence,
                  status = excluded.status, resolved_by = excluded.resolved_by,
                  resolved_at = excluded.resolved_at, updated_at = excluded.updated_at
                """).param("tenant", TENANT_ID).param("approver", COLLABORATOR_USER_ID).update();
        UUID owner = syntheticAdministrationId("person:guoqiang.xiao");
        UUID auditor = syntheticAdministrationId("person:zimo.dong");
        UUID pharmacist = syntheticAdministrationId("person:qinghua.deng");
        jdbc.sql("""
                insert into administration_workgroup(
                  tenant_id, workgroup_id, workgroup_code, display_name, purpose,
                  organization_id, facility_id, owner_person_id, status, effective_from, created_by)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000c641', 'WG-SECURITY-GOVERNANCE', '信息安全治理工作组', '安全审计、权限冲突和高风险账户处置', :organization, :facility, :auditor, 'ACTIVE', now()-interval '180 days', :author),
                  (:tenant,'018f0000-0000-7000-8000-00000000c642', 'WG-MASTER-DATA', '医院主数据管理组', '国家标准、医保编码和院内主数据映射维护', :organization, :facility, :owner, 'ACTIVE', now()-interval '180 days', :author),
                  (:tenant,'018f0000-0000-7000-8000-00000000c643', 'WG-MEDICATION-SAFETY', '合理用药与药事治理组', '药品目录、处方规则和高风险用药治理', :organization, :facility, :pharmacist, 'ACTIVE', now()-interval '180 days', :author)
                on conflict (tenant_id, workgroup_id) do update set display_name = excluded.display_name,
                  purpose = excluded.purpose, owner_person_id = excluded.owner_person_id,
                  status = 'ACTIVE', effective_until = null, updated_at = now()
                """).param("tenant", TENANT_ID).param("organization", ORGANIZATION_ID)
                .param("facility", FACILITY_ID).param("owner", owner).param("auditor", auditor)
                .param("pharmacist", pharmacist).param("author", USER_ID).update();
        jdbc.sql("""
                insert into administration_workgroup_member(
                  tenant_id, member_id, workgroup_id, person_id, role_code, responsibility,
                  status, effective_from, created_by)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000c651','018f0000-0000-7000-8000-00000000c641',:auditor,'SECURITY_AUDITOR','审计链核验与安全事件复核','ACTIVE',now()-interval '180 days',:author),
                  (:tenant,'018f0000-0000-7000-8000-00000000c652','018f0000-0000-7000-8000-00000000c642',:owner,'CLINICAL_ADMIN','主数据变更发起与影响评估','ACTIVE',now()-interval '180 days',:author),
                  (:tenant,'018f0000-0000-7000-8000-00000000c653','018f0000-0000-7000-8000-00000000c643',:pharmacist,'PHARMACIST','药品目录与处方规则复核','ACTIVE',now()-interval '180 days',:author)
                on conflict (tenant_id, member_id) do update set responsibility = excluded.responsibility,
                  status = 'ACTIVE', effective_until = null, updated_at = now()
                """).param("tenant", TENANT_ID).param("owner", owner).param("auditor", auditor)
                .param("pharmacist", pharmacist).param("author", USER_ID).update();
    }

    private void upsertBusinessConfigurationFixtures() {
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                select source.tenant_id, seed.runtime_id::uuid, source.config_type, seed.runtime_key,
                  source.display_name || ' · 当前生效', source.payload,
                  'ACTIVE', 1, source.schema_version, 'VALID', '[]'::jsonb,
                  'APPROVED', :approver, now() - interval '2 days', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c101'::uuid, '018f0000-0000-7000-8000-00000000c321', 'runtime-workflow-consult-v1'),
                  ('018f0000-0000-7000-8000-00000000c102'::uuid, '018f0000-0000-7000-8000-00000000c322', 'runtime-form-record-v1'),
                  ('018f0000-0000-7000-8000-00000000c103'::uuid, '018f0000-0000-7000-8000-00000000c323', 'runtime-rule-safety-v1'),
                  ('018f0000-0000-7000-8000-00000000c104'::uuid, '018f0000-0000-7000-8000-00000000c324', 'runtime-scope-clinical-v1')
                ) as seed(source_id, runtime_id, runtime_key)
                join config_item source on source.tenant_id = :tenant and source.config_id = seed.source_id
                on conflict (tenant_id, config_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();
        jdbc.sql("""
                insert into capability_pack(
                  tenant_id, capability_pack_id, pack_code, pack_name, inherits_from, status)
                select :tenant, seed.capability_pack_id::uuid, seed.pack_code,
                  seed.pack_name, seed.inherits_from, 'ACTIVE'
                from (values
                  ('018f0000-0000-7000-8000-00000000c301', 'SYN-CORE-CLINICAL', '临床核心能力包', null),
                  ('018f0000-0000-7000-8000-00000000c302', 'SYN-TERTIARY-HOSPITAL', '三级医院增强能力包', 'SYN-CORE-CLINICAL'),
                  ('018f0000-0000-7000-8000-00000000c303', 'SYN-CARDIOLOGY', '心血管专科能力包', 'SYN-TERTIARY-HOSPITAL')
                ) as seed(capability_pack_id, pack_code, pack_name, inherits_from)
                on conflict (tenant_id, pack_code) do nothing
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, created_by)
                values (:tenant, '018f0000-0000-7000-8000-00000000c304'::uuid,
                  'CAPABILITY_PACK_COMPOSITION', 'composition-syn-cardiology', '心血管专科能力包 · 能力组合',
                  jsonb_build_object(
                    'schema_version', 2,
                    'capability_pack_id', '018f0000-0000-7000-8000-00000000c303',
                    'selected_modules', jsonb_build_array('CORE_PATIENT','CLINICAL_RECORD','ORDER_RESULT','DIGITAL_SIGNATURE','AUDIT_OUTBOX','SPECIALTY_CARDIOLOGY'),
                    'dependencies', jsonb_build_array(
                      jsonb_build_object('module','CLINICAL_RECORD','requires','CORE_PATIENT'),
                      jsonb_build_object('module','ORDER_RESULT','requires','CORE_PATIENT'),
                      jsonb_build_object('module','DIGITAL_SIGNATURE','requires','CLINICAL_RECORD'),
                      jsonb_build_object('module','AUDIT_OUTBOX','requires','CORE_PATIENT'),
                      jsonb_build_object('module','SPECIALTY_CARDIOLOGY','requires','CLINICAL_RECORD'),
                      jsonb_build_object('module','SPECIALTY_CARDIOLOGY','requires','ORDER_RESULT')),
                    'conflicts', jsonb_build_array(jsonb_build_object('left','LEGACY_EXPORT','right','SPECIALTY_CARDIOLOGY')),
                    'protected_modules', jsonb_build_array('CORE_PATIENT','CLINICAL_RECORD','DIGITAL_SIGNATURE','AUDIT_OUTBOX'),
                    'scope_overrides', jsonb_build_array(jsonb_build_object('scope','心血管内科','module','SPECIALTY_CARDIOLOGY','effect','ENABLE')),
                    'rating_impact', '专科闭环 · A',
                    'rollout_tasks', jsonb_build_array('依赖解析','合成病例回放','科室负责人联合签署')),
                  'DRAFT', 1, 2, 'NOT_VALIDATED', '[]'::jsonb, 'DRAFT', :author)
                on conflict (tenant_id, config_id) do update set payload = excluded.payload,
                  display_name = excluded.display_name, updated_at = now(), row_version = config_item.row_version + 1
                where config_item.payload is distinct from excluded.payload
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into capability_pack_release(
                  tenant_id, release_id, capability_pack_id, release_version, lifecycle_status,
                  canary_started_at, promoted_at, retired_at, rollback_reason,
                  released_by, released_at, row_version)
                select :tenant, seed.release_id::uuid, seed.capability_pack_id::uuid,
                  seed.release_version, seed.lifecycle_status,
                  seed.canary_started_at, seed.promoted_at, null, null,
                  :author, seed.released_at, seed.row_version
                from (values
                  ('018f0000-0000-7000-8000-00000000c311', '018f0000-0000-7000-8000-00000000c301',
                    '2026.8.1', 'ACTIVE', now() - interval '60 days', now() - interval '45 days',
                    now() - interval '75 days', 3::bigint),
                  ('018f0000-0000-7000-8000-00000000c312', '018f0000-0000-7000-8000-00000000c302',
                    '2026.8.2-rc1', 'CANARY', now() - interval '7 days', null,
                    now() - interval '14 days', 2::bigint),
                  ('018f0000-0000-7000-8000-00000000c313', '018f0000-0000-7000-8000-00000000c303',
                    '2026.9.0-draft', 'DRAFT', null, null,
                    now() - interval '3 days', 1::bigint)
                ) as seed(release_id, capability_pack_id, release_version, lifecycle_status,
                  canary_started_at, promoted_at, released_at, row_version)
                on conflict (tenant_id, release_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into specialty_pack_release(
                  tenant_id, specialty_pack_release_id, pack_code, semantic_version,
                  content_hash, manifest, lifecycle_status, compatibility_range, created_by)
                select :tenant, seed.release_id::uuid, seed.pack_code, seed.semantic_version,
                  seed.content_hash, seed.manifest::jsonb, 'ACTIVE', seed.compatibility::jsonb, :author
                from (values
                  ('018f0000-0000-7000-8000-00000000c401', 'SYN-SP-GENERAL', '1.2.0',
                    '1111111111111111111111111111111111111111111111111111111111111111',
                    '{"scope":"GENERAL_MEDICINE","modules":["record","order","result"]}',
                    '{"core":">=2026.8.0"}'),
                  ('018f0000-0000-7000-8000-00000000c402', 'SYN-SP-CARDIOLOGY', '2.0.1',
                    '2222222222222222222222222222222222222222222222222222222222222222',
                    '{"scope":"CARDIOLOGY","modules":["ecg","critical-value","consult"]}',
                    '{"core":">=2026.8.0","terminology":"2026.1"}'),
                  ('018f0000-0000-7000-8000-00000000c403', 'SYN-SP-PEDIATRICS', '1.0.0',
                    '3333333333333333333333333333333333333333333333333333333333333333',
                    '{"scope":"PEDIATRICS","modules":["weight-dose","growth-chart"]}',
                    '{"core":">=2026.8.0"}')
                ) as seed(release_id, pack_code, semantic_version, content_hash, manifest, compatibility)
                on conflict (tenant_id, specialty_pack_release_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into department_support_assessment(
                  tenant_id, department_support_assessment_id, facility_id, department_id,
                  clinical_scope_code, support_level, pack_release_id, evidence_bundle_hash,
                  missing_safety_gates, assessed_by, assessed_at, expires_at, row_version)
                select :tenant, seed.assessment_id::uuid, :facility, :department,
                  seed.clinical_scope_code, seed.support_level, seed.pack_release_id::uuid,
                  seed.evidence_bundle_hash, seed.missing_safety_gates,
                  :author, now() - interval '1 day', seed.expires_at, 1
                from (values
                  ('018f0000-0000-7000-8000-00000000c411', 'GENERAL_MEDICINE', 'GENERAL_AVAILABLE',
                    '018f0000-0000-7000-8000-00000000c401',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    '{}'::text[], now() + interval '180 days'),
                  ('018f0000-0000-7000-8000-00000000c412', 'CARDIOLOGY', 'BASIC_CLOSED_LOOP',
                    '018f0000-0000-7000-8000-00000000c402',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    '{}'::text[], now() + interval '120 days'),
                  ('018f0000-0000-7000-8000-00000000c413', 'PEDIATRICS', 'PACK_PENDING',
                    '018f0000-0000-7000-8000-00000000c403', null,
                    array['PEDIATRIC_DRUG_CATALOG', 'GROWTH_STANDARD']::text[], null),
                  ('018f0000-0000-7000-8000-00000000c414', 'MENTAL_HEALTH', 'UNSUPPORTED',
                    null, null, array['CONSENT_TEMPLATE', 'RESTRICTED_DATA_REVIEW']::text[], null)
                ) as seed(assessment_id, clinical_scope_code, support_level, pack_release_id,
                  evidence_bundle_hash, missing_safety_gates, expires_at)
                on conflict (tenant_id, department_support_assessment_id) do nothing
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID)
                .param("department", SYNTHETIC_DEPARTMENT_ID).param("author", USER_ID).update();
        upsertTertiaryHospitalBusinessConfiguration();
    }

    private void upsertTertiaryHospitalBusinessConfiguration() {
        for (TertiaryBusinessConfigurationCatalog.ConfigurationSeed seed
                : TertiaryBusinessConfigurationCatalog.configurations()) {
            String payload = writeJson(seed.payload());
            jdbc.sql("""
                    update config_item set display_name = :name, payload = cast(:payload as jsonb),
                      schema_version = 2, updated_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and config_id = :config
                      and (display_name is distinct from :name or payload is distinct from cast(:payload as jsonb))
                    """).param("name", seed.displayName()).param("payload", payload)
                    .param("tenant", TENANT_ID).param("config", seed.draftId()).update();
            jdbc.sql("""
                    insert into config_item(
                      tenant_id, config_id, config_type, config_key, display_name, payload,
                      status, row_version, schema_version, validation_state, validation_errors,
                      approval_state, approved_by, published_at, created_by)
                    values (:tenant, :config, :type, :key, :name, cast(:payload as jsonb),
                      'ACTIVE', 1, 2, 'VALID', '[]'::jsonb, 'APPROVED', :approver,
                      now() - interval '7 days', :author)
                    on conflict (tenant_id, config_id) do update set
                      display_name = excluded.display_name, payload = excluded.payload,
                      status = 'ACTIVE', schema_version = 2, validation_state = 'VALID',
                      validation_errors = '[]'::jsonb, approval_state = 'APPROVED',
                      approved_by = excluded.approved_by,
                      published_at = coalesce(config_item.published_at, excluded.published_at),
                      updated_at = now(), row_version = config_item.row_version + 1
                    where config_item.display_name is distinct from excluded.display_name
                       or config_item.payload is distinct from excluded.payload
                       or config_item.status <> 'ACTIVE'
                    """).param("tenant", TENANT_ID).param("config", seed.runtimeId())
                    .param("type", seed.configType()).param("key", seed.runtimeKey())
                    .param("name", seed.displayName() + " · 当前生效").param("payload", payload)
                    .param("approver", COLLABORATOR_USER_ID).param("author", USER_ID).update();
        }

        for (TertiaryBusinessConfigurationCatalog.CapabilityPackSeed seed
                : TertiaryBusinessConfigurationCatalog.capabilityPacks()) {
            jdbc.sql("""
                    insert into capability_pack(
                      tenant_id, capability_pack_id, pack_code, pack_name, inherits_from, status)
                    values (:tenant, :pack, :code, :name, :inherits, 'ACTIVE')
                    on conflict (tenant_id, capability_pack_id) do update set
                      pack_name = excluded.pack_name, inherits_from = excluded.inherits_from,
                      status = 'ACTIVE', updated_at = now()
                    where (capability_pack.pack_name, capability_pack.inherits_from, capability_pack.status)
                      is distinct from (excluded.pack_name, excluded.inherits_from, 'ACTIVE')
                    """).param("tenant", TENANT_ID).param("pack", seed.packId())
                    .param("code", seed.packCode()).param("name", seed.packName())
                    .param("inherits", seed.inheritsFrom()).update();
            String compositionPayload = writeJson(
                    TertiaryBusinessConfigurationCatalog.compositionPayload(seed));
            String compositionKey = "composition-" + seed.packCode().toLowerCase();
            Long activeCompositionCount = jdbc.sql("""
                    select count(*) from config_item
                    where tenant_id = :tenant
                      and config_type = 'CAPABILITY_PACK_COMPOSITION'
                      and config_key = :key and status = 'ACTIVE'
                    """).param("tenant", TENANT_ID).param("key", compositionKey)
                    .query(Long.class).single();
            if (activeCompositionCount > 0) {
                jdbc.sql("""
                        update config_item set display_name = :name, payload = cast(:payload as jsonb),
                          schema_version = 2, validation_state = 'VALID', validation_errors = '[]'::jsonb,
                          approval_state = 'APPROVED', approved_by = :approver,
                          published_at = coalesce(published_at, now() - interval '7 days'),
                          updated_at = now(), row_version = row_version + 1
                        where tenant_id = :tenant
                          and config_type = 'CAPABILITY_PACK_COMPOSITION'
                          and config_key = :key and status = 'ACTIVE'
                          and (display_name is distinct from :name
                            or payload is distinct from cast(:payload as jsonb)
                            or schema_version <> 2 or validation_state <> 'VALID'
                            or approval_state <> 'APPROVED')
                        """).param("tenant", TENANT_ID).param("key", compositionKey)
                        .param("name", seed.packName() + " · 能力组合")
                        .param("payload", compositionPayload).param("approver", COLLABORATOR_USER_ID)
                        .update();
            } else {
                jdbc.sql("""
                        insert into config_item(
                          tenant_id, config_id, config_type, config_key, display_name, payload,
                          status, row_version, schema_version, validation_state, validation_errors,
                          approval_state, approved_by, published_at, created_by)
                        values (:tenant, :config, 'CAPABILITY_PACK_COMPOSITION', :key, :name,
                          cast(:payload as jsonb), 'ACTIVE', 1, 2, 'VALID', '[]'::jsonb,
                          'APPROVED', :approver, now() - interval '7 days', :author)
                        on conflict (tenant_id, config_id) do update set
                          config_type = excluded.config_type, config_key = excluded.config_key,
                          display_name = excluded.display_name, payload = excluded.payload,
                          status = 'ACTIVE', schema_version = 2,
                          validation_state = 'VALID', validation_errors = '[]'::jsonb,
                          approval_state = 'APPROVED', approved_by = excluded.approved_by,
                          published_at = coalesce(config_item.published_at, excluded.published_at),
                          updated_at = now(), row_version = config_item.row_version + 1
                        where config_item.config_type is distinct from excluded.config_type
                           or config_item.config_key is distinct from excluded.config_key
                           or config_item.display_name is distinct from excluded.display_name
                           or config_item.payload is distinct from excluded.payload
                           or config_item.status <> 'ACTIVE'
                        """).param("tenant", TENANT_ID).param("config", seed.compositionId())
                        .param("key", compositionKey).param("name", seed.packName() + " · 能力组合")
                        .param("payload", compositionPayload).param("approver", COLLABORATOR_USER_ID)
                        .param("author", USER_ID).update();
            }
            jdbc.sql("""
                    insert into capability_pack_release(
                      tenant_id, release_id, capability_pack_id, release_version, lifecycle_status,
                      canary_started_at, promoted_at, released_by, released_at, row_version)
                    select :tenant, :release, :pack, '2026.8.26-tertiary', 'ACTIVE',
                      now() - interval '30 days', now() - interval '21 days',
                      :author, now() - interval '45 days', 3
                    where not exists (
                      select 1 from capability_pack_release current
                      where current.tenant_id = :tenant and current.capability_pack_id = :pack
                        and current.lifecycle_status = 'ACTIVE')
                    on conflict (tenant_id, release_id) do nothing
                    """).param("tenant", TENANT_ID).param("release", seed.releaseId())
                    .param("pack", seed.packId()).param("author", USER_ID).update();
        }

        for (TertiaryBusinessConfigurationCatalog.SpecialtySeed seed
                : TertiaryBusinessConfigurationCatalog.specialties()) {
            String manifest = writeJson(Map.of(
                    "scope", seed.scopeCode(), "display_name", seed.displayName(),
                    "modules", seed.modules(), "synthetic_cases", 8,
                    "release_profile", "tertiary-hospital-closed-loop"));
            jdbc.sql("""
                    insert into specialty_pack_release(
                      tenant_id, specialty_pack_release_id, pack_code, semantic_version,
                      content_hash, manifest, lifecycle_status, compatibility_range, created_by)
                    values (:tenant, :release, :code, :version, :hash, cast(:manifest as jsonb),
                      'ACTIVE', '{"core":">=2026.8.0","evidence":"tertiary-v1"}'::jsonb, :author)
                    on conflict (tenant_id, specialty_pack_release_id) do update set
                      pack_code = excluded.pack_code, semantic_version = excluded.semantic_version,
                      content_hash = excluded.content_hash, manifest = excluded.manifest,
                      lifecycle_status = 'ACTIVE', compatibility_range = excluded.compatibility_range
                    where (specialty_pack_release.pack_code, specialty_pack_release.semantic_version,
                      specialty_pack_release.content_hash, specialty_pack_release.manifest,
                      specialty_pack_release.lifecycle_status, specialty_pack_release.compatibility_range)
                      is distinct from (excluded.pack_code, excluded.semantic_version,
                        excluded.content_hash, excluded.manifest, 'ACTIVE', excluded.compatibility_range)
                    """).param("tenant", TENANT_ID).param("release", seed.releaseId())
                    .param("code", seed.packCode()).param("version", seed.semanticVersion())
                    .param("hash", seed.evidenceHash())
                    .param("manifest", manifest).param("author", USER_ID).update();
            jdbc.sql("""
                    insert into department_support_assessment(
                      tenant_id, department_support_assessment_id, facility_id, department_id,
                      clinical_scope_code, support_level, pack_release_id, evidence_bundle_hash,
                      missing_safety_gates, assessed_by, assessed_at, expires_at, row_version)
                    values (:tenant, :assessment, :facility, :department, :scope, :level,
                      :release, :evidence, '{}'::text[], :author, now() - interval '2 days',
                      now() + interval '365 days', 1)
                    on conflict (tenant_id, department_support_assessment_id) do update set
                      facility_id = excluded.facility_id, department_id = excluded.department_id,
                      clinical_scope_code = excluded.clinical_scope_code,
                      support_level = excluded.support_level, pack_release_id = excluded.pack_release_id,
                      evidence_bundle_hash = excluded.evidence_bundle_hash,
                      missing_safety_gates = '{}'::text[], expires_at = excluded.expires_at,
                      assessed_at = excluded.assessed_at, row_version = department_support_assessment.row_version + 1
                    where (department_support_assessment.facility_id,
                      department_support_assessment.department_id,
                      department_support_assessment.clinical_scope_code,
                      department_support_assessment.support_level,
                      department_support_assessment.pack_release_id,
                      department_support_assessment.evidence_bundle_hash,
                      department_support_assessment.missing_safety_gates)
                      is distinct from (excluded.facility_id, excluded.department_id,
                        excluded.clinical_scope_code, excluded.support_level,
                        excluded.pack_release_id, excluded.evidence_bundle_hash, '{}'::text[])
                    """).param("tenant", TENANT_ID).param("assessment", seed.assessmentId())
                    .param("facility", FACILITY_ID).param("department", seed.departmentId())
                    .param("scope", seed.scopeCode()).param("level", seed.supportLevel())
                    .param("release", seed.releaseId()).param("evidence", seed.evidenceHash())
                    .param("author", USER_ID).update();
        }

        jdbc.sql("""
                insert into config_item_revision(
                  tenant_id, config_id, revision_no, display_name, payload, schema_version,
                  status, validation_state, validation_errors, approval_state, changed_by, change_reason)
                select item.tenant_id, item.config_id, item.row_version, item.display_name, item.payload,
                  item.schema_version, item.status, item.validation_state, item.validation_errors,
                  item.approval_state, :author, 'tertiary hospital synthetic configuration baseline'
                from config_item item
                where item.tenant_id = :tenant
                  and (item.config_key like 'runtime-%' or item.config_key like 'composition-syn-%')
                  and not exists (
                    select 1 from config_item_revision revision
                    where revision.tenant_id = item.tenant_id and revision.config_id = item.config_id
                      and revision.revision_no = item.row_version)
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
    }

    private void upsertTertiaryDataCenterFixtures() {
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                select :tenant, seed.config_id::uuid, seed.config_type, seed.config_key,
                  seed.display_name, cast(seed.payload as jsonb) || jsonb_build_object(
                    'fixture_source', 'tertiary-business-generator-v3',
                    'generation_method', 'DETERMINISTIC_SEEDED',
                    'generator_version', 'tertiary-business-cn-v3',
                    'default_record_count', 36,
                    'record_count_range', jsonb_build_array(12, 200),
                    'contains_real_phi', false),
                  'ACTIVE', 1, 1, 'VALID',
                  '[]'::jsonb, 'APPROVED', :approver, now() - interval '14 days', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000f101','INTEGRATION_CONNECTOR','lis-core-prod','LIS-CORE · 生产检验系统','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"全院检验申请、标本、报告和危急值回传主连接器","system_type":"LIS","protocol":"HL7 v2.5.1 / FHIR R4","capabilities":["ADT","ORM","ORU","危急值"],"endpoint":"医疗专网 10.20.4.18","secret_reference":"file://secrets/integration/lis-core-prod","timeout_retry":"5s / 3 次 / 指数退避","circuit_breaker":"60s / 人工降级","connector_version":"v3.2.1"}'),
                  ('018f0000-0000-7000-8000-00000000f102','INTEGRATION_CONNECTOR','pacs-a-prod','PACS-A · 影像归档与调阅','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"RIS/PACS 报告、Study 和影像调阅连接器","system_type":"PACS","protocol":"DICOMweb / DIMSE","capabilities":["QIDO","WADO","STOW","结构化报告"],"endpoint":"影像专网 10.20.8.31","secret_reference":"file://secrets/integration/pacs-a-prod","timeout_retry":"5s / 3 次 / 指数退避","circuit_breaker":"60s / 报告可用图像降级","connector_version":"v5.8.2"}'),
                  ('018f0000-0000-7000-8000-00000000f103','INTEGRATION_CONNECTOR','his-billing-prod','HIS-BILL · 费用医保交换','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"院内费用、医保结算与对账消息主连接器","system_type":"HIS","protocol":"REST / MQ","capabilities":["费用明细","医保预结算","结算回执"],"endpoint":"核心区 MQ-HIS-02","secret_reference":"file://secrets/integration/his-billing-prod","timeout_retry":"8s / 2 次 / 幂等业务键","circuit_breaker":"120s / 人工结算队列","connector_version":"v4.6.0"}'),
                  ('018f0000-0000-7000-8000-00000000f104','INTEGRATION_CONNECTOR','ca-sign-prod','CA-SIGN · 电子签名时间戳','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"病历签名、验签和可信时间戳服务","system_type":"CA","protocol":"HTTPS / 厂商 SDK","capabilities":["签名","验签","时间戳"],"endpoint":"DMZ proxy / CA-01","secret_reference":"file://secrets/ca-client.p12","timeout_retry":"3s / 1 次 / 禁止重复签名","circuit_breaker":"30s / 阻断签署终态","connector_version":"v2.9.4"}'),
                  ('018f0000-0000-7000-8000-00000000f105','INTEGRATION_CONNECTOR','region-hie-prod','REGION-HIE · 区域平台','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"区域平台 CDA 文档上传与回执对账","system_type":"HIE","protocol":"CDA R2 / FHIR R4","capabilities":["文档上传","回执","区域调阅"],"endpoint":"政务专网 HIE-GW-01","secret_reference":"file://secrets/integration/region-hie-prod","timeout_retry":"15s / 5 次 / 延迟队列","circuit_breaker":"300s / 院内流程继续","connector_version":"v3.1.7"}'),
                  ('018f0000-0000-7000-8000-00000000f111','DEVICE_CATALOG','card-monitor-01','CARD-MON-01 · 心电监护仪','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"心内科一病区床旁多参数监护","device_type":"MONITOR","manufacturer_model":"迈瑞 BeneVision N15","department":"心血管内科一病区","gateway":"GW-BEDSIDE-01 / VLAN-MED-12","standard_interface":"IEEE 11073 / HL7 ORU","calibration_due":"2027-02-28","clock_offset_seconds":2,"binding_policy":"腕带 + 床位双标识；解绑需责任护士确认"}'),
                  ('018f0000-0000-7000-8000-00000000f112','DEVICE_CATALOG','icu-vent-07','ICU-VENT-07 · 重症呼吸机','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"ICU 呼吸参数与告警事件采集","device_type":"VENTILATOR","manufacturer_model":"Drager Evita V600","department":"重症医学科","gateway":"GW-ICU-02 / VLAN-ICU-08","standard_interface":"ISO/IEEE 11073 / IHE PCD","calibration_due":"2027-01-15","clock_offset_seconds":4,"binding_policy":"腕带 + 床位 + 设备三标识；转床自动阻断旧绑定"}'),
                  ('018f0000-0000-7000-8000-00000000f113','DEVICE_CATALOG','pump-a-118','PUMP-A-118 · 智能输注泵','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"高警示药品输注参数与完成事件","device_type":"INFUSION_PUMP","manufacturer_model":"BD Alaris System","department":"急诊抢救区","gateway":"GW-ER-01 / WIFI-MED-IOT","standard_interface":"IHE PCD / FHIR DeviceMetric","calibration_due":"2026-12-31","clock_offset_seconds":1,"binding_policy":"患者腕带 + 医嘱双核对；高警示药双人确认"}'),
                  ('018f0000-0000-7000-8000-00000000f114','DEVICE_CATALOG','ct-01','CT-01 · 128 排 CT','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"影像设备身份、检查执行和剂量报告","device_type":"IMAGING","manufacturer_model":"Siemens SOMATOM Definition Edge","department":"医学影像科","gateway":"DICOM-GW-01 / VLAN-PACS","standard_interface":"DICOM MWL / MPPS / RDSR","calibration_due":"2027-03-20","clock_offset_seconds":3,"binding_policy":"检查申请号 + 患者腕带核对；侧别不一致阻断"}'),
                  ('018f0000-0000-7000-8000-00000000f121','RESEARCH_PROJECT','res-2026-014','RES-2026-014 · 真实世界高血压用药与控制','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"成年高血压门诊患者用药与 180 天血压控制研究","project_type":"OBSERVATIONAL","principal_investigator":"周教授","registry_number":"MRR-2026-001842","ethics_approval":"IRB-2026-119","approved_purpose":"高血压真实世界治疗结局分析","data_scope":["门诊病历","处方","检验","生命体征"],"member_count":8,"expires_at":"2027-07-31"}'),
                  ('018f0000-0000-7000-8000-00000000f122','RESEARCH_PROJECT','res-2026-021','RES-2026-021 · 心衰再入院风险队列','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"心衰出院患者 30 天再入院风险研究","project_type":"OBSERVATIONAL","principal_investigator":"刘主任","registry_number":"MRR-2026-002113","ethics_approval":"IRB-2026-184","approved_purpose":"心衰再入院风险因素分析","data_scope":["住院病历","出院记录","检验","随访"],"member_count":6,"expires_at":"2027-05-31"}'),
                  ('018f0000-0000-7000-8000-00000000f131','INTEGRATION_INCIDENT','tr-882177','TR-882177 · WADO-RS 调阅超时','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"PACS 图像只读调阅超时，报告仍可用","trace_id":"TR-882177","direction":"EMR_TO_PACS","event_type":"WADO-RS","business_object":"Study 8821","result":"TIMEOUT","latency":"5.0s","clinical_impact":"报告可用，图像暂不可用；临床页明确显示降级","retry_policy":"沿用父 Trace 和 Study UID 幂等重试，三次失败转影像科人工队列"}'),
                  ('018f0000-0000-7000-8000-00000000f132','INTEGRATION_INCIDENT','tr-882151','TR-882151 · 区域 CDA 待回执','{"schema_version":1,"fixture_source":"tertiary-data-center-v1","hospital_level":"三级甲等","organization":"江城大学附属医院","description":"区域平台 CDA 上传后回执延迟","trace_id":"TR-882151","direction":"EMR_TO_HIE","event_type":"CDA_UPLOAD","business_object":"CDA-21018","result":"PENDING_ACK","latency":"12m","clinical_impact":"不影响院内签署，区域共享状态保持待确认","retry_policy":"回执查询只读重试；上传使用文档哈希防止重复副作用"}')
                ) as seed(config_id, config_type, config_key, display_name, payload)
                on conflict (tenant_id, config_type, config_key) where status = 'ACTIVE' do update set
                  display_name = excluded.display_name, payload = excluded.payload,
                  validation_state = 'VALID', validation_errors = '[]'::jsonb,
                  approval_state = 'APPROVED', approved_by = excluded.approved_by,
                  published_at = case when config_item.status = 'ARCHIVED' then null
                    else coalesce(config_item.published_at, excluded.published_at) end,
                  updated_at = now(), row_version = config_item.row_version + 1
                where (config_item.display_name is distinct from excluded.display_name
                   or config_item.payload is distinct from excluded.payload)
                  and coalesce(config_item.payload->>'fixture_source', '')
                    <> 'tertiary-data-center-business-v2'
                """).param("tenant", TENANT_ID).param("approver", COLLABORATOR_USER_ID)
                .param("author", USER_ID).update();

        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                select :tenant, seed.config_id::uuid, 'MOCK_INTERFACE_PROFILE', seed.config_key,
                  seed.display_name, cast(seed.payload as jsonb) || jsonb_build_object(
                    'fixture_source', 'tertiary-business-generator-v3',
                    'generation_method', 'DETERMINISTIC_SEEDED',
                    'generator_version', 'tertiary-business-cn-v3',
                    'default_record_count', 36,
                    'record_count_range', jsonb_build_array(12, 200),
                    'contains_real_phi', false,
                    'organization_code', cast(:tenant as text),
                    'facility_code', 'JC-BENBU',
                    'production_adapter_state', 'SYNTHETIC_ONLY',
                    'china_standard_profile', jsonb_build_object(
                      'hospital_platform', 'WS/T 846.1-846.11—2024',
                      'hospital_platform_function', 'WS/T 847—2024',
                      'medical_record_management', '医疗机构病历管理规定（2013年版）',
                      'data_minimization', true, 'cross_border_allowed', false),
                    'agent_policy', jsonb_build_object(
                      'mode', 'RULE_GUARDED', 'clinical_write_allowed', false,
                      'human_review_on_degraded', true, 'block_on_identity_mismatch', true),
                    'critical_value_policy', case when cast(seed.payload as jsonb)->>'interface_code' = 'LIS_RESULTS'
                      then '{"policy_code":"JC-LAB-CRITICAL-2026-01","policy_name":"院级成人检验危急值演练目录","requires_recheck":true,"requires_reporter_receiver_ack":true,"requires_closed_loop":true,"thresholds":{"WBC":{"low":1.0,"high":30.0},"HGB":{"low":50.0,"high":200.0},"PLT":{"low":20.0,"high":1000.0},"K":{"low":2.5,"high":6.5},"NA":{"low":120.0,"high":160.0},"CREA":{"high":707.0},"GLU":{"low":2.8,"high":22.2}}}'::jsonb
                      else '{}'::jsonb end,
                    'documentation_version', 'v2.0 / 2026-08-31'),
                  'ACTIVE', 1, 1, 'VALID',
                  '[]'::jsonb, 'APPROVED', :approver, now() - interval '10 days', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000f801','admin-auth-tertiary','全院 OIDC 与 MFA 认证基线','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"admin-auth","interface_code":"IDP_AUTHENTICATE","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"临床、护理、医技和管理人员统一认证与高风险操作再认证","default_entity":"018f0000-0000-7000-8000-00000000aa04","default_scenario":"SUCCESS","owner_department":"信息中心身份安全组","operating_window":"7×24 小时；变更窗口周三 22:00–23:30","timeout_ms":3000,"retry_limit":1,"manual_fallback":"保留本地应急账号，高风险操作继续阻断并记录审计","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f802','ai-capture-tertiary','门诊语音转写与医生逐句确认','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"ai-capture","interface_code":"DICTATION_ASR","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"门诊音频引用、说话人分段、置信度与医生确认闭环","default_entity":"synthetic://dictation/opd-001","default_scenario":"SUCCESS","owner_department":"医务处门诊管理组","operating_window":"门诊时段 07:30–18:00","timeout_ms":8000,"retry_limit":2,"manual_fallback":"转人工录入，未确认句永不进入病历","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f803','model-connection-tertiary','临床模型 Provider 数据边界','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"model-connection","interface_code":"MODEL_PROVIDER","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"医疗模型兼容接口、引用证据、数据驻留与超时边界","default_entity":"MedBase-L-2.1","default_scenario":"SUCCESS","owner_department":"AI 治理委员会","operating_window":"7×24 小时；发布窗口周二 21:00–22:00","timeout_ms":12000,"retry_limit":1,"manual_fallback":"转人工复核，无引用结果默认不可采纳","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f804','model-routing-tertiary','临床摘要主备模型路由','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"model-routing","interface_code":"MODEL_PROVIDER","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"按数据等级选择主备模型，降级或不可用时转人工","default_entity":"clinical-summary-primary","default_scenario":"SUCCESS","owner_department":"AI 平台运行组","operating_window":"7×24 小时","timeout_ms":10000,"retry_limit":1,"manual_fallback":"主备均不可用时停止生成并转人工摘要","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f805','devices-tertiary','全院医疗设备可信绑定基线','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"devices","interface_code":"DEVICE_GATEWAY","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"设备身份、患者双标识绑定、校准与时钟偏移基线","default_entity":"BEDSIDE-MONITOR-01","default_scenario":"SUCCESS","owner_department":"医学工程处","operating_window":"7×24 小时","timeout_ms":3000,"retry_limit":3,"manual_fallback":"绑定失败停止入患者视图并联系设备值班","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f806','device-monitoring-tertiary','ICU 与病区设备遥测告警','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"device-monitoring","interface_code":"DEVICE_GATEWAY","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"床旁遥测趋势、质量标记、阈值告警与责任人确认","default_entity":"BEDSIDE-MONITOR-01","default_scenario":"SUCCESS","owner_department":"信息中心 IoMT 运维组","operating_window":"7×24 小时","timeout_ms":2000,"retry_limit":3,"manual_fallback":"缺测显示不可用，高级别告警转人工广播","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f807','integration-connectors-tertiary','LIS/PACS/HIS/CA 集成连接器','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"integration-connectors","interface_code":"LIS_RESULTS","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"全院 LIS、PACS、HIS 与 CA 标准契约、连通性和熔断基线","default_entity":"018f0000-0000-7000-8000-000000000101","default_scenario":"SUCCESS","owner_department":"信息中心集成平台组","operating_window":"7×24 小时","timeout_ms":5000,"retry_limit":3,"manual_fallback":"失败不伪装空数据，临床页显示来源降级并转工单","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f808','integration-messages-tertiary','集成消息 Trace 与幂等重试','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"integration-messages","interface_code":"HIS_INSURANCE","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"请求、应答、业务入账、死信和对账全链路","default_entity":"synthetic-trace-001","default_scenario":"SUCCESS","owner_department":"信息中心集成平台组","operating_window":"7×24 小时","timeout_ms":5000,"retry_limit":3,"manual_fallback":"死信只由授权人员重放，保持父 Trace 和业务幂等键","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f809','archive-scan-tertiary','病案扫描 OCR 与完整性校验','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"archive-scan","interface_code":"SCAN_CAPTURE","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"纸质病案扫描批次、页序、OCR 复核与完整性证据","default_entity":"SCAN-SYNTHETIC-001","default_scenario":"SUCCESS","owner_department":"病案管理科","operating_window":"工作日 08:00–18:00","timeout_ms":15000,"retry_limit":2,"manual_fallback":"缺页或校验失败立即阻断入档并返回扫描复核队列","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f810','archive-preservation-tertiary','电子病案 WORM 长期保存','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"archive-preservation","interface_code":"STORAGE_PRESERVE","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"内容哈希、30 年保留、WORM 封存与抽样恢复","default_entity":"synthetic://archive/case-001","default_scenario":"SUCCESS","owner_department":"病案管理科 / 信息中心","operating_window":"7×24 小时","timeout_ms":30000,"retry_limit":2,"manual_fallback":"哈希不一致停止封存并启动双人复核","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f811','pathology-tertiary','病理标本到诊断签署闭环','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"pathology-workbench","interface_code":"PATHOLOGY_DIAGNOSE","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"标本接收、取材、制片、诊断复核和签署状态轴","default_entity":"PATH-SYNTHETIC-001","default_scenario":"SUCCESS","owner_department":"病理科","operating_window":"工作日 08:00–20:00；冰冻病理 7×24","timeout_ms":10000,"retry_limit":2,"manual_fallback":"标本身份不一致立即阻断，转病理科双人核对","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f812','anesthesia-tertiary','手术麻醉事件轴与 PACU 去向','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"anesthesia-workbench","interface_code":"ANESTHESIA_EVENT","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"术前评估、诱导、连续监护、用药事件与复苏去向","default_entity":"018f0000-0000-7000-8000-000000000101","default_scenario":"SUCCESS","owner_department":"麻醉科","operating_window":"7×24 小时","timeout_ms":3000,"retry_limit":2,"manual_fallback":"数据中断转纸面/本地麻醉记录，恢复后按事件时间幂等补录","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f813','therapy-tertiary','治疗排程、双核对与不良事件闭环','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"therapy-workbench","interface_code":"THERAPY_EXECUTE","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"康复、放疗和高风险治疗排程、患者/医嘱核对与执行闭环","default_entity":"THER-SYNTHETIC-001","default_scenario":"SUCCESS","owner_department":"诊疗执行中心","operating_window":"工作日 07:30–20:00；急诊 7×24","timeout_ms":5000,"retry_limit":2,"manual_fallback":"核对失败禁止执行，不良事件转医疗安全上报","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f814','malware-scan-tertiary','恶意文件扫描与隔离阻断','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"malware-scan","interface_code":"MALWARE_SCAN","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"入档文件 ClamAV INSTREAM 扫描：干净放行、检出签名即隔离阻断","default_entity":"synthetic://scan/eicar-sample-001","default_scenario":"SUCCESS","owner_department":"信息中心安全组 / 病案管理科","operating_window":"7×24 小时","timeout_ms":10000,"retry_limit":1,"manual_fallback":"检出文件隔离阻断入档并登记审计，转安全组人工复核来源","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f815','cda-validation-tertiary','CDA 结构与语义校验','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"cda-validation","interface_code":"CDA_VALIDATION","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"CDA R2 结构/语义校验：XML、根元素、枚举与必填章节，失败即隔离整改","default_entity":"CDA-SYNTHETIC-001","default_scenario":"SUCCESS","owner_department":"病案管理科 / 信息中心","operating_window":"工作日 08:00–20:00","timeout_ms":15000,"retry_limit":2,"manual_fallback":"结构失败文书转整改队列重新校验，未通过不得归档终态","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f816','direct-report-tertiary','国家传染病/院感直报基线','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"direct-report","interface_code":"DIRECT_REPORT_GATEWAY","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"法定传染病与院感事件直报：报告卡号、回执、2/24 小时时限、更正与失败重放","default_entity":"EVT-DR-SYNTHETIC-001","default_scenario":"SUCCESS","owner_department":"公共卫生科 / 院感办","operating_window":"7×24 小时；报告时限 2 小时/24 小时","timeout_ms":5000,"retry_limit":3,"manual_fallback":"PENDING_RETRY 保留事件并幂等重试；不伪造国家平台已接收；确认需转人工电话上报","documentation_version":"v1.0 / 2026-08-28"}'),
                  ('018f0000-0000-7000-8000-00000000f817','empi-tertiary','患者主索引 EMPI 查询基线','{"schema_version":1,"fixture_source":"tertiary-mock-profile-v1","workbench_id":"empi","interface_code":"EMPI_PATIENT_LOOKUP","hospital_level":"三级甲等","organization":"江城大学附属医院","facility":"本部院区","description":"按登记号/人口学查询患者主索引候选、匹配度与疑似重复，合并必须人工确认","default_entity":"P-SYNTHETIC-10001","default_scenario":"SUCCESS","owner_department":"医务处 / 信息中心","operating_window":"7×24 小时","timeout_ms":3000,"retry_limit":2,"manual_fallback":"候选不确定时转人工核验；算法分不直接合并患者","documentation_version":"v1.0 / 2026-08-28"}')
                ) as seed(config_id, config_key, display_name, payload)
                on conflict (tenant_id, config_id) do update set
                  config_type = excluded.config_type, config_key = excluded.config_key,
                  display_name = excluded.display_name, payload = excluded.payload,
                  validation_state = 'VALID', validation_errors = '[]'::jsonb,
                  updated_at = now(), row_version = config_item.row_version + 1
                where config_item.status <> 'ARCHIVED'
                  and (config_item.display_name is distinct from excluded.display_name
                    or config_item.payload is distinct from excluded.payload)
                """).param("tenant", TENANT_ID).param("approver", COLLABORATOR_USER_ID)
                .param("author", USER_ID).update();

        jdbc.sql("""
                update config_item set status = 'ARCHIVED', published_at = null,
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and config_type = 'MOCK_INTERFACE_PROFILE'
                  and status <> 'ARCHIVED'
                  and (coalesce(payload->>'workbench_id', '') not in (
                    'admin-auth','devices','device-monitoring',
                    'integration-connectors','integration-messages','archive-scan','archive-preservation',
                    'malware-scan','cda-validation',
                    'pathology-workbench','anesthesia-workbench','therapy-workbench',
                    'direct-report','empi')
                    or coalesce(payload->>'interface_code', '') = ''
                    or coalesce(payload->>'organization', '') = '')
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into source_system_inventory(
                  tenant_id, source_system_id, source_code, display_name, system_type,
                  connection_status, registered_by, registered_at)
                select :tenant, seed.source_id::uuid, seed.code, seed.name, seed.system_type,
                  'ACTIVE', :author, now() - interval '120 days'
                from (values
                  ('018f0000-0000-7000-8000-00000000f201','LEGACY-HIS-82','Legacy HIS 8.2','EMR'),
                  ('018f0000-0000-7000-8000-00000000f202','LEGACY-LIS-6','Legacy LIS 6.4','LIS'),
                  ('018f0000-0000-7000-8000-00000000f203','LEGACY-PACS-5','Legacy PACS 5.1','PACS'),
                  ('018f0000-0000-7000-8000-00000000f204','LEGACY-PHARM','Legacy 药房系统','PHARMACY'),
                  ('018f0000-0000-7000-8000-00000000f205','LEGACY-BILL','Legacy 费用医保系统','BILLING')
                ) as seed(source_id, code, name, system_type)
                on conflict (tenant_id, source_system_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();

        jdbc.sql("""
                insert into source_field_mapping(
                  tenant_id, mapping_id, source_system_id, source_field, target_entity,
                  target_field, status, registered_by, registered_at)
                select :tenant, seed.mapping_id::uuid, seed.source_id::uuid, seed.source_field,
                  seed.target_entity, seed.target_field, 'ACTIVE', :author, now() - interval '60 days'
                from (values
                  ('018f0000-0000-7000-8000-00000000f211','018f0000-0000-7000-8000-00000000f202','PID-3','Patient','identifier'),
                  ('018f0000-0000-7000-8000-00000000f212','018f0000-0000-7000-8000-00000000f202','PV1-19','Encounter','identifier'),
                  ('018f0000-0000-7000-8000-00000000f213','018f0000-0000-7000-8000-00000000f202','ORC-2','ServiceRequest','identifier'),
                  ('018f0000-0000-7000-8000-00000000f214','018f0000-0000-7000-8000-00000000f202','OBR-25','DiagnosticReport','status'),
                  ('018f0000-0000-7000-8000-00000000f215','018f0000-0000-7000-8000-00000000f202','OBX-6','Observation','unit'),
                  ('018f0000-0000-7000-8000-00000000f216','018f0000-0000-7000-8000-00000000f203','AccessionNumber','ImagingStudy','identifier'),
                  ('018f0000-0000-7000-8000-00000000f217','018f0000-0000-7000-8000-00000000f201','PATIENT_NO','Patient','identifier')
                ) as seed(mapping_id, source_id, source_field, target_entity, target_field)
                on conflict (tenant_id, mapping_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();

        jdbc.sql("""
                insert into historical_migration_batch(
                  tenant_id, batch_id, source_system, batch_status, record_count,
                  mismatch_count, started_at, completed_at, created_by, row_version)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f701','LEGACY-HIS-82','RECONCILED',12050000,0,now()-interval '21 days',now()-interval '2 days',:author,2),
                  (:tenant,'018f0000-0000-7000-8000-00000000f702','LEGACY-LIS-6','TRIAL',2841306,23,now()-interval '3 days',null,:author,1)
                on conflict (tenant_id, batch_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into historical_migration_checkpoint(
                  tenant_id, checkpoint_id, batch_id, processed_records, last_source_key,
                  checkpointed_by, checkpointed_at)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f711','018f0000-0000-7000-8000-00000000f702',2100000,'LIS-2100000',:author,now()-interval '12 hours'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f712','018f0000-0000-7000-8000-00000000f702',2700000,'LIS-2700000',:author,now()-interval '2 hours')
                on conflict (tenant_id, checkpoint_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();

        jdbc.sql("""
                insert into data_quality_rule(
                  tenant_id, data_quality_rule_id, rule_code, rule_name, dimension,
                  target_entity, threshold, severity, status)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f301','DQ-MPI-IDENTITY','患者主索引身份完整性','COMPLETENESS','Patient',0.9999,'BLOCKING','INACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f302','DQ-ENCOUNTER-LINK','就诊患者关联一致性','CONSISTENCY','Encounter',1.0000,'BLOCKING','INACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f303','DQ-LAB-UNIT','检验结果单位标准化','VALIDITY','Observation',0.9950,'WARNING','INACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f304','DQ-REPORT-TIMELINESS','医技报告及时性','TIMELINESS','DiagnosticReport',0.9800,'WARNING','INACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f305','DQ-DOC-VERSION','病历文书版本唯一性','UNIQUENESS','ClinicalDocument',1.0000,'BLOCKING','INACTIVE')
                on conflict (tenant_id, data_quality_rule_id) do nothing
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into data_quality_evaluation(
                  tenant_id, data_quality_evaluation_id, data_quality_rule_id, target_entity_id,
                  measured_value, threshold, status, evaluated_at, evaluated_by)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f311','018f0000-0000-7000-8000-00000000f301','018f0000-0000-7000-8000-000000000001',1.0000,0.9999,'PASSED',now()-interval '1 day',:author),
                  (:tenant,'018f0000-0000-7000-8000-00000000f312','018f0000-0000-7000-8000-00000000f303','018f0000-0000-7000-8000-000000000001',0.9970,0.9950,'PASSED',now()-interval '4 hours',:author)
                on conflict (tenant_id, data_quality_evaluation_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();

        jdbc.sql("""
                insert into research_cohort(
                  tenant_id, research_cohort_id, cohort_code, cohort_name,
                  inclusion_criteria, exclusion_criteria, status)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f401','COHORT-HTN-2026','成年高血压门诊队列','年龄 >= 18；观察窗内存在 ICD-10 I10；至少 2 次门诊血压','妊娠相关高血压；eGFR < 30','ACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f402','COHORT-HF-READMIT','心衰 30 天再入院队列','心衰住院出院且随访窗 30 天','住院死亡或转院未完成','ACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f403','COHORT-PCI-OUTCOME','冠脉介入术后结局队列','完成 PCI 且有 180 天随访','缺失主要结局','ACTIVE')
                on conflict (tenant_id, research_cohort_id) do nothing
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into research_cohort_snapshot(
                  tenant_id, research_cohort_snapshot_id, research_cohort_id, member_count,
                  criteria_hash, computed_at, computed_by)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f411','018f0000-0000-7000-8000-00000000f401',12486,'f1c2de123beab8fa97dce9da233f6cb9432d96962bb1f06637e773d35e14d9f1',now()-interval '1 day',:author),
                  (:tenant,'018f0000-0000-7000-8000-00000000f412','018f0000-0000-7000-8000-00000000f402',3218,'a0c2de123beab8fa97dce9da233f6cb9432d96962bb1f06637e773d35e14d9f2',now()-interval '2 days',:author)
                on conflict (tenant_id, research_cohort_snapshot_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into research_cohort_member(
                  tenant_id, cohort_member_id, research_cohort_id, patient_id, computed_by, computed_at)
                values (:tenant,'018f0000-0000-7000-8000-00000000f421','018f0000-0000-7000-8000-00000000f401','018f0000-0000-7000-8000-000000000001',:author,now()-interval '1 day')
                on conflict (tenant_id, cohort_member_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into research_dataset_request(
                  tenant_id, request_id, requester_id, purpose, scope_description, status,
                  approved_by, approved_at, row_version)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f501',:author,'高血压治疗结局分析','队列 v6 的去标识人口学、诊断、处方、血压与检验字段','APPROVED',:approver,now()-interval '1 day',2),
                  (:tenant,'018f0000-0000-7000-8000-00000000f502',:author,'心衰再入院风险预分析','仅聚合统计与缺失率评估，不含自由文本','REQUESTED',null,null,1)
                on conflict (tenant_id, request_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();

        // 核心数据规模（DATA_CENTER）与科研统计（RESEARCH_STATS）不再注入固定快照：
        // 页面提供「按登记口径计算」，由 MetricSnapshotService 从患者/就诊/病历/医嘱、
        // 队列/成员/数据申请事实表实时计算并固化来源、公式与审计证据。
        jdbc.sql("""
                delete from metric_snapshot
                where tenant_id = :tenant and snapshot_id in (
                  '018f0000-0000-7000-8000-00000000d801',
                  '018f0000-0000-7000-8000-00000000d802',
                  '018f0000-0000-7000-8000-00000000d803',
                  '018f0000-0000-7000-8000-00000000d804',
                  '018f0000-0000-7000-8000-00000000d805',
                  '018f0000-0000-7000-8000-00000000d806'
                )
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into device(
                  tenant_id, device_id, device_code, display_name, device_type, manufacturer_model,
                  department, gateway, standard_interface, calibration_due, clock_offset_seconds, binding_policy, status)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f111','card-monitor-01','CARD-MON-01 · 心电监护仪','MONITOR','迈瑞 BeneVision N15','心血管内科一病区','GW-BEDSIDE-01 / VLAN-MED-12','IEEE 11073 / HL7 ORU',date '2027-02-28',2,'腕带 + 床位双标识；解绑需责任护士确认','ACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f112','icu-vent-07','ICU-VENT-07 · 重症呼吸机','VENTILATOR','Drager Evita V600','重症医学科','GW-ICU-02 / VLAN-ICU-08','ISO/IEEE 11073 / IHE PCD',date '2027-01-15',4,'腕带 + 床位 + 设备三标识；转床自动阻断旧绑定','ACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f113','pump-a-118','PUMP-A-118 · 智能输注泵','INFUSION_PUMP','BD Alaris System','急诊抢救区','GW-ER-01 / WIFI-MED-IOT','IHE PCD / FHIR DeviceMetric',date '2026-12-31',1,'患者腕带 + 医嘱双核对；高警示药双人确认','ACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f114','ct-01','CT-01 · 128 排 CT','IMAGING','Siemens SOMATOM Definition Edge','医学影像科','DICOM-GW-01 / VLAN-PACS','DICOM MWL / MPPS / RDSR',date '2027-03-20',3,'检查申请号 + 患者腕带核对；侧别不一致阻断','ACTIVE')
                on conflict (tenant_id, device_id) do nothing
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into research_project(
                  tenant_id, project_id, project_code, display_name, project_type,
                  principal_investigator, registry_number, ethics_approval, approved_purpose,
                  data_scope, member_count, expires_at, status)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000f121','res-2026-014','RES-2026-014 · 真实世界高血压用药与控制','OBSERVATIONAL','周教授','MRR-2026-001842','IRB-2026-119','高血压真实世界治疗结局分析',array['门诊病历','处方','检验','生命体征'],8,date '2027-07-31','ACTIVE'),
                  (:tenant,'018f0000-0000-7000-8000-00000000f122','res-2026-021','RES-2026-021 · 心衰再入院风险队列','OBSERVATIONAL','刘主任','MRR-2026-002113','IRB-2026-184','心衰再入院风险因素分析',array['住院病历','出院记录','检验','随访'],6,date '2027-05-31','ACTIVE')
                on conflict (tenant_id, project_id) do nothing
                """).param("tenant", TENANT_ID).update();
    }

    /**
     * Imports the complete data-center catalog from a validated external dataset. Seed rows are
     * insert-only so subsequent CRUD and lifecycle actions remain authoritative across restarts.
     */
    private void upsertTertiaryDataCenterDataset(TertiaryDataCenterDataset dataset) {
        for (JsonNode row : dataset.rows("configurations")) {
            jdbc.sql("""
                    insert into config_item(
                      tenant_id, config_id, config_type, config_key, display_name, payload,
                      status, row_version, schema_version, validation_state, validation_errors,
                      approval_state, approved_by, published_at, created_by)
                    values (:tenant, cast(:id as uuid), :type, :key, :name, cast(:payload as jsonb),
                      'ACTIVE', 1, 1, 'VALID', '[]'::jsonb, 'APPROVED', :approver,
                      now() - interval '14 days', :author)
                    on conflict (tenant_id, config_type, config_key) where status = 'ACTIVE' do update set
                      display_name = excluded.display_name,
                      payload = excluded.payload,
                      status = 'ACTIVE',
                      row_version = config_item.row_version + 1,
                      schema_version = excluded.schema_version,
                      validation_state = 'VALID',
                      validation_errors = '[]'::jsonb,
                      approval_state = 'APPROVED',
                      approved_by = excluded.approved_by,
                      published_at = excluded.published_at,
                      updated_at = now()
                    where config_item.payload->>'fixture_source' in (
                      'tertiary-data-center-v1', 'tertiary-business-generator-v3')
                    """).param("tenant", TENANT_ID)
                    .param("id", required(row, "id")).param("type", required(row, "config_type"))
                    .param("key", required(row, "config_key")).param("name", required(row, "display_name"))
                    .param("payload", writeJson(row.path("payload"))).param("approver", COLLABORATOR_USER_ID)
                    .param("author", USER_ID).update();
        }
        jdbc.sql("""
                update config_item set status = 'ARCHIVED', published_at = null,
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and status <> 'ARCHIVED'
                  and config_type in ('INTEGRATION_CONNECTOR','DEVICE_CATALOG',
                    'RESEARCH_PROJECT','INTEGRATION_INCIDENT')
                  and payload->>'fixture_source' = 'tertiary-data-center-v1'
                """).param("tenant", TENANT_ID).update();

        for (JsonNode row : dataset.rows("source_systems")) {
            jdbc.sql("""
                    insert into source_system_inventory(
                      tenant_id, source_system_id, source_code, display_name, system_type,
                      connection_status, registered_by, registered_at)
                    values (:tenant, cast(:id as uuid), :code, :name, :type, 'ACTIVE', :author,
                      now() - make_interval(days => :days))
                    on conflict (tenant_id, source_system_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("code", required(row, "source_code")).param("name", required(row, "display_name"))
                    .param("type", required(row, "system_type")).param("author", USER_ID)
                    .param("days", row.path("registered_days_ago").asInt()).update();
        }
        for (JsonNode row : dataset.rows("field_mappings")) {
            jdbc.sql("""
                    insert into source_field_mapping(
                      tenant_id, mapping_id, source_system_id, source_field, target_entity,
                      target_field, status, registered_by, registered_at)
                    values (:tenant, cast(:id as uuid), cast(:source as uuid), :source_field,
                      :target_entity, :target_field, 'ACTIVE', :author, now() - interval '45 days')
                    on conflict (tenant_id, mapping_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("source", required(row, "source_id"))
                    .param("source_field", required(row, "source_field"))
                    .param("target_entity", required(row, "target_entity"))
                    .param("target_field", required(row, "target_field"))
                    .param("author", USER_ID).update();
        }
        for (JsonNode row : dataset.rows("migration_batches")) {
            int completedDays = row.path("completed_days_ago").isNumber()
                    ? row.path("completed_days_ago").asInt() : -1;
            jdbc.sql("""
                    insert into historical_migration_batch(
                      tenant_id, batch_id, source_system, batch_status, record_count,
                      mismatch_count, started_at, completed_at, created_by, row_version)
                    values (:tenant, cast(:id as uuid), :source, :status, :records, :mismatches,
                      now() - make_interval(days => :started_days),
                      case when :completed_days >= 0
                        then now() - make_interval(days => :completed_days) else null end,
                      :author, 1)
                    on conflict (tenant_id, batch_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("source", required(row, "source_system"))
                    .param("status", required(row, "batch_status"))
                    .param("records", row.path("record_count").asInt())
                    .param("mismatches", row.path("mismatch_count").asInt())
                    .param("started_days", row.path("started_days_ago").asInt())
                    .param("completed_days", completedDays).param("author", USER_ID).update();
        }
        for (JsonNode row : dataset.rows("migration_checkpoints")) {
            jdbc.sql("""
                    insert into historical_migration_checkpoint(
                      tenant_id, checkpoint_id, batch_id, processed_records, last_source_key,
                      checkpointed_by, checkpointed_at)
                    values (:tenant, cast(:id as uuid), cast(:batch as uuid), :processed, :last_key,
                      :author, now() - make_interval(hours => :hours))
                    on conflict (tenant_id, checkpoint_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("batch", required(row, "batch_id"))
                    .param("processed", row.path("processed_records").asLong())
                    .param("last_key", required(row, "last_source_key"))
                    .param("author", USER_ID).param("hours", row.path("hours_ago").asInt()).update();
        }
        for (JsonNode row : dataset.rows("quality_rules")) {
            jdbc.sql("""
                    insert into data_quality_rule(
                      tenant_id, data_quality_rule_id, rule_code, rule_name, dimension,
                      target_entity, threshold, severity, status)
                    values (:tenant, cast(:id as uuid), :code, :name, :dimension,
                      :entity, :threshold, :severity,
                      case when :code in (
                        'DQ-ORDER-PATIENT', 'DQ-ALLERGY-CODE', 'DQ-CRITICAL-ACK',
                        'DQ-SURGERY-SITE', 'DQ-TRANSFUSION-TRACE', 'DQ-DISCHARGE-DIAG'
                      ) then 'ACTIVE' else 'INACTIVE' end)
                    on conflict (tenant_id, data_quality_rule_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("code", required(row, "rule_code")).param("name", required(row, "rule_name"))
                    .param("dimension", required(row, "dimension")).param("entity", required(row, "target_entity"))
                    .param("threshold", row.path("threshold").asDouble())
                    .param("severity", required(row, "severity")).update();
        }
        for (JsonNode row : dataset.rows("quality_evaluations")) {
            jdbc.sql("""
                    insert into data_quality_evaluation(
                      tenant_id, data_quality_evaluation_id, data_quality_rule_id, target_entity_id,
                      measured_value, threshold, status, evaluated_at, evaluated_by)
                    select :tenant, cast(:id as uuid), rule.data_quality_rule_id, cast(:entity as uuid),
                      :measured, rule.threshold,
                      case when :measured >= rule.threshold then 'PASSED' else 'FAILED' end,
                      now() - make_interval(hours => :hours), :author
                    from data_quality_rule rule
                    where rule.tenant_id = :tenant and rule.data_quality_rule_id = cast(:rule as uuid)
                    on conflict (tenant_id, data_quality_evaluation_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("rule", required(row, "rule_id"))
                    .param("entity", required(row, "target_entity_id"))
                    .param("measured", row.path("measured_value").asDouble())
                    .param("hours", row.path("hours_ago").asInt()).param("author", USER_ID).update();
        }
        for (JsonNode row : dataset.rows("research_cohorts")) {
            jdbc.sql("""
                    insert into research_cohort(
                      tenant_id, research_cohort_id, cohort_code, cohort_name,
                      inclusion_criteria, exclusion_criteria, status)
                    values (:tenant, cast(:id as uuid), :code, :name, :include, :exclude, 'ACTIVE')
                    on conflict (tenant_id, research_cohort_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("code", required(row, "cohort_code")).param("name", required(row, "cohort_name"))
                    .param("include", required(row, "inclusion_criteria"))
                    .param("exclude", required(row, "exclusion_criteria")).update();
        }
        for (JsonNode row : dataset.rows("research_snapshots")) {
            jdbc.sql("""
                    insert into research_cohort_snapshot(
                      tenant_id, research_cohort_snapshot_id, research_cohort_id, member_count,
                      criteria_hash, computed_at, computed_by)
                    values (:tenant, cast(:id as uuid), cast(:cohort as uuid), :members, :hash,
                      now() - make_interval(hours => :hours), :author)
                    on conflict (tenant_id, research_cohort_snapshot_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("cohort", required(row, "cohort_id"))
                    .param("members", row.path("member_count").asInt())
                    .param("hash", required(row, "criteria_hash"))
                    .param("hours", row.path("hours_ago").asInt()).param("author", USER_ID).update();
        }
        for (JsonNode row : dataset.rows("dataset_requests")) {
            String requestStatus = required(row, "status");
            String watermark = row.path("export_watermark").isTextual()
                    ? row.path("export_watermark").stringValue() : "";
            jdbc.sql("""
                    insert into research_dataset_request(
                      tenant_id, request_id, requester_id, purpose, scope_description, status,
                      approved_by, approved_at, exported_at, exported_by, export_watermark,
                      row_version, created_at, updated_at)
                    values (:tenant, cast(:id as uuid), :author, :purpose, :scope, :status,
                      case when :status in ('APPROVED','EXPORTED') then :approver else null end,
                      case when :status in ('APPROVED','EXPORTED') then now() - interval '2 days' else null end,
                      case when :status = 'EXPORTED' then now() - interval '1 day' else null end,
                      case when :status = 'EXPORTED' then :approver else null end,
                      case when :status = 'EXPORTED' then :watermark else null end,
                      1, now() - make_interval(days => :days), now() - interval '1 day')
                    on conflict (tenant_id, request_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("author", USER_ID).param("purpose", required(row, "purpose"))
                    .param("scope", required(row, "scope_description")).param("status", requestStatus)
                    .param("approver", COLLABORATOR_USER_ID).param("watermark", watermark)
                    .param("days", row.path("created_days_ago").asInt()).update();
        }
        for (JsonNode row : dataset.rows("metric_snapshots")) {
            jdbc.sql("""
                    insert into metric_snapshot(
                      tenant_id, snapshot_id, metric_type, metric_name, metric_value, unit,
                      dimension, period, status, computed_at)
                    values (:tenant, cast(:id as uuid), :type, :name, :value, :unit,
                      cast(:dimension as jsonb), current_date, 'FINAL', now() - interval '6 hours')
                    on conflict (tenant_id, snapshot_id) do nothing
                    """).param("tenant", TENANT_ID).param("id", required(row, "id"))
                    .param("type", required(row, "metric_type")).param("name", required(row, "metric_name"))
                    .param("value", row.path("metric_value").asDouble()).param("unit", required(row, "unit"))
                    .param("dimension", writeJson(row.path("dimension"))).update();
        }
    }

    private static String required(JsonNode row, String field) {
        return TertiaryDataCenterDataset.required(row, field);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception invalidFixture) {
            throw new IllegalStateException("Unable to serialize tertiary hospital configuration fixture", invalidFixture);
        }
    }

    private void upsertTertiaryTaskPathwayFixtures() {
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, row_version, schema_version, validation_state, validation_errors,
                  approval_state, approved_by, published_at, created_by)
                select :tenant, seed.config_id::uuid, seed.config_type, seed.config_key,
                  seed.display_name, cast(seed.payload as jsonb), 'ACTIVE', 1, 1, 'VALID',
                  '[]'::jsonb, 'APPROVED', :approver, now() - interval '30 days', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000fa01','CLINICAL_TASK_RULE','critical-value-15m','危急值 15 分钟闭环规则','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","task_type":"危急值处置","risk_level":"CRITICAL","due_minutes":15,"escalation_minutes":5,"assignment_strategy":"报告接收医师 → 患者主管医师 → 医疗组长","completion_source":"权威业务对象终态","channels":["IN_APP","OUTBOX"],"applies_to":["门诊","急诊","住院"],"enabled":true}'),
                  ('018f0000-0000-7000-8000-00000000fa02','CLINICAL_TASK_RULE','emergency-consult-10m','急会诊 10 分钟响应规则','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","task_type":"急会诊响应","risk_level":"CRITICAL","due_minutes":10,"escalation_minutes":3,"assignment_strategy":"会诊科室值班组 → 二线医师 → 科主任","completion_source":"权威业务对象终态","channels":["IN_APP","OUTBOX"],"applies_to":["急诊","住院"],"enabled":true}'),
                  ('018f0000-0000-7000-8000-00000000fa03','CLINICAL_TASK_RULE','inpatient-document-sla','住院文书审签与逾期规则','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","task_type":"住院文书审签","risk_level":"HIGH","due_minutes":480,"escalation_minutes":60,"assignment_strategy":"住院医师 → 主治医师 → 科主任","completion_source":"权威业务对象终态","channels":["IN_APP"],"applies_to":["住院"],"enabled":true}'),
                  ('018f0000-0000-7000-8000-00000000fa04','CLINICAL_TASK_RULE','pathway-variance-review','临床路径变异独立审核规则','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","task_type":"路径变异审核","risk_level":"HIGH","due_minutes":120,"escalation_minutes":30,"assignment_strategy":"非申请人主治医师 → 科室路径管理员","completion_source":"权威业务对象终态","channels":["IN_APP","OUTBOX"],"applies_to":["住院"],"enabled":true}'),
                  ('018f0000-0000-7000-8000-00000000fa05','CLINICAL_TASK_RULE','discharge-remediation-24h','出院病案整改 24 小时规则','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","task_type":"出院病案整改","risk_level":"ROUTINE","due_minutes":1440,"escalation_minutes":240,"assignment_strategy":"主管医师 → 医疗组长 → 病案科","completion_source":"权威业务对象终态","channels":["IN_APP"],"applies_to":["住院"],"enabled":true}'),
                  ('018f0000-0000-7000-8000-00000000fb01','CLINICAL_PATHWAY','hf-standard-v41','心力衰竭标准临床路径 v4.1','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","pathway_code":"HF-STANDARD","specialty_code":"CARDIOLOGY","diagnosis_code":"I50.9","version_no":4,"admission_criteria":"心力衰竭主要诊断明确，完成病情分级、禁忌证和患者意愿核对。","stages":[{"code":"ADMISSION_ASSESSMENT","name":"入院评估","days":"0-1"},{"code":"VOLUME_CONTROL","name":"容量负荷控制","days":"1-3"},{"code":"ETIOLOGY","name":"病因与合并症评估","days":"2-7"},{"code":"DISCHARGE_PREPARATION","name":"稳定与出院准备","days":"5-14"}],"publication_scope":"江城大学附属医院本部","version_immutable_after_publish":true}'),
                  ('018f0000-0000-7000-8000-00000000fb02','CLINICAL_PATHWAY','ami-pci-v32','急性心肌梗死 PCI 临床路径 v3.2','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","pathway_code":"AMI-PCI","specialty_code":"CARDIOLOGY","diagnosis_code":"I21.9","version_no":3,"admission_criteria":"急性 ST 段抬高型心肌梗死且符合急诊再灌注评估条件。","stages":[{"code":"GREEN_CHANNEL","name":"胸痛中心绿色通道","days":"0"},{"code":"PCI","name":"介入治疗","days":"0-1"},{"code":"MONITORING","name":"术后监护","days":"1-3"},{"code":"SECONDARY_PREVENTION","name":"二级预防与出院","days":"3-7"}],"publication_scope":"江城大学附属医院本部","version_immutable_after_publish":true}'),
                  ('018f0000-0000-7000-8000-00000000fb03','CLINICAL_PATHWAY','stroke-thrombolysis-v25','急性缺血性卒中溶栓路径 v2.5','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","pathway_code":"AIS-THROMBOLYSIS","specialty_code":"NEUROLOGY","diagnosis_code":"I63.9","version_no":2,"admission_criteria":"急性缺血性卒中且处于静脉溶栓或血管内治疗评估时间窗。","stages":[{"code":"STROKE_GREEN","name":"卒中绿色通道","days":"0"},{"code":"REPERFUSION","name":"再灌注治疗","days":"0-1"},{"code":"NEURO_MONITOR","name":"神经功能监测","days":"1-3"},{"code":"REHAB","name":"早期康复与二级预防","days":"2-14"}],"publication_scope":"江城大学附属医院本部","version_immutable_after_publish":true}'),
                  ('018f0000-0000-7000-8000-00000000fb04','CLINICAL_PATHWAY','cap-severe-v18','重症社区获得性肺炎路径 v1.8','{"schema_version":1,"fixture_source":"tertiary-task-pathway-v1","hospital_level":"三级甲等","pathway_code":"CAP-SEVERE","specialty_code":"RESPIRATORY","diagnosis_code":"J18.9","version_no":1,"admission_criteria":"社区获得性肺炎诊断明确并完成 CURB-65 与器官支持评估。","stages":[{"code":"SEVERITY","name":"严重度与病原学评估","days":"0-1"},{"code":"ANTIMICROBIAL","name":"经验性抗感染与器官支持","days":"0-3"},{"code":"DEESCALATION","name":"降阶梯治疗","days":"3-7"},{"code":"DISCHARGE","name":"出院与复评","days":"7-14"}],"publication_scope":"江城大学附属医院本部","version_immutable_after_publish":true}')
                ) as seed(config_id, config_type, config_key, display_name, payload)
                on conflict (tenant_id, config_id) do update set
                  display_name = excluded.display_name, payload = excluded.payload, status = 'ACTIVE',
                  validation_state = 'VALID', validation_errors = '[]'::jsonb,
                  approval_state = 'APPROVED', approved_by = excluded.approved_by,
                  published_at = coalesce(config_item.published_at, excluded.published_at),
                  updated_at = now(), row_version = config_item.row_version + 1
                where config_item.display_name is distinct from excluded.display_name
                   or config_item.payload is distinct from excluded.payload
                   or config_item.status <> 'ACTIVE'
                """).param("tenant", TENANT_ID).param("approver", COLLABORATOR_USER_ID)
                .param("author", USER_ID).update();

        jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id, ward_id,
                  source_type, source_id, task_type, title, risk_level, state, business_state,
                  assigned_user_id, claimed_by, due_at, source_route, row_version, created_at, updated_at)
                select :tenant, seed.task_id::uuid, '018f0000-0000-7000-8000-000000000002'::uuid,
                  '018f0000-0000-7000-8000-000000000102'::uuid, :facility, :ward,
                  seed.source_type, seed.source_id::uuid, seed.task_type, seed.title,
                  seed.risk_level, seed.state, seed.business_state,
                  cast(seed.assigned_user_id as uuid), cast(seed.claimed_by as uuid),
                  now() + seed.due_offset, seed.source_route, 1,
                  now() - seed.created_offset, now() - seed.updated_offset
                from (values
                  ('018f0000-0000-7000-8000-00000000fc01','DOCUMENT','018f0000-0000-7000-8000-00000000bb05','INPATIENT_DOCUMENT_SIGN','入院记录完整性与审签','HIGH','PENDING','待住院医师完善并提交','018f0000-0000-7000-8000-00000000aa04',null,interval '45 minutes','/inpatient-course',interval '35 minutes',interval '35 minutes'),
                  ('018f0000-0000-7000-8000-00000000fc02','DOCUMENT','018f0000-0000-7000-8000-00000000bb06','FIRST_COURSE_SLA','首次病程记录时限核验','CRITICAL','VIEWED','已查看·等待权威文书完成','018f0000-0000-7000-8000-00000000aa04',null,interval '-20 minutes','/inpatient-course',interval '90 minutes',interval '12 minutes'),
                  ('018f0000-0000-7000-8000-00000000fc03','DOCUMENT','018f0000-0000-7000-8000-00000000bb07','ATTENDING_REVIEW','上级医师查房审签','HIGH','CLAIMED','主管医师处理中','018f0000-0000-7000-8000-00000000aa04','018f0000-0000-7000-8000-00000000aa04',interval '4 hours','/inpatient-course',interval '70 minutes',interval '8 minutes'),
                  ('018f0000-0000-7000-8000-00000000fc04','DOCUMENT','018f0000-0000-7000-8000-00000000bb08','FOUR_LEVEL_QC','四级病案质控复核','HIGH','ASSIGNED','已分派独立质控岗位','018f0000-0000-7000-8000-00000000aa06',null,interval '2 hours','/record-center',interval '55 minutes',interval '18 minutes'),
                  ('018f0000-0000-7000-8000-00000000fc05','PATHWAY','018f0000-0000-7000-8000-00000000fb01','PATHWAY_ENROLLMENT','心衰标准路径入径评估','HIGH','PENDING','待核对入径标准与禁忌证','018f0000-0000-7000-8000-00000000aa04',null,interval '1 hour','/ip-pathway',interval '48 minutes',interval '48 minutes'),
                  ('018f0000-0000-7000-8000-00000000fc06','DISCHARGE_REMEDIATION','018f0000-0000-7000-8000-00000000fa05','DISCHARGE_REMEDIATION','出院病案预审整改','ROUTINE','COMPLETED','权威病案源已完成','018f0000-0000-7000-8000-00000000aa04','018f0000-0000-7000-8000-00000000aa04',interval '-1 day','/record-center',interval '2 days',interval '1 day')
                ) as seed(task_id, source_type, source_id, task_type, title, risk_level, state,
                  business_state, assigned_user_id, claimed_by, due_offset, source_route,
                  created_offset, updated_offset)
                on conflict (tenant_id, task_id) do update set
                  title = excluded.title, risk_level = excluded.risk_level,
                  business_state = excluded.business_state, source_route = excluded.source_route,
                  ward_id = excluded.ward_id
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID)
                .param("ward", SYNTHETIC_WARD_ID).update();
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type, previous_state,
                  resulting_state, actor_user_id, reason, occurred_at)
                select :tenant, seed.event_id::uuid, seed.task_id::uuid, 'CREATED', null,
                  seed.resulting_state, :author, '三级医院任务中心验收基线', now() - seed.occurred_offset
                from (values
                  ('018f0000-0000-7000-8000-00000000fd01','018f0000-0000-7000-8000-00000000fc01','PENDING',interval '35 minutes'),
                  ('018f0000-0000-7000-8000-00000000fd02','018f0000-0000-7000-8000-00000000fc02','VIEWED',interval '90 minutes'),
                  ('018f0000-0000-7000-8000-00000000fd03','018f0000-0000-7000-8000-00000000fc03','CLAIMED',interval '70 minutes'),
                  ('018f0000-0000-7000-8000-00000000fd04','018f0000-0000-7000-8000-00000000fc04','ASSIGNED',interval '55 minutes'),
                  ('018f0000-0000-7000-8000-00000000fd05','018f0000-0000-7000-8000-00000000fc05','PENDING',interval '48 minutes'),
                  ('018f0000-0000-7000-8000-00000000fd06','018f0000-0000-7000-8000-00000000fc06','COMPLETED',interval '2 days')
                ) as seed(event_id, task_id, resulting_state, occurred_offset)
                on conflict (tenant_id, task_event_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_task_team_queue(
                  tenant_id, queue_id, facility_id, department_id, clinical_task_id,
                  queue_status, enqueued_by, enqueued_at, claimed_by, claimed_at, row_version)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000fe01',:facility,:department,
                    '018f0000-0000-7000-8000-00000000fc04','ENQUEUED',:author,now()-interval '25 minutes',null,null,1),
                  (:tenant,'018f0000-0000-7000-8000-00000000fe02',:facility,:department,
                    '018f0000-0000-7000-8000-00000000fc06','COMPLETED',:author,now()-interval '2 days',
                    :author,now()-interval '47 hours',2)
                on conflict (tenant_id, queue_id) do nothing
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID)
                .param("department", SYNTHETIC_DEPARTMENT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_task_notification(
                  tenant_id, notification_id, task_id, recipient_user_id, kind, channel,
                  status, attempt_count, delivered_at, last_error, row_version,
                  created_at, updated_at, scheduled_at)
                values
                  (:tenant,'018f0000-0000-7000-8000-00000000ff01','018f0000-0000-7000-8000-00000000fc01',
                    :author,'CREATED','IN_APP','PENDING',0,null,null,1,now()-interval '35 minutes',now()-interval '35 minutes',now()-interval '35 minutes'),
                  (:tenant,'018f0000-0000-7000-8000-00000000ff02','018f0000-0000-7000-8000-00000000fc02',
                    :author,'OVERDUE','OUTBOX','FAILED',3,null,'医院消息总线短暂不可用，待幂等恢复',2,now()-interval '20 minutes',now()-interval '4 minutes',now()-interval '20 minutes'),
                  (:tenant,'018f0000-0000-7000-8000-00000000ff03','018f0000-0000-7000-8000-00000000fc04',
                    :collaborator,'CREATED','IN_APP','DELIVERED',1,now()-interval '16 minutes',null,2,now()-interval '18 minutes',now()-interval '16 minutes',now()-interval '18 minutes')
                on conflict (tenant_id, notification_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID)
                .param("collaborator", COLLABORATOR_USER_ID).update();
    }

    private void upsertAdministrationFixtures() {
        upsertAdministrationOrganizationFixtures();
        upsertTertiaryHospitalOrganizationFixtures();
        upsertTertiaryHospitalWorkforceFixtures();
        jdbc.sql("""
                insert into dictionary_item(
                  tenant_id, dictionary_item_id, dictionary_code, item_code, item_name,
                  status, effective_from)
                select :tenant, seed.item_id::uuid, seed.dictionary_code, seed.item_code,
                  seed.item_name, 'ACTIVE', date '2026-01-01'
                from (values
                  ('018f0000-0000-7000-8000-00000000d101', 'GENDER', 'M', '男性 / Male'),
                  ('018f0000-0000-7000-8000-00000000d102', 'GENDER', 'F', '女性 / Female'),
                  ('018f0000-0000-7000-8000-00000000d103', 'GENDER', 'U', '未知 / Unknown'),
                  ('018f0000-0000-7000-8000-00000000d111', 'ENCOUNTER_TYPE', 'OPD', '门诊 / Outpatient'),
                  ('018f0000-0000-7000-8000-00000000d112', 'ENCOUNTER_TYPE', 'ED', '急诊 / Emergency'),
                  ('018f0000-0000-7000-8000-00000000d113', 'ENCOUNTER_TYPE', 'IPD', '住院 / Inpatient'),
                  ('018f0000-0000-7000-8000-00000000d121', 'ALLERGY_SEVERITY', 'MILD', '轻度 / Mild'),
                  ('018f0000-0000-7000-8000-00000000d122', 'ALLERGY_SEVERITY', 'MODERATE', '中度 / Moderate'),
                  ('018f0000-0000-7000-8000-00000000d123', 'ALLERGY_SEVERITY', 'SEVERE', '重度 / Severe'),
                  ('018f0000-0000-7000-8000-00000000d131', 'LAB_UNIT', 'MMOL_L', '毫摩尔每升 / mmol/L'),
                  ('018f0000-0000-7000-8000-00000000d132', 'LAB_UNIT', 'MG_L', '毫克每升 / mg/L')
                ) as seed(item_id, dictionary_code, item_code, item_name)
                on conflict (tenant_id, dictionary_code, item_code) do nothing
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into authorization_policy(
                  tenant_id, policy_id, policy_code, policy_name, version_no, effect, status,
                  subject_role_code, resource_type, action_code, organization_id, facility_id,
                  patient_relationship_required, relationship_types, resource_statuses,
                  purpose_codes, emergency_override_allowed, priority, valid_from,
                  created_by, approved_by, published_at)
                select :tenant, seed.policy_id::uuid, seed.policy_code, seed.policy_name, 1, seed.effect, seed.status,
                  seed.role_code, seed.resource_type, seed.action_code, :organization, :facility,
                  seed.relationship_required, seed.relationship_types::text[], array['ACTIVE'],
                  seed.purpose_codes::text[], true, seed.priority, now() - interval '30 days',
                  seed.created_by::uuid,
                  case when seed.status = 'PUBLISHED' then :approver else null end,
                  case when seed.status = 'PUBLISHED' then now() - interval '7 days' else null end
                from (values
                  ('018f0000-0000-7000-8000-00000000d201', 'CLINICAL-DOCUMENT-READ', '临床病历查看权限', 'ALLOW', 'PUBLISHED', 'CLINICIAN', 'CLINICAL_DOCUMENT', 'READ', true, '{CARE_TEAM}', '{DIRECT_CARE}', 700, '018f0000-0000-7000-8000-00000000aa04'),
                  ('018f0000-0000-7000-8000-00000000d202', 'CLINICAL-DOCUMENT-WRITE', '临床病历起草权限', 'ALLOW', 'PUBLISHED', 'CLINICIAN', 'CLINICAL_DOCUMENT', 'WRITE_DRAFT', true, '{CARE_TEAM}', '{DOCUMENT_DRAFT}', 720, '018f0000-0000-7000-8000-00000000aa04'),
                  ('018f0000-0000-7000-8000-00000000d203', 'SYSTEM-ADMIN-WORKFORCE', '人员与账户管理权限', 'ALLOW', 'PUBLISHED', 'SYSTEM_ADMIN', 'WORKFORCE_PERSON', 'MANAGE', false, '{}', '{ADMINISTRATION}', 900, '018f0000-0000-7000-8000-00000000aa04'),
                  ('018f0000-0000-7000-8000-00000000d204', 'CROSS-DEPARTMENT-EXPORT-DENY', '禁止跨科室导出临床病历', 'DENY', 'PUBLISHED', 'CLINICIAN', 'CLINICAL_DOCUMENT', 'EXPORT', false, '{}', '{SECONDARY_USE}', 1000, '018f0000-0000-7000-8000-00000000aa04'),
                  ('018f0000-0000-7000-8000-00000000d205', 'RESEARCH-DATASET-READ', '科研数据集查看权限', 'ALLOW', 'DRAFT', 'RESEARCHER', 'RESEARCH_DATASET', 'READ', false, '{}', '{RESEARCH}', 500, '018f0000-0000-7000-8000-00000000aa06')
                ) as seed(policy_id, policy_code, policy_name, effect, status, role_code, resource_type,
                  action_code, relationship_required, relationship_types, purpose_codes,
                  priority, created_by)
                on conflict (tenant_id, policy_code, version_no) do nothing
                """).param("tenant", TENANT_ID).param("organization", ORGANIZATION_ID)
                .param("facility", FACILITY_ID).param("approver", COLLABORATOR_USER_ID).update();
        upsertTertiaryHospitalDictionaryFixtures();
        upsertTertiaryHospitalAuthorizationFixtures();
        upsertTertiaryHospitalAuditFixtures();
    }

    private void upsertTertiaryHospitalOrganizationFixtures() {
        UUID riversideFacilityId = syntheticAdministrationId("facility:riverside");
        UUID northFacilityId = syntheticAdministrationId("facility:north");
        jdbc.sql("""
                insert into facility(
                  tenant_id, organization_id, facility_id, facility_code, display_name, timezone, status)
                values
                  (:tenant, :organization, :riverside, 'JC-DXFS-BJ', '江城大学附属医院滨江院区', 'Asia/Shanghai', 'ACTIVE'),
                  (:tenant, :organization, :north, 'JC-DXFS-BC', '江城大学附属医院北城院区', 'Asia/Shanghai', 'ACTIVE')
                on conflict (tenant_id, facility_id) do update
                set organization_id = excluded.organization_id, facility_code = excluded.facility_code,
                  display_name = excluded.display_name, timezone = excluded.timezone, status = 'ACTIVE',
                  effective_until = null
                """).param("tenant", TENANT_ID).param("organization", ORGANIZATION_ID)
                .param("riverside", riversideFacilityId).param("north", northFacilityId).update();

        jdbc.sql("""
                insert into clinical_department(
                  tenant_id, facility_id, department_id, department_code, display_name, status, unit_type)
                values (:tenant, :facility, :department, 'CARDIOLOGY', '心血管内科', 'ACTIVE', 'DEPARTMENT')
                on conflict (tenant_id, facility_id, department_id) do update
                set department_code = excluded.department_code, display_name = excluded.display_name,
                  status = 'ACTIVE', unit_type = excluded.unit_type, effective_until = null
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID)
                .param("department", SYNTHETIC_DEPARTMENT_ID).update();

        List<AdministrationDepartment> departments = List.of(
                new AdministrationDepartment(FACILITY_ID, "RESPIRATORY", "呼吸与危重症医学科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "GASTROENTEROLOGY", "消化内科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "ENDOCRINOLOGY", "内分泌科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "NEPHROLOGY", "肾内科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "HEMATOLOGY", "血液内科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "ONCOLOGY", "肿瘤科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "INFECTIOUS", "感染性疾病科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "ICU", "重症医学科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "ORTHOPEDICS", "骨科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "NEUROSURGERY", "神经外科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "UROLOGY", "泌尿外科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "THORACIC-SURGERY", "胸外科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "OBSTETRICS", "产科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "GYNECOLOGY", "妇科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "OPHTHALMOLOGY", "眼科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "ENT", "耳鼻咽喉科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "DERMATOLOGY", "皮肤科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "ANESTHESIOLOGY", "麻醉科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "PATHOLOGY", "病理科", "MEDICAL_TECH"),
                new AdministrationDepartment(FACILITY_ID, "PHARMACY", "药学部", "MEDICAL_TECH"),
                new AdministrationDepartment(FACILITY_ID, "TRANSFUSION", "输血科", "MEDICAL_TECH"),
                new AdministrationDepartment(FACILITY_ID, "REHABILITATION", "康复医学科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "TCM", "中医科", "DEPARTMENT"),
                new AdministrationDepartment(FACILITY_ID, "MEDICAL-AFFAIRS", "医务处", "ADMINISTRATIVE"),
                new AdministrationDepartment(FACILITY_ID, "NURSING-ADMIN", "护理部", "ADMINISTRATIVE"),
                new AdministrationDepartment(FACILITY_ID, "MEDICAL-RECORDS", "病案统计室", "ADMINISTRATIVE"),
                new AdministrationDepartment(FACILITY_ID, "INFORMATION-CENTER", "信息中心", "ADMINISTRATIVE"),
                new AdministrationDepartment(FACILITY_ID, "INFECTION-CONTROL", "医院感染管理科", "ADMINISTRATIVE"),
                new AdministrationDepartment(riversideFacilityId, "BJ-EMERGENCY", "滨江院区急诊医学科", "DEPARTMENT"),
                new AdministrationDepartment(riversideFacilityId, "BJ-GENERAL-MED", "滨江院区综合内科", "DEPARTMENT"),
                new AdministrationDepartment(riversideFacilityId, "BJ-GENERAL-SURGERY", "滨江院区综合外科", "DEPARTMENT"),
                new AdministrationDepartment(riversideFacilityId, "BJ-REHABILITATION", "滨江院区康复医学科", "DEPARTMENT"),
                new AdministrationDepartment(riversideFacilityId, "BJ-LAB", "滨江院区医学检验科", "MEDICAL_TECH"),
                new AdministrationDepartment(northFacilityId, "BC-PEDIATRICS", "北城院区儿科", "DEPARTMENT"),
                new AdministrationDepartment(northFacilityId, "BC-WOMEN", "北城院区妇产科", "DEPARTMENT"),
                new AdministrationDepartment(northFacilityId, "BC-OUTPATIENT", "北城院区综合门诊部", "DEPARTMENT"),
                new AdministrationDepartment(northFacilityId, "BC-IMAGING", "北城院区医学影像科", "MEDICAL_TECH"));
        for (AdministrationDepartment department : departments) {
            UUID departmentId = syntheticAdministrationId("department:" + department.facilityId() + ":" + department.code());
            jdbc.sql("""
                    insert into clinical_department(
                      tenant_id, facility_id, department_id, department_code, display_name, status, unit_type)
                    values (:tenant, :facility, :department, :code, :name, 'ACTIVE', :unit_type)
                    on conflict (tenant_id, facility_id, department_code) do update
                    set display_name = excluded.display_name,
                      status = 'ACTIVE', unit_type = excluded.unit_type, effective_until = null
                    """).param("tenant", TENANT_ID).param("facility", department.facilityId())
                    .param("department", departmentId).param("code", department.code())
                    .param("name", department.name()).param("unit_type", department.unitType()).update();
        }

        List<AdministrationWard> wards = List.of(
                new AdministrationWard(FACILITY_ID, "RESPIRATORY", "RESP-1", "呼吸与危重症医学科一病区", 12),
                new AdministrationWard(FACILITY_ID, "GASTROENTEROLOGY", "GI-1", "消化内科一病区", 12),
                new AdministrationWard(FACILITY_ID, "NEPHROLOGY", "NEPH-1", "肾内科一病区", 10),
                new AdministrationWard(FACILITY_ID, "ONCOLOGY", "ONC-1", "肿瘤科一病区", 10),
                new AdministrationWard(FACILITY_ID, "ICU", "ICU-A", "综合重症监护病区", 10),
                new AdministrationWard(FACILITY_ID, "ORTHOPEDICS", "ORTH-1", "骨科一病区", 12),
                new AdministrationWard(FACILITY_ID, "NEUROSURGERY", "NS-1", "神经外科一病区", 10),
                new AdministrationWard(FACILITY_ID, "UROLOGY", "URO-1", "泌尿外科一病区", 10),
                new AdministrationWard(FACILITY_ID, "OBSTETRICS", "OBS-1", "产科一病区", 12),
                new AdministrationWard(FACILITY_ID, "GYNECOLOGY", "GYN-1", "妇科一病区", 10),
                new AdministrationWard(FACILITY_ID, "REHABILITATION", "REHAB-1", "康复医学科一病区", 10),
                new AdministrationWard(riversideFacilityId, "BJ-GENERAL-MED", "BJ-MED-1", "滨江综合内科病区", 10),
                new AdministrationWard(riversideFacilityId, "BJ-GENERAL-SURGERY", "BJ-SUR-1", "滨江综合外科病区", 10),
                new AdministrationWard(northFacilityId, "BC-PEDIATRICS", "BC-PED-1", "北城儿科病区", 10),
                new AdministrationWard(northFacilityId, "BC-WOMEN", "BC-WOMEN-1", "北城妇产科病区", 10));
        for (AdministrationWard ward : wards) {
            UUID departmentId = jdbc.sql("""
                    select department_id from clinical_department
                    where tenant_id = :tenant and facility_id = :facility and department_code = :code
                    """).param("tenant", TENANT_ID).param("facility", ward.facilityId())
                    .param("code", ward.departmentCode()).query(UUID.class).single();
            UUID wardId = syntheticAdministrationId("ward:" + ward.code());
            jdbc.sql("""
                    insert into clinical_ward(
                      tenant_id, facility_id, department_id, ward_id, ward_code, display_name, status)
                    values (:tenant, :facility, :department, :ward, :code, :name, 'ACTIVE')
                    on conflict (tenant_id, ward_id) do update
                    set facility_id = excluded.facility_id, department_id = excluded.department_id,
                      ward_code = excluded.ward_code, display_name = excluded.display_name,
                      status = 'ACTIVE', effective_until = null
                    """).param("tenant", TENANT_ID).param("facility", ward.facilityId())
                    .param("department", departmentId).param("ward", wardId)
                    .param("code", ward.code()).param("name", ward.name()).update();
            for (int number = 1; number <= ward.bedCount(); number++) {
                UUID bedId = syntheticAdministrationId("bed:" + ward.code() + ":" + number);
                String departmentName = ward.name().replace("一病区", "").replace("病区", "");
                jdbc.sql("""
                        insert into clinical_bed(tenant_id, bed_id, ward_id, bed_label, status)
                        values (:tenant, :bed, :ward, :label, 'ACTIVE')
                        on conflict (tenant_id, bed_id) do update
                        set ward_id = excluded.ward_id, bed_label = excluded.bed_label,
                          status = 'ACTIVE', effective_until = null
                        """).param("tenant", TENANT_ID).param("bed", bedId).param("ward", wardId)
                        .param("label", departmentName + "-" + String.format("%02d床", number)).update();
            }
        }
    }

    private void upsertTertiaryHospitalWorkforceFixtures() {
        List<AdministrationStaff> staff = List.of(
                new AdministrationStaff("JC-DR-1001", "赵启明 / Qiming Zhao", "qiming.zhao", "CHIEF_PHYSICIAN", "CARDIOLOGY", "主任医师", "PHYSICIAN_LICENSE"),
                new AdministrationStaff("JC-DR-1002", "孙雅宁 / Yaning Sun", "yaning.sun", "ATTENDING_PHYSICIAN", "RESPIRATORY", "主治医师", "PHYSICIAN_LICENSE"),
                new AdministrationStaff("JC-DR-1003", "郭文博 / Wenbo Guo", "wenbo.guo", "ATTENDING_PHYSICIAN", "GASTROENTEROLOGY", "主治医师", "PHYSICIAN_LICENSE"),
                new AdministrationStaff("JC-DR-1004", "何俊杰 / Junjie He", "junjie.he", "SURGEON", "ORTHOPEDICS", "副主任医师", "PHYSICIAN_LICENSE"),
                new AdministrationStaff("JC-DR-1005", "罗思源 / Siyuan Luo", "siyuan.luo", "EMERGENCY_PHYSICIAN", "EMERGENCY", "主治医师", "PHYSICIAN_LICENSE"),
                new AdministrationStaff("JC-DR-1006", "郑雨桐 / Yutong Zheng", "yutong.zheng", "PEDIATRICIAN", "PEDIATRICS", "副主任医师", "PHYSICIAN_LICENSE"),
                new AdministrationStaff("JC-DR-1007", "梁安然 / Anran Liang", "anran.liang", "ICU_PHYSICIAN", "ICU", "主治医师", "PHYSICIAN_LICENSE"),
                new AdministrationStaff("JC-DR-1008", "谢承宇 / Chengyu Xie", "chengyu.xie", "RADIOLOGIST", "RADIOLOGY", "主治医师", "PHYSICIAN_LICENSE"),
                new AdministrationStaff("JC-NR-2001", "唐静怡 / Jingyi Tang", "jingyi.tang", "NURSE_MANAGER", "NURSING-ADMIN", "护理部副主任", "NURSE_LICENSE"),
                new AdministrationStaff("JC-NR-2002", "许佳慧 / Jiahui Xu", "jiahui.xu", "REGISTERED_NURSE", "ICU", "主管护师", "NURSE_LICENSE"),
                new AdministrationStaff("JC-NR-2003", "韩雪晴 / Xueqing Han", "xueqing.han", "REGISTERED_NURSE", "RESPIRATORY", "护师", "NURSE_LICENSE"),
                new AdministrationStaff("JC-NR-2004", "冯悦琳 / Yuelin Feng", "yuelin.feng", "REGISTERED_NURSE", "OBSTETRICS", "主管护师", "NURSE_LICENSE"),
                new AdministrationStaff("JC-PH-3001", "邓清华 / Qinghua Deng", "qinghua.deng", "PHARMACIST", "PHARMACY", "主管药师", "PHARMACIST_LICENSE"),
                new AdministrationStaff("JC-PH-3002", "蒋书涵 / Shuhan Jiang", "shuhan.jiang", "PHARMACIST", "PHARMACY", "药师", "PHARMACIST_LICENSE"),
                new AdministrationStaff("JC-LT-4001", "曹瑞峰 / Ruifeng Cao", "ruifeng.cao", "LAB_TECHNICIAN", "LABORATORY", "主管技师", "TECHNICIAN_LICENSE"),
                new AdministrationStaff("JC-RT-4002", "彭晨曦 / Chenxi Peng", "chenxi.peng", "IMAGING_TECHNICIAN", "RADIOLOGY", "主管技师", "TECHNICIAN_LICENSE"),
                new AdministrationStaff("JC-PA-4003", "曾美琪 / Meiqi Zeng", "meiqi.zeng", "PATHOLOGY_TECHNICIAN", "PATHOLOGY", "病理技师", "TECHNICIAN_LICENSE"),
                new AdministrationStaff("JC-AD-5001", "肖国强 / Guoqiang Xiao", "guoqiang.xiao", "CLINICAL_ADMIN", "MEDICAL-AFFAIRS", "医务处干事", null),
                new AdministrationStaff("JC-MR-5002", "田淑兰 / Shulan Tian", "shulan.tian", "MEDICAL_RECORDS", "MEDICAL-RECORDS", "病案编码员", null),
                new AdministrationStaff("JC-IT-5003", "董子墨 / Zimo Dong", "zimo.dong", "SECURITY_AUDITOR", "INFORMATION-CENTER", "信息安全工程师", null),
                new AdministrationStaff("JC-RG-5004", "袁梦涵 / Menghan Yuan", "menghan.yuan", "REGISTRAR", "MEDICAL-AFFAIRS", "门诊服务专员", null),
                new AdministrationStaff("JC-RS-5005", "潘博文 / Bowen Pan", "bowen.pan", "RESEARCHER", "MEDICAL-AFFAIRS", "临床研究协调员", null));
        String workforcePasswordHash = new BCryptPasswordEncoder(12).encode(loginPassword);
        for (AdministrationStaff member : staff) {
            UUID personId = syntheticAdministrationId("person:" + member.externalSubject());
            UUID userId = syntheticAdministrationId("user:" + member.externalSubject());
            UUID roleId = syntheticAdministrationId("role:" + member.externalSubject());
            UUID departmentId = jdbc.sql("""
                    select department_id from clinical_department
                    where tenant_id = :tenant and facility_id = :facility and department_code = :code
                    """).param("tenant", TENANT_ID).param("facility", FACILITY_ID)
                    .param("code", member.departmentCode()).query(UUID.class).single();
            jdbc.sql("""
                    insert into workforce_person(
                      tenant_id, person_id, person_code, display_name, status, effective_from)
                    values (:tenant, :person, :code, :name, 'ACTIVE', timestamptz '2024-01-01 00:00:00+08')
                    on conflict (tenant_id, person_id) do update
                    set person_code = excluded.person_code, display_name = excluded.display_name,
                      status = 'ACTIVE', effective_until = null
                    """).param("tenant", TENANT_ID).param("person", personId).param("code", member.personCode())
                    .param("name", member.displayName()).update();
            jdbc.sql("""
                    insert into app_user(
                      tenant_id, user_id, person_id, external_subject, display_name, status)
                    values (:tenant, :user, :person, :subject, :name, 'ACTIVE')
                    on conflict (tenant_id, user_id) do update
                    set person_id = excluded.person_id, external_subject = excluded.external_subject,
                      display_name = excluded.display_name, status = 'ACTIVE'
                    """).param("tenant", TENANT_ID).param("user", userId).param("person", personId)
                    .param("subject", member.externalSubject()).param("name", member.displayName()).update();
            jdbc.sql("""
                    insert into dev_user_credential(tenant_id, user_id, username, password_hash)
                    values (:tenant, :user, :username, :password_hash)
                    on conflict (tenant_id, user_id) do update
                    set username = excluded.username,
                      password_hash = excluded.password_hash,
                      failed_attempts = 0,
                      locked_until = null,
                      updated_at = now()
                    """).param("tenant", TENANT_ID).param("user", userId)
                    .param("username", member.externalSubject()).param("password_hash", workforcePasswordHash)
                    .update();
            jdbc.sql("""
                    insert into role_assignment(
                      tenant_id, role_assignment_id, user_id, person_id, organization_id,
                      facility_id, role_code, valid_from, status)
                    values (:tenant, :role, :user, :person, :organization,
                      :facility, :role_code, timestamptz '2024-01-01 00:00:00+08', 'ACTIVE')
                    on conflict (tenant_id, role_assignment_id) do update
                    set user_id = excluded.user_id, person_id = excluded.person_id,
                      organization_id = excluded.organization_id, facility_id = excluded.facility_id,
                      role_code = excluded.role_code, status = 'ACTIVE', valid_until = null
                    """).param("tenant", TENANT_ID).param("role", roleId).param("user", userId)
                    .param("person", personId).param("organization", ORGANIZATION_ID)
                    .param("facility", FACILITY_ID).param("role_code", member.roleCode()).update();
            jdbc.sql("""
                    update workforce_assignment
                    set department_id = :department, ward_id = null, position_code = :position,
                      status = 'ACTIVE', valid_until = null
                    where tenant_id = :tenant and source_role_assignment_id = :role
                    """).param("department", departmentId).param("position", member.positionName())
                    .param("tenant", TENANT_ID).param("role", roleId).update();
            if (member.credentialType() != null) {
                UUID credentialId = syntheticAdministrationId("credential:" + member.externalSubject());
                jdbc.sql("""
                        insert into practitioner_credential(
                          tenant_id, credential_id, person_id, credential_type, registration_number,
                          issuing_authority, practice_scope, status, valid_from, valid_until)
                        values (:tenant, :credential, :person, :type, :registration,
                          '江城市卫生健康委员会', jsonb_build_object(
                            'primary_department', :department_code, 'professional_title', :position),
                          'ACTIVE', timestamptz '2023-01-01 00:00:00+08',
                          timestamptz '2032-12-31 23:59:59+08')
                        on conflict (tenant_id, credential_id) do update
                        set person_id = excluded.person_id, credential_type = excluded.credential_type,
                          registration_number = excluded.registration_number,
                          issuing_authority = excluded.issuing_authority,
                          practice_scope = excluded.practice_scope, status = 'ACTIVE',
                          valid_from = excluded.valid_from, valid_until = excluded.valid_until
                        """).param("tenant", TENANT_ID).param("credential", credentialId)
                        .param("person", personId).param("type", member.credentialType())
                        .param("registration", "DEV-" + member.personCode())
                        .param("department_code", member.departmentCode())
                        .param("position", member.positionName()).update();
            }
        }
    }

    private void upsertTertiaryHospitalDictionaryFixtures() {
        jdbc.sql("""
                insert into dictionary_item(
                  tenant_id, dictionary_item_id, dictionary_code, item_code, item_name,
                  status, effective_from)
                select :tenant,
                  overlay(overlay(md5('tertiary-dictionary:' || seed.dictionary_code || ':' || seed.item_code)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  seed.dictionary_code, seed.item_code, seed.item_name, 'ACTIVE', date '2026-01-01'
                from (values
                  ('BLOOD_TYPE','A','A型 / Type A'), ('BLOOD_TYPE','B','B型 / Type B'),
                  ('BLOOD_TYPE','AB','AB型 / Type AB'), ('BLOOD_TYPE','O','O型 / Type O'),
                  ('RH_TYPE','POSITIVE','Rh阳性 / Rh Positive'), ('RH_TYPE','NEGATIVE','Rh阴性 / Rh Negative'),
                  ('ADMISSION_SOURCE','OUTPATIENT','门诊收入 / From Outpatient'),
                  ('ADMISSION_SOURCE','EMERGENCY','急诊收入 / From Emergency'),
                  ('ADMISSION_SOURCE','TRANSFER','其他医疗机构转入 / Transfer In'),
                  ('ADMISSION_SOURCE','OTHER','其他来源 / Other'),
                  ('DISCHARGE_DISPOSITION','HOME','医嘱离院 / Home'),
                  ('DISCHARGE_DISPOSITION','TRANSFER','转院 / Transfer'),
                  ('DISCHARGE_DISPOSITION','DEATH','死亡 / Deceased'),
                  ('DISCHARGE_DISPOSITION','AMA','非医嘱离院 / Against Medical Advice'),
                  ('TRIAGE_LEVEL','LEVEL_1','一级·濒危 / Level 1 Resuscitation'),
                  ('TRIAGE_LEVEL','LEVEL_2','二级·危重 / Level 2 Emergent'),
                  ('TRIAGE_LEVEL','LEVEL_3','三级·急症 / Level 3 Urgent'),
                  ('TRIAGE_LEVEL','LEVEL_4','四级·非急症 / Level 4 Less Urgent'),
                  ('DOCUMENT_STATUS','DRAFT','草稿 / Draft'), ('DOCUMENT_STATUS','SIGNED','已签署 / Signed'),
                  ('DOCUMENT_STATUS','ARCHIVED','已归档 / Archived'), ('DOCUMENT_STATUS','VOID','已作废 / Void'),
                  ('CREDENTIAL_TYPE','PHYSICIAN_LICENSE','医师执业证书 / Physician License'),
                  ('CREDENTIAL_TYPE','NURSE_LICENSE','护士执业证书 / Nurse License'),
                  ('CREDENTIAL_TYPE','PHARMACIST_LICENSE','药师资格证书 / Pharmacist License'),
                  ('CREDENTIAL_TYPE','TECHNICIAN_LICENSE','卫生专业技术资格 / Technician License'),
                  ('MARITAL_STATUS','UNMARRIED','未婚 / Unmarried'), ('MARITAL_STATUS','MARRIED','已婚 / Married'),
                  ('MARITAL_STATUS','DIVORCED','离异 / Divorced'), ('MARITAL_STATUS','WIDOWED','丧偶 / Widowed'),
                  ('PAYMENT_TYPE','UEBMI','城镇职工基本医疗保险 / Employee Insurance'),
                  ('PAYMENT_TYPE','URRBMI','城乡居民基本医疗保险 / Resident Insurance'),
                  ('PAYMENT_TYPE','SELF_PAY','全自费 / Self-pay'), ('PAYMENT_TYPE','OTHER','其他支付 / Other'),
                  ('CONSENT_STATUS','PENDING','待签署 / Pending'), ('CONSENT_STATUS','SIGNED','已签署 / Signed'),
                  ('CONSENT_STATUS','REFUSED','已拒绝 / Refused'), ('CONSENT_STATUS','REVOKED','已撤回 / Revoked'),
                  ('BED_CLASS','STANDARD','普通床 / Standard'), ('BED_CLASS','ICU','监护床 / ICU'),
                  ('BED_CLASS','ISOLATION','隔离床 / Isolation'), ('BED_CLASS','MATERNITY','产科床 / Maternity')
                ) as seed(dictionary_code, item_code, item_name)
                on conflict (tenant_id, dictionary_code, item_code) do update
                set dictionary_item_id = excluded.dictionary_item_id
                where dictionary_item.dictionary_item_id =
                  md5('tertiary-dictionary:' || dictionary_item.dictionary_code || ':' || dictionary_item.item_code)::uuid
                  and dictionary_item.dictionary_item_id <> excluded.dictionary_item_id
                """).param("tenant", TENANT_ID).update();
    }

    private void upsertTertiaryHospitalAuthorizationFixtures() {
        jdbc.sql("""
                insert into authorization_policy(
                  tenant_id, policy_id, policy_code, policy_name, version_no, effect, status,
                  subject_role_code, resource_type, action_code, organization_id, facility_id,
                  patient_relationship_required, relationship_types, resource_statuses,
                  purpose_codes, emergency_override_allowed, priority, valid_from,
                  created_by, approved_by, published_at)
                select :tenant,
                  overlay(overlay(md5('tertiary-policy:' || seed.policy_code) placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  seed.policy_code, seed.policy_name, 1, seed.effect, 'PUBLISHED', seed.role_code,
                  seed.resource_type, seed.action_code, :organization, :facility,
                  seed.relationship_required, seed.relationship_types::text[], array['ACTIVE'],
                  seed.purpose_codes::text[], seed.emergency_allowed, seed.priority,
                  now() - interval '30 days', :creator, :approver, now() - interval '7 days'
                from (values
                  ('NURSING-RECORD-WRITE','护理记录书写权限','ALLOW','REGISTERED_NURSE','NURSING_RECORD','WRITE',true,'{CARE_TEAM}','{DIRECT_CARE}',true,760),
                  ('NURSING-RECORD-REVIEW','护理记录审核权限','ALLOW','NURSE_MANAGER','NURSING_RECORD','REVIEW',true,'{CARE_TEAM}','{DIRECT_CARE}',true,800),
                  ('MEDICATION-DISPENSE','处方调剂权限','ALLOW','PHARMACIST','MEDICATION_ORDER','DISPENSE',true,'{CARE_TEAM}','{DIRECT_CARE}',true,780),
                  ('LAB-RESULT-VERIFY','检验结果审核权限','ALLOW','LAB_TECHNICIAN','LAB_RESULT','VERIFY',false,'{}','{DIRECT_CARE}',true,780),
                  ('IMAGING-RESULT-VERIFY','影像报告审核权限','ALLOW','RADIOLOGIST','IMAGING_RESULT','VERIFY',true,'{CARE_TEAM}','{DIRECT_CARE}',true,780),
                  ('ADMISSION-REGISTER','入院登记办理权限','ALLOW','REGISTRAR','INPATIENT_ADMISSION','CREATE',true,'{REGISTRATION}','{DIRECT_CARE}',true,730),
                  ('MEDICAL-RECORDS-ARCHIVE','病案归档权限','ALLOW','MEDICAL_RECORDS','CLINICAL_DOCUMENT','ARCHIVE',false,'{}','{MEDICAL_RECORDS}',false,850),
                  ('CLINICAL-ADMIN-ORG','机构与科室维护权限','ALLOW','CLINICAL_ADMIN','ORGANIZATION_UNIT','MANAGE',false,'{}','{ADMINISTRATION}',false,900),
                  ('SECURITY-AUDIT-READ','安全审计查看权限','ALLOW','SECURITY_AUDITOR','AUDIT_EVENT','READ',false,'{}','{AUDIT}',false,920),
                  ('SYSTEM-CONFIG-PUBLISH','系统配置发布权限','ALLOW','SYSTEM_ADMIN','CONFIG_ITEM','PUBLISH',false,'{}','{ADMINISTRATION}',false,950),
                  ('RESEARCH-DEIDENTIFIED-READ','科研脱敏数据查看权限','ALLOW','RESEARCHER','RESEARCH_DATASET','READ',false,'{}','{RESEARCH}',false,600),
                  ('RESEARCH-IDENTIFIED-DENY','禁止科研角色查看患者身份信息','DENY','RESEARCHER','PATIENT_IDENTITY','READ',false,'{}','{RESEARCH}',false,1100),
                  ('NURSE-PRESCRIBE-DENY','禁止护士开立药品医嘱','DENY','REGISTERED_NURSE','MEDICATION_ORDER','CREATE',false,'{}','{DIRECT_CARE}',false,1100),
                  ('REGISTRAR-CLINICAL-NOTE-DENY','禁止挂号人员查看病历正文','DENY','REGISTRAR','CLINICAL_DOCUMENT','READ_CONTENT',false,'{}','{REGISTRATION}',false,1100)
                ) as seed(policy_code, policy_name, effect, role_code, resource_type, action_code,
                  relationship_required, relationship_types, purpose_codes, emergency_allowed, priority)
                on conflict (tenant_id, policy_code, version_no) do nothing
                """).param("tenant", TENANT_ID).param("organization", ORGANIZATION_ID)
                .param("facility", FACILITY_ID).param("creator", USER_ID)
                .param("approver", COLLABORATOR_USER_ID).update();
    }

    private void upsertTertiaryHospitalAuditFixtures() {
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                select :tenant, md5('tertiary-audit:' || seed.sequence_no)::uuid,
                  now() - seed.age_hours * interval '1 hour', seed.actor_id::uuid,
                  seed.action_code, seed.resource_type, seed.resource_id::uuid,
                  'syn-admin-' || lpad(seed.sequence_no::text, 4, '0'),
                  case when seed.sequence_no = 1 then null
                    else md5('tertiary-audit-hash:' || (seed.sequence_no - 1)) || md5('tertiary-audit-hash:' || (seed.sequence_no - 1)) end,
                  md5('tertiary-audit-hash:' || seed.sequence_no) || md5('tertiary-audit-hash:' || seed.sequence_no),
                  jsonb_build_object('result', seed.result, 'summary', seed.summary,
                    'source', 'dev-synthetic-tertiary-hospital')
                from (values
                  (1, 168, '018f0000-0000-7000-8000-00000000aa04', 'ORGANIZATION_UNIT_CREATED', 'FACILITY', '018f0000-0000-7000-8000-00000000aa03', '成功', '本部院区组织资料复核通过'),
                  (2, 144, '018f0000-0000-7000-8000-00000000aa06', 'WORKFORCE_PERSON_ONBOARDED', 'WORKFORCE_PERSON', '018f0000-0000-7000-8000-00000000aa06', '成功', '临床人员身份、账号与岗位绑定完成'),
                  (3, 120, '018f0000-0000-7000-8000-00000000aa04', 'AUTHORIZATION_POLICY_PUBLISHED', 'AUTHORIZATION_POLICY', '018f0000-0000-7000-8000-00000000d201', '成功', '临床病历查看策略发布'),
                  (4, 96, '018f0000-0000-7000-8000-00000000aa04', 'DICTIONARY_IMPORT_COMPLETED', 'DICTIONARY_ITEM', '018f0000-0000-7000-8000-00000000d101', '成功', '院级基础字典批量导入并完成冲突检查'),
                  (5, 72, '018f0000-0000-7000-8000-00000000aa06', 'CONFIGURATION_VALIDATED', 'CONFIG_ITEM', '018f0000-0000-7000-8000-00000000c111', '成功', '三级医院主数据基线验证通过'),
                  (6, 48, '018f0000-0000-7000-8000-00000000aa04', 'ROLE_ASSIGNMENT_REVIEWED', 'ROLE_ASSIGNMENT', '018f0000-0000-7000-8000-00000000aa09', '成功', '系统管理员高权角色季度复核完成'),
                  (7, 24, '018f0000-0000-7000-8000-00000000aa06', 'EMERGENCY_ACCESS_REVIEWED', 'EMERGENCY_ACCESS', '018f0000-0000-7000-8000-00000000aa10', '通过', '紧急访问事后复核通过，访问范围符合最小必要原则'),
                  (8, 6, '018f0000-0000-7000-8000-00000000aa04', 'CONFIGURATION_PUBLISHED', 'CONFIG_ITEM', '018f0000-0000-7000-8000-00000000c113', '成功', '危急值通知对账任务配置发布')
                ) as seed(sequence_no, age_hours, actor_id, action_code, resource_type, resource_id, result, summary)
                on conflict (tenant_id, audit_event_id) do nothing
                """).param("tenant", TENANT_ID).update();
    }

    private void upsertAdministrationOrganizationFixtures() {
        jdbc.sql("""
                insert into clinical_department(
                  tenant_id, facility_id, department_id, department_code, display_name, status, unit_type)
                select :tenant, :facility, seed.department_id::uuid, seed.department_code,
                  seed.display_name, 'ACTIVE', seed.unit_type
                from (values
                  ('018f0000-0000-7000-8000-00000000ab01', 'EMERGENCY', '急诊医学科', 'DEPARTMENT'),
                  ('018f0000-0000-7000-8000-00000000ab02', 'NEUROLOGY', '神经内科', 'DEPARTMENT'),
                  ('018f0000-0000-7000-8000-00000000ab03', 'GENERAL-SURGERY', '普通外科', 'DEPARTMENT'),
                  ('018f0000-0000-7000-8000-00000000ab04', 'PEDIATRICS', '儿科', 'DEPARTMENT'),
                  ('018f0000-0000-7000-8000-00000000ab05', 'LABORATORY', '医学检验科', 'MEDICAL_TECH'),
                  ('018f0000-0000-7000-8000-00000000ab06', 'RADIOLOGY', '医学影像科', 'MEDICAL_TECH'),
                  ('018f0000-0000-7000-8000-00000000ab14', 'MENTAL-HEALTH', '精神心理科', 'DEPARTMENT'),
                  ('018f0000-0000-7000-8000-00000000ab15', 'GENERAL-MEDICINE', '全科医学科', 'DEPARTMENT')
                ) as seed(department_id, department_code, display_name, unit_type)
                on conflict (tenant_id, facility_id, department_id) do update
                set department_code = excluded.department_code, display_name = excluded.display_name,
                  status = 'ACTIVE', effective_until = null, unit_type = excluded.unit_type,
                  row_version = clinical_department.row_version + 1, updated_at = now()
                where (clinical_department.department_code, clinical_department.display_name,
                  clinical_department.status, clinical_department.effective_until, clinical_department.unit_type)
                  is distinct from (excluded.department_code, excluded.display_name,
                    'ACTIVE', null, excluded.unit_type)
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into clinical_ward(
                  tenant_id, facility_id, department_id, ward_id, ward_code, display_name, status)
                select :tenant, :facility, seed.department_id::uuid, seed.ward_id::uuid,
                  seed.ward_code, seed.display_name, 'ACTIVE'
                from (values
                  ('018f0000-0000-7000-8000-00000000ab01', '018f0000-0000-7000-8000-00000000bc01', 'ED-OBS', '急诊留观病区'),
                  ('018f0000-0000-7000-8000-00000000ab02', '018f0000-0000-7000-8000-00000000bc02', 'NEURO-1', '神经内科一病区'),
                  ('018f0000-0000-7000-8000-00000000ab03', '018f0000-0000-7000-8000-00000000bc03', 'GS-1', '普通外科一病区'),
                  ('018f0000-0000-7000-8000-00000000ab04', '018f0000-0000-7000-8000-00000000bc04', 'PED-1', '儿科一病区')
                ) as seed(department_id, ward_id, ward_code, display_name)
                on conflict (tenant_id, ward_id) do update
                set facility_id = excluded.facility_id, department_id = excluded.department_id,
                  ward_code = excluded.ward_code, display_name = excluded.display_name,
                  status = 'ACTIVE', effective_until = null,
                  row_version = clinical_ward.row_version + 1, updated_at = now()
                where (clinical_ward.facility_id, clinical_ward.department_id,
                  clinical_ward.ward_code, clinical_ward.display_name,
                  clinical_ward.status, clinical_ward.effective_until)
                  is distinct from (excluded.facility_id, excluded.department_id,
                    excluded.ward_code, excluded.display_name, 'ACTIVE', null)
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into clinical_bed(tenant_id, bed_id, ward_id, bed_label, status)
                select :tenant, seed.bed_id::uuid, seed.ward_id::uuid, seed.bed_label, 'ACTIVE'
                from (values
                  ('018f0000-0000-7000-8000-00000000bd01', '018f0000-0000-7000-8000-00000000bc01', '急诊医学科-留观01床'),
                  ('018f0000-0000-7000-8000-00000000bd02', '018f0000-0000-7000-8000-00000000bc01', '急诊医学科-留观02床'),
                  ('018f0000-0000-7000-8000-00000000bd03', '018f0000-0000-7000-8000-00000000bc01', '急诊医学科-留观03床'),
                  ('018f0000-0000-7000-8000-00000000bd04', '018f0000-0000-7000-8000-00000000bc02', '神经内科-01床'),
                  ('018f0000-0000-7000-8000-00000000bd05', '018f0000-0000-7000-8000-00000000bc02', '神经内科-02床'),
                  ('018f0000-0000-7000-8000-00000000bd06', '018f0000-0000-7000-8000-00000000bc02', '神经内科-03床'),
                  ('018f0000-0000-7000-8000-00000000bd07', '018f0000-0000-7000-8000-00000000bc03', '普通外科-01床'),
                  ('018f0000-0000-7000-8000-00000000bd08', '018f0000-0000-7000-8000-00000000bc03', '普通外科-02床'),
                  ('018f0000-0000-7000-8000-00000000bd09', '018f0000-0000-7000-8000-00000000bc03', '普通外科-03床'),
                  ('018f0000-0000-7000-8000-00000000bd10', '018f0000-0000-7000-8000-00000000bc04', '儿科-01床'),
                  ('018f0000-0000-7000-8000-00000000bd11', '018f0000-0000-7000-8000-00000000bc04', '儿科-02床'),
                  ('018f0000-0000-7000-8000-00000000bd12', '018f0000-0000-7000-8000-00000000bc04', '儿科-03床')
                ) as seed(bed_id, ward_id, bed_label)
                on conflict (tenant_id, bed_id) do update
                set ward_id = excluded.ward_id, bed_label = excluded.bed_label,
                  status = 'ACTIVE', effective_until = null,
                  row_version = clinical_bed.row_version + 1, updated_at = now()
                where (clinical_bed.ward_id, clinical_bed.bed_label,
                  clinical_bed.status, clinical_bed.effective_until)
                  is distinct from (excluded.ward_id, excluded.bed_label, 'ACTIVE', null)
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into practitioner_credential(
                  tenant_id, credential_id, person_id, credential_type,
                  registration_number, issuing_authority, practice_scope,
                  status, valid_from, valid_until)
                select :tenant, seed.credential_id::uuid, seed.person_id::uuid,
                  'PHYSICIAN_LICENSE', seed.registration_number,
                  '江城市卫生健康委员会', seed.practice_scope::jsonb,
                  'ACTIVE', timestamptz '2022-01-01 00:00:00+08',
                  timestamptz '2031-12-31 23:59:59+08'
                from (values
                  ('018f0000-0000-7000-8000-00000000be01', '018f0000-0000-7000-8000-00000000aa04',
                    '110420000001', '{"primary_department":"CARDIOLOGY","professional_title":"主治医师"}'),
                  ('018f0000-0000-7000-8000-00000000be02', '018f0000-0000-7000-8000-00000000aa06',
                    '110420000002', '{"primary_department":"CARDIOLOGY","professional_title":"住院医师"}'),
                  ('018f0000-0000-7000-8000-00000000be03', '018f0000-0000-7000-8000-00000000aa10',
                    '110420000003', '{"primary_department":"CARDIOLOGY","professional_title":"副主任医师"}'),
                  ('018f0000-0000-7000-8000-00000000be04', '018f0000-0000-7000-8000-00000000aa12',
                    '110420000004', '{"primary_department":"CARDIOLOGY","professional_title":"主任医师"}')
                ) as seed(credential_id, person_id, registration_number, practice_scope)
                on conflict (tenant_id, credential_id) do update
                set person_id = excluded.person_id,
                  credential_type = excluded.credential_type,
                  registration_number = excluded.registration_number,
                  issuing_authority = excluded.issuing_authority,
                  practice_scope = excluded.practice_scope,
                  status = 'ACTIVE', valid_from = excluded.valid_from,
                  valid_until = excluded.valid_until,
                  row_version = practitioner_credential.row_version + 1,
                  updated_at = now()
                where (practitioner_credential.person_id,
                  practitioner_credential.credential_type,
                  practitioner_credential.registration_number,
                  practitioner_credential.issuing_authority,
                  practitioner_credential.practice_scope,
                  practitioner_credential.status,
                  practitioner_credential.valid_from,
                  practitioner_credential.valid_until)
                  is distinct from (excluded.person_id, excluded.credential_type,
                    excluded.registration_number, excluded.issuing_authority,
                    excluded.practice_scope, 'ACTIVE', excluded.valid_from,
                    excluded.valid_until)
                """).param("tenant", TENANT_ID).update();
    }

    private void upsertOutpatientClinicalFixture() {
        UUID patientId = UUID.fromString("018f0000-0000-7000-8000-000000000001");
        UUID encounterId = UUID.fromString("018f0000-0000-7000-8000-000000000101");

        jdbc.sql("""
                insert into clinical_diagnosis(
                  tenant_id, diagnosis_id, patient_id, encounter_id, facility_id, lifecycle_status,
                  current_diagnosis_role, current_version_id, author_user_id)
                select :tenant, '018f0000-0000-7000-8000-00000000ed01'::uuid, :patient, :encounter,
                  :facility, 'ACTIVE', 'PRIMARY', '018f0000-0000-7000-8000-00000000ed11'::uuid, :author
                where not exists (
                  select 1 from clinical_diagnosis where tenant_id = :tenant and encounter_id = :encounter
                    and lifecycle_status = 'ACTIVE' and current_diagnosis_role = 'PRIMARY')
                on conflict (tenant_id, diagnosis_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_diagnosis_version(
                  tenant_id, diagnosis_version_id, diagnosis_id, version_no, terminology_system,
                  terminology_release, code, code_display_snapshot, diagnosis_text, diagnosis_role,
                  certainty, evidence_summary, plan_summary, effective_at, change_type, authored_by)
                select :tenant, '018f0000-0000-7000-8000-00000000ed11'::uuid,
                  '018f0000-0000-7000-8000-00000000ed01'::uuid, 1, 'ICD-10-CN', '2026B', 'I10.0',
                  '原发性高血压（更新术语）', '原发性高血压 2 级（高危）', 'PRIMARY', 'CONFIRMED',
                  '诊室血压 168/102 mmHg，家庭血压监测连续升高',
                  '评估靶器官损害，启动降压治疗并安排两周复诊',
                  now() - interval '2 hour', 'CREATED', :author
                where exists (select 1 from clinical_diagnosis where tenant_id = :tenant
                  and diagnosis_id = '018f0000-0000-7000-8000-00000000ed01'::uuid)
                on conflict (tenant_id, diagnosis_version_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_diagnosis(
                  tenant_id, diagnosis_id, patient_id, encounter_id, facility_id, lifecycle_status,
                  current_diagnosis_role, current_version_id, author_user_id)
                values (:tenant, '018f0000-0000-7000-8000-00000000ed02'::uuid, :patient, :encounter,
                  :facility, 'ACTIVE', 'DIFFERENTIAL', '018f0000-0000-7000-8000-00000000ed12'::uuid, :author)
                on conflict (tenant_id, diagnosis_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_diagnosis_version(
                  tenant_id, diagnosis_version_id, diagnosis_id, version_no, terminology_system,
                  terminology_release, code, code_display_snapshot, diagnosis_text, diagnosis_role,
                  certainty, evidence_summary, plan_summary, effective_at, change_type, authored_by)
                values (:tenant, '018f0000-0000-7000-8000-00000000ed12'::uuid,
                  '018f0000-0000-7000-8000-00000000ed02'::uuid, 1, 'ICD-10-CN', '2026B', 'I10.9',
                  '高血压病', '继发性高血压待排', 'DIFFERENTIAL', 'PROVISIONAL',
                  '发病年龄与血压波动需要结合肾功能及内分泌筛查',
                  '复核药物史，完善肾功能、电解质和尿常规', now() - interval '90 minute',
                  'CREATED', :author)
                on conflict (tenant_id, diagnosis_version_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();

        jdbc.sql("""
                insert into medication_catalog_version(
                  tenant_id, medication_catalog_version_id, catalog_code, drug_code, ingredient_code,
                  display_name, minimum_single_dose, maximum_single_dose, dose_unit,
                  effective_from, release_version, status)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef01'::uuid, 'MED-AMLODIPINE-5',
                  'DRUG-AMLODIPINE', 'ING-AMLODIPINE', '苯磺酸氨氯地平片', 2.5, 10, 'mg',
                  date '2026-01-01', '2026.1', 'ACTIVE')
                on conflict (tenant_id, medication_catalog_version_id) do nothing
                """).param("tenant", TENANT_ID).update();
        upsertOutpatientOrder("018f0000-0000-7000-8000-00000000ef11", "ACTIVE",
                "控制高血压并降低心脑血管风险");
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, instructions, item_state,
                  medication_catalog_version_id, drug_code, ingredient_code, dose_value,
                  dose_unit, route_code, frequency_code)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef21'::uuid,
                  '018f0000-0000-7000-8000-00000000ef11'::uuid, 'MEDICATION', 'MED-AMLODIPINE-5',
                  '苯磺酸氨氯地平片', 14, '片', '每日上午口服，监测血压及下肢水肿', 'ACTIVE',
                  '018f0000-0000-7000-8000-00000000ef01'::uuid, 'DRUG-AMLODIPINE',
                  'ING-AMLODIPINE', 5, 'mg', 'PO', 'QD')
                on conflict (tenant_id, order_item_id) do nothing
                """).param("tenant", TENANT_ID).update();
        upsertExecutionTask("018f0000-0000-7000-8000-00000000ef31",
                "018f0000-0000-7000-8000-00000000ef11", "018f0000-0000-7000-8000-00000000ef21",
                patientId, encounterId, "PENDING", 14, 0, "片");

        upsertOutpatientOrder("018f0000-0000-7000-8000-00000000ef12", "COMPLETED",
                "评估高血压相关肾功能与电解质异常");
        upsertOrderItem("018f0000-0000-7000-8000-00000000ef22",
                "018f0000-0000-7000-8000-00000000ef12", "LAB", "LAB-K", "血钾", "COMPLETED");
        upsertExecutionTask("018f0000-0000-7000-8000-00000000ef32",
                "018f0000-0000-7000-8000-00000000ef12", "018f0000-0000-7000-8000-00000000ef22",
                patientId, encounterId, "COMPLETED", 1, 1, "次");

        upsertOutpatientOrder("018f0000-0000-7000-8000-00000000ef13", "ACTIVE",
                "评估长期高血压导致的心脏结构改变");
        upsertOrderItem("018f0000-0000-7000-8000-00000000ef23",
                "018f0000-0000-7000-8000-00000000ef13", "IMAGING", "IMG-ECHO", "超声心动图", "ACTIVE");
        upsertExecutionTask("018f0000-0000-7000-8000-00000000ef33",
                "018f0000-0000-7000-8000-00000000ef13", "018f0000-0000-7000-8000-00000000ef23",
                patientId, encounterId, "PENDING", 1, 0, "次");

        jdbc.sql("""
                insert into clinical_result(
                  tenant_id, result_id, patient_id, encounter_id, facility_id, order_id,
                  execution_task_id, report_type, source_system, source_report_key,
                  current_version_id, author_user_id)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef41'::uuid, :patient, :encounter,
                  :facility, '018f0000-0000-7000-8000-00000000ef12'::uuid,
                  '018f0000-0000-7000-8000-00000000ef32'::uuid, 'LAB', 'SYNTHETIC_LIS',
                  'SYN-OPD-001-K', '018f0000-0000-7000-8000-00000000ef42'::uuid, :author)
                on conflict (tenant_id, result_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_result_version(
                  tenant_id, result_version_id, result_id, version_no, report_status, conclusion,
                  reported_at, change_type, authored_by)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef42'::uuid,
                  '018f0000-0000-7000-8000-00000000ef41'::uuid, 1, 'FINAL',
                  '血钾轻度降低，建议结合用药及肾功能复核并安排复查。',
                  now() - interval '45 minute', 'INITIAL', :author)
                on conflict (tenant_id, result_version_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_result_observation(
                  tenant_id, observation_id, result_version_id, item_code, item_name, value_type,
                  numeric_value, unit, reference_low, reference_high, abnormal_flag)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef43'::uuid,
                  '018f0000-0000-7000-8000-00000000ef42'::uuid, 'K', '血钾', 'NUMERIC',
                  3.3, 'mmol/L', 3.5, 5.5, 'LOW')
                on conflict (tenant_id, observation_id) do nothing
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into referral(
                  tenant_id, referral_id, patient_id, encounter_id, facility_id, referral_type,
                  target_department, reason, clinical_summary, status, sent_at)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef51'::uuid, :patient, :encounter,
                  :facility, 'INTERNAL', '心血管内科', '高血压高危分层及用药方案会诊',
                  '血压持续升高，已完善血钾检查并启动基础降压治疗，请评估继发因素及联合用药。',
                  'SENT', now() - interval '30 minute')
                on conflict (tenant_id, referral_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into referral(
                  tenant_id, referral_id, patient_id, encounter_id, facility_id, referral_type,
                  target_department, reason, clinical_summary, status)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef52'::uuid, :patient, :encounter,
                  :facility, 'INTERNAL', '营养科', '高血压生活方式干预评估',
                  '需要制定限盐、体重管理与家庭血压监测计划。', 'DRAFT')
                on conflict (tenant_id, referral_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).update();

        upsertOutpatientOrder("018f0000-0000-7000-8000-00000000ef14", "COMPLETED",
                "评估高血压相关心律与心肌缺血风险");
        upsertOrderItem("018f0000-0000-7000-8000-00000000ef24",
                "018f0000-0000-7000-8000-00000000ef14", "IMAGING", "ECG-12LEAD", "十二导联心电图", "COMPLETED");
        upsertExecutionTask("018f0000-0000-7000-8000-00000000ef34",
                "018f0000-0000-7000-8000-00000000ef14", "018f0000-0000-7000-8000-00000000ef24",
                patientId, encounterId, "COMPLETED", 1, 1, "次");
        jdbc.sql("""
                insert into clinical_result(
                  tenant_id, result_id, patient_id, encounter_id, facility_id, order_id,
                  execution_task_id, report_type, source_system, source_report_key,
                  current_version_id, author_user_id)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef61'::uuid, :patient, :encounter,
                  :facility, '018f0000-0000-7000-8000-00000000ef14'::uuid,
                  '018f0000-0000-7000-8000-00000000ef34'::uuid, 'IMAGING',
                  'JC-AFFILIATED-HOSPITAL-ECG-SIMULATION', 'ECG-20260828-OP0842',
                  '018f0000-0000-7000-8000-00000000ef62'::uuid, :author)
                on conflict (tenant_id, result_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .param("facility", FACILITY_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_result_version(
                  tenant_id, result_version_id, result_id, version_no, report_status, conclusion,
                  reported_at, change_type, authored_by)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef62'::uuid,
                  '018f0000-0000-7000-8000-00000000ef61'::uuid, 1, 'FINAL',
                  '窦性心律，心率 76 次/分；电轴正常，未见急性 ST-T 缺血性改变。',
                  now() - interval '35 minute', 'INITIAL', :author)
                on conflict (tenant_id, result_version_id) do nothing
                """).param("tenant", TENANT_ID).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_result_observation(
                  tenant_id, observation_id, result_version_id, item_code, item_name, value_type,
                  text_value, abnormal_flag)
                values (:tenant, '018f0000-0000-7000-8000-00000000ef63'::uuid,
                  '018f0000-0000-7000-8000-00000000ef62'::uuid, 'ECG-CONCLUSION', '心电图结论',
                  'TEXT', '窦性心律，心率 76 次/分，未见急性缺血性改变', 'NORMAL')
                on conflict (tenant_id, observation_id) do nothing
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                insert into outpatient_followup(
                  tenant_id, followup_id, patient_id, encounter_id, followup_type, content,
                  outcome, status, due_at, completed_at, created_at)
                select :tenant, seed.followup_id::uuid, :patient, :encounter,
                  seed.followup_type, seed.content, seed.outcome, seed.status,
                  seed.due_at, seed.completed_at, seed.created_at
                from (values
                  ('018f0000-0000-7000-8000-00000000efa1', 'EDUCATION',
                   '高血压家庭监测教育：每早晚规范测量并记录，限盐少于 5 g/日，出现胸痛或神经系统症状立即就诊。',
                   '已完成面对面教育，患者可正确复述测压方法、用药时间和红旗症状。',
                   'COMPLETED', now() - interval '1 hour', now() - interval '50 minute', now() - interval '2 hour'),
                  ('018f0000-0000-7000-8000-00000000efa2', 'REVISIT',
                   '两周后心内科复诊：携家庭血压日志，复查血钾、肌酐和 eGFR，评估氨氯地平疗效与下肢水肿。',
                   null, 'PENDING', now() + interval '14 day', null, now() - interval '90 minute'),
                  ('018f0000-0000-7000-8000-00000000efa3', 'FOLLOWUP',
                   '用药后 72 小时电话随访：核对服药依从性、家庭血压、头晕及心悸，若持续血压 ≥180/110 mmHg 启动紧急处置。',
                   null, 'PENDING', now() + interval '3 day', null, now() - interval '80 minute'),
                  ('018f0000-0000-7000-8000-00000000efa4', 'FOLLOWUP',
                   '血钾偏低处置后当日随访：核对无肌无力、心悸或晕厥，确认复测安排与饮食建议。',
                   '患者无肌无力、心悸或晕厥；已安排 48 小时内复查电解质。',
                   'COMPLETED', now() - interval '30 minute', now() - interval '20 minute', now() - interval '70 minute')
                ) as seed(followup_id, followup_type, content, outcome, status,
                  due_at, completed_at, created_at)
                on conflict (tenant_id, followup_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId).param("encounter", encounterId)
                .update();
    }

    private void upsertOutpatientOrder(String orderId, String status, String indication) {
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                values (:tenant, cast(:order as uuid), '018f0000-0000-7000-8000-000000000001'::uuid,
                  '018f0000-0000-7000-8000-000000000101'::uuid, :facility, 'TEMPORARY', :status,
                  :indication, :author, :author, now() - interval '1 hour', 'RULESET-MEDICATION-6')
                on conflict (tenant_id, order_id) do nothing
                """).param("tenant", TENANT_ID).param("order", orderId).param("facility", FACILITY_ID)
                .param("status", status).param("indication", indication).param("author", USER_ID).update();
    }

    private void upsertOrderItem(
            String itemId, String orderId, String itemType, String catalogCode, String displayName, String state) {
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, instructions, item_state)
                values (:tenant, cast(:item as uuid), cast(:order as uuid), :type, :code, :name,
                  1, '次', '按申请完成并回传结构化结果', :state)
                on conflict (tenant_id, order_item_id) do nothing
                """).param("tenant", TENANT_ID).param("item", itemId).param("order", orderId)
                .param("type", itemType).param("code", catalogCode).param("name", displayName)
                .param("state", state).update();
    }

    private void upsertExecutionTask(
            String taskId, String orderId, String itemId, UUID patientId, UUID encounterId,
            String state, int requested, int performed, String unit) {
        jdbc.sql("""
                insert into order_execution_task(
                  tenant_id, execution_task_id, order_id, order_item_id, patient_id, encounter_id,
                  task_state, requested_quantity, performed_quantity, quantity_unit)
                values (:tenant, cast(:task as uuid), cast(:order as uuid), cast(:item as uuid),
                  :patient, :encounter, :state, :requested, :performed, :unit)
                on conflict (tenant_id, execution_task_id) do nothing
                """).param("tenant", TENANT_ID).param("task", taskId).param("order", orderId)
                .param("item", itemId).param("patient", patientId).param("encounter", encounterId)
                .param("state", state).param("requested", requested).param("performed", performed)
                .param("unit", unit).update();
    }

    private void upsertDocumentTemplates(JsonNode root) {
        jdbc.sql("""
                select document_type_code, display_name, template_sections::text
                from inpatient_document_rule where tenant_id = :tenant and status = 'ACTIVE'
                """).param("tenant", TENANT_ID)
                .query((rs, row) -> new SyntheticTemplate(
                        rs.getString("document_type_code"), rs.getString("display_name"),
                        objectMapper.readTree(rs.getString("template_sections"))))
                .list().forEach(template -> ensureDocumentTemplate(
                        template.documentTypeCode(), template.displayName(), template.sections()));
        for (JsonNode item : root.path("cases")) {
            for (JsonNode document : item.path("documents")) {
                List<String> fields = new ArrayList<>();
                fields.addAll(document.path("sections").propertyNames());
                ensureDocumentTemplate(document.path("document_type_code").stringValue(),
                        document.path("document_type_code").stringValue(), objectMapper.valueToTree(fields));
            }
        }
    }

    private void ensureDocumentTemplate(String documentTypeCode, String displayName, JsonNode sections) {
        long existing = jdbc.sql("""
                select count(*) from clinical_document_template template
                join clinical_document_template_version version
                  on version.tenant_id = template.tenant_id and version.template_id = template.template_id
                where template.tenant_id = :tenant and template.document_type_code = :type
                  and template.lifecycle_status = 'ACTIVE' and version.status = 'PUBLISHED'
                """).param("tenant", TENANT_ID).param("type", documentTypeCode).query(Long.class).single();
        if (existing > 0) return;
        UUID templateId = UUID.nameUUIDFromBytes(
                ("synthetic-template:" + documentTypeCode).getBytes(StandardCharsets.UTF_8));
        UUID versionId = UUID.nameUUIDFromBytes(
                ("synthetic-template-version:" + documentTypeCode + ":1").getBytes(StandardCharsets.UTF_8));
        List<String> requiredFields = new ArrayList<>();
        sections.forEach(value -> requiredFields.add(value.stringValue()));
        Map<String, Object> properties = new LinkedHashMap<>();
        requiredFields.forEach(field -> properties.put(field, Map.of("type", "string")));
        String schema;
        try {
            schema = objectMapper.writeValueAsString(Map.of(
                    "type", "object", "properties", properties, "additionalProperties", true));
        } catch (Exception impossible) {
            throw new IllegalStateException("Synthetic template schema is invalid", impossible);
        }
        jdbc.sql("""
                insert into clinical_document_template(
                  tenant_id, template_id, template_code, display_name, document_type_code, created_by)
                values (:tenant, :template, :code, :display_name, :type, :creator)
                on conflict (tenant_id, template_id) do nothing
                """).param("tenant", TENANT_ID).param("template", templateId)
                .param("code", "EMR." + documentTypeCode).param("display_name", displayName)
                .param("type", documentTypeCode).param("creator", USER_ID).update();
        jdbc.sql("""
                insert into clinical_document_template_version(
                  tenant_id, template_id, template_version_id, version_no, status,
                  section_schema, required_fields, display_rules, effective_from,
                  created_by, approved_by, published_at)
                values (:tenant, :template, :version, 1, 'PUBLISHED', cast(:schema as jsonb),
                  cast(:required as text[]), '{"fixture_source":"dev-simulation"}'::jsonb, now() - interval '1 day',
                  :creator, :approver, now() - interval '1 day')
                on conflict (tenant_id, template_id, template_version_id) do nothing
                """).param("tenant", TENANT_ID).param("template", templateId).param("version", versionId)
                .param("schema", schema).param("required", "{" + String.join(",", requiredFields) + "}")
                .param("creator", USER_ID).param("approver", COLLABORATOR_USER_ID).update();
    }

    private void upsertInpatientDocumentRules() {
        jdbc.sql("""
                insert into inpatient_document_rule(
                  tenant_id, rule_code, document_type_code, display_name, category_code, trigger_type,
                  due_minutes, required_signature_level, template_sections, status)
                select :tenant, seed.rule_code, seed.document_type_code, seed.display_name,
                  seed.category_code, seed.trigger_type, seed.due_minutes, seed.signature_level,
                  seed.sections::jsonb, 'ACTIVE'
                from (values
                  ('IP-ADMISSION', 'WS445.5.ADMISSION_RECORD', '入院记录', 'ADMISSION', 'ADMISSION', 1440, 'ATTENDING', '["chief_complaint","present_illness","past_history","personal_history","marital_history","family_history","physical_examination","specialty_examination","diagnostic_basis","differential_diagnosis","assessment","treatment_plan","communication"]'),
                  ('IP-FIRST-COURSE', 'WS445.5.FIRST_COURSE_RECORD', '首次病程记录', 'COURSE', 'ADMISSION', 480, 'ATTENDING', '["case_features","provisional_diagnosis","diagnostic_basis","differential_diagnosis","risk_assessment","treatment_plan","communication"]'),
                  ('IP-ATTENDING-ROUND', 'WS445.5.ATTENDING_REVIEW', '主治医师首次查房记录', 'ROUND', 'ADMISSION', 2880, 'ATTENDING', '["round_time","participants","history_supplement","examination_findings","diagnostic_analysis","treatment_adjustment","attending_opinion"]'),
                  ('IP-DAILY-COURSE', 'WS445.5.DAILY_COURSE_RECORD', '日常病程记录', 'COURSE', 'DAILY', 1440, 'AUTHOR', '["event_time","condition_change","examination_findings","result_analysis","treatment_response","assessment","treatment_plan","communication"]'),
                  ('IP-CHIEF-ROUND', 'WS445.5.CHIEF_REVIEW', '主任医师查房记录', 'ROUND', 'MANUAL', 1440, 'CHIEF', '["round_time","participants","case_summary","diagnostic_analysis","differential_diagnosis","treatment_guidance","chief_opinion"]'),
                  ('IP-STAGE-SUMMARY', 'WS445.5.STAGE_SUMMARY', '阶段小结', 'COURSE', 'MANUAL', 1440, 'ATTENDING', '["period","admission_summary","diagnosis_evolution","treatment_course","current_condition","next_plan"]'),
                  ('IP-CONSULTATION', 'WS445.5.CONSULTATION_RECORD', '会诊记录', 'CONSULTATION', 'EVENT', 1440, 'ATTENDING', '["request_time","consultation_time","reason","consultant","consultation_opinion","disposition"]'),
                  ('IP-TRANSFER', 'WS445.5.TRANSFER_RECORD', '转科记录', 'EVENT', 'EVENT', 360, 'ATTENDING', '["transfer_time","from_department","to_department","condition_before_transfer","diagnosis","treatment_course","transfer_reason","handover_plan"]'),
                  ('IP-PREOPERATIVE', 'WS445.5.PREOPERATIVE_SUMMARY', '术前小结', 'PERIOPERATIVE', 'EVENT', 720, 'ATTENDING', '["preoperative_diagnosis","surgical_indication","operation_plan","risk_assessment","preparation","consent"]'),
                  ('IP-OPERATION', 'WS445.5.OPERATION_RECORD', '手术记录', 'PERIOPERATIVE', 'EVENT', 1440, 'ATTENDING', '["operation_time","preoperative_diagnosis","postoperative_diagnosis","operation_name","surgeon","anesthesia","procedure","findings","specimen","complications"]'),
                  ('IP-RESCUE', 'WS445.5.RESCUE_RECORD', '抢救记录', 'EVENT', 'EVENT', 360, 'ATTENDING', '["event_time","recorded_time","condition","participants","measures","medications","response","outcome","late_entry_reason"]'),
                  ('IP-TRANSFUSION', 'WS445.5.TRANSFUSION_RECORD', '输血记录', 'EVENT', 'EVENT', 360, 'ATTENDING', '["indication","consent","blood_component","verification","start_time","end_time","monitoring","reaction","outcome"]'),
                  ('IP-CRITICAL', 'WS445.5.CRITICAL_ILLNESS_RECORD', '病危病重记录', 'EVENT', 'EVENT', 360, 'ATTENDING', '["event_time","severity","clinical_basis","treatment","communication","family_acknowledgement"]'),
                  ('IP-DISCHARGE', 'WS445.5.DISCHARGE_RECORD', '出院记录', 'TERMINAL', 'DISCHARGE', 1440, 'ATTENDING', '["admission_diagnosis","discharge_diagnosis","admission_condition","treatment_course","discharge_condition","discharge_medication","follow_up","education"]'),
                  ('IP-DEATH', 'WS445.5.DEATH_RECORD', '死亡记录', 'TERMINAL', 'EVENT', 1440, 'CHIEF', '["death_time","admission_condition","treatment_course","rescue_process","cause_of_death","death_diagnosis","family_communication"]'),
                  ('IP-FOUR-LEVEL-E2E', 'EMR.FOUR_LEVEL_REVIEW', '四级审签文书', 'COURSE', 'MANUAL', 1440, 'MEDICAL_RECORDS', '["case_summary","diagnostic_basis","treatment_course","quality_conclusion"]')
                ) as seed(rule_code, document_type_code, display_name, category_code, trigger_type,
                  due_minutes, signature_level, sections)
                on conflict (tenant_id, rule_code) do nothing
                """).param("tenant", TENANT_ID).update();
    }

    private void upsertClinicalPathwayDefinitions() {
        jdbc.sql("""
                insert into clinical_pathway_definition(
                  tenant_id, pathway_definition_id, pathway_code, display_name,
                  specialty_code, diagnosis_code, status, created_by)
                values (:tenant, :definition, 'HF-INPATIENT', '心力衰竭住院标准路径',
                  'CARDIOLOGY', 'I50', 'ACTIVE', :creator)
                on conflict (tenant_id, pathway_definition_id) do nothing
                """).param("tenant", TENANT_ID).param("definition", HEART_FAILURE_PATHWAY_ID)
                .param("creator", USER_ID).update();
        jdbc.sql("""
                insert into clinical_pathway_version(
                  tenant_id, pathway_version_id, pathway_definition_id, version_no, status,
                  admission_criteria, created_by, approved_by, created_at, published_at)
                values (:tenant, :version, :definition, 1, 'PUBLISHED',
                  '主要诊断为心力衰竭，临床医生已核对诊断、禁忌证、合并症与患者意愿后确认入径。',
                  :creator, :approver, now() - interval '2 days', now() - interval '1 day')
                on conflict (tenant_id, pathway_version_id) do nothing
                """).param("tenant", TENANT_ID).param("version", HEART_FAILURE_PATHWAY_V1_ID)
                .param("definition", HEART_FAILURE_PATHWAY_ID).param("creator", USER_ID)
                .param("approver", ATTENDING_USER_ID).update();
        jdbc.sql("""
                insert into clinical_pathway_stage(
                  tenant_id, pathway_version_id, stage_code, display_name, sequence_no,
                  expected_day_start, expected_day_end)
                select :tenant, :version, seed.stage_code, seed.display_name,
                  seed.sequence_no, seed.day_start, seed.day_end
                from (values
                  ('ADMISSION_ASSESSMENT', '入院评估', 1, 0, 1),
                  ('DIAGNOSIS_TREATMENT', '诊断与治疗监测', 2, 1, 7),
                  ('DISCHARGE_PREPARATION', '稳定与出院准备', 3, 3, 14)
                ) as seed(stage_code, display_name, sequence_no, day_start, day_end)
                on conflict (tenant_id, pathway_version_id, stage_code) do nothing
                """).param("tenant", TENANT_ID).param("version", HEART_FAILURE_PATHWAY_V1_ID).update();
        jdbc.sql("""
                insert into clinical_pathway_stage_task(
                  tenant_id, pathway_version_id, stage_code, task_code, display_name,
                  source_type, source_key, required, sequence_no)
                select :tenant, :version, seed.stage_code, seed.task_code, seed.display_name,
                  seed.source_type, seed.source_key, seed.required, seed.sequence_no
                from (values
                  ('ADMISSION_ASSESSMENT', 'ADMISSION_RECORD', '完成并审签入院记录',
                    'DOCUMENT_TASK', 'WS445.5.ADMISSION_RECORD', true, 1),
                  ('ADMISSION_ASSESSMENT', 'FIRST_COURSE', '完成并审签首次病程记录',
                    'DOCUMENT_TASK', 'WS445.5.FIRST_COURSE_RECORD', true, 2),
                  ('DIAGNOSIS_TREATMENT', 'TROPONIN_RESULT', '完成肌钙蛋白医嘱及执行',
                    'ORDER_ITEM', 'LAB.TROPONIN.I', true, 1),
                  ('DIAGNOSIS_TREATMENT', 'DAILY_COURSE', '形成日常病程记录',
                    'DOCUMENT_TASK', 'WS445.5.DAILY_COURSE_RECORD', false, 2),
                  ('DISCHARGE_PREPARATION', 'DISCHARGE_RECORD', '完成并审签出院记录',
                    'DOCUMENT_TASK', 'WS445.5.DISCHARGE_RECORD', true, 1)
                ) as seed(stage_code, task_code, display_name, source_type, source_key, required, sequence_no)
                on conflict (tenant_id, pathway_version_id, task_code) do nothing
                """).param("tenant", TENANT_ID).param("version", HEART_FAILURE_PATHWAY_V1_ID).update();
    }

    private void upsertInfrastructure() {
        jdbc.sql("insert into tenant(tenant_id, tenant_code, display_name, status) values (:id, 'JC-HEALTH', '江城市医疗集团', 'ACTIVE') on conflict (tenant_id) do update set tenant_code = excluded.tenant_code, display_name = excluded.display_name, status = 'ACTIVE'")
                .param("id", TENANT_ID).update();
        jdbc.sql("insert into organization(tenant_id, organization_id, organization_code, display_name, status) values (:tenant, :id, 'JC-DXFS', '江城大学附属医院', 'ACTIVE') on conflict (tenant_id, organization_id) do update set display_name = excluded.display_name, status = 'ACTIVE'")
                .param("tenant", TENANT_ID).param("id", ORGANIZATION_ID).update();
        jdbc.sql("insert into facility(tenant_id, organization_id, facility_id, facility_code, display_name, status) values (:tenant, :org, :id, 'JC-DXFS-BB', '江城大学附属医院本部', 'ACTIVE') on conflict (tenant_id, facility_id) do update set display_name = excluded.display_name, status = 'ACTIVE'")
                .param("tenant", TENANT_ID).param("org", ORGANIZATION_ID).param("id", FACILITY_ID).update();
        jdbc.sql("insert into app_user(tenant_id, user_id, external_subject, display_name, status) values (:tenant, :id, 'william.lin', '林伟 / William Lin', 'ACTIVE') on conflict (tenant_id, user_id) do update set external_subject = excluded.external_subject, display_name = excluded.display_name, status = 'ACTIVE'")
                .param("tenant", TENANT_ID).param("id", USER_ID).update();
        jdbc.sql("insert into app_user(tenant_id, user_id, external_subject, display_name, status) values (:tenant, :id, 'xue.wang', '王雪 / Xue Wang', 'ACTIVE') on conflict (tenant_id, user_id) do update set external_subject = excluded.external_subject, display_name = excluded.display_name, status = 'ACTIVE'")
                .param("tenant", TENANT_ID).param("id", COLLABORATOR_USER_ID).update();
        upsertSyntheticReviewer(ATTENDING_USER_ID, ATTENDING_ROLE_ID,
                "ming.zhou", "周明 / Ming Zhou", "ATTENDING_PHYSICIAN");
        upsertSyntheticReviewer(CHIEF_USER_ID, CHIEF_ROLE_ID,
                "lan.chen", "陈岚 / Lan Chen", "CHIEF_PHYSICIAN");
        upsertSyntheticReviewer(MEDICAL_RECORDS_USER_ID, MEDICAL_RECORDS_ROLE_ID,
                "chang.liu", "刘畅 / Chang Liu", "MEDICAL_RECORDS");
        jdbc.sql("""
                update workforce_person person
                set person_code = seed.person_code, display_name = seed.display_name,
                  status = 'ACTIVE', effective_until = null, row_version = person.row_version + 1,
                  updated_at = now()
                from (values
                  (cast(:clinician as uuid), 'DOC-10001', '林伟 / William Lin'),
                  (cast(:collaborator as uuid), 'DOC-10002', '王雪 / Xue Wang'),
                  (cast(:attending as uuid), 'DOC-10003', '周明 / Ming Zhou'),
                  (cast(:chief as uuid), 'DOC-10004', '陈岚 / Lan Chen'),
                  (cast(:records as uuid), 'HIM-10001', '刘畅 / Chang Liu')
                ) as seed(person_id, person_code, display_name)
                where person.tenant_id = :tenant and person.person_id = seed.person_id
                  and (person.person_code is distinct from seed.person_code
                    or person.display_name is distinct from seed.display_name
                    or person.status <> 'ACTIVE' or person.effective_until is not null)
                """).param("tenant", TENANT_ID).param("clinician", USER_ID)
                .param("collaborator", COLLABORATOR_USER_ID).param("attending", ATTENDING_USER_ID)
                .param("chief", CHIEF_USER_ID).param("records", MEDICAL_RECORDS_USER_ID).update();
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :org, :facility, 'CLINICIAN', now(), 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do nothing
                """)
                .param("tenant", TENANT_ID).param("role", ROLE_ASSIGNMENT_ID).param("user", USER_ID)
                .param("org", ORGANIZATION_ID).param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :org, :facility, 'SYSTEM_ADMIN', now(), 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do nothing
                """)
                .param("tenant", TENANT_ID).param("role", ADMIN_ROLE_ASSIGNMENT_ID).param("user", USER_ID)
                .param("org", ORGANIZATION_ID).param("facility", FACILITY_ID).update();
        // 演示账号林伟附加病案岗（MEDICAL_RECORDS）：病案资产中心等仅放行该岗位/CLINICAL_ADMIN，
        // 系统管理员与临床医生均不能替代；此任期仅为合成验收数据，不修改服务端授权规则。
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :org, :facility, 'MEDICAL_RECORDS', now(), 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do nothing
                """)
                .param("tenant", TENANT_ID).param("role", DEMO_MEDICAL_RECORDS_ROLE_ASSIGNMENT_ID)
                .param("user", USER_ID).param("org", ORGANIZATION_ID).param("facility", FACILITY_ID).update();
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :org, :facility, 'CLINICIAN', now(), 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do nothing
                """).param("tenant", TENANT_ID).param("role", COLLABORATOR_ROLE_ID)
                .param("user", COLLABORATOR_USER_ID).param("org", ORGANIZATION_ID)
                .param("facility", FACILITY_ID).update();
        upsertConfigurationRole(CONFIG_AUTHOR_ROLE_ID, USER_ID, "CONFIG_AUTHOR");
        upsertConfigurationRole(CONFIG_APPROVER_ROLE_ID, COLLABORATOR_USER_ID, "CONFIG_APPROVER");
        upsertDevelopmentCredential();
        jdbc.sql("""
                insert into ai_use_case_policy(
                  tenant_id, use_case_code, enabled, provider_code, model_code,
                  model_residency_policy, prompt_version)
                values (:tenant, 'DOCUMENT_DRAFT_ASSIST', false, 'DETERMINISTIC_FAKE',
                  'haoemr-ci-fake-v1', 'ON_PREM_ONLY', 'draft-assist-v1')
                on conflict (tenant_id, use_case_code) do update
                set enabled = excluded.enabled, provider_code = excluded.provider_code,
                  model_code = excluded.model_code,
                  model_residency_policy = excluded.model_residency_policy,
                  prompt_version = excluded.prompt_version, updated_at = now(),
                  config_version = ai_use_case_policy.config_version + 1
                where (ai_use_case_policy.enabled, ai_use_case_policy.provider_code,
                  ai_use_case_policy.model_code, ai_use_case_policy.model_residency_policy,
                  ai_use_case_policy.prompt_version)
                  is distinct from (excluded.enabled, excluded.provider_code,
                    excluded.model_code, excluded.model_residency_policy, excluded.prompt_version)
                """).param("tenant", TENANT_ID).update();
    }

    private void upsertConfigurationRole(UUID roleAssignmentId, UUID userId, String roleCode) {
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :org, :facility, :role_code, now(), 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do update
                set user_id = excluded.user_id, organization_id = excluded.organization_id,
                  facility_id = excluded.facility_id, role_code = excluded.role_code,
                  status = 'ACTIVE', valid_until = null
                """).param("tenant", TENANT_ID).param("role", roleAssignmentId).param("user", userId)
                .param("org", ORGANIZATION_ID).param("facility", FACILITY_ID)
                .param("role_code", roleCode).update();
    }

    void upsertDevelopmentCredential() {
        jdbc.sql("""
                insert into dev_user_credential(tenant_id, user_id, username, password_hash)
                values (:tenant, :user, 'linwei', :password_hash)
                on conflict (tenant_id, user_id) do update
                set username = excluded.username,
                  password_hash = excluded.password_hash,
                  failed_attempts = 0,
                  locked_until = null,
                  updated_at = now()
                """).param("tenant", TENANT_ID).param("user", USER_ID)
                .param("password_hash", new BCryptPasswordEncoder(12).encode(loginPassword)).update();
    }

    private void upsertSyntheticReviewer(
            UUID userId, UUID roleAssignmentId, String subject, String displayName, String roleCode) {
        jdbc.sql("""
                insert into app_user(tenant_id, user_id, external_subject, display_name, status)
                values (:tenant, :user, :subject, :display_name, 'ACTIVE')
                on conflict (tenant_id, user_id) do update
                set external_subject = excluded.external_subject,
                  display_name = excluded.display_name, status = 'ACTIVE'
                """).param("tenant", TENANT_ID).param("user", userId).param("subject", subject)
                .param("display_name", displayName).update();
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (:tenant, :role, :user, :org, :facility, :role_code, now(), 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do update
                set role_code = excluded.role_code, status = 'ACTIVE', valid_until = null
                """).param("tenant", TENANT_ID).param("role", roleAssignmentId).param("user", userId)
                .param("org", ORGANIZATION_ID).param("facility", FACILITY_ID).param("role_code", roleCode).update();
    }

    private void upsertCase(JsonNode item) {
        JsonNode patient = item.path("patient");
        UUID patientId = UUID.fromString(patient.path("patient_id").stringValue());
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (:tenant, :patient, :name, :sex, cast(:birth_date as date), 'ACTIVE')
                on conflict (tenant_id, patient_id) do update
                set display_name = excluded.display_name, sex_code = excluded.sex_code,
                    birth_date = excluded.birth_date, updated_at = now(), row_version = patient.row_version + 1
                where (patient.display_name, patient.sex_code, patient.birth_date)
                  is distinct from (excluded.display_name, excluded.sex_code, excluded.birth_date)
                """)
                .param("tenant", TENANT_ID).param("patient", patientId)
                .param("name", patient.path("display_name").stringValue())
                .param("sex", normalizeSyntheticSex(patient.path("gender_code").stringValue()))
                .param("birth_date", patient.path("birth_date").stringValue()).update();

        JsonNode encounter = item.path("encounter");
        UUID encounterId = UUID.fromString(encounter.path("encounter_id").stringValue());
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (:tenant, :encounter, :patient, :org, :facility, :type, :status,
                  cast(:started_at as timestamptz), 'SYNTHETIC', :source_key)
                on conflict (tenant_id, encounter_id) do nothing
                """)
                .param("tenant", TENANT_ID).param("encounter", encounterId).param("patient", patientId)
                .param("org", ORGANIZATION_ID).param("facility", FACILITY_ID)
                .param("type", encounter.path("encounter_type").stringValue())
                .param("status", encounter.path("status").stringValue())
                .param("started_at", OffsetDateTime.parse(encounter.path("started_at").stringValue()).toString())
                .param("source_key", item.path("case_id").stringValue()).update();

        for (JsonNode document : item.path("documents")) {
            upsertDocument(patientId, encounterId, document);
        }
    }

    private void upsertDiseaseCases(List<SyntheticDiseaseCaseCatalog.DiseaseCase> cases) {
        UUID outpatientSlotId = scenarioId("catalog", "outpatient-slot");
        jdbc.sql("""
                insert into schedule_slot(
                  tenant_id, schedule_slot_id, organization_id, facility_id, department_id, doctor_user_id,
                  visit_type, slot_date, start_time, end_time, total_capacity, booked_count, status)
                values (:tenant, :slot, :org, :facility, :department, :doctor, 'OUTPATIENT', current_date,
                  time '08:00', time '18:00', 50, 20, 'OPEN')
                on conflict (tenant_id, schedule_slot_id) do update set slot_date = current_date,
                  department_id = excluded.department_id, doctor_user_id = excluded.doctor_user_id,
                  total_capacity = 50, booked_count = 20, status = 'OPEN', updated_at = now()
                """).param("tenant", TENANT_ID).param("slot", outpatientSlotId)
                .param("org", ORGANIZATION_ID).param("facility", FACILITY_ID)
                .param("department", SYNTHETIC_DEPARTMENT_ID).param("doctor", USER_ID).update();

        for (int index = 0; index < cases.size(); index++) {
            upsertDiseaseCase(cases.get(index), index, outpatientSlotId);
        }
    }

    private void upsertDiseaseCase(
            SyntheticDiseaseCaseCatalog.DiseaseCase item, int index, UUID outpatientSlotId) {
        UUID patientId = scenarioId(item.caseId(), "patient");
        UUID encounterId = scenarioId(item.caseId(), "encounter");
        String encounterStatus = encounterStatus(item.domain(), index);

        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (:tenant, :patient, :name, :sex, cast(:birth_date as date), 'ACTIVE')
                on conflict (tenant_id, patient_id) do update set display_name = excluded.display_name,
                  sex_code = excluded.sex_code, birth_date = excluded.birth_date, status = 'ACTIVE',
                  updated_at = now()
                """).param("tenant", TENANT_ID).param("patient", patientId)
                .param("name", item.patientName()).param("sex", item.sex())
                .param("birth_date", item.birthDate()).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (:tenant, :encounter, :patient, :org, :facility, :domain, :status,
                  now() - (:hours * interval '1 hour'), 'SYNTHETIC-50', :source_key)
                on conflict (tenant_id, encounter_id) do nothing
                """).param("tenant", TENANT_ID).param("encounter", encounterId).param("patient", patientId)
                .param("org", ORGANIZATION_ID).param("facility", FACILITY_ID).param("domain", item.domain())
                .param("status", encounterStatus).param("hours", 3 + index).param("source_key", item.caseId()).update();

        upsertDiseaseCaseDocument(item, patientId, encounterId, "primary-document",
                documentType(item.domain()), primarySections(item));
        upsertDiseaseCaseDiagnosis(item, patientId, encounterId);
        upsertDiseaseCaseOrderAndResult(item, patientId, encounterId);

        switch (item.domain()) {
            case "OUTPATIENT" -> upsertOutpatientDiseaseCase(item, index, patientId, encounterId, outpatientSlotId);
            case "EMERGENCY" -> upsertEmergencyDiseaseCase(item, index, patientId, encounterId);
            case "INPATIENT" -> upsertInpatientDiseaseCase(item, index, patientId, encounterId);
            default -> throw new IllegalArgumentException("Unsupported disease case domain: " + item.domain());
        }
    }

    private void upsertDiseaseCaseDocument(
            SyntheticDiseaseCaseCatalog.DiseaseCase item, UUID patientId, UUID encounterId,
            String key, String documentType, JsonNode sections) {
        UUID documentId = scenarioId(item.caseId(), key);
        var document = objectMapper.createObjectNode();
        document.put("document_id", documentId.toString());
        document.put("document_type_code", documentType);
        document.put("version_no", 1);
        document.put("status", "DRAFT");
        document.set("sections", sections);
        document.put("content_hash", syntheticHash(item.caseId() + ":" + key + ":" + sections));
        upsertDocument(patientId, encounterId, document);
    }

    private JsonNode primarySections(SyntheticDiseaseCaseCatalog.DiseaseCase item) {
        var sections = objectMapper.createObjectNode();
        sections.put("chief_complaint", item.chiefComplaint());
        sections.put("present_illness", item.presentIllness());
        sections.put("past_history", item.pastHistory());
        sections.put("allergy_history", item.allergyHistory());
        sections.put("physical_examination", item.physicalExamination());
        sections.put("vital_signs", item.vitalSigns());
        sections.put("assessment", item.diagnosisText());
        sections.put("diagnostic_evidence", item.evidenceSummary());
        sections.put("treatment_plan", item.treatmentPlan());
        sections.put("synthetic_case_notice", "本记录为 HaoEMR 完全合成测试病例，不对应真实患者。");
        return sections;
    }

    private void upsertDiseaseCaseDiagnosis(
            SyntheticDiseaseCaseCatalog.DiseaseCase item, UUID patientId, UUID encounterId) {
        UUID diagnosisId = scenarioId(item.caseId(), "diagnosis");
        UUID versionId = scenarioId(item.caseId(), "diagnosis-version");
        jdbc.sql("""
                insert into diagnosis_terminology_entry(
                  terminology_system, terminology_release, code, display_name,
                  lifecycle_status, effective_from)
                values ('ICD-10-CN', 'SYNTHETIC-50-V1', :code, :display, 'ACTIVE', date '2026-01-01')
                on conflict (terminology_system, terminology_release, code) do nothing
                """).param("code", item.diseaseCode()).param("display", item.diseaseName()).update();
        jdbc.sql("""
                insert into clinical_diagnosis(
                  tenant_id, diagnosis_id, patient_id, encounter_id, facility_id, lifecycle_status,
                  current_diagnosis_role, current_version_id, author_user_id)
                values (:tenant, :diagnosis, :patient, :encounter, :facility, 'ACTIVE',
                  'PRIMARY', :version, :author)
                on conflict (tenant_id, diagnosis_id) do nothing
                """).param("tenant", TENANT_ID).param("diagnosis", diagnosisId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY_ID)
                .param("version", versionId).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_diagnosis_version(
                  tenant_id, diagnosis_version_id, diagnosis_id, version_no, terminology_system,
                  terminology_release, code, code_display_snapshot, diagnosis_text, diagnosis_role,
                  certainty, evidence_summary, plan_summary, effective_at, change_type, authored_by)
                values (:tenant, :version, :diagnosis, 1, 'ICD-10-CN', 'SYNTHETIC-50-V1',
                  :code, :display, :diagnosis_text, 'PRIMARY', 'CONFIRMED', :evidence, :plan,
                  now() - interval '2 hour', 'CREATED', :author)
                on conflict (tenant_id, diagnosis_version_id) do nothing
                """).param("tenant", TENANT_ID).param("version", versionId).param("diagnosis", diagnosisId)
                .param("code", item.diseaseCode()).param("display", item.diseaseName())
                .param("diagnosis_text", item.diagnosisText()).param("evidence", item.evidenceSummary())
                .param("plan", item.treatmentPlan()).param("author", USER_ID).update();
    }

    private void upsertDiseaseCaseOrderAndResult(
            SyntheticDiseaseCaseCatalog.DiseaseCase item, UUID patientId, UUID encounterId) {
        UUID orderId = scenarioId(item.caseId(), "order");
        UUID orderItemId = scenarioId(item.caseId(), "order-item");
        UUID executionTaskId = scenarioId(item.caseId(), "execution-task");
        UUID resultId = scenarioId(item.caseId(), "result");
        UUID resultVersionId = scenarioId(item.caseId(), "result-version");
        UUID observationId = scenarioId(item.caseId(), "result-observation");

        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                values (:tenant, :order, :patient, :encounter, :facility, 'TEMPORARY', 'COMPLETED',
                  :indication, :author, :author, now() - interval '90 minute', 'SYNTHETIC-50-V1')
                on conflict (tenant_id, order_id) do nothing
                """).param("tenant", TENANT_ID).param("order", orderId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY_ID)
                .param("indication", item.evidenceSummary()).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, instructions, item_state)
                values (:tenant, :item, :order, :type, :code, :name, 1, '次',
                  '用于合成病例诊断依据与疗效风险评估，结果需回写当前就诊。', 'COMPLETED')
                on conflict (tenant_id, order_item_id) do nothing
                """).param("tenant", TENANT_ID).param("item", orderItemId).param("order", orderId)
                .param("type", item.orderType()).param("code", item.orderCode()).param("name", item.orderName()).update();
        jdbc.sql("""
                insert into order_execution_task(
                  tenant_id, execution_task_id, order_id, order_item_id, patient_id, encounter_id,
                  task_state, requested_quantity, performed_quantity, quantity_unit)
                values (:tenant, :task, :order, :item, :patient, :encounter, 'COMPLETED', 1, 1, '次')
                on conflict (tenant_id, execution_task_id) do nothing
                """).param("tenant", TENANT_ID).param("task", executionTaskId).param("order", orderId)
                .param("item", orderItemId).param("patient", patientId).param("encounter", encounterId).update();
        jdbc.sql("""
                insert into clinical_result(
                  tenant_id, result_id, patient_id, encounter_id, facility_id, order_id,
                  execution_task_id, report_type, source_system, source_report_key,
                  current_version_id, author_user_id)
                values (:tenant, :result, :patient, :encounter, :facility, :order, :task, :report_type,
                  'SYNTHETIC-50', :source_key, :version, :author)
                on conflict (tenant_id, result_id) do nothing
                """).param("tenant", TENANT_ID).param("result", resultId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY_ID).param("order", orderId)
                .param("task", executionTaskId).param("report_type", item.orderType())
                .param("source_key", item.caseId() + ":" + item.resultCode())
                .param("version", resultVersionId).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_result_version(
                  tenant_id, result_version_id, result_id, version_no, report_status, conclusion,
                  reported_at, change_type, authored_by)
                values (:tenant, :version, :result, 1, 'FINAL', :conclusion,
                  now() - interval '1 hour', 'INITIAL', :author)
                on conflict (tenant_id, result_version_id) do nothing
                """).param("tenant", TENANT_ID).param("version", resultVersionId).param("result", resultId)
                .param("conclusion", item.resultName() + "结果已回传；" + item.evidenceSummary())
                .param("author", USER_ID).update();
        upsertDiseaseCaseResultObservation(item, resultVersionId, observationId);
    }

    private void upsertDiseaseCaseResultObservation(
            SyntheticDiseaseCaseCatalog.DiseaseCase item, UUID resultVersionId, UUID observationId) {
        if (item.resultValue().isNumber()) {
            jdbc.sql("""
                    insert into clinical_result_observation(
                      tenant_id, observation_id, result_version_id, item_code, item_name, value_type,
                      numeric_value, unit, reference_low, reference_high, abnormal_flag)
                    values (:tenant, :observation, :version, :code, :name, 'NUMERIC', :value,
                      :unit, :reference_low, :reference_high, :flag)
                    on conflict (tenant_id, observation_id) do nothing
                    """).param("tenant", TENANT_ID).param("observation", observationId)
                    .param("version", resultVersionId).param("code", item.resultCode())
                    .param("name", item.resultName()).param("value", item.resultValue().asDouble())
                    .param("unit", item.resultUnit()).param("reference_low", item.referenceLow())
                    .param("reference_high", item.referenceHigh()).param("flag", item.abnormalFlag()).update();
        } else {
            jdbc.sql("""
                    insert into clinical_result_observation(
                      tenant_id, observation_id, result_version_id, item_code, item_name, value_type,
                      text_value, abnormal_flag)
                    values (:tenant, :observation, :version, :code, :name, 'TEXT', :value, :flag)
                    on conflict (tenant_id, observation_id) do nothing
                    """).param("tenant", TENANT_ID).param("observation", observationId)
                    .param("version", resultVersionId).param("code", item.resultCode())
                    .param("name", item.resultName()).param("value", item.resultValue().stringValue())
                    .param("flag", item.abnormalFlag()).update();
        }
    }

    private void upsertOutpatientDiseaseCase(
            SyntheticDiseaseCaseCatalog.DiseaseCase item, int index, UUID patientId,
            UUID encounterId, UUID slotId) {
        UUID appointmentId = scenarioId(item.caseId(), "appointment");
        UUID queueEntryId = scenarioId(item.caseId(), "queue-entry");
        String queueStatus = outpatientQueueStatus(index);
        jdbc.sql("""
                insert into appointment(
                  tenant_id, appointment_id, schedule_slot_id, patient_id, organization_id, facility_id,
                  visit_type, source, status, booked_at, check_in_at, encounter_id)
                values (:tenant, :appointment, :slot, :patient, :org, :facility, 'OUTPATIENT',
                  'APPOINTMENT', 'CHECKED_IN', now() - interval '4 hour',
                  now() - interval '3 hour', :encounter)
                on conflict (tenant_id, appointment_id) do update set encounter_id = excluded.encounter_id,
                  status = 'CHECKED_IN', check_in_at = excluded.check_in_at, updated_at = now()
                """).param("tenant", TENANT_ID).param("appointment", appointmentId).param("slot", slotId)
                .param("patient", patientId).param("org", ORGANIZATION_ID).param("facility", FACILITY_ID)
                .param("encounter", encounterId).update();
        jdbc.sql("""
                insert into waiting_queue_entry(
                  tenant_id, waiting_queue_entry_id, appointment_id, facility_id, queue_date,
                  sequence_no, status, called_at, called_by)
                values (:tenant, :entry, :appointment, :facility, current_date, :sequence, :status,
                  case when :status = 'WAITING' then null else now() - interval '30 minute' end,
                  case when :status = 'WAITING' then null else :author end)
                on conflict (tenant_id, waiting_queue_entry_id) do update set queue_date = current_date,
                  status = excluded.status, called_at = excluded.called_at, called_by = excluded.called_by,
                  updated_at = now()
                """).param("tenant", TENANT_ID).param("entry", queueEntryId).param("appointment", appointmentId)
                .param("facility", FACILITY_ID).param("sequence", 101 + index).param("status", queueStatus)
                .param("author", USER_ID).update();
    }

    private void upsertEmergencyDiseaseCase(
            SyntheticDiseaseCaseCatalog.DiseaseCase item, int index, UUID patientId, UUID encounterId) {
        UUID triageId = scenarioId(item.caseId(), "triage");
        UUID nursingNoteId = scenarioId(item.caseId(), "emergency-nursing");
        UUID observationId = scenarioId(item.caseId(), "emergency-observation");
        boolean immediate = "LEVEL_1".equals(item.triageLevel());
        jdbc.sql("""
                insert into emergency_triage_assessment(
                  tenant_id, triage_assessment_id, patient_id, encounter_id, facility_id,
                  triage_level, chief_complaint, triaged_at, immediate_action_required, status)
                values (:tenant, :triage, :patient, :encounter, :facility, :level, :complaint,
                  now() - (:minutes * interval '1 minute'), :immediate, 'ACTIVE')
                on conflict (tenant_id, triage_assessment_id) do nothing
                """).param("tenant", TENANT_ID).param("triage", triageId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY_ID).param("level", item.triageLevel())
                .param("complaint", item.chiefComplaint()).param("minutes", 25 + index)
                .param("immediate", immediate).update();
        jdbc.sql("""
                insert into emergency_nursing_note(
                  tenant_id, note_id, patient_id, encounter_id, facility_id,
                  assessment, intervention, risk_flag, recorded_at)
                values (:tenant, :note, :patient, :encounter, :facility, :assessment,
                  :intervention, true, now() - interval '15 minute')
                on conflict (tenant_id, note_id) do nothing
                """).param("tenant", TENANT_ID).param("note", nursingNoteId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY_ID)
                .param("assessment", item.vitalSigns() + " " + item.physicalExamination())
                .param("intervention", item.treatmentPlan()).update();
        jdbc.sql("""
                insert into emergency_observation(
                  tenant_id, observation_id, patient_id, encounter_id, facility_id,
                  observation_started_at, disposition, status)
                values (:tenant, :observation, :patient, :encounter, :facility,
                  now() - interval '20 minute', 'PENDING', 'OBSERVING')
                on conflict (tenant_id, observation_id) do nothing
                """).param("tenant", TENANT_ID).param("observation", observationId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY_ID).update();
    }

    private void upsertInpatientDiseaseCase(
            SyntheticDiseaseCaseCatalog.DiseaseCase item, int index, UUID patientId, UUID encounterId) {
        UUID bedId = scenarioId(item.caseId(), "bed");
        UUID admissionId = scenarioId(item.caseId(), "admission");
        UUID occupancyId = scenarioId(item.caseId(), "bed-occupancy");
        UUID taskId = scenarioId(item.caseId(), "first-course-task");
        String bedLabel = "心血管内科-" + String.format("%02d", index + 1) + "床";
        jdbc.sql("""
                insert into clinical_bed(tenant_id, bed_id, ward_id, bed_label, status)
                values (:tenant, :bed, :ward, :label, 'ACTIVE')
                on conflict (tenant_id, bed_id) do nothing
                """).param("tenant", TENANT_ID).param("bed", bedId).param("ward", SYNTHETIC_WARD_ID)
                .param("label", bedLabel).update();
        jdbc.sql("""
                insert into inpatient_admission(
                  tenant_id, admission_id, encounter_id, patient_id, facility_id, ward_id,
                  current_bed_id, attending_user_id, status, admitted_at, admission_no, department_id,
                  admission_source, admission_type, condition_level, admitting_diagnosis_code,
                  admitting_diagnosis_text, payment_method_code, identity_verification_method,
                  contact_name, contact_relationship, contact_phone, remarks)
                values (:tenant, :admission, :encounter, :patient, :facility, :ward,
                  :bed, :attending, 'ADMITTED', now() - (:hours * interval '1 hour'),
                  :admission_no, :department, :admission_source, :admission_type, :condition_level,
                  :diagnosis_code, :diagnosis_text, 'URBMI', 'RESIDENT_ID', :contact_name,
                  :contact_relationship, :contact_phone, :remarks)
                on conflict (tenant_id, admission_id) do update
                set admission_no = excluded.admission_no, department_id = excluded.department_id,
                  admission_source = excluded.admission_source, admission_type = excluded.admission_type,
                  condition_level = excluded.condition_level,
                  admitting_diagnosis_code = excluded.admitting_diagnosis_code,
                  admitting_diagnosis_text = excluded.admitting_diagnosis_text,
                  contact_name = excluded.contact_name, contact_relationship = excluded.contact_relationship,
                  contact_phone = excluded.contact_phone, remarks = excluded.remarks
                """).param("tenant", TENANT_ID).param("admission", admissionId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", FACILITY_ID).param("ward", SYNTHETIC_WARD_ID)
                .param("bed", bedId).param("attending", USER_ID).param("hours", 12 + index)
                .param("admission_no", "IP-SYN50-" + item.caseId().substring(item.caseId().length() - 3))
                .param("department", SYNTHETIC_DEPARTMENT_ID)
                .param("admission_source", index % 3 == 0 ? "EMERGENCY" : "OUTPATIENT")
                .param("admission_type", index % 3 == 0 ? "EMERGENCY" : "URGENT")
                .param("condition_level", index % 5 == 0 ? "CRITICAL" : index % 2 == 0 ? "SERIOUS" : "GENERAL")
                .param("diagnosis_code", item.diseaseCode()).param("diagnosis_text", item.diagnosisText())
                .param("contact_name", item.patientName().substring(0, 1) + ("F".equals(item.sex()) ? "秀英" : "建军"))
                .param("contact_relationship", "F".equals(item.sex()) ? "母亲" : "父亲")
                .param("contact_phone", "138" + String.format("%08d", 10000000 + index))
                .param("remarks", item.treatmentPlan()).update();
        jdbc.sql("""
                insert into bed_occupancy(
                  tenant_id, bed_occupancy_id, admission_id, ward_id, bed_id, started_at)
                values (:tenant, :occupancy, :admission, :ward, :bed,
                  now() - (:hours * interval '1 hour'))
                on conflict (tenant_id, bed_occupancy_id) do nothing
                """).param("tenant", TENANT_ID).param("occupancy", occupancyId).param("admission", admissionId)
                .param("ward", SYNTHETIC_WARD_ID).param("bed", bedId).param("hours", 12 + index).update();

        var courseSections = objectMapper.createObjectNode();
        courseSections.put("case_features", item.presentIllness() + " " + item.physicalExamination());
        courseSections.put("provisional_diagnosis", item.diagnosisText());
        courseSections.put("diagnostic_evidence", item.evidenceSummary());
        courseSections.put("risk_assessment", item.vitalSigns());
        courseSections.put("treatment_plan", item.treatmentPlan());
        courseSections.put("course_summary", "入院后已完成首轮评估、关键检查和风险分层，继续按计划动态复核。");
        upsertDiseaseCaseDocument(item, patientId, encounterId, "first-course-document",
                "WS445.5.FIRST_COURSE_RECORD", courseSections);
        jdbc.sql("""
                insert into inpatient_document_task(
                  tenant_id, task_id, admission_id, document_type_code, task_state, due_at,
                  occurrence_key, rule_version, required_signature_level)
                values (:tenant, :task, :admission, 'WS445.5.FIRST_COURSE_RECORD', 'PENDING',
                  now() + interval '4 hour', 'SYNTHETIC-50', 1,
                  (select required_signature_level from inpatient_document_rule
                   where tenant_id = :tenant and document_type_code = 'WS445.5.FIRST_COURSE_RECORD'
                     and rule_version = 1 order by rule_code limit 1))
                on conflict (tenant_id, task_id) do nothing
                """).param("tenant", TENANT_ID).param("task", taskId).param("admission", admissionId).update();
    }

    private static String documentType(String domain) {
        return switch (domain) {
            case "OUTPATIENT" -> "WS445.2.OUTPATIENT_RECORD";
            case "EMERGENCY" -> "WS445.3.EMERGENCY_RECORD";
            case "INPATIENT" -> "WS445.12.ADMISSION_NOTE";
            default -> throw new IllegalArgumentException("Unsupported disease case domain: " + domain);
        };
    }

    private static String encounterStatus(String domain, int index) {
        if (!"OUTPATIENT".equals(domain)) return "IN_PROGRESS";
        return "IN_CONSULTATION".equals(outpatientQueueStatus(index)) ? "IN_PROGRESS" : "ARRIVED";
    }

    private static String outpatientQueueStatus(int index) {
        return switch (index % 3) {
            case 0 -> "WAITING";
            case 1 -> "CALLED";
            default -> "IN_CONSULTATION";
        };
    }

    private static UUID scenarioId(String caseId, String resource) {
        // Persisted fixture UUID namespaces are identities, independent of product branding.
        return UUID.nameUUIDFromBytes(("openemr2026:synthetic-50:" + caseId + ":" + resource)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String syntheticHash(String value) {
        String compact = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return compact + compact;
    }

    private void upsertInpatientFixture() {
        UUID patientId = UUID.fromString("018f0000-0000-7000-8000-000000000002");
        UUID encounterId = UUID.fromString("018f0000-0000-7000-8000-000000000102");
        OffsetDateTime admittedAt = OffsetDateTime.parse("2026-08-13T16:10:00+08:00");
        jdbc.sql("""
                insert into clinical_department(
                  tenant_id, facility_id, department_id, department_code, display_name, status, unit_type)
                values (:tenant, :facility, :department, 'CARDIOLOGY', '心血管内科', 'ACTIVE', 'DEPARTMENT')
                on conflict (tenant_id, facility_id, department_id) do update
                set department_code = excluded.department_code, display_name = excluded.display_name, status = 'ACTIVE'
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID)
                .param("department", SYNTHETIC_DEPARTMENT_ID).update();
        jdbc.sql("""
                insert into clinical_ward(
                  tenant_id, facility_id, department_id, ward_id, ward_code, display_name, status)
                values (:tenant, :facility, :department, :ward, 'CARDIO-1', '心血管内科一病区', 'ACTIVE')
                on conflict (tenant_id, ward_id) do update
                set department_id = excluded.department_id, ward_code = excluded.ward_code,
                  display_name = excluded.display_name, status = 'ACTIVE'
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID)
                .param("department", SYNTHETIC_DEPARTMENT_ID)
                .param("ward", SYNTHETIC_WARD_ID).update();
        for (int number = 1; number <= 12; number++) {
            UUID bedId = number == 2 ? SYNTHETIC_BED_ID : number == 3 ? SYNTHETIC_FREE_BED_ID
                    : UUID.nameUUIDFromBytes(("cardiology-bed-" + number).getBytes(StandardCharsets.UTF_8));
            jdbc.sql("""
                    insert into clinical_bed(tenant_id, bed_id, ward_id, bed_label, status)
                    values (:tenant, :bed, :ward, :label, 'ACTIVE')
                    on conflict (tenant_id, bed_id) do update
                    set ward_id = excluded.ward_id, bed_label = excluded.bed_label, status = 'ACTIVE'
                    """).param("tenant", TENANT_ID).param("bed", bedId).param("ward", SYNTHETIC_WARD_ID)
                    .param("label", "心血管内科-" + String.format("%02d", number) + "床").update();
        }
        jdbc.sql("""
                update workforce_assignment set department_id = :department, ward_id = :ward,
                  position_code = case when position_code = 'CLINICIAN' then 'ATTENDING_PHYSICIAN' else position_code end,
                  updated_at = now(), row_version = row_version + 1
                where tenant_id = :tenant
                  and source_role_assignment_id in (:clinician, :collaborator, :attending, :chief)
                  and (department_id is distinct from :department or ward_id is distinct from :ward)
                """).param("department", SYNTHETIC_DEPARTMENT_ID).param("ward", SYNTHETIC_WARD_ID)
                .param("tenant", TENANT_ID).param("clinician", ROLE_ASSIGNMENT_ID)
                .param("collaborator", COLLABORATOR_ROLE_ID)
                .param("attending", ATTENDING_ROLE_ID).param("chief", CHIEF_ROLE_ID).update();
        jdbc.sql("""
                insert into ward_role_scope(tenant_id, ward_id, role_assignment_id)
                values (:tenant, :ward, :role)
                on conflict (tenant_id, ward_id, role_assignment_id) do nothing
                """).param("tenant", TENANT_ID).param("ward", SYNTHETIC_WARD_ID)
                .param("role", ROLE_ASSIGNMENT_ID).update();
        List.of(ATTENDING_ROLE_ID, CHIEF_ROLE_ID, MEDICAL_RECORDS_ROLE_ID).forEach(roleId -> jdbc.sql("""
                insert into ward_role_scope(tenant_id, ward_id, role_assignment_id)
                values (:tenant, :ward, :role)
                on conflict (tenant_id, ward_id, role_assignment_id) do nothing
                """).param("tenant", TENANT_ID).param("ward", SYNTHETIC_WARD_ID)
                .param("role", roleId).update());
        jdbc.sql("""
                insert into inpatient_admission(
                  tenant_id, admission_id, encounter_id, patient_id, facility_id, ward_id,
                  current_bed_id, attending_user_id, status, admitted_at, admission_no, department_id,
                  admission_source, admission_type, condition_level, admitting_diagnosis_code,
                  admitting_diagnosis_text, payment_method_code, identity_verification_method,
                  contact_name, contact_relationship, contact_phone, admission_certificate_no, remarks)
                values (:tenant, :admission, :encounter, :patient, :facility, :ward,
                  :bed, :attending, 'ADMITTED', :admitted_at, 'IP-20260813-0002', :department,
                  'OUTPATIENT', 'ELECTIVE', 'GENERAL', 'I50.9', '慢性心力衰竭急性加重',
                  'URBMI', 'RESIDENT_ID', '张敏', '配偶', '13800138002', 'RYZ-20260813-002',
                  '门诊评估后收入心血管内科继续治疗')
                on conflict (tenant_id, admission_id) do update
                set department_id = excluded.department_id, admission_no = excluded.admission_no,
                  admitting_diagnosis_text = excluded.admitting_diagnosis_text,
                  contact_name = excluded.contact_name, contact_relationship = excluded.contact_relationship,
                  contact_phone = excluded.contact_phone
                """).param("tenant", TENANT_ID).param("admission", SYNTHETIC_ADMISSION_ID)
                .param("encounter", encounterId).param("patient", patientId).param("facility", FACILITY_ID)
                .param("ward", SYNTHETIC_WARD_ID).param("bed", SYNTHETIC_BED_ID)
                .param("attending", USER_ID).param("department", SYNTHETIC_DEPARTMENT_ID)
                .param("admitted_at", admittedAt).update();
        jdbc.sql("""
                insert into bed_occupancy(
                  tenant_id, bed_occupancy_id, admission_id, ward_id, bed_id, started_at)
                values (:tenant, :occupancy, :admission, :ward, :bed, :started_at)
                on conflict (tenant_id, bed_occupancy_id) do nothing
                """).param("tenant", TENANT_ID)
                .param("occupancy", UUID.fromString("018f0000-0000-7000-8000-00000000bb04"))
                .param("admission", SYNTHETIC_ADMISSION_ID).param("ward", SYNTHETIC_WARD_ID)
                .param("bed", SYNTHETIC_BED_ID).param("started_at", admittedAt).update();
        upsertInpatientTask("018f0000-0000-7000-8000-00000000bb05", "WS445.5.ADMISSION_RECORD",
                admittedAt.plusHours(24));
        upsertInpatientTask("018f0000-0000-7000-8000-00000000bb06", "WS445.5.FIRST_COURSE_RECORD",
                admittedAt.plusHours(8));
        upsertInpatientTask("018f0000-0000-7000-8000-00000000bb07", "WS445.5.ATTENDING_REVIEW",
                admittedAt.plusHours(48));
        upsertInpatientTask("018f0000-0000-7000-8000-00000000bb08", "EMR.FOUR_LEVEL_REVIEW",
                admittedAt.plusHours(72));
    }

    /**
     * Seeds real persisted execution-center cases for the four workbenches that previously only
     * had browser simulation state. Every fixture is de-identified, uses two patient identifiers,
     * carries China tertiary-hospital execution evidence, and has an immutable event timeline.
     */
    private void upsertTertiaryExecutionCenterFixtures() {
        UUID therapyPatientId = UUID.fromString("58aa778c-4af6-353c-b181-20211ce18efb");
        UUID therapyEncounterId = UUID.fromString("e9824311-08f1-33ee-bcf5-9015ad2e2d73");
        UUID anesthesiaPatientId = UUID.fromString("fa1fc427-250b-3b6f-a651-b963916e8f52");
        UUID anesthesiaEncounterId = UUID.fromString("44eab79d-9059-3eb4-b05c-3831587ece7b");
        UUID devicePatientId = UUID.fromString("70597718-f934-3fbc-a3cf-e07691a8c54d");
        UUID deviceEncounterId = UUID.fromString("a5953854-b7e0-338a-ae24-c40fd3a76260");
        UUID outpatientPatientId = UUID.fromString("018f0000-0000-7000-8000-000000000001");
        UUID outpatientEncounterId = UUID.fromString("018f0000-0000-7000-8000-000000000101");
        jdbc.sql("""
                insert into specialty_execution_case(
                  tenant_id, specialty_execution_case_id, business_number, domain,
                  patient_id, encounter_id, facility_id, title, priority, status,
                  planned_at, payload, created_by, last_actor_user_id, row_version)
                select :tenant, seed.case_id::uuid, seed.business_number, seed.domain,
                  seed.patient_id::uuid, seed.encounter_id::uuid, :facility,
                  seed.title, seed.priority, seed.status, seed.planned_at,
                  seed.payload::jsonb, :actor, :actor, seed.row_version
                from (values
                  ('018f0000-0000-7000-8000-00000000f901', 'BL-SYN-20260831-001', 'PATHOLOGY',
                   :outpatient_patient::text, :outpatient_encounter::text,
                   '左肺上叶切除标本常规病理', 'URGENT', 'READY',
                   now() + interval '40 minute',
                   '{"patient_identifier_one":"MZ202608310001","patient_identifier_two":"1978-04-16","patient_verification_method":"门诊号+出生日期","accession_number":"BL202608310001","specimen_type":"手术切除标本","specimen_site":"左肺上叶","fixative":"10%中性福尔马林，固定12小时","grossing_record":"已核对标本袋、申请单与病理号","block_slide_evidence":"A1-A4 / HE-01~04"}', 2::bigint),
                  ('018f0000-0000-7000-8000-00000000f902', 'ZL-SYN-20260831-001', 'THERAPY',
                   :therapy_patient::text, :therapy_encounter::text,
                   '心力衰竭住院康复与呼吸训练', 'ROUTINE', 'READY',
                   now() + interval '2 hour',
                   '{"patient_identifier_one":"ZY202608250040","patient_identifier_two":"WB-CV-40","patient_verification_method":"住院号+腕带扫码","therapy_code":"REHAB-CARDIO-PHASE-I","course_number":"1","session_number":"3","verification_method":"治疗师与护士双人核对","treatment_parameters":"床旁主动运动15分钟，呼吸训练10分钟，连续监测SpO2与心率","adverse_event_plan":"胸痛、SpO2<90%或心率增幅>20%立即中止并上报"}', 2::bigint),
                  ('018f0000-0000-7000-8000-00000000f903', 'MZ-SYN-20260831-001', 'ANESTHESIA',
                   :anesthesia_patient::text, :anesthesia_encounter::text,
                   '全麻术前评估与PACU准入', 'URGENT', 'READY',
                   now() + interval '3 hour',
                   '{"patient_identifier_one":"ZY202608250038","patient_identifier_two":"WB-CV-38","patient_verification_method":"住院号+腕带扫码","surgical_procedure_id":"SS-SYN-20260831-008","asa_class":"ASA III","anesthesia_method":"全身麻醉","fasting_confirmed":true,"consent_confirmed":true,"airway_plan":"Mallampati II级，备可视嗉镜","pacu_disposition":"Aldrete评分达9分后返心内科监护病房"}', 2::bigint),
                  ('018f0000-0000-7000-8000-00000000f904', 'SB-SYN-20260831-001', 'DEVICE_MONITORING',
                   :device_patient::text, :device_encounter::text,
                   '心内科43床旁监护仪患者绑定', 'URGENT', 'IN_PROGRESS',
                   now() - interval '20 minute',
                   '{"patient_identifier_one":"ZY202608250043","patient_identifier_two":"WB-CV-43","patient_verification_method":"住院号+腕带扫码","device_id":"CCU-MON-43","device_type":"床旁监护仪","binding_verified":true,"clock_offset_seconds":2,"alarm_policy":"CCU-ADULT-v3.2","monitoring_parameters":"HR/SpO2/NIBP，1秒采样，5分钟归档","alarm_escalation":"高优先级告警30秒未确认自动升级到值班医生"}', 3::bigint)
                ) as seed(case_id, business_number, domain, patient_id, encounter_id,
                  title, priority, status, planned_at, payload, row_version)
                on conflict (tenant_id, specialty_execution_case_id) do update
                set business_number = excluded.business_number,
                    domain = excluded.domain,
                    patient_id = excluded.patient_id,
                    encounter_id = excluded.encounter_id,
                    facility_id = excluded.facility_id,
                    title = excluded.title,
                    priority = excluded.priority,
                    status = excluded.status,
                    planned_at = excluded.planned_at,
                    payload = excluded.payload,
                    last_actor_user_id = excluded.last_actor_user_id,
                    row_version = excluded.row_version,
                    updated_at = now()
                """).param("tenant", TENANT_ID).param("facility", FACILITY_ID).param("actor", USER_ID)
                .param("outpatient_patient", outpatientPatientId).param("outpatient_encounter", outpatientEncounterId)
                .param("therapy_patient", therapyPatientId).param("therapy_encounter", therapyEncounterId)
                .param("anesthesia_patient", anesthesiaPatientId).param("anesthesia_encounter", anesthesiaEncounterId)
                .param("device_patient", devicePatientId).param("device_encounter", deviceEncounterId)
                .update();

        jdbc.sql("""
                insert into specialty_execution_case_event(
                  tenant_id, specialty_execution_event_id, specialty_execution_case_id,
                  event_type, from_status, to_status, note, snapshot, actor_user_id, occurred_at)
                select :tenant, seed.event_id::uuid, seed.case_id::uuid, seed.event_type,
                  seed.from_status, seed.to_status, seed.note,
                  jsonb_build_object('fixture_source', 'tertiary-execution-center-v1',
                    'business_number', seed.business_number, 'status', seed.to_status),
                  :actor, now() - seed.age_minutes * interval '1 minute'
                from (values
                  ('018f0000-0000-7000-8000-00000000f911','018f0000-0000-7000-8000-00000000f901','CREATED',null,'DRAFT','病理申请与组织标本已建档','BL-SYN-20260831-001',55),
                  ('018f0000-0000-7000-8000-00000000f912','018f0000-0000-7000-8000-00000000f901','READY','DRAFT','READY','双标识、病理号、取材部位和固定信息核对通过','BL-SYN-20260831-001',50),
                  ('018f0000-0000-7000-8000-00000000f913','018f0000-0000-7000-8000-00000000f902','CREATED',null,'DRAFT','康复治疗申请已建档','ZL-SYN-20260831-001',45),
                  ('018f0000-0000-7000-8000-00000000f914','018f0000-0000-7000-8000-00000000f902','READY','DRAFT','READY','治疗参数、不良事件预案及双人核对完成','ZL-SYN-20260831-001',40),
                  ('018f0000-0000-7000-8000-00000000f915','018f0000-0000-7000-8000-00000000f903','CREATED',null,'DRAFT','麻醉术前评估已建档','MZ-SYN-20260831-001',35),
                  ('018f0000-0000-7000-8000-00000000f916','018f0000-0000-7000-8000-00000000f903','READY','DRAFT','READY','ASA分级、禁食禁饮、知情同意和气道方案核对通过','MZ-SYN-20260831-001',30),
                  ('018f0000-0000-7000-8000-00000000f917','018f0000-0000-7000-8000-00000000f904','CREATED',null,'DRAFT','监护设备绑定申请已建档','SB-SYN-20260831-001',28),
                  ('018f0000-0000-7000-8000-00000000f918','018f0000-0000-7000-8000-00000000f904','READY','DRAFT','READY','设备身份、患者腕带、时钟偏移和告警策略核对通过','SB-SYN-20260831-001',25),
                  ('018f0000-0000-7000-8000-00000000f919','018f0000-0000-7000-8000-00000000f904','STARTED','READY','IN_PROGRESS','已开始采集并按告警策略升级','SB-SYN-20260831-001',20)
                ) as seed(event_id, case_id, event_type, from_status, to_status,
                  note, business_number, age_minutes)
                on conflict (tenant_id, specialty_execution_event_id) do nothing
                """).param("tenant", TENANT_ID).param("actor", USER_ID).update();
    }

    /**
     * Adds de-identified, clinically plausible tertiary-hospital workflow evidence for the
     * inpatient workbench. These are synthetic records: they exercise real constraints and state
     * transitions without importing production PHI.
     */
    private void upsertTertiaryInpatientWorkspaceFixtures() {
        UUID patientId = UUID.fromString("018f0000-0000-7000-8000-000000000002");
        UUID encounterId = UUID.fromString("018f0000-0000-7000-8000-000000000102");
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                select :tenant, seed.order_id::uuid, :patient, :encounter, :facility,
                  seed.order_scope, seed.status, seed.indication, :author, :signer,
                  seed.signed_at, 'TERTIARY-INPATIENT-RULESET-2026.08'
                from (values
                  ('018f0000-0000-7000-8000-00000000bc01', 'LONG_TERM', 'ACTIVE',
                   '慢性心力衰竭急性加重，优化容量管理并监测血压、肾功能与电解质。',
                   '2026-08-25 08:20:00+08'::timestamptz),
                  ('018f0000-0000-7000-8000-00000000bc02', 'TEMPORARY', 'COMPLETED',
                   '评估心肌损伤、心衰程度、肾功能与利尿治疗相关电解质变化。',
                   '2026-08-26 06:35:00+08'::timestamptz),
                  ('018f0000-0000-7000-8000-00000000bc03', 'TEMPORARY', 'COMPLETED',
                   '评估左心室收缩功能、瓣膜状态及容量负荷，指导心衰分型与治疗。',
                   '2026-08-26 07:00:00+08'::timestamptz)
                ) as seed(order_id, order_scope, status, indication, signed_at)
                on conflict (tenant_id, order_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY_ID)
                .param("author", USER_ID).param("signer", ATTENDING_USER_ID).update();
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, instructions, item_state)
                select :tenant, seed.item_id::uuid, seed.order_id::uuid, seed.item_type,
                  seed.catalog_code, seed.display_name, seed.quantity, seed.unit,
                  seed.instructions, seed.item_state
                from (values
                  ('018f0000-0000-7000-8000-00000000bc11',
                   '018f0000-0000-7000-8000-00000000bc01', 'MEDICATION', 'MED-FUROSEMIDE-IV-20',
                   '呋塞米注射液', 14::numeric, '支',
                   '20 mg 静脉注射，每日 2 次；严密记录出入量，每日监测体重、血压、血钾和肾功能。', 'ACTIVE'),
                  ('018f0000-0000-7000-8000-00000000bc12',
                   '018f0000-0000-7000-8000-00000000bc02', 'LAB', 'LAB.TROPONIN.I',
                   '高敏心肌肌钙蛋白 I + NT-proBNP + 肾功能电解质', 1::numeric, '次',
                   '急检；危急值按临床窗口报告并完成闭环处置。', 'COMPLETED'),
                  ('018f0000-0000-7000-8000-00000000bc13',
                   '018f0000-0000-7000-8000-00000000bc03', 'IMAGING', 'IMG-ECHOCARDIOGRAPHY',
                   '经胸超声心动图', 1::numeric, '次',
                   '床旁完成，重点评估 LVEF、节段性室壁运动、瓣膜及下腔静脉。', 'COMPLETED')
                ) as seed(item_id, order_id, item_type, catalog_code, display_name,
                  quantity, unit, instructions, item_state)
                on conflict (tenant_id, order_item_id) do nothing
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into order_execution_task(
                  tenant_id, execution_task_id, order_id, order_item_id, patient_id, encounter_id,
                  task_state, requested_quantity, performed_quantity, quantity_unit)
                select :tenant, seed.task_id::uuid, seed.order_id::uuid, seed.item_id::uuid,
                  :patient, :encounter, seed.task_state, seed.requested, seed.performed, seed.unit
                from (values
                  ('018f0000-0000-7000-8000-00000000bc21',
                   '018f0000-0000-7000-8000-00000000bc01',
                   '018f0000-0000-7000-8000-00000000bc11', 'PENDING', 14::numeric, 0::numeric, '支'),
                  ('018f0000-0000-7000-8000-00000000bc22',
                   '018f0000-0000-7000-8000-00000000bc02',
                   '018f0000-0000-7000-8000-00000000bc12', 'COMPLETED', 1::numeric, 1::numeric, '次'),
                  ('018f0000-0000-7000-8000-00000000bc23',
                   '018f0000-0000-7000-8000-00000000bc03',
                   '018f0000-0000-7000-8000-00000000bc13', 'COMPLETED', 1::numeric, 1::numeric, '次')
                ) as seed(task_id, order_id, item_id, task_state, requested, performed, unit)
                on conflict (tenant_id, execution_task_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId)
                .param("encounter", encounterId).update();
        jdbc.sql("""
                insert into clinical_result(
                  tenant_id, result_id, patient_id, encounter_id, facility_id, order_id,
                  execution_task_id, report_type, source_system, source_report_key,
                  current_version_id, author_user_id)
                select :tenant, seed.result_id::uuid, :patient, :encounter, :facility,
                  seed.order_id::uuid, seed.task_id::uuid, seed.report_type,
                  'JC-AFFILIATED-HOSPITAL-SIMULATION', seed.report_key,
                  seed.version_id::uuid, :author
                from (values
                  ('018f0000-0000-7000-8000-00000000bc31',
                   '018f0000-0000-7000-8000-00000000bc02',
                   '018f0000-0000-7000-8000-00000000bc22', 'LAB',
                   'LIS-IP-20260826-006381', '018f0000-0000-7000-8000-00000000bc41'),
                  ('018f0000-0000-7000-8000-00000000bc32',
                   '018f0000-0000-7000-8000-00000000bc03',
                   '018f0000-0000-7000-8000-00000000bc23', 'IMAGING',
                   'PACS-ECHO-IP-20260826-000917', '018f0000-0000-7000-8000-00000000bc42')
                ) as seed(result_id, order_id, task_id, report_type, report_key, version_id)
                on conflict (tenant_id, result_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY_ID)
                .param("author", ATTENDING_USER_ID).update();
        jdbc.sql("""
                insert into clinical_result_version(
                  tenant_id, result_version_id, result_id, version_no, report_status, conclusion,
                  reported_at, change_type, authored_by)
                select :tenant, seed.version_id::uuid, seed.result_id::uuid, 1, 'FINAL',
                  seed.conclusion, seed.reported_at, 'INITIAL', :author
                from (values
                  ('018f0000-0000-7000-8000-00000000bc41',
                   '018f0000-0000-7000-8000-00000000bc31',
                   'hs-cTnI 升高并达危急值，NT-proBNP 显著升高，伴低钾血症及轻度肾功能受损；已通知临床并完成接收确认。',
                   '2026-08-26 07:12:00+08'::timestamptz),
                  ('018f0000-0000-7000-8000-00000000bc42',
                   '018f0000-0000-7000-8000-00000000bc32',
                   '左心室扩大，左心室整体收缩功能中度降低，LVEF 约 35%；二尖瓣轻至中度反流，下腔静脉增宽且吸气塌陷率减低。',
                   '2026-08-26 10:08:00+08'::timestamptz)
                ) as seed(version_id, result_id, conclusion, reported_at)
                on conflict (tenant_id, result_version_id) do nothing
                """).param("tenant", TENANT_ID).param("author", ATTENDING_USER_ID).update();
        jdbc.sql("""
                insert into clinical_result_observation(
                  tenant_id, observation_id, result_version_id, item_code, item_name, value_type,
                  numeric_value, unit, reference_low, reference_high, abnormal_flag)
                select :tenant, seed.observation_id::uuid, seed.version_id::uuid, seed.item_code,
                  seed.item_name, 'NUMERIC', seed.numeric_value, seed.unit, seed.reference_low,
                  seed.reference_high, seed.abnormal_flag
                from (values
                  ('018f0000-0000-7000-8000-00000000bc51',
                   '018f0000-0000-7000-8000-00000000bc41', 'HS-TNI', '高敏心肌肌钙蛋白 I',
                   286::numeric, 'ng/L', 0::numeric, 34::numeric, 'CRITICAL_HIGH'),
                  ('018f0000-0000-7000-8000-00000000bc52',
                   '018f0000-0000-7000-8000-00000000bc41', 'NT-PROBNP', 'N 末端 B 型利钠肽原',
                   4860::numeric, 'pg/mL', 0::numeric, 900::numeric, 'HIGH'),
                  ('018f0000-0000-7000-8000-00000000bc53',
                   '018f0000-0000-7000-8000-00000000bc41', 'K', '血钾',
                   3.2::numeric, 'mmol/L', 3.5::numeric, 5.5::numeric, 'LOW'),
                  ('018f0000-0000-7000-8000-00000000bc54',
                   '018f0000-0000-7000-8000-00000000bc41', 'CREA', '血清肌酐',
                   138::numeric, 'µmol/L', 57::numeric, 111::numeric, 'HIGH'),
                  ('018f0000-0000-7000-8000-00000000bc55',
                   '018f0000-0000-7000-8000-00000000bc42', 'LVEF', '左心室射血分数',
                   35::numeric, '%', 50::numeric, 75::numeric, 'LOW')
                ) as seed(observation_id, version_id, item_code, item_name, numeric_value,
                  unit, reference_low, reference_high, abnormal_flag)
                on conflict (tenant_id, observation_id) do nothing
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                insert into critical_value_case(
                  tenant_id, critical_value_id, result_id, observation_id, patient_id,
                  encounter_id, state, row_version, created_at, updated_at)
                values (:tenant, '018f0000-0000-7000-8000-00000000bc61',
                  '018f0000-0000-7000-8000-00000000bc31',
                  '018f0000-0000-7000-8000-00000000bc51', :patient, :encounter,
                  'ACKNOWLEDGED', 2, '2026-08-26 07:12:30+08', '2026-08-26 07:18:00+08')
                on conflict (tenant_id, critical_value_id) do nothing
                """).param("tenant", TENANT_ID).param("patient", patientId)
                .param("encounter", encounterId).update();
        jdbc.sql("""
                insert into critical_value_event(
                  tenant_id, critical_value_event_id, critical_value_id, event_type,
                  actor_user_id, notification_method, recipient_confirmed, occurred_at)
                select :tenant, seed.event_id::uuid,
                  '018f0000-0000-7000-8000-00000000bc61', seed.event_type, seed.actor,
                  seed.notification_method, seed.recipient_confirmed, seed.occurred_at
                from (values
                  ('018f0000-0000-7000-8000-00000000bc71', 'CREATED', :laboratory,
                   null::varchar, null::boolean, '2026-08-26 07:12:30+08'::timestamptz),
                  ('018f0000-0000-7000-8000-00000000bc72', 'ACKNOWLEDGED', :clinician,
                   '电话+工作台消息', true, '2026-08-26 07:18:00+08'::timestamptz)
                ) as seed(event_id, event_type, actor, notification_method,
                  recipient_confirmed, occurred_at)
                on conflict (tenant_id, critical_value_event_id) do nothing
                """).param("tenant", TENANT_ID).param("laboratory", ATTENDING_USER_ID)
                .param("clinician", USER_ID).update();

        jdbc.sql("""
                insert into inpatient_consultation(
                  tenant_id, consultation_id, admission_id, organization_id, facility_id,
                  patient_id, encounter_id, requested_department, urgency, reason,
                  clinical_question, status, due_at, requested_by, requested_at)
                values (:tenant, '018f0000-0000-7000-8000-00000000bd01', :admission,
                  :organization, :facility, :patient, :encounter, '临床药学部', 'URGENT',
                  '心力衰竭患者合并肾功能下降，需要评估利尿剂、RAAS 抑制剂与电解质风险。',
                  '请给出基于当前肾功能、血钾与血压的个体化用药调整及监测建议。',
                  'REQUESTED', '2026-08-29 11:00:00+08', :requester,
                  '2026-08-28 09:05:00+08')
                on conflict (tenant_id, consultation_id) do nothing
                """).param("tenant", TENANT_ID).param("admission", SYNTHETIC_ADMISSION_ID)
                .param("organization", ORGANIZATION_ID).param("facility", FACILITY_ID)
                .param("patient", patientId).param("encounter", encounterId)
                .param("requester", USER_ID).update();
        jdbc.sql("""
                insert into inpatient_consultation(
                  tenant_id, consultation_id, admission_id, organization_id, facility_id,
                  patient_id, encounter_id, requested_department, urgency, reason,
                  clinical_question, status, due_at, requested_by, requested_at,
                  accepted_by, accepted_at, opinion, recommendation, opinion_signed_by,
                  opinion_signed_at, completed_by, completed_at)
                values (:tenant, '018f0000-0000-7000-8000-00000000bd02', :admission,
                  :organization, :facility, :patient, :encounter, '肾内科', 'ROUTINE',
                  '利尿后肌酐波动，心肾综合征风险评估。',
                  '当前容量负荷与肾功能变化是否支持继续利尿及 RAAS 抑制治疗？',
                  'COMPLETED', '2026-08-26 17:00:00+08', :requester,
                  '2026-08-26 09:10:00+08', :consultant, '2026-08-26 09:32:00+08',
                  '结合尿量、下腔静脉宽度与 NT-proBNP 趋势，当前仍存在容量负荷，暂不建议停用利尿治疗。',
                  '每日监测体重、出入量、肌酐、eGFR 及血钾；如收缩压低于 90 mmHg 或肌酐较基线升高超过 30%，及时复评方案。',
                  :consultant, '2026-08-26 10:15:00+08', :requester,
                  '2026-08-26 10:22:00+08')
                on conflict (tenant_id, consultation_id) do nothing
                """).param("tenant", TENANT_ID).param("admission", SYNTHETIC_ADMISSION_ID)
                .param("organization", ORGANIZATION_ID).param("facility", FACILITY_ID)
                .param("patient", patientId).param("encounter", encounterId)
                .param("requester", USER_ID).param("consultant", ATTENDING_USER_ID).update();

        UUID pathwayInstanceId = UUID.fromString("018f0000-0000-7000-8000-00000000be01");
        jdbc.sql("""
                insert into inpatient_pathway_instance(
                  tenant_id, pathway_instance_id, admission_id, organization_id, facility_id,
                  patient_id, encounter_id, pathway_definition_id, pathway_version_id, status,
                  current_stage_code, admission_basis, enrolled_by, enrolled_at)
                select :tenant, :instance, :admission, :organization, :facility, :patient,
                  :encounter, :definition, :version, 'ACTIVE', 'ADMISSION_ASSESSMENT',
                  '主要诊断为慢性心力衰竭急性加重，已核对血流动力学状态、肾功能、用药禁忌与患者意愿，符合标准路径入径条件。',
                  :actor, '2026-08-25 08:40:00+08'
                where not exists (
                  select 1 from inpatient_pathway_instance
                  where tenant_id = :tenant and admission_id = :admission and status = 'ACTIVE')
                on conflict (tenant_id, pathway_instance_id) do nothing
                """).param("tenant", TENANT_ID).param("instance", pathwayInstanceId)
                .param("admission", SYNTHETIC_ADMISSION_ID).param("organization", ORGANIZATION_ID)
                .param("facility", FACILITY_ID).param("patient", patientId).param("encounter", encounterId)
                .param("definition", HEART_FAILURE_PATHWAY_ID).param("version", HEART_FAILURE_PATHWAY_V1_ID)
                .param("actor", USER_ID).update();
        jdbc.sql("""
                insert into inpatient_pathway_task(
                  tenant_id, pathway_task_id, pathway_instance_id, stage_code, task_code,
                  display_name, source_type, source_key, required, sequence_no, state)
                select :tenant, seed.task_id::uuid, :instance, template.stage_code,
                  template.task_code, template.display_name, template.source_type,
                  template.source_key, template.required, template.sequence_no, 'PENDING'
                from clinical_pathway_stage_task template
                join (values
                  ('ADMISSION_RECORD', '018f0000-0000-7000-8000-00000000be11'),
                  ('FIRST_COURSE', '018f0000-0000-7000-8000-00000000be12'),
                  ('TROPONIN_RESULT', '018f0000-0000-7000-8000-00000000be13'),
                  ('DAILY_COURSE', '018f0000-0000-7000-8000-00000000be14'),
                  ('DISCHARGE_RECORD', '018f0000-0000-7000-8000-00000000be15')
                ) as seed(task_code, task_id) on seed.task_code = template.task_code
                where template.tenant_id = :tenant and template.pathway_version_id = :version
                  and exists (select 1 from inpatient_pathway_instance
                    where tenant_id = :tenant and pathway_instance_id = :instance)
                on conflict (tenant_id, pathway_task_id) do nothing
                """).param("tenant", TENANT_ID).param("instance", pathwayInstanceId)
                .param("version", HEART_FAILURE_PATHWAY_V1_ID).update();
    }

    private void upsertInpatientTask(String taskId, String documentTypeCode, OffsetDateTime dueAt) {
        jdbc.sql("""
                insert into inpatient_document_task(
                  tenant_id, task_id, admission_id, document_type_code, task_state, due_at,
                  occurrence_key, rule_version, required_signature_level)
                values (:tenant, :task, :admission, :type, 'PENDING', :due_at, 'ADMISSION', 1,
                  (select required_signature_level from inpatient_document_rule
                   where tenant_id = :tenant and document_type_code = :type and rule_version = 1
                   order by rule_code limit 1))
                on conflict (tenant_id, task_id) do nothing
                """).param("tenant", TENANT_ID).param("task", UUID.fromString(taskId))
                .param("admission", SYNTHETIC_ADMISSION_ID).param("type", documentTypeCode)
                .param("due_at", dueAt).update();
    }

    private void upsertDocument(UUID patientId, UUID encounterId, JsonNode document) {
        UUID documentId = UUID.fromString(document.path("document_id").stringValue());
        UUID versionId = UUID.nameUUIDFromBytes(("synthetic-version:" + documentId).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                insert into clinical_document(tenant_id, document_id, patient_id, encounter_id,
                  document_type_code, template_version_id, status, created_by)
                values (:tenant, :document, :patient, :encounter, :type,
                  (select version.template_version_id from clinical_document_template template
                    join clinical_document_template_version version
                      on version.tenant_id = template.tenant_id and version.template_id = template.template_id
                    where template.tenant_id = :tenant and template.document_type_code = :type
                      and template.lifecycle_status = 'ACTIVE' and version.status = 'PUBLISHED'
                    order by version.version_no desc limit 1), :status, :author)
                on conflict (tenant_id, document_id) do nothing
                """)
                .param("tenant", TENANT_ID).param("document", documentId).param("patient", patientId)
                .param("encounter", encounterId).param("type", document.path("document_type_code").stringValue())
                .param("status", document.path("status").stringValue()).param("author", USER_ID).update();
        jdbc.sql("""
                insert into clinical_document_version(tenant_id, document_id, document_version_id, version_no,
                  status, sections, content_hash, author_user_id)
                values (:tenant, :document, :version, :version_no, :status, cast(:sections as jsonb), :hash, :author)
                on conflict (tenant_id, document_id, document_version_id) do nothing
                """)
                .param("tenant", TENANT_ID).param("document", documentId).param("version", versionId)
                .param("version_no", document.path("version_no").asInt())
                .param("status", document.path("status").stringValue())
                .param("sections", document.path("sections").toString())
                .param("hash", document.path("content_hash").stringValue()).param("author", USER_ID).update();
        jdbc.sql("""
                update clinical_document set current_version_id = :version, updated_at = now()
                where tenant_id = :tenant and document_id = :document and current_version_id is null
                """)
                .param("version", versionId).param("tenant", TENANT_ID).param("document", documentId).update();
    }

    private void refreshPlaceholderPatientProfiles() {
        List<PlaceholderPatient> patients = jdbc.sql("""
                select patient.patient_id, patient.sex_code, patient.birth_date
                from patient
                where patient.tenant_id = :tenant
                  and patient.patient_id not in (
                    '018f0000-0000-7000-8000-000000000001'::uuid,
                    '018f0000-0000-7000-8000-000000000002'::uuid,
                    '018f0000-0000-7000-8000-000000000003'::uuid,
                    '018f0000-0000-7000-8000-000000000004'::uuid,
                    '018f0000-0000-7000-8000-000000000005'::uuid,
                    '018f0000-0000-7000-8000-000000000006'::uuid)
                  and not exists (
                    select 1 from encounter canonical
                    where canonical.tenant_id = patient.tenant_id
                      and canonical.patient_id = patient.patient_id
                      and canonical.source_system in ('SYNTHETIC', 'SYNTHETIC-50'))
                  and (
                    patient.display_name ~* '(合成|测试|患者|synthetic|patient|demo|sample)'
                    or exists (
                      select 1 from encounter generated
                      where generated.tenant_id = patient.tenant_id
                        and generated.patient_id = patient.patient_id
                        and (generated.source_system like 'SYNTHETIC-%'
                          or generated.source_system in (
                            'HAOEMR-TEST', 'HAOEMR-APPOINTMENT', 'ARCHIVE-TEST'))))
                order by patient.patient_id
                """)
                .param("tenant", TENANT_ID)
                .query((resultSet, rowNumber) -> new PlaceholderPatient(
                        resultSet.getObject("patient_id", UUID.class), resultSet.getString("sex_code"),
                        resultSet.getObject("birth_date", LocalDate.class)))
                .list();
        for (PlaceholderPatient patient : patients) {
            String sex = normalizeSyntheticSex(patient.sexCode());
            if (!"M".equals(sex) && !"F".equals(sex)) {
                sex = (patient.patientId().getLeastSignificantBits() & 1L) == 0L ? "M" : "F";
            }
            jdbc.sql("""
                    update patient
                    set display_name = :name, sex_code = :sex, birth_date = :birth_date, updated_at = now(),
                      row_version = row_version + 1
                    where tenant_id = :tenant and patient_id = :patient
                      and (display_name, sex_code, birth_date)
                        is distinct from (:name, :sex, :birth_date)
                    """)
                    .param("name", realisticPatientName(patient.patientId(), sex))
                    .param("sex", sex).param("birth_date", realisticBirthDate(patient.patientId(), patient.birthDate()))
                    .param("tenant", TENANT_ID).param("patient", patient.patientId()).update();
        }
    }

    static String realisticPatientName(UUID patientId, String sexCode) {
        String[] surnames = {
            "王", "李", "张", "刘", "陈", "杨", "黄", "赵", "吴", "周",
            "徐", "孙", "马", "朱", "胡", "郭", "何", "高", "林", "罗",
            "郑", "梁", "谢", "宋", "唐", "许", "韩", "冯", "邓", "曹",
            "彭", "曾", "肖", "田", "董", "袁", "潘", "于", "蒋", "蔡",
            "余", "杜", "叶", "程", "苏", "魏", "吕", "丁", "任", "沈"
        };
        String[] firstNames = "F".equals(sexCode)
                ? new String[]{"静", "晓", "雅", "雨", "欣", "婉", "思", "佳", "慧", "丽",
                    "雪", "梦", "若", "安", "芷", "依", "淑", "秀", "素", "美",
                    "怡", "清", "敏", "悦", "晨", "月", "丹", "琴", "兰", "桂"}
                : new String[]{"志", "建", "明", "浩", "国", "俊", "子", "庆", "伟", "振",
                    "德", "宇", "福", "凯", "天", "长", "文", "嘉", "海", "泽",
                    "宏", "正", "启", "景", "承", "博", "瑞", "东", "华", "远"};
        String[] secondNames = "F".equals(sexCode)
                ? new String[]{"敏", "怡", "桐", "然", "彤", "琴", "琪", "清", "兰", "芬",
                    "华", "英", "悦", "曦", "琳", "芳", "梅", "娜", "珍", "涵",
                    "洁", "宁", "妍", "晴", "茹", "菲", "蓉", "瑶", "璇", "颖"}
                : new String[]{"远", "国", "轩", "强", "然", "墨", "杰", "华", "胜", "航",
                    "林", "凯", "佑", "青", "博", "诚", "峰", "鹏", "平", "辰",
                    "宇", "伟", "豪", "瑞", "涛", "宁", "健", "斌", "成", "毅"};
        long seed = mix64(patientId.getMostSignificantBits() ^ Long.rotateLeft(
                patientId.getLeastSignificantBits(), 29));
        long firstSeed = mix64(seed + 0x9E3779B97F4A7C15L);
        long secondSeed = mix64(firstSeed + 0x9E3779B97F4A7C15L);
        return surnames[(int) Math.floorMod(seed, surnames.length)]
                + firstNames[(int) Math.floorMod(firstSeed, firstNames.length)]
                + secondNames[(int) Math.floorMod(secondSeed, secondNames.length)];
    }

    static String normalizeSyntheticSex(String sexCode) {
        if (sexCode == null) return "U";
        return switch (sexCode) {
            case "1" -> "M";
            case "2" -> "F";
            default -> sexCode;
        };
    }

    static LocalDate realisticBirthDate(UUID patientId, LocalDate currentBirthDate) {
        if (currentBirthDate == null) return LocalDate.of(1980, 1, 1);
        LocalDate today = LocalDate.now();
        int year = Math.min(currentBirthDate.getYear(), today.getYear());
        int hash = patientId.hashCode();
        if (year == today.getYear()) {
            return LocalDate.ofYearDay(year, 1 + Math.floorMod(hash, today.getDayOfYear()));
        }
        int month = 1 + Math.floorMod(Integer.rotateLeft(hash, 7), 12);
        int day = 1 + Math.floorMod(Integer.rotateLeft(hash, 17),
                YearMonth.of(year, month).lengthOfMonth());
        return LocalDate.of(year, month, day);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static UUID syntheticAdministrationId(String resource) {
        return UUID.nameUUIDFromBytes(("openemr2026:tertiary-hospital:" + resource)
                .getBytes(StandardCharsets.UTF_8));
    }

    private record PlaceholderPatient(UUID patientId, String sexCode, LocalDate birthDate) {}

    private record SyntheticTemplate(String documentTypeCode, String displayName, JsonNode sections) {}

    private record AdministrationDepartment(UUID facilityId, String code, String name, String unitType) {}

    private record AdministrationWard(
            UUID facilityId, String departmentCode, String code, String name, int bedCount) {}

    private record AdministrationStaff(
            String personCode, String displayName, String externalSubject, String roleCode,
            String departmentCode, String positionName, String credentialType) {}
}
