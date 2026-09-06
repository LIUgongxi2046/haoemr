-- ACTIVE must mean executable. Retain legacy rule identity and evidence, but
-- stop offering rules without a registered, read-only fact scanner.
update data_quality_rule
set status = 'INACTIVE', updated_at = now()
where status = 'ACTIVE'
  and rule_code not in (
    'DQ-ORDER-PATIENT', 'DQ-ALLERGY-CODE', 'DQ-CRITICAL-ACK',
    'DQ-SURGERY-SITE', 'DQ-TRANSFUSION-TRACE', 'DQ-DISCHARGE-DIAG'
  );
