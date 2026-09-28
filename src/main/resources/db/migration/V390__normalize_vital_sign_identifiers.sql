alter table vital_sign_record disable trigger vital_sign_record_immutable;

with invalid_rows as (
  select tenant_id, vital_sign_record_id, encounter_id,
    row_number() over (
      partition by tenant_id, encounter_id
      order by recorded_at desc, vital_sign_record_id
    ) as ordinal
  from vital_sign_record
  where substring(vital_sign_record_id::text from 15 for 1) not in ('1','2','3','4','5','6','7','8')
     or substring(vital_sign_record_id::text from 20 for 1) not in ('8','9','a','b')
), normalized_rows as (
  select tenant_id, vital_sign_record_id,
    overlay(overlay(
      md5('tertiary-operational-v1:vital:' || encounter_id || ':' || ordinal)
      placing '4' from 13 for 1) placing '8' from 17 for 1)::uuid as normalized_id
  from invalid_rows
)
update vital_sign_record record
set vital_sign_record_id = normalized.normalized_id,
    updated_at = now()
from normalized_rows normalized
where record.tenant_id = normalized.tenant_id
  and record.vital_sign_record_id = normalized.vital_sign_record_id;

alter table vital_sign_record enable trigger vital_sign_record_immutable;
