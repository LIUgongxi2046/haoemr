-- Development acceptance controls are deliberately isolated from clinical data.
-- The endpoint that mutates this table is only registered for dev-synthetic.
create table development_acceptance_setting (
  tenant_id uuid primary key references tenant(tenant_id),
  inpatient_role_simulation_enabled boolean not null default false,
  updated_by uuid,
  updated_at timestamptz not null default now(),
  row_version bigint not null default 1,
  foreign key (tenant_id, updated_by) references app_user(tenant_id, user_id)
);
