select set_config('haoemr.budget_update_authorized', 'true', true);

-- This one-time code alignment predates the immutable-code trigger.  The trigger is restored
-- in the same transactional migration, so ordinary updates remain fail-closed afterwards.
drop trigger if exists agent_run_budget_immutable on agent_run_budget;

update agent_run_budget
set budget_code = 'BUDGET_RESULT_FOLLOWUP_COORDINATOR', updated_at = now(), row_version = row_version + 1
where budget_code = 'BUDGET_RESULT_FOLLOWUP';

create trigger agent_run_budget_immutable
  before update of budget_code, budget_name, max_tokens, max_duration_seconds on agent_run_budget
  for each row execute function prevent_agent_run_budget_mutation();
