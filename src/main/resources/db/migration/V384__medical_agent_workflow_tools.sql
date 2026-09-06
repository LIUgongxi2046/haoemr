-- Real read adapters for the already-published assistant workflows. No clinical write grants.
insert into tool_registry(tenant_id, tool_registry_id, tool_code, tool_name, tool_version, tool_type, status)
select tenant.tenant_id, gen_random_uuid(), tool.code, tool.name, '1.0.0', 'DATABASE_QUERY', 'ACTIVE'
from tenant cross join (values
  ('PATIENT_CONTEXT_READ', '患者基本信息与当前诊断读取'),
  ('BILLING_CONTEXT_READ', '当前就诊收费明细读取'),
  ('SCHEDULING_CONTEXT_READ', '当前就诊医技安排读取'),
  ('RESEARCH_CONTEXT_READ', '研究纳排条件读取'),
  ('TCM_CONTEXT_READ', '四诊与方药处方读取')
) as tool(code, name)
on conflict (tenant_id, tool_code, tool_version) do nothing;

insert into agent_dependency(tenant_id, agent_dependency_id, agent_registry_id, dependency_type, dependency_code)
select agent.tenant_id, gen_random_uuid(), agent.agent_registry_id, 'TOOL', capability.code
from agent_registry agent
join medical_agent_release release on release.agent_code = agent.agent_code
  and release.agent_level = 'MAIN' and release.status = 'ACTIVE'
cross join (values ('PATIENT_CONTEXT_READ'), ('KNOWLEDGE_SEARCH')) as capability(code)
where agent.status = 'ACTIVE'
on conflict (tenant_id, agent_registry_id, dependency_type, dependency_code) do nothing;

insert into agent_dependency(tenant_id, agent_dependency_id, agent_registry_id, dependency_type, dependency_code)
select agent.tenant_id, gen_random_uuid(), agent.agent_registry_id, 'TOOL', capability.tool
from agent_registry agent join (values
  ('INSURANCE_COMPLIANCE', 'BILLING_CONTEXT_READ'),
  ('MEDICAL_TECH_SCHEDULING', 'SCHEDULING_CONTEXT_READ'),
  ('RESEARCH_FOLLOWUP', 'RESEARCH_CONTEXT_READ'),
  ('TCM_SYNDROME_REVIEW', 'TCM_CONTEXT_READ')
) as capability(agent, tool) on capability.agent = agent.agent_code
where agent.status = 'ACTIVE'
on conflict (tenant_id, agent_registry_id, dependency_type, dependency_code) do nothing;
