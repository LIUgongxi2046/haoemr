package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class TertiaryOperationalDataImportTest {

    private static final UUID TENANT_ID = SyntheticDataImporter.TENANT_ID;
    private static final UUID OUTPATIENT_ENCOUNTER =
            UUID.fromString("018f0000-0000-7000-8000-000000000101");
    private static final UUID INPATIENT_ENCOUNTER =
            UUID.fromString("018f0000-0000-7000-8000-000000000102");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TertiaryOperationalDataImporter importer;

    @Test
    void importsHospitalScaleOperationalWorkloadInsteadOfTwoRowDemoFixtures() {
        Map<String, Integer> counts = operationalCounts();

        assertThat(counts.get("charges")).isGreaterThanOrEqualTo(224);
        assertThat(counts.get("dispensing")).isGreaterThanOrEqualTo(116);
        assertThat(counts.get("imaging")).isGreaterThanOrEqualTo(58);
        assertThat(counts.get("vitals")).isGreaterThanOrEqualTo(68);
        assertThat(counts.get("carePlans")).isGreaterThanOrEqualTo(35);
        assertThat(counts.get("bedsideNotes")).isGreaterThanOrEqualTo(51);
        assertThat(counts.get("followups")).isGreaterThanOrEqualTo(21);
        assertThat(counts.get("referrals")).isGreaterThanOrEqualTo(12);
        assertThat(counts.get("specimens")).isGreaterThanOrEqualTo(44);
        assertThat(counts.get("surgery")).isGreaterThanOrEqualTo(19);
        assertThat(counts.get("transfusion")).isGreaterThanOrEqualTo(10);
        assertThat(counts.get("administrations")).isGreaterThanOrEqualTo(5);
        assertThat(counts.get("handoverPatients")).isGreaterThanOrEqualTo(120);
    }

    @Test
    void canonicalMenuContextsContainCompletePersistedBusinessLists() {
        Map<String, Integer> canonical = jdbc.sql("""
                select
                  (select count(*) from charge_item where tenant_id = :tenant
                    and encounter_id = :outpatient) as outpatient_charges,
                  (select count(*) from pharmacy_dispensing where tenant_id = :tenant
                    and encounter_id = :outpatient) as outpatient_dispensing,
                  (select count(*) from pharmacy_dispensing where tenant_id = :tenant
                    and encounter_id = :inpatient) as inpatient_dispensing,
                  (select count(*) from lab_specimen where tenant_id = :tenant
                    and encounter_id = :outpatient) as outpatient_specimens,
                  (select count(*) from imaging_order where tenant_id = :tenant
                    and encounter_id = :outpatient) as outpatient_imaging,
                  (select count(*) from surgical_procedure where tenant_id = :tenant
                    and encounter_id = :inpatient) as inpatient_surgery,
                  (select count(*) from blood_transfusion where tenant_id = :tenant
                    and encounter_id = :inpatient) as inpatient_transfusion,
                  (select count(*) from medication_administration where tenant_id = :tenant
                    and encounter_id = :inpatient) as inpatient_administrations,
                  (select count(*) from vital_sign_record where tenant_id = :tenant
                    and encounter_id = :inpatient) as inpatient_vitals,
                  (select count(*) from nursing_care_plan where tenant_id = :tenant
                    and encounter_id = :inpatient) as inpatient_care_plans,
                  (select count(*) from nursing_bedside_note where tenant_id = :tenant
                    and encounter_id = :inpatient) as inpatient_bedside_notes,
                  (select count(*) from inpatient_pathway_task task
                    join inpatient_pathway_instance instance on instance.tenant_id = task.tenant_id
                      and instance.pathway_instance_id = task.pathway_instance_id
                    where task.tenant_id = :tenant and instance.encounter_id = :inpatient) as pathway_tasks
                """).param("tenant", TENANT_ID).param("outpatient", OUTPATIENT_ENCOUNTER)
                .param("inpatient", INPATIENT_ENCOUNTER)
                .query((rs, row) -> {
                    Map<String, Integer> result = new LinkedHashMap<>();
                    result.put("outpatientCharges", rs.getInt("outpatient_charges"));
                    result.put("outpatientDispensing", rs.getInt("outpatient_dispensing"));
                    result.put("inpatientDispensing", rs.getInt("inpatient_dispensing"));
                    result.put("outpatientSpecimens", rs.getInt("outpatient_specimens"));
                    result.put("outpatientImaging", rs.getInt("outpatient_imaging"));
                    result.put("inpatientSurgery", rs.getInt("inpatient_surgery"));
                    result.put("inpatientTransfusion", rs.getInt("inpatient_transfusion"));
                    result.put("inpatientAdministrations", rs.getInt("inpatient_administrations"));
                    result.put("inpatientVitals", rs.getInt("inpatient_vitals"));
                    result.put("inpatientCarePlans", rs.getInt("inpatient_care_plans"));
                    result.put("inpatientBedsideNotes", rs.getInt("inpatient_bedside_notes"));
                    result.put("pathwayTasks", rs.getInt("pathway_tasks"));
                    return result;
                }).single();

        assertThat(canonical.get("outpatientCharges")).isGreaterThanOrEqualTo(12);
        assertThat(canonical.get("outpatientDispensing")).isGreaterThanOrEqualTo(8);
        assertThat(canonical.get("inpatientDispensing")).isGreaterThanOrEqualTo(8);
        assertThat(canonical.get("outpatientSpecimens")).isGreaterThanOrEqualTo(8);
        assertThat(canonical.get("outpatientImaging")).isGreaterThanOrEqualTo(8);
        assertThat(canonical.get("inpatientSurgery")).isGreaterThanOrEqualTo(5);
        assertThat(canonical.get("inpatientTransfusion")).isGreaterThanOrEqualTo(6);
        assertThat(canonical.get("inpatientAdministrations")).isGreaterThanOrEqualTo(5);
        assertThat(canonical.get("inpatientVitals")).isGreaterThanOrEqualTo(8);
        assertThat(canonical.get("inpatientCarePlans")).isGreaterThanOrEqualTo(5);
        assertThat(canonical.get("inpatientBedsideNotes")).isGreaterThanOrEqualTo(6);
        assertThat(canonical.get("pathwayTasks")).isGreaterThanOrEqualTo(5);
    }

    @Test
    void generatedRowsRemainRelationallyLinkedAndContainNoPlaceholderPatients() {
        Integer orphanCount = jdbc.sql("""
                select
                  (select count(*) from charge_item row left join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and e.encounter_id is null)
                  + (select count(*) from pharmacy_dispensing row left join patient p
                    on p.tenant_id = row.tenant_id and p.patient_id = row.patient_id
                    where row.tenant_id = :tenant and p.patient_id is null)
                  + (select count(*) from lab_specimen row left join clinical_order_item item
                    on item.tenant_id = row.tenant_id and item.order_item_id = row.order_item_id
                    where row.tenant_id = :tenant and item.order_item_id is null)
                  + (select count(*) from vital_sign_record row left join inpatient_admission admission
                    on admission.tenant_id = row.tenant_id and admission.admission_id = row.admission_id
                    where row.tenant_id = :tenant and row.admission_id is not null
                      and admission.admission_id is null)
                """).param("tenant", TENANT_ID).query(Integer.class).single();
        Integer placeholderPatients = jdbc.sql("""
                select count(distinct patient.patient_id)
                from patient
                join encounter on encounter.tenant_id = patient.tenant_id
                  and encounter.patient_id = patient.patient_id
                where patient.tenant_id = :tenant and encounter.source_system = 'SYNTHETIC-50'
                  and patient.display_name ~* '(合成|测试|患者|synthetic|patient|demo|sample)'
                """).param("tenant", TENANT_ID).query(Integer.class).single();

        assertThat(orphanCount).isZero();
        assertThat(placeholderPatients).isZero();
    }

    @Test
    void generatedOperationalIdentifiersConformToTheRfc4122WireContract() {
        var identifiers = jdbc.sql("""
                select price_version_id as identifier from price_catalog_version
                  where tenant_id = :tenant and catalog_code = 'JC-DXFS-2026' and release_version = '2026.1'
                union all
                select row.charge_item_id from charge_item row join encounter e
                  on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                  where row.tenant_id = :tenant and
                    (e.source_system = 'SYNTHETIC-50' or e.encounter_id in (:outpatient, :inpatient))
                union all
                select row.imaging_order_id from imaging_order row join encounter e
                  on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                  where row.tenant_id = :tenant and
                    (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :outpatient)
                union all
                select row.surgical_procedure_id from surgical_procedure row join encounter e
                  on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                  where row.tenant_id = :tenant and
                    (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)
                union all
                select row.transfusion_id from blood_transfusion row join encounter e
                  on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                  where row.tenant_id = :tenant and
                    (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)
                """).param("tenant", TENANT_ID).param("outpatient", OUTPATIENT_ENCOUNTER)
                .param("inpatient", INPATIENT_ENCOUNTER).query(UUID.class).list();

        assertThat(identifiers).isNotEmpty().allSatisfy(identifier -> {
            assertThat(identifier.version()).isBetween(1, 8);
            assertThat(identifier.variant()).isEqualTo(2);
        });
    }

    @Test
    void repeatedImportIsIdempotent() {
        Map<String, Integer> before = operationalCounts();
        importer.importData();
        Map<String, Integer> after = operationalCounts();

        assertThat(after).isEqualTo(before);
    }

    private Map<String, Integer> operationalCounts() {
        return jdbc.sql("""
                select
                  (select count(*) from charge_item row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id in (:outpatient, :inpatient))) charges,
                  (select count(*) from pharmacy_dispensing row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id in (:outpatient, :inpatient))) dispensing,
                  (select count(*) from lab_specimen row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id in (:outpatient, :inpatient))) specimens,
                  (select count(*) from imaging_order row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :outpatient)) imaging,
                  (select count(*) from surgical_procedure row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)) surgery,
                  (select count(*) from blood_transfusion row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)) transfusion,
                  (select count(*) from medication_administration where tenant_id = :tenant
                    and encounter_id = :inpatient) administrations,
                  (select count(*) from vital_sign_record row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)) vitals,
                  (select count(*) from nursing_care_plan row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)) care_plans,
                  (select count(*) from nursing_bedside_note row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :inpatient)) bedside_notes,
                  (select count(*) from shift_handover_patient where tenant_id = :tenant
                    and handover_id in (select overlay(overlay(
                      md5('tertiary-operational-v1:handover:' || n)
                      placing '5' from 13 for 1) placing '8' from 17 for 1)::uuid
                      from generate_series(1, 8) n)) handover_patients,
                  (select count(*) from outpatient_followup row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and
                      (e.source_system = 'SYNTHETIC-50' or e.encounter_id = :outpatient)) followups,
                  (select count(*) from referral row join encounter e
                    on e.tenant_id = row.tenant_id and e.encounter_id = row.encounter_id
                    where row.tenant_id = :tenant and e.source_system = 'SYNTHETIC-50') referrals
                """).param("tenant", TENANT_ID).param("outpatient", OUTPATIENT_ENCOUNTER)
                .param("inpatient", INPATIENT_ENCOUNTER)
                .query((rs, row) -> {
                    Map<String, Integer> result = new LinkedHashMap<>();
                    result.put("charges", rs.getInt("charges"));
                    result.put("dispensing", rs.getInt("dispensing"));
                    result.put("specimens", rs.getInt("specimens"));
                    result.put("imaging", rs.getInt("imaging"));
                    result.put("surgery", rs.getInt("surgery"));
                    result.put("transfusion", rs.getInt("transfusion"));
                    result.put("administrations", rs.getInt("administrations"));
                    result.put("vitals", rs.getInt("vitals"));
                    result.put("carePlans", rs.getInt("care_plans"));
                    result.put("bedsideNotes", rs.getInt("bedside_notes"));
                    result.put("handoverPatients", rs.getInt("handover_patients"));
                    result.put("followups", rs.getInt("followups"));
                    result.put("referrals", rs.getInt("referrals"));
                    return result;
                }).single();
    }
}
