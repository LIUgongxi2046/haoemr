package org.haoemr.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class TertiaryInpatientWorkspaceFixtureTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void importsCompleteDeidentifiedTertiaryInpatientWorkflowEvidence() {
        Map<String, Integer> coverage = jdbc.sql("""
                select
                  (select count(*) from clinical_order
                    where tenant_id = :tenant and encounter_id = cast(:encounter as uuid)
                      and order_id between '018f0000-0000-7000-8000-00000000bc01'::uuid
                        and '018f0000-0000-7000-8000-00000000bc03'::uuid) as orders,
                  (select count(*) from clinical_result
                    where tenant_id = :tenant and encounter_id = cast(:encounter as uuid)
                      and result_id between '018f0000-0000-7000-8000-00000000bc31'::uuid
                        and '018f0000-0000-7000-8000-00000000bc32'::uuid) as results,
                  (select count(*) from inpatient_consultation
                    where tenant_id = :tenant and admission_id = :admission
                      and consultation_id between '018f0000-0000-7000-8000-00000000bd01'::uuid
                        and '018f0000-0000-7000-8000-00000000bd02'::uuid) as consultations,
                  (select count(*) from inpatient_pathway_instance
                    where tenant_id = :tenant and admission_id = :admission and status = 'ACTIVE') as pathways,
                  (select count(*) from inpatient_pathway_task task
                    join inpatient_pathway_instance instance
                      on instance.tenant_id = task.tenant_id
                     and instance.pathway_instance_id = task.pathway_instance_id
                    where task.tenant_id = :tenant and instance.admission_id = :admission) as pathway_tasks
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .param("encounter", "018f0000-0000-7000-8000-000000000102")
                .param("admission", SyntheticDataImporter.SYNTHETIC_ADMISSION_ID)
                .query((rs, row) -> Map.of(
                        "orders", rs.getInt("orders"),
                        "results", rs.getInt("results"),
                        "consultations", rs.getInt("consultations"),
                        "pathways", rs.getInt("pathways"),
                        "pathway_tasks", rs.getInt("pathway_tasks")))
                .single();

        assertThat(coverage).containsEntry("orders", 3)
                .containsEntry("results", 2)
                .containsEntry("consultations", 2)
                .containsEntry("pathways", 1)
                .containsEntry("pathway_tasks", 5);
    }

    @Test
    void preservesWorkflowStatesAndClinicalSafetyEvidence() {
        Map<String, Integer> evidence = jdbc.sql("""
                select
                  (select count(*) from clinical_order
                    where tenant_id = :tenant and encounter_id = cast(:encounter as uuid)
                      and status in ('ACTIVE', 'COMPLETED') and signed_by is not null) as signed_orders,
                  (select count(*) from critical_value_case
                    where tenant_id = :tenant and encounter_id = cast(:encounter as uuid)
                      and state = 'ACKNOWLEDGED' and row_version = 2) as acknowledged_critical_values,
                  (select count(*) from critical_value_event event
                    join critical_value_case critical on critical.tenant_id = event.tenant_id
                      and critical.critical_value_id = event.critical_value_id
                    where event.tenant_id = :tenant
                      and critical.encounter_id = cast(:encounter as uuid)) as critical_events,
                  (select count(*) from inpatient_consultation
                    where tenant_id = :tenant and admission_id = :admission
                      and status = 'COMPLETED' and opinion_signed_by is not null
                      and completed_by = requested_by) as completed_consultations,
                  (select count(*) from clinical_result
                    where tenant_id = :tenant and encounter_id = cast(:encounter as uuid)
                      and source_system = 'JC-AFFILIATED-HOSPITAL-SIMULATION') as deidentified_results
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .param("encounter", "018f0000-0000-7000-8000-000000000102")
                .param("admission", SyntheticDataImporter.SYNTHETIC_ADMISSION_ID)
                .query((rs, row) -> Map.of(
                        "orders", rs.getInt("signed_orders"),
                        "critical_values", rs.getInt("acknowledged_critical_values"),
                        "critical_events", rs.getInt("critical_events"),
                        "consultations", rs.getInt("completed_consultations"),
                        "results", rs.getInt("deidentified_results")))
                .single();

        assertThat(evidence.get("orders")).isGreaterThanOrEqualTo(3);
        assertThat(evidence).containsEntry("critical_values", 1)
                .containsEntry("critical_events", 2)
                .containsEntry("consultations", 1)
                .containsEntry("results", 2);
    }

    @Test
    void activeInpatientWorklistContainsNoPlaceholderNames() {
        Integer invalidNames = jdbc.sql("""
                select count(*) from inpatient_admission admission
                join patient on patient.tenant_id = admission.tenant_id
                  and patient.patient_id = admission.patient_id
                where admission.tenant_id = :tenant
                  and admission.status in ('ADMITTED', 'TRANSFER_PENDING', 'DISCHARGE_PENDING')
                  and patient.display_name ~* '(合成|测试|患者|synthetic|patient|demo|sample)'
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .query(Integer.class).single();

        assertThat(invalidNames).isZero();
    }

    @Test
    void operationalMedicationWorkflowUsesRfc4122Identifiers() {
        Map<String, Integer> identifiers = jdbc.sql("""
                with target_orders as (
                  select order_id
                  from clinical_order
                  where tenant_id = :tenant and encounter_id = cast(:encounter as uuid)
                    and rule_watermark = 'TERTIARY-OPERATIONAL-V1'
                    and clinical_indication = '心力衰竭与冠心病住院期间容量、血栓及胃黏膜保护综合治疗'
                ), target_ids as (
                  select order_id as id from target_orders
                  union all
                  select order_item_id from clinical_order_item
                    where order_id in (select order_id from target_orders)
                  union all
                  select execution_task_id from order_execution_task
                    where order_id in (select order_id from target_orders)
                  union all
                  select administration_id from medication_administration
                    where order_id in (select order_id from target_orders)
                )
                select
                  (select count(*) from target_orders) as orders,
                  count(*) as workflow_ids,
                  count(*) filter (where substring(replace(id::text, '-', ''), 13, 1) = '3'
                    and substring(replace(id::text, '-', ''), 17, 1) = '8') as valid_ids
                from target_ids
                """).param("tenant", SyntheticDataImporter.TENANT_ID)
                .param("encounter", "018f0000-0000-7000-8000-000000000102")
                .query((rs, row) -> Map.of(
                        "orders", rs.getInt("orders"),
                        "workflow_ids", rs.getInt("workflow_ids"),
                        "valid_ids", rs.getInt("valid_ids")))
                .single();

        assertThat(identifiers).containsEntry("orders", 5)
                .containsEntry("workflow_ids", 20)
                .containsEntry("valid_ids", 20);
    }
}
