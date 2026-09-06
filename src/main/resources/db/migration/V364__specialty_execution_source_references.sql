-- Make specialty execution cases reference their authoritative upstream facts directly.
alter table specialty_execution_case
  add column lab_specimen_id uuid,
  add column order_execution_task_id uuid,
  add column surgical_procedure_id uuid;

alter table specialty_execution_case
  add constraint specialty_execution_case_lab_specimen_fk
    foreign key (tenant_id, lab_specimen_id) references lab_specimen(tenant_id, specimen_id),
  add constraint specialty_execution_case_order_task_fk
    foreign key (tenant_id, order_execution_task_id) references order_execution_task(tenant_id, execution_task_id),
  add constraint specialty_execution_case_surgical_procedure_fk
    foreign key (tenant_id, surgical_procedure_id) references surgical_procedure(tenant_id, surgical_procedure_id);

create index specialty_execution_case_source_reference_idx
  on specialty_execution_case (tenant_id, lab_specimen_id, order_execution_task_id, surgical_procedure_id)
  where lab_specimen_id is not null or order_execution_task_id is not null or surgical_procedure_id is not null;
