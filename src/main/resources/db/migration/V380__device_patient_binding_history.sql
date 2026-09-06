-- device_status keeps only the active binding for fast operational display. The
-- history table is the source of truth for timeline attribution, so unbinding
-- never removes the patient association of telemetry already received.
create table device_patient_binding (
  tenant_id uuid not null,
  device_patient_binding_id uuid not null,
  device_code varchar(128) not null,
  patient_id uuid not null,
  encounter_id uuid not null,
  bound_by uuid not null,
  bound_at timestamptz not null default now(),
  verification_method varchar(256) not null check (length(trim(verification_method)) >= 2),
  unbound_by uuid,
  unbound_at timestamptz,
  unbinding_reason varchar(1000),
  row_version bigint not null default 1,
  primary key (tenant_id, device_patient_binding_id),
  foreign key (tenant_id, patient_id) references patient(tenant_id, patient_id),
  foreign key (tenant_id, encounter_id) references encounter(tenant_id, encounter_id),
  foreign key (tenant_id, bound_by) references app_user(tenant_id, user_id),
  foreign key (tenant_id, unbound_by) references app_user(tenant_id, user_id),
  check (
    (unbound_by is null and unbound_at is null and unbinding_reason is null)
    or
    (unbound_by is not null and unbound_at is not null and length(trim(unbinding_reason)) >= 2)
  ),
  check (unbound_at is null or unbound_at >= bound_at)
);

create unique index device_patient_binding_one_active_idx
  on device_patient_binding (tenant_id, device_code)
  where unbound_at is null;

create index device_patient_binding_timeline_idx
  on device_patient_binding (tenant_id, patient_id, encounter_id, bound_at, unbound_at);
