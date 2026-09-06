create table device_alert (
  tenant_id uuid not null,
  device_alert_id uuid not null,
  device_code varchar(128) not null,
  observation_id uuid,
  metric varchar(32) not null,
  alarm_level varchar(16) not null check (alarm_level in ('MEDIUM', 'HIGH')),
  state varchar(16) not null check (state in ('OPEN', 'ACKNOWLEDGED', 'ESCALATED', 'CLOSED')),
  summary varchar(1000) not null check (length(trim(summary)) >= 2),
  opened_at timestamptz not null default now(),
  acknowledged_by uuid,
  acknowledged_at timestamptz,
  escalated_by uuid,
  escalated_at timestamptz,
  closed_by uuid,
  closed_at timestamptz,
  resolution_note varchar(1000),
  row_version bigint not null default 1 check (row_version > 0),
  primary key (tenant_id, device_alert_id),
  foreign key (tenant_id, observation_id) references device_observation(tenant_id, observation_id),
  foreign key (tenant_id, acknowledged_by) references app_user(tenant_id, user_id),
  foreign key (tenant_id, escalated_by) references app_user(tenant_id, user_id),
  foreign key (tenant_id, closed_by) references app_user(tenant_id, user_id),
  check ((acknowledged_by is null) = (acknowledged_at is null)),
  check ((escalated_by is null) = (escalated_at is null)),
  check ((closed_by is null) = (closed_at is null)),
  check ((state = 'CLOSED') = (closed_at is not null))
);

create unique index device_alert_active_metric_key
  on device_alert (tenant_id, device_code, metric)
  where state <> 'CLOSED';
create index device_alert_worklist_idx
  on device_alert (tenant_id, state, alarm_level desc, opened_at, device_alert_id);
