update nursing_care_plan plan
set care_plan_id = overlay(overlay(
    md5('tertiary-operational-v1:care-plan:' || plan.encounter_id || ':' || seed.ordinal)
    placing '4' from 13 for 1) placing '8' from 17 for 1)::uuid
from generate_series(1, 5) as seed(ordinal), encounter
where encounter.tenant_id = plan.tenant_id
  and encounter.encounter_id = plan.encounter_id
  and plan.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
  and (encounter.source_system = 'SYNTHETIC-50'
    or encounter.encounter_id = '018f0000-0000-7000-8000-000000000102'::uuid)
  and plan.care_plan_id = md5('tertiary-operational-v1:care-plan:'
    || plan.encounter_id || ':' || seed.ordinal)::uuid
  and not exists (
    select 1 from audit_event evidence
    where evidence.tenant_id = plan.tenant_id and evidence.resource_id = plan.care_plan_id
  )
  and not exists (
    select 1 from outbox_event evidence
    where evidence.tenant_id = plan.tenant_id and evidence.aggregate_id = plan.care_plan_id
  );
