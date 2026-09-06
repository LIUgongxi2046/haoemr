create table historical_migration_source_import (
  tenant_id uuid not null,
  source_import_id uuid not null,
  source_system_id uuid not null,
  file_name varchar(256) not null check (length(trim(file_name)) >= 1),
  content_hash char(64) not null check (content_hash ~ '^[0-9a-f]{64}$'),
  row_count integer not null check (row_count > 0 and row_count <= 1000),
  imported_by uuid not null,
  imported_at timestamptz not null default now(),
  primary key (tenant_id, source_import_id),
  unique (tenant_id, source_system_id, content_hash),
  foreign key (tenant_id, source_system_id) references source_system_inventory(tenant_id, source_system_id),
  foreign key (tenant_id, imported_by) references app_user(tenant_id, user_id)
);

create index historical_migration_source_import_source_idx
  on historical_migration_source_import (tenant_id, source_system_id, imported_at desc, source_import_id desc);

alter table source_patient_match_candidate
  add column if not exists source_import_id uuid;

alter table source_patient_match_candidate
  add constraint source_patient_match_candidate_import_fk
  foreign key (tenant_id, source_import_id)
  references historical_migration_source_import(tenant_id, source_import_id);

create index source_patient_match_candidate_import_idx
  on source_patient_match_candidate (tenant_id, source_import_id, candidate_id);

alter table historical_migration_batch
  add column if not exists source_import_id uuid;

alter table historical_migration_batch
  add constraint historical_migration_batch_import_fk
  foreign key (tenant_id, source_import_id)
  references historical_migration_source_import(tenant_id, source_import_id);

create index historical_migration_batch_import_idx
  on historical_migration_batch (tenant_id, source_import_id, started_at desc);

create function prevent_historical_migration_source_import_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'historical migration source import evidence is immutable';
end;
$$;

create trigger historical_migration_source_import_immutable
  before update or delete on historical_migration_source_import
  for each row execute function prevent_historical_migration_source_import_mutation();
