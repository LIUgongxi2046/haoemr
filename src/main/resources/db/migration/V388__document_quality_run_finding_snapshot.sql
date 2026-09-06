-- Additive evidence only. Legacy runs remain explicitly unknown: never reconstruct
-- their historical findings from today's mutable quality_finding rows.
alter table document_quality_run
  add column trigger_stage varchar(24)
    check (trigger_stage in ('MANUAL', 'POST_SAVE', 'PRE_SIGN', 'TERMINAL')),
  add column finding_snapshots jsonb;

create function validate_document_quality_run_snapshot() returns trigger language plpgsql as $$
declare
  item jsonb;
  blocking integer := 0;
  warnings integer := 0;
begin
  -- Rolling upgrades: old application nodes may still write the old contract.
  -- Such runs are visibly legacy, never passed off as snapshotted evidence.
  if new.trigger_stage is null and new.finding_snapshots is null then return new; end if;
  if new.trigger_stage is null or new.finding_snapshots is null
    or jsonb_typeof(new.finding_snapshots) <> 'array' then
    raise exception 'new quality runs require stage and immutable finding snapshots';
  end if;
  if jsonb_array_length(new.finding_snapshots) <> new.finding_count then
    raise exception 'quality run snapshot count mismatch';
  end if;
  for item in select value from jsonb_array_elements(new.finding_snapshots) loop
    if not exists (
      select 1 from quality_finding f
      where f.tenant_id = new.tenant_id and f.document_id = new.document_id
        and f.document_version_id = new.document_version_id
        and f.finding_id = (item->>'finding_id')::uuid
        and f.document_version_id = (item->>'document_version_id')::uuid
        and f.rule_code = item->>'rule_code' and f.rule_version = new.rule_version
        and f.severity = item->>'severity' and f.message = item->>'message'
        and f.field_path is not distinct from item->>'field_path'
        and f.state = item->>'state'
    ) then
      raise exception 'quality run snapshot scope or evidence mismatch';
    end if;
    blocking := blocking + case when item->>'severity' = 'BLOCKING' then 1 else 0 end;
    warnings := warnings + case when item->>'severity' = 'WARNING' then 1 else 0 end;
  end loop;
  if blocking <> new.blocking_count or warnings <> new.warning_count
    or (select count(distinct value->>'finding_id') from jsonb_array_elements(new.finding_snapshots)) <> new.finding_count then
    raise exception 'quality run snapshot severity or uniqueness mismatch';
  end if;
  return new;
end $$;

create trigger document_quality_run_snapshot_guard before insert on document_quality_run
  for each row execute function validate_document_quality_run_snapshot();
