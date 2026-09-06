package org.haoemr.development;

import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds a persisted, relationally consistent tertiary-hospital workload from the synthetic
 * clinical trajectories. Reference catalogs are stable, while every business row is derived
 * from patients, encounters, orders, admissions, staff and facilities already stored in the
 * database. No production PHI is used.
 */
@Component
@Profile("dev-synthetic")
final class TertiaryOperationalDataImporter {

    private static final UUID TENANT_ID = SyntheticDataImporter.TENANT_ID;
    private static final UUID FACILITY_ID = SyntheticDataImporter.FACILITY_ID;
    private static final UUID USER_ID = SyntheticDataImporter.USER_ID;
    private static final UUID COLLABORATOR_USER_ID = SyntheticDataImporter.COLLABORATOR_USER_ID;
    private static final UUID ATTENDING_USER_ID = SyntheticDataImporter.ATTENDING_USER_ID;
    private static final UUID CHIEF_USER_ID = SyntheticDataImporter.CHIEF_USER_ID;
    private static final UUID WARD_ID = SyntheticDataImporter.SYNTHETIC_WARD_ID;
    private static final UUID CANONICAL_OUTPATIENT_ENCOUNTER =
            UUID.fromString("018f0000-0000-7000-8000-000000000101");
    private static final UUID CANONICAL_INPATIENT_ENCOUNTER =
            UUID.fromString("018f0000-0000-7000-8000-000000000102");
    private static final UUID HEART_FAILURE_PATHWAY_ID = SyntheticDataImporter.HEART_FAILURE_PATHWAY_ID;
    private static final UUID HEART_FAILURE_PATHWAY_V1_ID = SyntheticDataImporter.HEART_FAILURE_PATHWAY_V1_ID;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    TertiaryOperationalDataImporter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    void importData() {
        transactions.executeWithoutResult(status -> {
            removeInvalidOperationalFixtures();
            seedPriceCatalog();
            seedCharges();
            seedPharmacyDispensing();
            seedCanonicalLabOrders();
            seedLabSpecimens();
            seedImagingOrders();
            seedSurgicalProcedures();
            seedBloodTransfusions();
            seedMedicationWorkflow();
            seedNursingWorkload();
            seedFollowupsAndReferrals();
            seedHeartFailurePathway();
        });
    }

    private void removeInvalidOperationalFixtures() {
        jdbc.sql("""
                delete from lab_specimen specimen
                using clinical_order_item item, clinical_order orders
                where specimen.tenant_id = :tenant
                  and item.tenant_id = specimen.tenant_id
                  and item.order_item_id = specimen.order_item_id
                  and orders.tenant_id = item.tenant_id
                  and orders.order_id = item.order_id
                  and orders.rule_watermark = 'TERTIARY-OPERATIONAL-V1'
                  and item.item_type = 'LAB'
                  and (substring(orders.order_id::text, 15, 1) !~ '[1-8]'
                    or substring(orders.order_id::text, 20, 1) !~ '[89abAB]'
                    or substring(item.order_item_id::text, 15, 1) !~ '[1-8]'
                    or substring(item.order_item_id::text, 20, 1) !~ '[89abAB]')
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                delete from clinical_order_item item
                using clinical_order orders
                where item.tenant_id = :tenant
                  and orders.tenant_id = item.tenant_id
                  and orders.order_id = item.order_id
                  and orders.rule_watermark = 'TERTIARY-OPERATIONAL-V1'
                  and item.item_type = 'LAB'
                  and (substring(orders.order_id::text, 15, 1) !~ '[1-8]'
                    or substring(orders.order_id::text, 20, 1) !~ '[89abAB]'
                    or substring(item.order_item_id::text, 15, 1) !~ '[1-8]'
                    or substring(item.order_item_id::text, 20, 1) !~ '[89abAB]')
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                delete from clinical_order
                where tenant_id = :tenant
                  and rule_watermark = 'TERTIARY-OPERATIONAL-V1'
                  and (substring(order_id::text, 15, 1) !~ '[1-8]'
                    or substring(order_id::text, 20, 1) !~ '[89abAB]')
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                delete from outpatient_followup
                where tenant_id = :tenant
                  and (substring(followup_id::text, 15, 1) !~ '[1-8]'
                    or substring(followup_id::text, 20, 1) !~ '[89abAB]')
                  and content = '复核症状变化、家庭血压或体重记录、药物依从性与不良反应，根据检验结果调整慢病管理计划。'
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                delete from price_catalog_version
                where tenant_id = :tenant and catalog_code = 'JC-DXFS-2026'
                  and release_version = '2026.1'
                  and (substring(price_version_id::text, 15, 1) !~ '[1-8]'
                    or substring(price_version_id::text, 20, 1) !~ '[89abAB]')
                """).param("tenant", TENANT_ID).update();
        jdbc.sql("""
                delete from charge_item row using encounter e
                where row.tenant_id = :tenant and e.tenant_id = row.tenant_id
                  and e.encounter_id = row.encounter_id
                  and (e.source_system = 'SYNTHETIC-50'
                    or e.encounter_id in (:outpatient, :inpatient))
                  and (substring(row.charge_item_id::text, 15, 1) !~ '[1-8]'
                    or substring(row.charge_item_id::text, 20, 1) !~ '[89abAB]')
                """).param("tenant", TENANT_ID).param("outpatient", CANONICAL_OUTPATIENT_ENCOUNTER)
                .param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).update();
        jdbc.sql("""
                delete from imaging_order row using encounter e
                where row.tenant_id = :tenant and e.tenant_id = row.tenant_id
                  and e.encounter_id = row.encounter_id
                  and (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :outpatient)
                  and (substring(row.imaging_order_id::text, 15, 1) !~ '[1-8]'
                    or substring(row.imaging_order_id::text, 20, 1) !~ '[89abAB]')
                """).param("tenant", TENANT_ID).param("outpatient", CANONICAL_OUTPATIENT_ENCOUNTER).update();
        jdbc.sql("""
                delete from surgical_procedure row using encounter e
                where row.tenant_id = :tenant and e.tenant_id = row.tenant_id
                  and e.encounter_id = row.encounter_id
                  and (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)
                  and (substring(row.surgical_procedure_id::text, 15, 1) !~ '[1-8]'
                    or substring(row.surgical_procedure_id::text, 20, 1) !~ '[89abAB]')
                """).param("tenant", TENANT_ID).param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).update();
        jdbc.sql("""
                delete from blood_transfusion row using encounter e
                where row.tenant_id = :tenant and e.tenant_id = row.tenant_id
                  and e.encounter_id = row.encounter_id
                  and (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)
                  and (substring(row.transfusion_id::text, 15, 1) !~ '[1-8]'
                    or substring(row.transfusion_id::text, 20, 1) !~ '[89abAB]')
                """).param("tenant", TENANT_ID).param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).update();
    }

    private void seedPriceCatalog() {
        jdbc.sql("""
                insert into price_catalog_version(
                  tenant_id, price_version_id, catalog_code, item_code, item_name,
                  unit_price, unit, effective_from, release_version, status)
                select :tenant, overlay(overlay(md5('tertiary-operational-v1:price:' || seed.item_code)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  'JC-DXFS-2026', seed.item_code, seed.item_name, seed.unit_price,
                  seed.unit, date '2026-01-01', '2026.1', 'ACTIVE'
                from (values
                  ('REG-OPD', '普通门诊诊查费', 20.00::numeric, '次'),
                  ('REG-ER', '急诊诊查费', 35.00::numeric, '次'),
                  ('BED-CARDIO', '心内科床位费', 45.00::numeric, '日'),
                  ('NURSING-L2', '二级护理费', 18.00::numeric, '日'),
                  ('LAB-CBC', '血细胞分析', 25.00::numeric, '组'),
                  ('LAB-BIOCHEM', '生化全套', 138.00::numeric, '组'),
                  ('LAB-COAG', '凝血功能检查', 92.00::numeric, '组'),
                  ('IMG-CT-CHEST', '胸部CT平扫', 280.00::numeric, '部位'),
                  ('IMG-US-CARDIAC', '经胸超声心动图', 210.00::numeric, '次'),
                  ('ECG-12LEAD', '十二导联心电图', 36.00::numeric, '次'),
                  ('DRUG-SERVICE', '静脉用药配置费', 12.00::numeric, '次'),
                  ('CONSULT-MD', '院内会诊费', 80.00::numeric, '次')
                ) as seed(item_code, item_name, unit_price, unit)
                on conflict (tenant_id, catalog_code, item_code, release_version) do update set
                  item_name = excluded.item_name,
                  unit_price = excluded.unit_price,
                  unit = excluded.unit,
                  effective_from = excluded.effective_from,
                  status = excluded.status
                """).param("tenant", TENANT_ID).update();
    }

    private void seedCharges() {
        jdbc.sql("""
                with business_encounter as (
                  select encounter_id, patient_id, facility_id
                  from encounter
                  where tenant_id = :tenant and source_system = 'SYNTHETIC-50'
                  union
                  select encounter_id, patient_id, facility_id
                  from encounter
                  where tenant_id = :tenant and encounter_id in (:outpatient, :inpatient)
                ), catalog as (
                  select row_number() over ()::int as ordinal, item_code, item_name, unit_price, unit
                  from (values
                    ('REG-OPD', '门急诊诊查费', 20.00::numeric, '次'),
                    ('LAB-CBC', '血细胞分析', 25.00::numeric, '组'),
                    ('LAB-BIOCHEM', '生化全套', 138.00::numeric, '组'),
                    ('ECG-12LEAD', '十二导联心电图', 36.00::numeric, '次'),
                    ('BED-CARDIO', '心内科床位费', 45.00::numeric, '日'),
                    ('NURSING-L2', '二级护理费', 18.00::numeric, '日'),
                    ('LAB-COAG', '凝血功能检查', 92.00::numeric, '组'),
                    ('IMG-CT-CHEST', '胸部CT平扫', 280.00::numeric, '部位'),
                    ('IMG-US-CARDIAC', '经胸超声心动图', 210.00::numeric, '次'),
                    ('DRUG-SERVICE', '静脉用药配置费', 12.00::numeric, '次'),
                    ('CONSULT-MD', '院内会诊费', 80.00::numeric, '次'),
                    ('OXYGEN', '中心吸氧', 5.00::numeric, '小时')
                  ) v(item_code, item_name, unit_price, unit)
                ), workload as (
                  select e.*, c.*, case when c.ordinal in (5, 6, 12) then 2::numeric else 1::numeric end quantity
                  from business_encounter e cross join catalog c
                  where c.ordinal <= case when e.encounter_id in (:outpatient, :inpatient) then 12 else 4 end
                )
                insert into charge_item(
                  tenant_id, charge_item_id, patient_id, encounter_id, facility_id,
                  item_code, item_name, quantity, unit_price, amount, unit, status,
                  charged_at, charged_by, reversed_at, reversed_by, reverse_reason)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:charge:' || encounter_id || ':' || item_code)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  patient_id, encounter_id, facility_id, item_code, item_name, quantity,
                  unit_price, round(quantity * unit_price, 2), unit, 'CHARGED',
                  now() - ((ordinal * 13 + abs(hashtext(encounter_id::text)) % 360) * interval '1 minute'),
                  :operator, null, null, null
                from workload
                on conflict (tenant_id, charge_item_id) do nothing
                """).param("tenant", TENANT_ID).param("outpatient", CANONICAL_OUTPATIENT_ENCOUNTER)
                .param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).param("operator", USER_ID).update();
    }

    private void seedPharmacyDispensing() {
        jdbc.sql("""
                with business_encounter as (
                  select encounter_id, patient_id, facility_id
                  from encounter
                  where tenant_id = :tenant and source_system = 'SYNTHETIC-50'
                  union
                  select encounter_id, patient_id, facility_id
                  from encounter
                  where tenant_id = :tenant and encounter_id in (:outpatient, :inpatient)
                ), drug as (
                  select * from (values
                    (1, 'AMLODIPINE-5MG', '批A260801', 14::numeric, '片'),
                    (2, 'ATORVASTATIN-20MG', '批A260715', 7::numeric, '片'),
                    (3, 'ASPIRIN-100MG', '批A260622', 30::numeric, '片'),
                    (4, 'CLOPIDOGREL-75MG', '批A260709', 14::numeric, '片'),
                    (5, 'FUROSEMIDE-20MG', '批A260811', 14::numeric, '片'),
                    (6, 'SPIRONOLACTONE-20MG', '批A260530', 14::numeric, '片'),
                    (7, 'SACUBITRIL-VALSARTAN-100MG', '批A260726', 28::numeric, '片'),
                    (8, 'PANTOPRAZOLE-40MG', '批A260803', 7::numeric, '片')
                  ) v(ordinal, drug_code, batch_number, quantity, quantity_unit)
                ), workload as (
                  select e.*, d.* from business_encounter e cross join drug d
                  where d.ordinal <= case when e.encounter_id in (:outpatient, :inpatient) then 8 else 2 end
                )
                insert into pharmacy_dispensing(
                  tenant_id, dispensing_id, patient_id, encounter_id, facility_id, drug_code,
                  batch_number, quantity, quantity_unit, dispensed_by, verified_by, status,
                  prepared_at, verified_at, dispensed_at)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:dispensing:' || encounter_id || ':' || drug_code)
                    placing '4' from 13 for 1) placing 'a' from 17 for 1)::uuid,
                  patient_id, encounter_id, facility_id, drug_code, batch_number, quantity, quantity_unit,
                  :operator, case when ordinal % 4 = 1 then null else :verifier end,
                  case when ordinal % 4 = 1 then 'PREPARED' when ordinal % 4 = 2 then 'VERIFIED' else 'DISPENSED' end,
                  now() - ((ordinal * 19 + 60) * interval '1 minute'),
                  case when ordinal % 4 = 1 then null else now() - ((ordinal * 19 + 50) * interval '1 minute') end,
                  case when ordinal % 4 in (1, 2) then null else now() - ((ordinal * 19 + 40) * interval '1 minute') end
                from workload
                on conflict (tenant_id, dispensing_id) do nothing
                """).param("tenant", TENANT_ID).param("outpatient", CANONICAL_OUTPATIENT_ENCOUNTER)
                .param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).param("operator", USER_ID)
                .param("verifier", COLLABORATOR_USER_ID).update();
    }

    private void seedCanonicalLabOrders() {
        jdbc.sql("""
                with encounter_context as (
                  select encounter_id, patient_id, facility_id from encounter
                  where tenant_id = :tenant and encounter_id = :encounter
                ), test as (
                  select * from (values
                    (1, 'LAB.CBC', '血细胞分析'),
                    (2, 'LAB.BIOCHEM', '肝肾功能与电解质'),
                    (3, 'LAB.COAG', '凝血功能'),
                    (4, 'LAB.TROPONIN.I', '高敏肌钙蛋白I'),
                    (5, 'LAB.BNP', 'B型利钠肽'),
                    (6, 'LAB.HBA1C', '糖化血红蛋白'),
                    (7, 'LAB.LIPID', '血脂全套'),
                    (8, 'LAB.CRP', 'C反应蛋白')
                  ) v(ordinal, catalog_code, display_name)
                ), seeded as (
                  select c.*, t.*,
                    overlay(overlay(md5('tertiary-operational-v1:lab-order:' || t.catalog_code)
                      placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid order_id,
                    overlay(overlay(md5('tertiary-operational-v1:lab-item:' || t.catalog_code)
                      placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid order_item_id
                  from encounter_context c cross join test t
                )
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                select :tenant, order_id, patient_id, encounter_id, facility_id, 'TEMPORARY',
                  case when ordinal <= 5 then 'COMPLETED' else 'ACTIVE' end,
                  '心血管专科门诊评估、用药安全监测与慢病风险分层',
                  :author, :author, now() - ((ordinal + 5) * interval '1 hour'),
                  'TERTIARY-OPERATIONAL-V1'
                from seeded
                on conflict (tenant_id, order_id) do nothing
                """).param("tenant", TENANT_ID).param("encounter", CANONICAL_OUTPATIENT_ENCOUNTER)
                .param("author", USER_ID).update();

        jdbc.sql("""
                with test as (
                  select * from (values
                    (1, 'LAB.CBC', '血细胞分析'),
                    (2, 'LAB.BIOCHEM', '肝肾功能与电解质'),
                    (3, 'LAB.COAG', '凝血功能'),
                    (4, 'LAB.TROPONIN.I', '高敏肌钙蛋白I'),
                    (5, 'LAB.BNP', 'B型利钠肽'),
                    (6, 'LAB.HBA1C', '糖化血红蛋白'),
                    (7, 'LAB.LIPID', '血脂全套'),
                    (8, 'LAB.CRP', 'C反应蛋白')
                  ) v(ordinal, catalog_code, display_name)
                )
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, instructions, item_state)
                select :tenant, overlay(overlay(md5('tertiary-operational-v1:lab-item:' || catalog_code)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  overlay(overlay(md5('tertiary-operational-v1:lab-order:' || catalog_code)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  'LAB', catalog_code, display_name, 1, '管',
                  '采集前核对患者双标识，依据项目要求确认空腹与抗凝剂条件',
                  case when ordinal <= 5 then 'COMPLETED' else 'ACTIVE' end
                from test
                on conflict (tenant_id, order_item_id) do nothing
                """).param("tenant", TENANT_ID).update();
    }

    private void seedLabSpecimens() {
        jdbc.sql("""
                with eligible as (
                  select item.order_item_id, item.order_id, orders.patient_id, orders.encounter_id,
                    orders.facility_id,
                    row_number() over (partition by orders.encounter_id order by item.catalog_code)::int ordinal
                  from clinical_order_item item
                  join clinical_order orders on orders.tenant_id = item.tenant_id and orders.order_id = item.order_id
                  join encounter on encounter.tenant_id = orders.tenant_id and encounter.encounter_id = orders.encounter_id
                  where item.tenant_id = :tenant and item.item_type = 'LAB'
                    and (encounter.source_system = 'SYNTHETIC-50' or encounter.encounter_id in (:outpatient, :inpatient))
                )
                insert into lab_specimen(
                  tenant_id, specimen_id, order_id, order_item_id, patient_id, encounter_id,
                  facility_id, specimen_type, collection_status, collected_at, collected_by,
                  received_at, received_by, rejection_reason)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:specimen:' || order_item_id)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  order_id, order_item_id, patient_id, encounter_id, facility_id,
                  case when ordinal % 5 = 0 then 'URINE' else 'BLOOD' end,
                  case when ordinal % 4 = 1 then 'ORDERED' when ordinal % 4 = 2 then 'COLLECTED' else 'RECEIVED' end,
                  case when ordinal % 4 = 1 then null else now() - ((ordinal * 17 + 40) * interval '1 minute') end,
                  case when ordinal % 4 = 1 then null else :collector end,
                  case when ordinal % 4 in (1, 2) then null else now() - ((ordinal * 17 + 25) * interval '1 minute') end,
                  case when ordinal % 4 in (1, 2) then null else :receiver end,
                  null
                from eligible
                on conflict (tenant_id, order_item_id) do nothing
                """).param("tenant", TENANT_ID).param("outpatient", CANONICAL_OUTPATIENT_ENCOUNTER)
                .param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).param("collector", USER_ID)
                .param("receiver", COLLABORATOR_USER_ID).update();
    }

    private void seedImagingOrders() {
        jdbc.sql("""
                with business_encounter as (
                  select encounter_id, patient_id, facility_id
                  from encounter where tenant_id = :tenant and source_system = 'SYNTHETIC-50'
                  union
                  select encounter_id, patient_id, facility_id
                  from encounter where tenant_id = :tenant and encounter_id = :outpatient
                ), exam as (
                  select * from (values
                    (1, 'CT', 'CHEST', 'NONE', true),
                    (2, 'ULTRASOUND', 'CHEST', 'NONE', false),
                    (3, 'XRAY', 'CHEST', 'NONE', false),
                    (4, 'MRI', 'HEAD', 'NONE', true),
                    (5, 'CT', 'ABDOMEN', 'NONE', true),
                    (6, 'ULTRASOUND', 'ABDOMEN', 'NONE', false),
                    (7, 'XRAY', 'LOWER_EXTREMITY', 'LEFT', false),
                    (8, 'MRI', 'SPINE', 'NONE', false)
                  ) v(ordinal, modality, body_part, laterality, contrast_required)
                ), workload as (
                  select e.*, x.* from business_encounter e cross join exam x
                  where x.ordinal <= case when e.encounter_id = :outpatient then 8 else 1 end
                )
                insert into imaging_order(
                  tenant_id, imaging_order_id, patient_id, encounter_id, facility_id, modality,
                  body_part, laterality, contrast_required, status, ordered_at, performed_at, reported_at)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:imaging:' || encounter_id || ':' || ordinal)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  patient_id, encounter_id, facility_id, modality, body_part, laterality, contrast_required,
                  case when ordinal % 4 = 1 then 'ORDERED' when ordinal % 4 = 2 then 'PERFORMED' else 'REPORTED' end,
                  now() - ((ordinal * 6 + 12) * interval '1 hour'),
                  case when ordinal % 4 = 1 then null else now() - ((ordinal * 6 + 10) * interval '1 hour') end,
                  case when ordinal % 4 in (1, 2) then null else now() - ((ordinal * 6 + 8) * interval '1 hour') end
                from workload
                on conflict (tenant_id, imaging_order_id) do nothing
                """).param("tenant", TENANT_ID).param("outpatient", CANONICAL_OUTPATIENT_ENCOUNTER).update();
    }

    private void seedSurgicalProcedures() {
        jdbc.sql("""
                with inpatient as (
                  select admission.encounter_id, admission.patient_id, admission.facility_id,
                    row_number() over (order by admission.admitted_at, admission.admission_id)::int admission_no
                  from inpatient_admission admission
                  join encounter on encounter.tenant_id = admission.tenant_id
                    and encounter.encounter_id = admission.encounter_id
                  where admission.tenant_id = :tenant
                    and (encounter.source_system = 'SYNTHETIC-50' or encounter.encounter_id = :inpatient)
                ), procedure_catalog as (
                  select * from (values
                    (1, '经皮冠状动脉介入治疗', 'CHEST', 'NONE'),
                    (2, '冠状动脉造影术', 'CHEST', 'NONE'),
                    (3, '临时心脏起搏器植入术', 'CHEST', 'NONE'),
                    (4, '中心静脉导管置入术', 'NECK', 'RIGHT'),
                    (5, '胸腔闭式引流术', 'CHEST', 'RIGHT')
                  ) v(ordinal, procedure_name, body_site, laterality)
                ), workload as (
                  select i.*, p.* from inpatient i cross join procedure_catalog p
                  where p.ordinal <= case when i.encounter_id = :inpatient then 5 else 1 end
                )
                insert into surgical_procedure(
                  tenant_id, surgical_procedure_id, patient_id, encounter_id, facility_id,
                  procedure_name, body_site, laterality, surgeon_id, anesthesiologist_id,
                  status, scheduled_at, time_out_at, completed_at)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:surgery:' || encounter_id || ':' || ordinal)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  patient_id, encounter_id, facility_id, procedure_name, body_site, laterality,
                  :surgeon, :anesthesiologist,
                  case when ordinal % 3 = 1 then 'SCHEDULED' when ordinal % 3 = 2 then 'TIME_OUT_COMPLETED' else 'COMPLETED' end,
                  now() - ((ordinal * 20 + admission_no) * interval '1 hour'),
                  case when ordinal % 3 = 1 then null else now() - ((ordinal * 20 + admission_no - 1) * interval '1 hour') end,
                  case when ordinal % 3 = 0 then now() - ((ordinal * 20 + admission_no - 3) * interval '1 hour') else null end
                from workload
                on conflict (tenant_id, surgical_procedure_id) do nothing
                """).param("tenant", TENANT_ID).param("inpatient", CANONICAL_INPATIENT_ENCOUNTER)
                .param("surgeon", ATTENDING_USER_ID).param("anesthesiologist", CHIEF_USER_ID).update();
    }

    private void seedBloodTransfusions() {
        jdbc.sql("""
                with inpatient as (
                  select admission.encounter_id, admission.patient_id, admission.facility_id,
                    row_number() over (order by admission.admitted_at, admission.admission_id)::int admission_no
                  from inpatient_admission admission
                  join encounter on encounter.tenant_id = admission.tenant_id
                    and encounter.encounter_id = admission.encounter_id
                  where admission.tenant_id = :tenant
                    and (encounter.source_system = 'SYNTHETIC-50' or encounter.encounter_id = :inpatient)
                ), blood as (
                  select * from (values
                    (1, 'RED_CELLS', 'A_POS', 200),
                    (2, 'RED_CELLS', 'A_POS', 200),
                    (3, 'PLASMA', 'A_POS', 200),
                    (4, 'PLATELETS', 'A_POS', 250),
                    (5, 'CRYO', 'A_POS', 100),
                    (6, 'RED_CELLS', 'A_POS', 200)
                  ) v(ordinal, blood_product, blood_type, volume_ml)
                ), workload as (
                  select i.*, b.* from inpatient i cross join blood b
                  where (i.encounter_id = :inpatient and b.ordinal <= 6)
                     or (i.encounter_id <> :inpatient and i.admission_no % 3 = 0 and b.ordinal = 1)
                )
                insert into blood_transfusion(
                  tenant_id, transfusion_id, patient_id, encounter_id, facility_id, blood_product,
                  blood_type, unit_number, volume_ml, started_at, administered_by, verified_by,
                  verification_note, reaction_type, reaction_noted_at, reaction_noted_by)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:transfusion:' || encounter_id || ':' || ordinal)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  patient_id, encounter_id, facility_id, blood_product, blood_type,
                  'JC-BB-26-' || upper(substr(md5(encounter_id::text || ':' || ordinal), 1, 10)),
                  volume_ml, now() - ((ordinal * 9 + admission_no) * interval '1 hour'),
                  :operator, :verifier, '已核对患者、血型、血袋号、交叉配血结果与有效期',
                  case when ordinal = 4 then 'FEBRILE' else 'NONE' end,
                  case when ordinal = 4 then now() - interval '24 hour' else null end,
                  case when ordinal = 4 then :verifier else null end
                from workload
                on conflict (tenant_id, transfusion_id) do nothing
                """).param("tenant", TENANT_ID).param("inpatient", CANONICAL_INPATIENT_ENCOUNTER)
                .param("operator", USER_ID).param("verifier", COLLABORATOR_USER_ID).update();
    }

    private void seedMedicationWorkflow() {
        jdbc.sql("""
                with encounter_context as (
                  select encounter_id, patient_id, facility_id from encounter
                  where tenant_id = :tenant and encounter_id = :encounter
                ), medication as (
                  select * from (values
                    (1, 'FUROSEMIDE-IV', '呋塞米注射液', 20::numeric, 'mg', 'IV'),
                    (2, 'POTASSIUM-CHLORIDE-IV', '氯化钾注射液', 1::numeric, 'g', 'IV'),
                    (3, 'ENOXAPARIN-SC', '依诺肝素钠注射液', 4000::numeric, 'IU', 'SC'),
                    (4, 'NITROGLYCERIN-IV', '硝酸甘油注射液', 5::numeric, 'mg', 'IV'),
                    (5, 'PANTOPRAZOLE-IV', '泮托拉唑注射剂', 40::numeric, 'mg', 'IV')
                  ) v(ordinal, drug_code, display_name, dose_value, dose_unit, route_code)
                ), seeded as (
                  select c.*, m.*,
                    overlay(overlay(md5('tertiary-operational-v1:med-order:' || m.drug_code)
                      placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid order_id,
                    overlay(overlay(md5('tertiary-operational-v1:med-item:' || m.drug_code)
                      placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid order_item_id,
                    overlay(overlay(md5('tertiary-operational-v1:med-task:' || m.drug_code)
                      placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid execution_task_id
                  from encounter_context c cross join medication m
                )
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                select :tenant, order_id, patient_id, encounter_id, facility_id, 'TEMPORARY', 'COMPLETED',
                  '心力衰竭与冠心病住院期间容量、血栓及胃黏膜保护综合治疗',
                  :author, :author, now() - ((ordinal + 1) * interval '1 day'), 'TERTIARY-OPERATIONAL-V1'
                from seeded on conflict (tenant_id, order_id) do nothing
                """).param("tenant", TENANT_ID).param("encounter", CANONICAL_INPATIENT_ENCOUNTER)
                .param("author", ATTENDING_USER_ID).update();

        jdbc.sql("""
                with medication as (
                  select * from (values
                    (1, 'FUROSEMIDE-IV', '呋塞米注射液', 20::numeric, 'mg', 'IV'),
                    (2, 'POTASSIUM-CHLORIDE-IV', '氯化钾注射液', 1::numeric, 'g', 'IV'),
                    (3, 'ENOXAPARIN-SC', '依诺肝素钠注射液', 4000::numeric, 'IU', 'SC'),
                    (4, 'NITROGLYCERIN-IV', '硝酸甘油注射液', 5::numeric, 'mg', 'IV'),
                    (5, 'PANTOPRAZOLE-IV', '泮托拉唑注射剂', 40::numeric, 'mg', 'IV')
                  ) v(ordinal, drug_code, display_name, dose_value, dose_unit, route_code)
                )
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, instructions, item_state)
                select :tenant, overlay(overlay(md5('tertiary-operational-v1:med-item:' || drug_code)
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  overlay(overlay(md5('tertiary-operational-v1:med-order:' || drug_code)
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  'MEDICATION', drug_code, display_name, dose_value, dose_unit,
                  '执行前核对双标识、医嘱、药品、剂量、途径和时间，高警示药双人核对', 'COMPLETED'
                from medication on conflict (tenant_id, order_item_id) do nothing
                """).param("tenant", TENANT_ID).update();

        jdbc.sql("""
                with context as (
                  select orders.order_id, item.order_item_id, orders.patient_id, orders.encounter_id,
                    item.requested_quantity, item.quantity_unit, item.catalog_code,
                    row_number() over (order by item.catalog_code)::int ordinal
                  from clinical_order orders
                  join clinical_order_item item on item.tenant_id = orders.tenant_id and item.order_id = orders.order_id
                  where orders.tenant_id = :tenant and orders.rule_watermark = 'TERTIARY-OPERATIONAL-V1'
                    and item.item_type = 'MEDICATION' and orders.encounter_id = :encounter
                )
                insert into order_execution_task(
                  tenant_id, execution_task_id, order_id, order_item_id, patient_id, encounter_id,
                  task_state, requested_quantity, performed_quantity, quantity_unit)
                select :tenant, overlay(overlay(md5('tertiary-operational-v1:med-task:' || catalog_code)
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  order_id, order_item_id, patient_id, encounter_id, 'COMPLETED',
                  requested_quantity, requested_quantity, quantity_unit
                from context on conflict (tenant_id, execution_task_id) do nothing
                """).param("tenant", TENANT_ID).param("encounter", CANONICAL_INPATIENT_ENCOUNTER).update();

        jdbc.sql("""
                with context as (
                  select task.execution_task_id, task.order_id, task.patient_id, task.encounter_id,
                    orders.facility_id, item.catalog_code drug_code, item.requested_quantity dose_value,
                    item.quantity_unit dose_unit,
                    case when item.catalog_code like '%-SC' then 'SC' else 'IV' end route_code,
                    row_number() over (order by item.catalog_code)::int ordinal
                  from order_execution_task task
                  join clinical_order orders on orders.tenant_id = task.tenant_id and orders.order_id = task.order_id
                  join clinical_order_item item on item.tenant_id = task.tenant_id and item.order_item_id = task.order_item_id
                  where task.tenant_id = :tenant and orders.rule_watermark = 'TERTIARY-OPERATIONAL-V1'
                    and task.encounter_id = :encounter
                )
                insert into medication_administration(
                  tenant_id, administration_id, execution_task_id, order_id, patient_id,
                  encounter_id, facility_id, drug_code, dose_value, dose_unit, route_code,
                  administered_at, administered_by, verified_by, verification_note)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:administration:' || execution_task_id)
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  execution_task_id, order_id, patient_id, encounter_id, facility_id, drug_code,
                  dose_value, dose_unit, route_code, now() - (ordinal * interval '8 hour'),
                  :operator, :verifier, '扫码核对通过，药品、剂量、途径和执行时间与医嘱一致'
                from context on conflict (tenant_id, administration_id) do nothing
                """).param("tenant", TENANT_ID).param("encounter", CANONICAL_INPATIENT_ENCOUNTER)
                .param("operator", USER_ID).param("verifier", COLLABORATOR_USER_ID).update();
    }

    private void seedNursingWorkload() {
        jdbc.sql("""
                with inpatient as (
                  select admission.admission_id, admission.encounter_id, admission.patient_id,
                    admission.facility_id,
                    row_number() over (order by admission.admitted_at, admission.admission_id)::int admission_no
                  from inpatient_admission admission
                  join encounter on encounter.tenant_id = admission.tenant_id
                    and encounter.encounter_id = admission.encounter_id
                  where admission.tenant_id = :tenant
                    and (encounter.source_system = 'SYNTHETIC-50' or encounter.encounter_id = :inpatient)
                ), observation as (select generate_series(1, 8)::int ordinal)
                insert into vital_sign_record(
                  tenant_id, vital_sign_record_id, patient_id, encounter_id, facility_id, admission_id,
                  recorded_at, recorded_by, source, temperature, pulse, respiration,
                  systolic_bp, diastolic_bp, spo2)
                select :tenant,
                  md5('tertiary-operational-v1:vital:' || encounter_id || ':' || ordinal)::uuid,
                  patient_id, encounter_id, facility_id, admission_id,
                  now() - ((ordinal * 4 + admission_no) * interval '1 hour'), :operator,
                  case when ordinal % 3 = 0 then 'DEVICE' else 'MANUAL' end,
                  36.4 + ((ordinal + admission_no) % 7) * 0.1,
                  68 + ((ordinal * 7 + admission_no) % 38),
                  16 + ((ordinal + admission_no) % 7),
                  108 + ((ordinal * 5 + admission_no) % 34),
                  64 + ((ordinal * 3 + admission_no) % 22),
                  94 + ((ordinal + admission_no) % 6)
                from inpatient cross join observation
                where ordinal <= case when encounter_id = :inpatient then 8 else 4 end
                on conflict (tenant_id, vital_sign_record_id) do nothing
                """).param("tenant", TENANT_ID).param("inpatient", CANONICAL_INPATIENT_ENCOUNTER)
                .param("operator", USER_ID).update();

        jdbc.sql("""
                with inpatient as (
                  select admission.admission_id, admission.encounter_id, admission.patient_id,
                    admission.facility_id
                  from inpatient_admission admission
                  join encounter on encounter.tenant_id = admission.tenant_id
                    and encounter.encounter_id = admission.encounter_id
                  where admission.tenant_id = :tenant
                    and (encounter.source_system = 'SYNTHETIC-50' or encounter.encounter_id = :inpatient)
                ), plan as (
                  select * from (values
                    (1, '心排出量减少，活动后气促', '维持血流动力学稳定并减轻肺淤血', '监测血压心率与血氧，半卧位，观察胸闷气促变化', 'HIGH'),
                    (2, '体液过多，双下肢水肿', '24小时出入量基本平衡，体重逐步下降', '严格记录出入量与每日体重，限钠限水，观察利尿剂反应', 'HIGH'),
                    (3, '抗凝与多联抗血小板治疗相关出血风险', '住院期间无严重出血事件', '观察皮肤黏膜、牙龈、尿便及穿刺点，复核血红蛋白与凝血', 'MEDIUM'),
                    (4, '疾病与药物自我管理知识不足', '出院前能准确复述药物、体重监测和复诊要求', '分次健康教育，使用回教法核对理解，邀请家属共同参与', 'MEDIUM'),
                    (5, '住院环境与利尿治疗相关跌倒风险', '住院期间不发生跌倒坠床', '评估Morse跌倒风险，床旁警示，起床及如厕时协助', 'LOW')
                  ) v(ordinal, nursing_problem, goal, intervention, priority)
                )
                insert into nursing_care_plan(
                  tenant_id, care_plan_id, patient_id, encounter_id, facility_id, admission_id,
                  nursing_problem, goal, intervention, evaluation, priority, status,
                  created_by, completed_by, completed_at)
                select :tenant,
                  md5('tertiary-operational-v1:care-plan:' || encounter_id || ':' || ordinal)::uuid,
                  patient_id, encounter_id, facility_id, admission_id, nursing_problem, goal, intervention,
                  case when ordinal = 5 then '已完成环境整理与家属教育，患者能主动求助' else null end,
                  priority, case when ordinal = 5 then 'COMPLETED' else 'ACTIVE' end,
                  :operator, case when ordinal = 5 then :verifier else null end,
                  case when ordinal = 5 then now() - interval '6 hour' else null end
                from inpatient cross join plan
                where ordinal <= case when encounter_id = :inpatient then 5 else 2 end
                on conflict (tenant_id, care_plan_id) do nothing
                """).param("tenant", TENANT_ID).param("inpatient", CANONICAL_INPATIENT_ENCOUNTER)
                .param("operator", USER_ID).param("verifier", COLLABORATOR_USER_ID).update();

        jdbc.sql("""
                with inpatient as (
                  select admission.encounter_id, admission.patient_id, admission.facility_id
                  from inpatient_admission admission
                  join encounter on encounter.tenant_id = admission.tenant_id
                    and encounter.encounter_id = admission.encounter_id
                  where admission.tenant_id = :tenant
                    and (encounter.source_system = 'SYNTHETIC-50' or encounter.encounter_id = :inpatient)
                ), note as (
                  select * from (values
                    (1, 'VITAL_SIGNS', '晨间体征已复核，血压、心率与血氧趋势稳定，无新发胸闷。'),
                    (2, 'INTAKE_OUTPUT', '8小时入量780 ml，尿量1050 ml，利尿反应可，继续严格出入量管理。'),
                    (3, 'NURSING_NOTE', '双下肢水肿较前减轻，穿刺点敷料干燥，已完成抗凝出血风险宣教。'),
                    (4, 'VITAL_SIGNS', '午后活动后心率轻度增快，休息后恢复，无低氧与明显呼吸困难。'),
                    (5, 'INTAKE_OUTPUT', '日间饮水与静脉入量在限制范围，尿量达标，晚班继续观察。'),
                    (6, 'NURSING_NOTE', '完成睡前用药及跌倒风险核查，床栏与呼叫器位置正确，患者能配合。')
                  ) v(ordinal, note_type, content)
                )
                insert into nursing_bedside_note(
                  tenant_id, note_id, patient_id, encounter_id, facility_id, note_type,
                  recorded_at, synced_at, device_id, content)
                select :tenant,
                  md5('tertiary-operational-v1:bedside-note:' || encounter_id || ':' || ordinal)::uuid,
                  patient_id, encounter_id, facility_id, note_type,
                  now() - ((ordinal * 3 + 1) * interval '1 hour'),
                  now() - (ordinal * 3 * interval '1 hour'), 'NURSE-PDA-CARDIO-03', content
                from inpatient cross join note
                where ordinal <= case when encounter_id = :inpatient then 6 else 3 end
                on conflict (tenant_id, note_id) do nothing
                """).param("tenant", TENANT_ID).param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).update();

        seedShiftHandovers();
    }

    private void seedShiftHandovers() {
        jdbc.sql("""
                with shift as (select generate_series(1, 8)::int ordinal)
                insert into shift_handover(
                  tenant_id, handover_id, ward_id, facility_id, shift_from, shift_to,
                  outgoing_user_id, incoming_user_id, handover_summary, status, completed_at)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:handover:' || ordinal)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  :ward, :facility,
                  now() - ((ordinal * 8 + 8) * interval '1 hour'),
                  now() - (ordinal * 8 * interval '1 hour'),
                  case when ordinal % 2 = 0 then :operator else :verifier end,
                  case when ordinal % 2 = 0 then :verifier else :operator end,
                  '心内科当班共有重点监护、容量管理、抗凝出血风险及待检查患者；已核对医嘱、管路、危急值、输血和夜间观察要点。',
                  'COMPLETED', now() - (ordinal * 8 * interval '1 hour')
                from shift on conflict (tenant_id, handover_id) do nothing
                """).param("tenant", TENANT_ID).param("ward", WARD_ID).param("facility", FACILITY_ID)
                .param("operator", USER_ID).param("verifier", COLLABORATOR_USER_ID).update();

        jdbc.sql("""
                with patient_list as (
                  select admission.patient_id,
                    row_number() over (order by admission.admitted_at, admission.admission_id)::int patient_no
                  from inpatient_admission admission
                  join encounter on encounter.tenant_id = admission.tenant_id
                    and encounter.encounter_id = admission.encounter_id
                  where admission.tenant_id = :tenant and admission.ward_id = :ward
                    and (encounter.source_system = 'SYNTHETIC-50' or encounter.encounter_id = :inpatient)
                ), shift as (select generate_series(1, 8)::int ordinal)
                insert into shift_handover_patient(
                  tenant_id, shift_handover_patient_id, handover_id, patient_id, summary, risk_flag)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:handover-patient:' || shift.ordinal || ':' || patient_id)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  overlay(overlay(md5('tertiary-operational-v1:handover:' || shift.ordinal)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid, patient_id,
                  case when patient_no % 3 = 0
                    then '重点观察呼吸困难、尿量、血压及血氧变化，异常立即通知值班医师。'
                    else '按医嘱完成体征、出入量和用药核对，跟进未完成检查与健康教育。' end,
                  patient_no % 3 = 0
                from shift cross join patient_list
                on conflict (tenant_id, shift_handover_patient_id) do nothing
                """).param("tenant", TENANT_ID).param("ward", WARD_ID)
                .param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).update();
    }

    private void seedFollowupsAndReferrals() {
        jdbc.sql("""
                with outpatient as (
                  select encounter.encounter_id, encounter.patient_id,
                    row_number() over (order by encounter.started_at, encounter.encounter_id)::int ordinal
                  from encounter
                  where tenant_id = :tenant and (source_system = 'SYNTHETIC-50' or encounter_id = :outpatient)
                    and encounter_type = 'OUTPATIENT'
                )
                insert into outpatient_followup(
                  tenant_id, followup_id, patient_id, encounter_id, followup_type, content,
                  outcome, status, due_at, completed_at)
                select :tenant, overlay(overlay(md5('tertiary-operational-v1:followup:' || encounter_id)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  patient_id, encounter_id,
                  case when ordinal % 3 = 0 then 'EDUCATION' when ordinal % 3 = 1 then 'REVISIT' else 'FOLLOWUP' end,
                  '复核症状变化、家庭血压或体重记录、药物依从性与不良反应，根据检验结果调整慢病管理计划。',
                  case when ordinal % 4 = 0 then '电话随访已完成，症状稳定，患者能正确复述用药及复诊要求。' else null end,
                  case when ordinal % 4 = 0 then 'COMPLETED' else 'PENDING' end,
                  now() + ((3 + ordinal % 28) * interval '1 day'),
                  case when ordinal % 4 = 0 then now() - interval '1 day' else null end
                from outpatient on conflict (tenant_id, followup_id) do nothing
                """).param("tenant", TENANT_ID).param("outpatient", CANONICAL_OUTPATIENT_ENCOUNTER).update();

        jdbc.sql("""
                with business_encounter as (
                  select encounter.encounter_id, encounter.patient_id, encounter.facility_id,
                    row_number() over (order by encounter.started_at, encounter.encounter_id)::int ordinal,
                    coalesce((select version.diagnosis_text
                      from clinical_diagnosis diagnosis
                      join clinical_diagnosis_version version on version.tenant_id = diagnosis.tenant_id
                        and version.diagnosis_version_id = diagnosis.current_version_id
                      where diagnosis.tenant_id = encounter.tenant_id
                        and diagnosis.encounter_id = encounter.encounter_id limit 1), '待进一步专科评估') diagnosis_text
                  from encounter
                  where tenant_id = :tenant and source_system = 'SYNTHETIC-50'
                )
                insert into referral(
                  tenant_id, referral_id, patient_id, encounter_id, facility_id, referral_type,
                  target_department, target_organization, reason, clinical_summary, status,
                  sent_at, resolved_at)
                select :tenant, overlay(overlay(md5('tertiary-operational-v1:referral:' || encounter_id)
                    placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  patient_id, encounter_id, facility_id, 'INTERNAL',
                  case when ordinal % 4 = 0 then '重症医学科' when ordinal % 4 = 1 then '心血管内科'
                    when ordinal % 4 = 2 then '临床营养科' else '康复医学科' end,
                  null, '需要多学科联合评估当前病情、合并症风险与后续治疗方案。',
                  '当前主要诊断：' || diagnosis_text || '。已完成首轮检验或影像评估，请结合病情给出专科意见。',
                  case when ordinal % 3 = 0 then 'ACCEPTED' else 'SENT' end,
                  now() - ((ordinal % 10 + 1) * interval '1 hour'),
                  case when ordinal % 3 = 0 then now() - ((ordinal % 10) * interval '1 hour') else null end
                from business_encounter where ordinal % 4 = 0
                on conflict (tenant_id, referral_id) do nothing
                """).param("tenant", TENANT_ID).update();
    }

    private void seedHeartFailurePathway() {
        jdbc.sql("""
                insert into inpatient_pathway_instance(
                  tenant_id, pathway_instance_id, admission_id, organization_id, facility_id,
                  patient_id, encounter_id, pathway_definition_id, pathway_version_id, status,
                  current_stage_code, admission_basis, enrolled_by, enrolled_at)
                select :tenant, overlay(overlay(md5('tertiary-operational-v1:pathway:' || admission.admission_id)
                    placing '3' from 13 for 1) placing '8' from 17 for 1)::uuid,
                  admission.admission_id, encounter.organization_id, admission.facility_id,
                  admission.patient_id, admission.encounter_id, :definition, :version, 'ACTIVE',
                  'DIAGNOSIS_TREATMENT',
                  '主要诊断为心力衰竭，已由主治医师核对入径标准、合并症、禁忌证与患者意愿。',
                  :operator, greatest(admission.admitted_at, now() - interval '7 day')
                from inpatient_admission admission
                join encounter on encounter.tenant_id = admission.tenant_id
                  and encounter.encounter_id = admission.encounter_id
                where admission.tenant_id = :tenant and admission.encounter_id = :inpatient
                  and not exists (
                    select 1 from inpatient_pathway_instance existing
                    where existing.tenant_id = admission.tenant_id
                      and existing.admission_id = admission.admission_id
                      and existing.status = 'ACTIVE')
                on conflict (tenant_id, pathway_instance_id) do nothing
                """).param("tenant", TENANT_ID).param("definition", HEART_FAILURE_PATHWAY_ID)
                .param("version", HEART_FAILURE_PATHWAY_V1_ID).param("operator", ATTENDING_USER_ID)
                .param("inpatient", CANONICAL_INPATIENT_ENCOUNTER).update();

        jdbc.sql("""
                insert into inpatient_pathway_task(
                  tenant_id, pathway_task_id, pathway_instance_id, stage_code, task_code,
                  display_name, source_type, source_key, required, sequence_no, state)
                select :tenant,
                  overlay(overlay(md5('tertiary-operational-v1:pathway-task:' || instance.pathway_instance_id
                    || ':' || template.task_code) placing '3' from 13 for 1)
                    placing '8' from 17 for 1)::uuid,
                  instance.pathway_instance_id,
                  template.stage_code, template.task_code, template.display_name,
                  template.source_type, template.source_key, template.required,
                  template.sequence_no, 'PENDING'
                from inpatient_admission admission
                join inpatient_pathway_instance instance on instance.tenant_id = admission.tenant_id
                  and instance.admission_id = admission.admission_id and instance.status = 'ACTIVE'
                cross join clinical_pathway_stage_task template
                where admission.tenant_id = :tenant and admission.encounter_id = :inpatient
                  and template.tenant_id = :tenant and template.pathway_version_id = :version
                on conflict do nothing
                """).param("tenant", TENANT_ID).param("inpatient", CANONICAL_INPATIENT_ENCOUNTER)
                .param("version", HEART_FAILURE_PATHWAY_V1_ID).update();
    }
}
