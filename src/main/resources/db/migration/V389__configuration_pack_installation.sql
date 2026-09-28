-- Additive installation evidence; existing template/organization/approval tables remain authoritative.
create table configuration_pack_precheck (
  tenant_id uuid not null references tenant(tenant_id),
  precheck_id uuid not null,
  organization_id uuid not null,
  facility_id uuid not null,
  requested_by uuid not null,
  idempotency_key varchar(128) not null,
  request_hash char(64) not null,
  manifest_json text not null check (octet_length(manifest_json) <= 10485760),
  manifest_hash char(64) not null,
  keep_codes jsonb not null check (jsonb_typeof(keep_codes) = 'array'),
  target_snapshot char(64) not null,
  response jsonb not null check (jsonb_typeof(response) = 'object'),
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  primary key (tenant_id, precheck_id),
  unique (tenant_id, requested_by, idempotency_key),
  foreign key (tenant_id, organization_id) references organization(tenant_id, organization_id),
  foreign key (tenant_id, facility_id) references facility(tenant_id, facility_id),
  foreign key (tenant_id, requested_by) references app_user(tenant_id, user_id),
  check (expires_at > created_at)
);

create table configuration_pack_operation (
  tenant_id uuid not null references tenant(tenant_id),
  operation_id uuid not null,
  organization_id uuid not null,
  facility_id uuid not null,
  precheck_id uuid not null,
  requested_by uuid not null,
  idempotency_key varchar(128) not null,
  request_hash char(64) not null,
  pack_code varchar(64) not null,
  pack_version varchar(32) not null,
  manifest_hash char(64) not null,
  status varchar(32) not null check (status in ('RUNNING','SUCCEEDED','FAILED_ROLLED_BACK','RECOVERY_REQUIRED')),
  retry_of uuid,
  error_code varchar(96),
  imported_count integer not null default 0 check (imported_count >= 0),
  retained_count integer not null default 0 check (retained_count >= 0),
  created_at timestamptz not null default now(),
  finished_at timestamptz,
  primary key (tenant_id, operation_id),
  unique (tenant_id, idempotency_key),
  foreign key (tenant_id, precheck_id) references configuration_pack_precheck(tenant_id, precheck_id),
  foreign key (tenant_id, retry_of) references configuration_pack_operation(tenant_id, operation_id),
  foreign key (tenant_id, organization_id) references organization(tenant_id, organization_id),
  foreign key (tenant_id, facility_id) references facility(tenant_id, facility_id),
  foreign key (tenant_id, requested_by) references app_user(tenant_id, user_id),
  check ((status in ('SUCCEEDED','FAILED_ROLLED_BACK')) = (finished_at is not null)),
  check (status <> 'FAILED_ROLLED_BACK' or (imported_count = 0 and retained_count = 0 and error_code is not null))
);
create unique index configuration_pack_single_effect on configuration_pack_operation
  (tenant_id, organization_id, facility_id, pack_code, pack_version)
  where status in ('RUNNING','SUCCEEDED','RECOVERY_REQUIRED');
create index configuration_pack_operation_scope on configuration_pack_operation
  (tenant_id, organization_id, facility_id, created_at desc);

create table configuration_pack_item_source (
  tenant_id uuid not null,
  operation_id uuid not null,
  item_code varchar(128) not null,
  template_id uuid not null,
  template_version_id uuid not null,
  original_content jsonb not null check (jsonb_typeof(original_content) = 'object'),
  original_hash char(64) not null,
  primary key (tenant_id, operation_id, item_code),
  unique (tenant_id, template_version_id),
  foreign key (tenant_id, operation_id) references configuration_pack_operation(tenant_id, operation_id),
  foreign key (tenant_id, template_id, template_version_id)
    references clinical_document_template_version(tenant_id, template_id, template_version_id)
);

create function guard_configuration_pack_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'Configuration installation evidence is immutable';
end $$;
create trigger configuration_pack_precheck_immutable before update or delete on configuration_pack_precheck
  for each row execute function guard_configuration_pack_immutable();
create trigger configuration_pack_source_immutable before update or delete on configuration_pack_item_source
  for each row execute function guard_configuration_pack_immutable();

create function guard_configuration_pack_operation() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then raise exception 'Installation history cannot be deleted'; end if;
  if tg_op = 'UPDATE' then
    if old.status in ('SUCCEEDED','FAILED_ROLLED_BACK') or
       (to_jsonb(old) - array['status','error_code','imported_count','retained_count','finished_at']) <>
       (to_jsonb(new) - array['status','error_code','imported_count','retained_count','finished_at']) then
      raise exception 'Installation identity or terminal evidence is immutable';
    end if;
    if old.status = 'RECOVERY_REQUIRED' and new.status <> 'FAILED_ROLLED_BACK' then
      raise exception 'Recovery requires confirmed rollback';
    end if;
  end if;
  if not exists (select 1 from configuration_pack_precheck p
    where p.tenant_id=new.tenant_id and p.precheck_id=new.precheck_id
      and p.organization_id=new.organization_id and p.facility_id=new.facility_id
      and p.requested_by=new.requested_by and p.manifest_hash=new.manifest_hash) then
    raise exception 'Installation scope must match precheck';
  end if;
  return new;
end $$;
create trigger configuration_pack_operation_guard before insert or update or delete on configuration_pack_operation
  for each row execute function guard_configuration_pack_operation();
