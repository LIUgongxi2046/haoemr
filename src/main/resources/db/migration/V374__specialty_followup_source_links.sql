alter table ophthalmology_postop_followup
  add column preop_verification_id uuid;
alter table ophthalmology_postop_followup
  add constraint ophthalmology_postop_preop_fk
    foreign key (tenant_id, preop_verification_id)
    references ophthalmology_preop_verification(tenant_id, verification_id);
create index ophthalmology_postop_preop_idx
  on ophthalmology_postop_followup (tenant_id, preop_verification_id)
  where preop_verification_id is not null;
drop trigger ophthalmology_postop_immutable on ophthalmology_postop_followup;
create trigger ophthalmology_postop_immutable
  before update of patient_id, encounter_id, surgical_eye, preop_verification_id, followup_date, iop_mmhg, complication_note
  on ophthalmology_postop_followup
  for each row execute function prevent_ophthalmology_postop_mutation();

alter table mental_health_crisis_followup
  add column crisis_handover_id uuid;
alter table mental_health_crisis_followup
  add constraint mental_health_crisis_followup_handover_fk
    foreign key (tenant_id, crisis_handover_id)
    references mental_health_crisis_handover(tenant_id, crisis_handover_id);
create index mental_health_crisis_followup_handover_idx
  on mental_health_crisis_followup (tenant_id, crisis_handover_id)
  where crisis_handover_id is not null;
drop trigger mental_health_crisis_followup_immutable on mental_health_crisis_followup;
create trigger mental_health_crisis_followup_immutable
  before update of patient_id, encounter_id, crisis_handover_id, followup_date, risk_level, protective_measures, data_classification
  on mental_health_crisis_followup
  for each row execute function prevent_mental_health_crisis_followup_mutation();

alter table ent_followup_record
  add column airway_handover_id uuid;
alter table ent_followup_record
  add constraint ent_followup_airway_handover_fk
    foreign key (tenant_id, airway_handover_id)
    references ent_airway_risk_handover(tenant_id, handover_id);
create index ent_followup_airway_handover_idx
  on ent_followup_record (tenant_id, airway_handover_id)
  where airway_handover_id is not null;
drop trigger ent_followup_immutable on ent_followup_record;
create trigger ent_followup_immutable
  before update of patient_id, encounter_id, airway_handover_id, followup_reason, scheduled_date, attended, no_show_reason, outcome_note
  on ent_followup_record
  for each row execute function prevent_ent_followup_mutation();
