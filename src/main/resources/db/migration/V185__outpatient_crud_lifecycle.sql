alter table outpatient_followup drop constraint outpatient_followup_status_check;
alter table outpatient_followup
  add constraint outpatient_followup_status_check check (status in ('PENDING', 'COMPLETED', 'CANCELLED'));

alter table referral drop constraint referral_status_check;
alter table referral
  add constraint referral_status_check check (status in ('DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'CANCELLED'));

alter table referral drop constraint referral_check2;
alter table referral
  add constraint referral_sent_at_check
  check ((status in ('SENT', 'ACCEPTED', 'REJECTED')) = (sent_at is not null));

alter table referral drop constraint referral_check3;
alter table referral
  add constraint referral_resolved_at_check
  check ((status in ('ACCEPTED', 'REJECTED', 'CANCELLED')) = (resolved_at is not null));

drop trigger referral_immutable on referral;
drop function prevent_referral_mutation();

create function prevent_referral_identity_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'referral patient and encounter identity is immutable once created';
end $$;

create trigger referral_identity_immutable
  before update of patient_id, encounter_id, facility_id on referral
  for each row execute function prevent_referral_identity_mutation();

create function require_controlled_referral_edit() returns trigger language plpgsql as $$
begin
  if coalesce(current_setting('haoemr.allow_referral_edit', true), 'false') <> 'true' then
    raise exception 'referral clinical content may only change through the versioned service command';
  end if;
  return new;
end $$;

create trigger referral_clinical_content_controlled
  before update of referral_type, target_department, target_organization, reason, clinical_summary on referral
  for each row execute function require_controlled_referral_edit();
