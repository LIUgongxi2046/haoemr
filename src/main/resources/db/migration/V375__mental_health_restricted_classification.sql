alter table mental_health_treatment_record
  add column data_classification varchar(16) not null default 'RESTRICTED'
    check (data_classification = 'RESTRICTED');
drop trigger mental_health_treatment_immutable on mental_health_treatment_record;
create trigger mental_health_treatment_immutable
  before update of patient_id, encounter_id, facility_id, assessment, intervention, risk_flag, data_classification, recorded_at
  on mental_health_treatment_record
  for each row execute function prevent_mental_health_treatment_mutation();

alter table mental_health_evidence_record
  add column data_classification varchar(16) not null default 'RESTRICTED'
    check (data_classification = 'RESTRICTED');
drop trigger mental_health_evidence_immutable on mental_health_evidence_record;
create trigger mental_health_evidence_immutable
  before update of patient_id, encounter_id, facility_id, assessment, intervention, risk_flag, data_classification, recorded_at
  on mental_health_evidence_record
  for each row execute function prevent_mental_health_evidence_mutation();

alter table mental_health_qc_review
  add column data_classification varchar(16) not null default 'RESTRICTED'
    check (data_classification = 'RESTRICTED');
drop trigger mental_qc_immutable on mental_health_qc_review;
create trigger mental_qc_immutable
  before update of patient_id, encounter_id, reviewed_record_type, reviewed_record_id,
    review_conclusion, defect_description, data_classification, reviewed_at
  on mental_health_qc_review
  for each row execute function prevent_mental_qc_mutation();
