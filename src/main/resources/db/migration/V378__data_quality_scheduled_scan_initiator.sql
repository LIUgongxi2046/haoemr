-- A scheduled scan is a system action, not a human action.  Keep that distinction
-- in the durable evidence instead of attributing it to an arbitrary administrator.
alter table data_quality_scan_run
  alter column started_by drop not null;

alter table data_quality_scan_run
  add column initiator_kind varchar(16) not null default 'MANUAL'
    check (initiator_kind in ('MANUAL', 'SCHEDULED'));

alter table data_quality_scan_run
  add constraint data_quality_scan_run_initiator_check
    check (
      (initiator_kind = 'MANUAL' and started_by is not null)
      or (initiator_kind = 'SCHEDULED' and started_by is null)
    );

-- DETECTED events inherit the scan initiator.  Scheduled findings therefore have
-- no human actor, while all user-led transitions remain actor-attributed.
alter table data_quality_finding_event
  alter column actor_user_id drop not null;
