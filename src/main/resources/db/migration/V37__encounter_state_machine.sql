alter table encounter drop constraint encounter_status_check;

alter table encounter
  add column department_id uuid,
  add column responsible_user_id uuid,
  add constraint encounter_status_check check (
    status in ('PLANNED', 'ARRIVED', 'IN_PROGRESS', 'SUSPENDED', 'FINISHED', 'CANCELLED')),
  add constraint encounter_department_fk foreign key (tenant_id, facility_id, department_id)
    references clinical_department(tenant_id, facility_id, department_id),
  add constraint encounter_responsible_user_fk foreign key (tenant_id, responsible_user_id)
    references app_user(tenant_id, user_id);

update encounter
set ended_at = coalesce(ended_at, updated_at, started_at)
where status in ('FINISHED', 'CANCELLED') and ended_at is null;

alter table encounter
  add constraint encounter_terminal_time_check check (
    (status in ('FINISHED', 'CANCELLED')) = (ended_at is not null));

create table encounter_state_event (
  tenant_id uuid not null,
  encounter_state_event_id uuid not null,
  encounter_id uuid not null,
  version_no bigint not null check (version_no > 0),
  from_status varchar(24),
  to_status varchar(24) not null,
  occurred_at timestamptz not null,
  reason varchar(1000),
  changed_by uuid,
  created_at timestamptz not null default now(),
  primary key (tenant_id, encounter_state_event_id),
  unique (tenant_id, encounter_id, version_no),
  foreign key (tenant_id, encounter_id) references encounter(tenant_id, encounter_id),
  foreign key (tenant_id, changed_by) references app_user(tenant_id, user_id),
  check (from_status is null or from_status in (
    'PLANNED', 'ARRIVED', 'IN_PROGRESS', 'SUSPENDED', 'FINISHED', 'CANCELLED')),
  check (to_status in (
    'PLANNED', 'ARRIVED', 'IN_PROGRESS', 'SUSPENDED', 'FINISHED', 'CANCELLED'))
);

create index encounter_state_event_timeline_idx
  on encounter_state_event (tenant_id, encounter_id, version_no desc);

insert into encounter_state_event(
  tenant_id, encounter_state_event_id, encounter_id, version_no,
  from_status, to_status, occurred_at, reason, changed_by)
select tenant_id, gen_random_uuid(), encounter_id, row_version,
  null, status, coalesce(updated_at, created_at, started_at), 'MIGRATED_BASELINE', null
from encounter;

create function enforce_encounter_state_machine() returns trigger language plpgsql as $$
begin
  if new.status is distinct from old.status then
    if new.row_version <> old.row_version + 1 then
      raise exception 'encounter state transition must advance row version exactly once';
    end if;
    if not (
      (old.status = 'PLANNED' and new.status in ('ARRIVED', 'CANCELLED')) or
      (old.status = 'ARRIVED' and new.status in ('IN_PROGRESS', 'CANCELLED')) or
      (old.status = 'IN_PROGRESS' and new.status in ('SUSPENDED', 'FINISHED')) or
      (old.status = 'SUSPENDED' and new.status in ('IN_PROGRESS', 'CANCELLED'))
    ) then
      raise exception 'illegal encounter state transition: % -> %', old.status, new.status;
    end if;
    if (new.status in ('FINISHED', 'CANCELLED')) <> (new.ended_at is not null) then
      raise exception 'terminal encounter state and ended_at must change together';
    end if;
  end if;
  return new;
end $$;

create trigger encounter_state_machine_guard
before update of status on encounter
for each row execute function enforce_encounter_state_machine();

create function append_encounter_state_event() returns trigger language plpgsql as $$
declare
  transition_reason text;
  transition_actor uuid;
  transition_time timestamptz;
begin
  if tg_op = 'UPDATE' and new.status is not distinct from old.status then
    return new;
  end if;
  transition_reason := nullif(current_setting('haoemr.encounter_transition_reason', true), '');
  transition_actor := nullif(current_setting('haoemr.encounter_transition_actor', true), '')::uuid;
  transition_time := coalesce(
    nullif(current_setting('haoemr.encounter_transition_time', true), '')::timestamptz,
    case when new.status in ('FINISHED', 'CANCELLED') then new.ended_at else null end,
    new.started_at,
    now());
  insert into encounter_state_event(
    tenant_id, encounter_state_event_id, encounter_id, version_no,
    from_status, to_status, occurred_at, reason, changed_by)
  values (
    new.tenant_id, gen_random_uuid(), new.encounter_id, new.row_version,
    case when tg_op = 'INSERT' then null else old.status end,
    new.status, transition_time,
    coalesce(transition_reason, case when tg_op = 'INSERT' then 'ENCOUNTER_CREATED' else 'SYSTEM_STATE_TRANSITION' end),
    transition_actor);
  return new;
end $$;

create trigger encounter_state_event_append
after insert or update of status on encounter
for each row execute function append_encounter_state_event();

create function prevent_encounter_state_event_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'encounter state events are immutable';
end $$;

create trigger encounter_state_event_immutable
before update or delete on encounter_state_event
for each row execute function prevent_encounter_state_event_mutation();
