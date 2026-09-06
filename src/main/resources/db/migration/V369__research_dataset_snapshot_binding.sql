-- A cohort snapshot retains the exact member attributes used by a research export.
-- Patient charts can later change; an approved export must not silently drift.
alter table research_cohort_snapshot
  add column member_snapshot_materialized boolean not null default false;

create table research_cohort_snapshot_member (
  tenant_id uuid not null,
  research_cohort_snapshot_id uuid not null,
  cohort_member_id uuid not null,
  patient_id uuid not null,
  sex_code varchar(32) not null,
  birth_year integer not null check (birth_year between 1850 and 2200),
  primary key (tenant_id, research_cohort_snapshot_id, patient_id),
  foreign key (tenant_id, research_cohort_snapshot_id)
    references research_cohort_snapshot(tenant_id, research_cohort_snapshot_id),
  foreign key (tenant_id, cohort_member_id)
    references research_cohort_member(tenant_id, cohort_member_id),
  foreign key (tenant_id, patient_id) references patient(tenant_id, patient_id)
);

create index research_cohort_snapshot_member_snapshot_idx
  on research_cohort_snapshot_member (tenant_id, research_cohort_snapshot_id, patient_id);

alter table research_dataset_request
  add column research_cohort_snapshot_id uuid;

alter table research_dataset_request
  add constraint research_dataset_request_snapshot_fk
  foreign key (tenant_id, research_cohort_snapshot_id)
  references research_cohort_snapshot(tenant_id, research_cohort_snapshot_id);

create index research_dataset_request_snapshot_idx
  on research_dataset_request (tenant_id, research_cohort_snapshot_id, created_at desc);
