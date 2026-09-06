\set ON_ERROR_STOP on

do $guard$
begin
  if current_database() <> 'haoemr_dev' then
    raise exception 'This maintenance script may only run against haoemr_dev';
  end if;
end
$guard$;

begin;

-- Browser acceptance writes these UUID-suffixed rows. They are not reusable
-- simulation fixtures, so their dependent revisions and rows can be removed.
delete from config_item_revision revision
using config_item item
where revision.tenant_id = item.tenant_id
  and revision.config_id = item.config_id
  and item.tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and (
    item.config_key like 'acceptance-master-%'
    or item.config_key ~ '^R-[0-9a-f]{8}$'
    or item.config_key ~ '^WF-(INVALID-|LIFE-)?[0-9a-f]{8}$'
  );

delete from config_item
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and (
    config_key like 'acceptance-master-%'
    or config_key ~ '^R-[0-9a-f]{8}$'
    or config_key ~ '^WF-(INVALID-|LIFE-)?[0-9a-f]{8}$'
  );

delete from dictionary_item
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and (
    dictionary_code like 'DICT-%'
    or item_code like 'ACC\_%' escape '\'
    or item_name ~ '(验收值|Acceptance Value)'
  );

-- Authorization acceptance creates disposable DRAFT policies. Published
-- policies are never removed by this maintenance script.
delete from authorization_policy
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'DRAFT'
  and policy_code like 'SYN-%';

-- Clinical test rows may be referenced by immutable evidence. Preserve those
-- foreign-key targets but retire them so they cannot appear as active master data.
update clinical_bed bed
set status = 'INACTIVE', effective_until = coalesce(effective_until, now()),
    row_version = row_version + 1, updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'ACTIVE'
  and (
    bed_label ~ '^(ACC-|B-[0-9a-f]{8}$|SYN-|TEST-)'
    or exists (
    select 1 from clinical_ward ward
    join clinical_department department
      on department.tenant_id = ward.tenant_id
     and department.facility_id = ward.facility_id
     and department.department_id = ward.department_id
    where ward.tenant_id = bed.tenant_id and ward.ward_id = bed.ward_id
      and (ward.display_name ~ '(合成|测试|验收)' or department.display_name ~ '(合成|测试|验收)')
    )
  );

update clinical_ward
set status = 'INACTIVE', effective_until = coalesce(effective_until, now()),
    row_version = row_version + 1, updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'ACTIVE' and display_name ~ '(合成|测试|验收)';

update clinical_department
set status = 'INACTIVE', effective_until = coalesce(effective_until, now()),
    row_version = row_version + 1, updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'ACTIVE' and display_name ~ '(合成|测试|验收)';

update role_assignment assignment
set status = 'EXPIRED', valid_until = coalesce(valid_until, now()),
    row_version = row_version + 1
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'ACTIVE'
  and person_id in (
    select person_id from workforce_person
    where tenant_id = assignment.tenant_id
      and (display_name = '合成上级审签用户' or display_name like '归档测试-%')
  );

update app_user account
set status = 'DISABLED', row_version = row_version + 1, updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'ACTIVE'
  and person_id in (
    select person_id from workforce_person
    where tenant_id = account.tenant_id
      and (display_name = '合成上级审签用户' or display_name like '归档测试-%')
  );

update workforce_person
set status = 'INACTIVE', effective_until = coalesce(effective_until, now()),
    row_version = row_version + 1, updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'ACTIVE'
  and (display_name = '合成上级审签用户' or display_name like '归档测试-%');

update organization
set organization_code = 'JCUH', row_version = row_version + 1, updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'ACTIVE' and organization_code = 'SYN-ORG';

update facility
set facility_code = 'JCUH-MAIN', row_version = row_version + 1, updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and status = 'ACTIVE' and facility_code = 'SYN-FAC';

-- Normalize reusable simulation master data so system-administration screens
-- expose hospital-style business codes instead of UUID/test-run labels.
with numbered_beds as (
  select bed_id,
         row_number() over (
           partition by tenant_id, ward_id
           order by case when bed_label ~ '^[0-9]{2}$' then 0 when bed_label ~ '^S[0-9]+$' then 1 else 2 end,
                    case when bed_label ~ '[0-9]+' then substring(bed_label from '[0-9]+')::integer else 9999 end,
                    bed_id
         ) as bed_no
  from clinical_bed
  where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
    and ward_id = '018f0000-0000-7000-8000-00000000bb01'
    and status = 'ACTIVE'
)
update clinical_bed bed
set bed_label = '心血管内科-' || lpad(numbered_beds.bed_no::text, 2, '0') || '床',
    row_version = row_version + 1,
    updated_at = now()
from numbered_beds
where bed.bed_id = numbered_beds.bed_id
  and bed.bed_label is distinct from '心血管内科-' || lpad(numbered_beds.bed_no::text, 2, '0') || '床';

update config_item
set config_key = regexp_replace(config_key, '^syn-', ''), updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and config_type in ('MASTER_DATA', 'PARAMETER', 'JOB')
  and config_key like 'syn-%';

update clinical_document_template
set template_code = 'TPL-' || regexp_replace(
      regexp_replace(document_type_code, '^SYNTHETIC[.]', ''), '[^A-Za-z0-9]+', '-', 'g'
    ),
    display_name = case document_type_code
      when 'WS445.3.EMERGENCY_RECORD' then '急诊病历'
      when 'WS445.12.ADMISSION_NOTE' then '住院入院记录'
      when 'WS445.2.OUTPATIENT_RECORD' then '门诊病历'
      when 'SYNTHETIC.FOUR_LEVEL_REVIEW' then '四级审签文书'
      else display_name
    end,
    row_version = row_version + 1,
    updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and template_code like 'SYNTHETIC.%';

update clinical_document_template
set document_type_code = 'EMR.FOUR_LEVEL_REVIEW', display_name = '四级审签文书',
    row_version = row_version + 1, updated_at = now()
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and document_type_code = 'SYNTHETIC.FOUR_LEVEL_REVIEW';

update inpatient_document_rule
set document_type_code = 'EMR.FOUR_LEVEL_REVIEW', display_name = '四级审签文书'
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and document_type_code = 'SYNTHETIC.FOUR_LEVEL_REVIEW';

update inpatient_document_task
set document_type_code = 'EMR.FOUR_LEVEL_REVIEW'
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  and document_type_code = 'SYNTHETIC.FOUR_LEVEL_REVIEW';

commit;

select 'dictionary_items' as entity, count(*) as remaining_active
from dictionary_item where tenant_id = '018f0000-0000-7000-8000-00000000aa01' and status = 'ACTIVE'
union all
select 'configuration_items', count(*) from config_item
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
union all
select 'organization_units', count(*) from (
  select status from organization where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  union all select status from facility where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  union all select status from clinical_department where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  union all select status from clinical_ward where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
  union all select status from clinical_bed where tenant_id = '018f0000-0000-7000-8000-00000000aa01'
) units where status = 'ACTIVE'
union all
select 'workforce_people', count(*) from workforce_person
where tenant_id = '018f0000-0000-7000-8000-00000000aa01' and status = 'ACTIVE';
