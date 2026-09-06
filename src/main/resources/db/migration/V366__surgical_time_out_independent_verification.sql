create table surgical_time_out_verification (
  tenant_id uuid not null,
  surgical_time_out_verification_id uuid not null,
  surgical_procedure_id uuid not null,
  verified_by uuid not null,
  verification_note varchar(1000) not null check (length(trim(verification_note)) >= 2),
  verified_at timestamptz not null default now(),
  primary key (tenant_id, surgical_time_out_verification_id),
  unique (tenant_id, surgical_procedure_id),
  foreign key (tenant_id, surgical_procedure_id) references surgical_procedure(tenant_id, surgical_procedure_id),
  foreign key (tenant_id, verified_by) references app_user(tenant_id, user_id)
);

create index surgical_time_out_verification_actor_idx
  on surgical_time_out_verification (tenant_id, verified_by, verified_at desc);

create function prevent_surgical_time_out_verification_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'surgical time-out verification is immutable once recorded';
end;
$$;

create trigger surgical_time_out_verification_immutable
  before update or delete on surgical_time_out_verification
  for each row execute function prevent_surgical_time_out_verification_mutation();
