alter table historical_migration_batch
  add column observation_started_at timestamptz,
  add column observation_completed_at timestamptz,
  add column observation_summary varchar(1000),
  add column archived_at timestamptz;

alter table historical_migration_batch
  drop constraint historical_migration_completed_check,
  add constraint historical_migration_completed_check
    check ((batch_status in ('RECONCILED', 'SWITCHED', 'OBSERVING', 'ARCHIVED', 'ROLLED_BACK')) = (completed_at is not null)),
  add constraint historical_migration_observation_started_check
    check ((batch_status in ('OBSERVING', 'ARCHIVED')) = (observation_started_at is not null)),
  add constraint historical_migration_observation_completed_check
    check ((batch_status = 'ARCHIVED') = (observation_completed_at is not null)),
  add constraint historical_migration_observation_summary_check
    check ((batch_status = 'ARCHIVED') = (observation_summary is not null)),
  add constraint historical_migration_archive_check
    check ((batch_status = 'ARCHIVED') = (archived_at is not null)),
  add constraint historical_migration_observation_time_check
    check (observation_completed_at is null or observation_completed_at >= observation_started_at);

alter table historical_migration_batch
  drop constraint if exists historical_migration_batch_batch_status_check,
  drop constraint if exists historical_migration_batch_status_check;

alter table historical_migration_batch
  add constraint historical_migration_batch_status_check
    check (batch_status in ('TRIAL', 'RECONCILED', 'SWITCHED', 'OBSERVING', 'ARCHIVED', 'ROLLED_BACK'));

create function prevent_historical_migration_observation_mutation() returns trigger language plpgsql as $$
begin
  if (old.observation_started_at is not null and new.observation_started_at is distinct from old.observation_started_at)
    or (old.observation_completed_at is not null and new.observation_completed_at is distinct from old.observation_completed_at)
    or (old.observation_summary is not null and new.observation_summary is distinct from old.observation_summary)
    or (old.archived_at is not null and new.archived_at is distinct from old.archived_at) then
    raise exception 'historical migration observation evidence is immutable once recorded';
  end if;
  return new;
end $$;

create trigger historical_migration_observation_immutable
  before update of observation_started_at, observation_completed_at, observation_summary, archived_at
  on historical_migration_batch
  for each row
  execute function prevent_historical_migration_observation_mutation();
