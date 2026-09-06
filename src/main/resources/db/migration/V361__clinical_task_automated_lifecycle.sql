alter table clinical_task_event
  drop constraint clinical_task_event_event_type_check;

alter table clinical_task_event
  add constraint clinical_task_event_event_type_check check (event_type in (
    'CREATED', 'ASSIGNED', 'DELIVERED', 'VIEWED', 'CLAIMED', 'STARTED',
    'DELEGATED', 'TRANSFERRED', 'ESCALATED', 'AUTO_ESCALATED',
    'DELEGATION_EXPIRED', 'SOURCE_COMPLETED', 'SOURCE_WITHDRAWN', 'EXPIRED'
  ));

-- A lifecycle transition is an auditable system action, not an impersonated clinician action.
alter table clinical_task_event
  alter column actor_user_id drop not null;

create index clinical_task_delegation_expiry_idx
  on clinical_task_delegation (tenant_id, valid_until, task_id);
