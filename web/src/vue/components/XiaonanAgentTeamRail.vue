<script setup lang="ts">
import { computed } from 'vue';
import type { MedicalAgentFamilyWire } from '../../generated/contracts';
import { doctorFacingAiText, doctorFacingTeamName } from '../medical-ai-terminology';

const props = defineProps<{
  agents: MedicalAgentFamilyWire[];
  selectedAgentCode: string;
  collapsed: boolean;
  busy?: boolean;
}>();

const emit = defineEmits<{
  toggle: [];
  select: [agent: MedicalAgentFamilyWire];
}>();

const categoryOrder: Array<{ code: string; label: string }> = [
  { code: 'CLINICAL', label: '临床诊疗' },
  { code: 'SPECIALTY', label: '专科医助' },
  { code: 'GOVERNANCE', label: '治理与运营' },
  { code: 'CARE', label: '随访与宣教' },
];

const groupedAgents = computed(() => {
  const groups = new Map<string, MedicalAgentFamilyWire[]>();
  for (const agent of props.agents) {
    const code = agent.main_agent.category ?? 'OTHER';
    if (!groups.has(code)) groups.set(code, []);
    groups.get(code)!.push(agent);
  }
  return categoryOrder
    .filter((item) => groups.has(item.code))
    .map((item) => ({ label: item.label, agents: groups.get(item.code)! }));
});

function teamName(agent: MedicalAgentFamilyWire) {
  return doctorFacingTeamName(agent.main_agent.display_name);
}
</script>

<template>
  <aside class="xiaonan-harness-team-rail" :class="{ collapsed }" aria-label="AI医助 EVA 团队">
    <header>
      <div v-if="!collapsed"><strong>医助团队</strong><span>{{ agents.length }} 组 · 按诊疗任务调用</span></div>
      <button type="button" :aria-label="collapsed ? '展开医助团队' : '折叠医助团队'" :title="collapsed ? '展开医助团队' : '折叠医助团队'" @click="emit('toggle')">{{ collapsed ? '›' : '‹' }}</button>
    </header>

    <div v-if="agents.length === 0" class="xiaonan-rail-state">{{ collapsed ? '…' : '正在读取医助团队…' }}</div>
    <nav v-else class="xiaonan-team-list" aria-label="选择医助团队">
      <template v-for="(group, groupIndex) in groupedAgents" :key="group.label">
        <div v-if="!collapsed" class="xiaonan-team-group">{{ group.label }}<span>{{ group.agents.length }}</span></div>
        <div v-else-if="groupIndex > 0" class="xiaonan-group-divider" aria-hidden="true"></div>
        <button
          v-for="agent in group.agents"
          :key="agent.main_agent.agent_code"
          type="button"
          :class="{ selected: selectedAgentCode === agent.main_agent.agent_code }"
          :aria-pressed="selectedAgentCode === agent.main_agent.agent_code"
          :disabled="busy"
          :title="teamName(agent)"
          @click="emit('select', agent)"
        >
          <i aria-hidden="true">{{ teamName(agent).slice(0, 1) }}</i>
          <span v-if="!collapsed"><b>{{ teamName(agent) }}</b><small>{{ doctorFacingAiText(agent.main_agent.display_role) }} · {{ agent.child_agents.length }} 位医助</small></span>
          <em v-if="!collapsed" class="xiaonan-usage">已用 {{ agent.main_agent.usage_count }} 次</em>
        </button>
      </template>
    </nav>
  </aside>
</template>

<style scoped>
.xiaonan-harness-team-rail { display: grid; grid-template-rows: auto minmax(0,1fr); width: 278px; min-width: 0; height: 100%; overflow: hidden; border-right: 1px solid #d8e3ef; background: #f6f9fd; transition: width .18s ease; }
.xiaonan-harness-team-rail.collapsed { width: 62px; }
header { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 58px; padding: 11px 12px; border-bottom: 1px solid #d8e3ef; background: #fff; }
header > div { display: grid; gap: 3px; min-width: 0; }
header strong { color: #263f58; font-size: 13px; }
header span { color: #738397; font-size: 9px; }
header button { display: grid; place-items: center; width: 30px; height: 30px; flex: 0 0 30px; padding: 0; color: #315a83; border: 1px solid #c8d7e7; border-radius: 8px; background: #f8fbff; font-size: 21px; cursor: pointer; }
.collapsed header { justify-content: center; padding-inline: 8px; }
.xiaonan-team-list { display: grid; gap: 6px; padding: 10px; overflow-y: auto; }
.xiaonan-team-group { display: flex; align-items: center; justify-content: space-between; grid-column: 1 / -1; padding: 8px 2px 2px; color: #7b8b9a; font-size: 8px; font-weight: 800; letter-spacing: .3px; }
.xiaonan-team-group + .xiaonan-team-group { margin-top: 8px; }
.xiaonan-team-group span { display: grid; place-items: center; min-width: 18px; height: 18px; padding: 0 5px; color: #6a7f93; border-radius: 999px; background: #e6edf4; font-size: 8px; }
.xiaonan-group-divider { grid-column: 1 / -1; height: 1px; margin: 2px 4px; background: #d8e3ef; }
.xiaonan-team-list > button { display: grid; grid-template-columns: 34px minmax(0,1fr) auto; align-items: center; gap: 9px; width: 100%; min-width: 0; padding: 7px; color: inherit; border: 1px solid transparent; border-radius: 9px; background: transparent; text-align: left; cursor: pointer; }
.xiaonan-team-list > button:hover { background: #edf5ff; }
.xiaonan-team-list > button.selected { border-color: #9dbfe6; background: #e8f2ff; box-shadow: 0 0 0 1px rgb(23 105 224 / 8%); }
.xiaonan-team-list > button:disabled { opacity: .55; cursor: not-allowed; }
.xiaonan-team-list i { display: grid; place-items: center; width: 34px; height: 34px; color: #fff; border-radius: 9px; background: linear-gradient(145deg,#1f6fbc,#19a1a0); font-size: 13px; font-style: normal; font-weight: 800; }
.xiaonan-team-list span { display: grid; gap: 3px; min-width: 0; }
.xiaonan-team-list b { overflow: hidden; color: #29435d; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.xiaonan-team-list small { overflow: hidden; color: #738397; font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.xiaonan-usage { flex: 0 0 auto; padding: 2px 7px; color: #6a7f93; border-radius: 999px; background: #eef3f8; font-size: 7px; font-style: normal; font-weight: 800; white-space: nowrap; }
.collapsed .xiaonan-team-list { padding: 9px; }
.collapsed .xiaonan-team-list > button { display: grid; grid-template-columns: 34px; place-content: center; padding: 4px; }
.xiaonan-rail-state { padding: 18px 10px; color: #74869a; font-size: 9px; text-align: center; }
@media (max-width: 760px) {
  .xiaonan-harness-team-rail { width: 100%; height: auto; border-right: 0; border-bottom: 1px solid #d8e3ef; }
  .xiaonan-harness-team-rail.collapsed { width: 100%; }
  .collapsed header { justify-content: space-between; }
  .collapsed header::before { content: '医助团队'; color: #263f58; font-size: 12px; font-weight: 800; }
  .collapsed .xiaonan-team-list { display: none; }
  .xiaonan-team-list { grid-template-columns: repeat(auto-fit,minmax(150px,1fr)); }
}
</style>
