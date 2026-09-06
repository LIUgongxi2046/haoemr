alter table device_status
  add column bound_encounter_id uuid,
  add column bound_by uuid,
  add column bound_at timestamptz,
  add column binding_verification_method varchar(256);

alter table device_status
  add constraint device_status_bound_encounter_fk
    foreign key (tenant_id, bound_encounter_id) references encounter(tenant_id, encounter_id),
  add constraint device_status_binding_integrity_check check (
    (bound_patient_id is null and bound_encounter_id is null and bound_by is null and bound_at is null
      and binding_verification_method is null)
    or
    (bound_patient_id is not null and bound_encounter_id is not null and bound_by is not null
      and bound_at is not null and length(trim(binding_verification_method)) >= 2)
  );

create index device_status_patient_encounter_idx
  on device_status (tenant_id, bound_patient_id, bound_encounter_id)
  where bound_patient_id is not null;
