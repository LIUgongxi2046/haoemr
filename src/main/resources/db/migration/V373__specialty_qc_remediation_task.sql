alter table clinical_task drop constraint clinical_task_source_type_check;

alter table clinical_task add constraint clinical_task_source_type_check
  check (source_type in ('ORDER_EXECUTION', 'CRITICAL_VALUE', 'DOCUMENT', 'CONSULTATION',
    'PATHWAY', 'DISCHARGE_REMEDIATION', 'AI_APPROVAL', 'REMINDER', 'SPECIALTY_QC_REVIEW'));
