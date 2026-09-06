create table medical_agent_document_adoption (
  tenant_id uuid not null,
  adoption_id uuid not null,
  source_run_id uuid not null,
  document_id uuid not null,
  document_version_id uuid not null,
  source_payload_hash varchar(64) not null,
  adopted_fields jsonb not null check (jsonb_typeof(adopted_fields) = 'array'),
  adopted_by uuid not null,
  adopted_at timestamptz not null default now(),
  primary key (tenant_id, adoption_id),
  unique (tenant_id, document_version_id),
  foreign key (tenant_id, source_run_id) references medical_agent_run(tenant_id, run_id),
  foreign key (tenant_id, document_id) references clinical_document(tenant_id, document_id),
  foreign key (tenant_id, document_version_id) references clinical_document_version(tenant_id, document_version_id),
  foreign key (tenant_id, adopted_by) references app_user(tenant_id, user_id)
);

create function prevent_medical_agent_adoption_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'Medical agent adoption provenance is immutable';
end $$;
create trigger medical_agent_adoption_immutable before update or delete on medical_agent_document_adoption
  for each row execute function prevent_medical_agent_adoption_mutation();
