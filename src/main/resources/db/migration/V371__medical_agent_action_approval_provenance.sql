alter table action_approval
  add column if not exists source_medical_agent_run_id uuid,
  add column if not exists source_payload_hash varchar(64),
  add column if not exists source_references jsonb not null default '[]'::jsonb;

alter table action_approval
  add constraint action_approval_source_run_fk
  foreign key (tenant_id, source_medical_agent_run_id)
  references medical_agent_run(tenant_id, run_id);

alter table action_approval
  add constraint action_approval_source_provenance_check
  check ((source_medical_agent_run_id is null and source_payload_hash is null)
    or (source_medical_agent_run_id is not null and source_payload_hash ~ '^[0-9a-f]{64}$'));

create unique index action_approval_source_run_uidx
  on action_approval(tenant_id, source_medical_agent_run_id)
  where source_medical_agent_run_id is not null;

drop trigger action_approval_immutable on action_approval;
create trigger action_approval_immutable
  before update of patient_id, encounter_id, facility_id, action_type,
    proposed_action_summary, proposed_by, proposed_at, source_medical_agent_run_id,
    source_payload_hash, source_references on action_approval
  for each row execute function prevent_action_approval_mutation();
