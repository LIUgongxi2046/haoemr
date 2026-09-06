package org.haoemr.executioncenter;

import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
final class ExecutionWorklistService {

    private static final Set<String> DOMAINS = Set.of(
            "CARE_OPERATIONS", "BILLING", "OUTPATIENT_PHARMACY", "INPATIENT_PHARMACY",
            "LAB", "PATHOLOGY", "IMAGING", "THERAPY", "SURGERY", "ANESTHESIA",
            "TRANSFUSION", "DEVICE_MONITORING");

    private final JdbcClient jdbc;

    ExecutionWorklistService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<ExecutionWorklistItem> list(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, String requestedDomain) {
        String domain = normalizeDomain(requestedDomain);
        return jdbc.sql("""
                with source_fact as (
                  select 'CARE_OPERATIONS' domain, task.patient_id, task.encounter_id,
                    task.execution_task_id::text business_id, item.display_name task_label,
                    task.task_state status, task.updated_at activity_at, false critical
                  from order_execution_task task
                  join clinical_order_item item on item.tenant_id = task.tenant_id
                    and item.order_item_id = task.order_item_id
                  where task.tenant_id = :tenant
                  union all
                  select 'BILLING', charge.patient_id, charge.encounter_id,
                    charge.charge_item_id::text, charge.item_name, charge.status,
                    charge.updated_at, false
                  from charge_item charge where charge.tenant_id = :tenant
                  union all
                  select case when encounter.encounter_type = 'INPATIENT'
                      then 'INPATIENT_PHARMACY' else 'OUTPATIENT_PHARMACY' end,
                    dispensing.patient_id, dispensing.encounter_id, dispensing.dispensing_id::text,
                    '药品 ' || dispensing.drug_code || ' / 批号 ' || dispensing.batch_number,
                    dispensing.status, dispensing.updated_at, false
                  from pharmacy_dispensing dispensing
                  join encounter on encounter.tenant_id = dispensing.tenant_id
                    and encounter.encounter_id = dispensing.encounter_id
                  where dispensing.tenant_id = :tenant
                  union all
                  select case when specimen.specimen_type = 'TISSUE' then 'PATHOLOGY' else 'LAB' end,
                    specimen.patient_id, specimen.encounter_id, specimen.specimen_id::text,
                    case when specimen.specimen_type = 'TISSUE' then '组织病理标本' else specimen.specimen_type || ' 标本' end,
                    specimen.collection_status, specimen.updated_at, false
                  from lab_specimen specimen where specimen.tenant_id = :tenant
                  union all
                  select 'IMAGING', imaging.patient_id, imaging.encounter_id, imaging.imaging_order_id::text,
                    imaging.modality || ' · ' || imaging.body_part, imaging.status,
                    imaging.updated_at, false
                  from imaging_order imaging where imaging.tenant_id = :tenant
                  union all
                  select 'THERAPY', task.patient_id, task.encounter_id, task.execution_task_id::text,
                    item.display_name, task.task_state, task.updated_at, false
                  from order_execution_task task
                  join clinical_order_item item on item.tenant_id = task.tenant_id
                    and item.order_item_id = task.order_item_id and item.item_type = 'TREATMENT'
                  where task.tenant_id = :tenant
                  union all
                  select 'SURGERY', surgery.patient_id, surgery.encounter_id,
                    surgery.surgical_procedure_id::text, surgery.procedure_name,
                    surgery.status, surgery.updated_at, false
                  from surgical_procedure surgery where surgery.tenant_id = :tenant
                  union all
                  select 'ANESTHESIA', surgery.patient_id, surgery.encounter_id,
                    surgery.surgical_procedure_id::text, '麻醉 · ' || surgery.procedure_name,
                    surgery.status, surgery.updated_at, false
                  from surgical_procedure surgery where surgery.tenant_id = :tenant
                  union all
                  select 'TRANSFUSION', transfusion.patient_id, transfusion.encounter_id,
                    transfusion.transfusion_id::text, transfusion.blood_product || ' · ' || transfusion.unit_number,
                    case when transfusion.reaction_type is null then 'IN_PROGRESS' else 'COMPLETED' end,
                    transfusion.updated_at,
                    coalesce(transfusion.reaction_type, 'NONE') not in ('NONE')
                  from blood_transfusion transfusion where transfusion.tenant_id = :tenant
                  union all
                  select 'DEVICE_MONITORING', vital.patient_id, vital.encounter_id,
                    vital.vital_sign_record_id::text,
                    case when vital.source = 'DEVICE' then '设备生命体征监测' else '人工生命体征复核' end,
                    'COMPLETED', vital.recorded_at, false
                  from vital_sign_record vital where vital.tenant_id = :tenant
                  union all
                  select specialty.domain, specialty.patient_id, specialty.encounter_id,
                    specialty.specialty_execution_case_id::text, specialty.title, specialty.status,
                    specialty.updated_at, false
                  from specialty_execution_case specialty where specialty.tenant_id = :tenant
                ), scoped as (
                  select fact.*, encounter.encounter_type, patient.display_name,
                    patient.sex_code, patient.birth_date
                  from source_fact fact
                  join encounter on encounter.tenant_id = :tenant
                    and encounter.encounter_id = fact.encounter_id
                    and encounter.patient_id = fact.patient_id
                    and encounter.organization_id = :organization
                    and encounter.facility_id = :facility
                  join patient on patient.tenant_id = :tenant and patient.patient_id = fact.patient_id
                  where fact.domain = :domain and patient.status = 'ACTIVE'
                )
                select scoped.patient_id, scoped.encounter_id, admission.admission_id,
                  scoped.display_name, scoped.sex_code, scoped.birth_date, scoped.encounter_type,
                  coalesce(ward.display_name || '-' || bed.bed_label || '床',
                    case scoped.encounter_type when 'OUTPATIENT' then '门诊执行队列'
                      when 'EMERGENCY' then '急诊执行队列' else '住院执行队列' end) location,
                  string_agg(distinct scoped.task_label, '；' order by scoped.task_label) task_label,
                  case when bool_or(scoped.critical) then 'CRITICAL'
                    when count(*) filter (where scoped.status not in ('COMPLETED','CANCELLED','REVERSED','REPORTED','DISPENSED')) > 0
                      then 'PENDING' else 'COMPLETED' end status,
                  count(*) filter (where scoped.status not in ('COMPLETED','CANCELLED','REVERSED','REPORTED','DISPENSED'))::int pending_count,
                  count(*) filter (where scoped.status not in ('COMPLETED','CANCELLED','REVERSED','REPORTED','DISPENSED')
                    and scoped.activity_at < now() - interval '4 hours')::int overdue_count,
                  count(*) filter (where scoped.critical)::int critical_count,
                  max(scoped.activity_at) latest_activity_at
                from scoped
                left join inpatient_admission admission on admission.tenant_id = :tenant
                  and admission.encounter_id = scoped.encounter_id
                  and admission.status in ('ADMITTED','TRANSFER_PENDING','DISCHARGE_PENDING')
                left join clinical_ward ward on ward.tenant_id = admission.tenant_id
                  and ward.ward_id = admission.ward_id
                left join clinical_bed bed on bed.tenant_id = admission.tenant_id
                  and bed.bed_id = admission.current_bed_id
                group by scoped.patient_id, scoped.encounter_id, admission.admission_id,
                  scoped.display_name, scoped.sex_code, scoped.birth_date, scoped.encounter_type,
                  ward.display_name, bed.bed_label
                order by critical_count desc, overdue_count desc, latest_activity_at desc,
                  scoped.display_name, scoped.patient_id
                """)
                .param("tenant", identity.tenantId())
                .param("organization", organizationId)
                .param("facility", facilityId)
                .param("domain", domain)
                .query((rs, row) -> new ExecutionWorklistItem(
                        domain,
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("admission_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("sex_code"),
                        date(rs.getDate("birth_date")),
                        rs.getString("encounter_type"),
                        rs.getString("location"),
                        rs.getString("task_label"),
                        rs.getString("status"),
                        rs.getInt("pending_count"),
                        rs.getInt("overdue_count"),
                        rs.getInt("critical_count"),
                        rs.getObject("latest_activity_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    static String normalizeDomain(String requestedDomain) {
        String domain = requestedDomain == null ? "" : requestedDomain.trim().toUpperCase(Locale.ROOT);
        if (!DOMAINS.contains(domain)) {
            throw new ExecutionWorklistException(
                    "EXECUTION_DOMAIN_INVALID", 400, "未知的诊疗执行专业队列");
        }
        return domain;
    }

    private static java.time.LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }
}
