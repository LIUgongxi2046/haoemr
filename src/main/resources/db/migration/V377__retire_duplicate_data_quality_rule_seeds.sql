-- Keep the historical rows and any linked evaluations or scan evidence, but
-- release the canonical rule codes for the data-center dataset records.
-- Rule identity is normally immutable, so this controlled migration briefly
-- removes the identity trigger and restores it before the transaction commits.
drop trigger if exists data_quality_rule_immutable on data_quality_rule;

update data_quality_rule
set rule_code = case data_quality_rule_id::text
      when '018f0000-0000-7000-8000-00000000f306' then 'RETIRED_DQ_ORDER_PATIENT'
      when '018f0000-0000-7000-8000-00000000f307' then 'RETIRED_DQ_ALLERGY_CODE'
      when '018f0000-0000-7000-8000-00000000f308' then 'RETIRED_DQ_CRITICAL_ACK'
      when '018f0000-0000-7000-8000-00000000f309' then 'RETIRED_DQ_SURGERY_SITE'
      when '018f0000-0000-7000-8000-00000000f310' then 'RETIRED_DQ_TRANSFUSION_TRACE'
      when '018f0000-0000-7000-8000-00000000f311' then 'RETIRED_DQ_DISCHARGE_DIAG'
    end,
    status = 'INACTIVE',
    updated_at = now()
where data_quality_rule_id::text in (
  '018f0000-0000-7000-8000-00000000f306',
  '018f0000-0000-7000-8000-00000000f307',
  '018f0000-0000-7000-8000-00000000f308',
  '018f0000-0000-7000-8000-00000000f309',
  '018f0000-0000-7000-8000-00000000f310',
  '018f0000-0000-7000-8000-00000000f311'
)
  and rule_code in (
    'DQ-ORDER-PATIENT', 'DQ-ALLERGY-CODE', 'DQ-CRITICAL-ACK',
    'DQ-SURGERY-SITE', 'DQ-TRANSFUSION-TRACE', 'DQ-DISCHARGE-DIAG'
  );

create trigger data_quality_rule_immutable
  before update of rule_code, dimension, target_entity, threshold, severity on data_quality_rule
  for each row execute function prevent_data_quality_rule_mutation();
