-- AI 中心收口：版本化编辑、运行额度并发控制，以及无效验收数据清理。

alter table agent_registry
  drop constraint if exists agent_registry_tenant_id_agent_code_key;
create unique index if not exists agent_registry_code_version_uidx
  on agent_registry(tenant_id, agent_code, agent_version);
create unique index if not exists agent_registry_active_code_uidx
  on agent_registry(tenant_id, agent_code) where status = 'ACTIVE';

alter table skill_registry
  drop constraint if exists skill_registry_tenant_id_skill_code_key;
create unique index if not exists skill_registry_code_version_uidx
  on skill_registry(tenant_id, skill_code, skill_version);
create unique index if not exists skill_registry_active_code_uidx
  on skill_registry(tenant_id, skill_code) where status = 'ACTIVE';

alter table tool_registry
  drop constraint if exists tool_registry_tenant_id_tool_code_key;
create unique index if not exists tool_registry_code_version_uidx
  on tool_registry(tenant_id, tool_code, tool_version);
create unique index if not exists tool_registry_active_code_uidx
  on tool_registry(tenant_id, tool_code) where status = 'ACTIVE';

alter table agent_run_budget
  add column if not exists row_version bigint not null default 1;
alter table agent_run_budget
  drop constraint if exists agent_run_budget_row_version_check;
alter table agent_run_budget
  add constraint agent_run_budget_row_version_check check (row_version > 0);

drop trigger if exists agent_run_budget_immutable on agent_run_budget;
drop function if exists prevent_agent_run_budget_mutation();
create function prevent_agent_run_budget_mutation() returns trigger language plpgsql as $$
begin
  if new.budget_code is distinct from old.budget_code then
    raise exception 'agent run budget code is immutable once defined';
  end if;
  if (new.budget_name is distinct from old.budget_name
      or new.max_tokens is distinct from old.max_tokens
      or new.max_duration_seconds is distinct from old.max_duration_seconds)
     and coalesce(current_setting('haoemr.budget_update_authorized', true), 'false') <> 'true' then
    raise exception 'agent run budget limits can only change through the audited budget service';
  end if;
  return new;
end $$;
create trigger agent_run_budget_immutable
  before update of budget_code, budget_name, max_tokens, max_duration_seconds on agent_run_budget
  for each row execute function prevent_agent_run_budget_mutation();

-- 模型评测属于不可篡改证据，不允许物理删除。将内部验收模型退出业务选择，
-- 同时保留既有评测记录，避免破坏模型治理审计链。
update model_deployment
set status = 'INACTIVE', row_version = row_version + 1, updated_at = now()
where (model_deployment_id = '018f0000-0000-7000-8000-00000000f004'::uuid
   or model_code = 'DETERMINISTIC-CLINICAL-FAKE')
  and status = 'ACTIVE';

-- 移除重复、无目标医助的通用评测草稿；保留五个可定位到团队的真实门禁数据。
delete from config_item_revision
where config_id = '018f0000-0000-7000-8000-00000000c107'::uuid;
delete from config_item
where config_id = '018f0000-0000-7000-8000-00000000c107'::uuid;

-- 现有环境将小南策略和五个团队评测切换为已审批、已发布的仿真流程状态。
update config_item
set config_key = 'xiaonan-clinical-policy-v1',
    display_name = 'AI医助小南临床工作策略',
    status = 'ACTIVE', validation_state = 'VALID', validation_errors = '[]'::jsonb,
    approval_state = 'APPROVED',
    approved_by = '018f0000-0000-7000-8000-00000000aa06'::uuid,
    published_at = coalesce(published_at, now()), row_version = row_version + 1, updated_at = now()
where config_id = '018f0000-0000-7000-8000-00000000c108'::uuid;

update config_item
set status = 'ACTIVE', validation_state = 'VALID', validation_errors = '[]'::jsonb,
    approval_state = 'APPROVED',
    approved_by = '018f0000-0000-7000-8000-00000000aa06'::uuid,
    published_at = coalesce(published_at, now()), row_version = row_version + 1, updated_at = now()
where config_id in (
  '018f0000-0000-7000-8000-00000000f801'::uuid,
  '018f0000-0000-7000-8000-00000000f802'::uuid,
  '018f0000-0000-7000-8000-00000000f803'::uuid,
  '018f0000-0000-7000-8000-00000000f804'::uuid,
  '018f0000-0000-7000-8000-00000000f805'::uuid
);

-- 补齐五个医助团队的近期任务用量，使运行监测呈现完整诊疗流程样本。
insert into agent_run_budget_consumption(
  tenant_id, consumption_id, budget_id, run_id, tokens_consumed,
  duration_seconds, recorded_by, recorded_at)
select '018f0000-0000-7000-8000-00000000aa01'::uuid,
  seed.consumption_id::uuid, seed.budget_id::uuid, seed.run_id::uuid,
  seed.tokens_consumed, seed.duration_seconds,
  '018f0000-0000-7000-8000-00000000aa04'::uuid,
  now() - seed.age_hours * interval '1 hour'
from (values
  ('018f0000-0000-7000-8000-00000000f604', '018f0000-0000-7000-8000-00000000f303', '018f0000-0000-7000-8000-00000000f704', 3860::bigint, 16::bigint, 2),
  ('018f0000-0000-7000-8000-00000000f605', '018f0000-0000-7000-8000-00000000f305', '018f0000-0000-7000-8000-00000000f705', 6120::bigint, 27::bigint, 1)
) as seed(consumption_id, budget_id, run_id, tokens_consumed, duration_seconds, age_hours)
join agent_run_budget budget
  on budget.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
 and budget.budget_id = seed.budget_id::uuid
join app_user recorder
  on recorder.tenant_id = budget.tenant_id
 and recorder.user_id = '018f0000-0000-7000-8000-00000000aa04'::uuid
on conflict (tenant_id, consumption_id) do nothing;
