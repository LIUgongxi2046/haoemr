<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { ModelDeploymentWire } from '../../generated/contracts';

type AuthorizationLevel = 'READ_ONLY' | 'STANDARD' | 'EXTENDED';
type ContextScope = 'RECORDS' | 'ORDERS' | 'RESULTS' | 'TASKS' | 'ATTACHMENTS' | 'CONFIGURATION';
type Panel = 'context' | 'mode' | 'model' | null;

const props = defineProps<{
  models: ModelDeploymentWire[];
  modelId: string;
  authorizationLevel: AuthorizationLevel;
  contextScopes: ContextScope[];
  disabled?: boolean;
  compact?: boolean;
}>();

const emit = defineEmits<{
  'update:modelId': [value: string];
  'update:authorizationLevel': [value: AuthorizationLevel];
  'update:contextScopes': [value: ContextScope[]];
}>();

const openPanel = ref<Panel>(null);
const root = ref<HTMLElement | null>(null);

const scopeOptions: Array<{ code: ContextScope; label: string; description: string }> = [
  { code: 'CONFIGURATION', label: '知识与规则', description: '已发布知识、文书模板和业务规则' },
  { code: 'RECORDS', label: '病历文书', description: '当前就诊已确认文书及版本' },
  { code: 'ORDERS', label: '医嘱执行', description: '医嘱、执行状态与异常' },
  { code: 'RESULTS', label: '检查检验', description: '报告、结果版本与危急值' },
  { code: 'TASKS', label: '任务随访', description: '开放任务、责任人与随访' },
  { code: 'ATTACHMENTS', label: '病历附件', description: '已通过安全检查的附件' },
];
const modeOptions: Array<{ code: AuthorizationLevel; label: string; description: string }> = [
  { code: 'READ_ONLY', label: '只读', description: '只读取已选资料，不生成系统操作' },
  { code: 'STANDARD', label: '标准', description: '可读取、分析和草拟；写入前由医生确认' },
  { code: 'EXTENDED', label: '扩展', description: '可调用更多院内工具；每项写入仍需确认' },
];
const authorizationShort = computed(() => ({
  READ_ONLY: '只读', STANDARD: '标准', EXTENDED: '扩展',
}[props.authorizationLevel]));
const selectedModel = computed(() => props.models.find((model) => model.model_deployment_id === props.modelId));

function togglePanel(panel: Exclude<Panel, null>) {
  openPanel.value = openPanel.value === panel ? null : panel;
}
function closePanels() { openPanel.value = null; }
function toggleScope(code: ContextScope) {
  const selected = new Set(props.contextScopes);
  if (selected.has(code)) {
    if (selected.size === 1) return;
    selected.delete(code);
  } else selected.add(code);
  emit('update:contextScopes', scopeOptions.filter((item) => selected.has(item.code)).map((item) => item.code));
}
function selectMode(level: AuthorizationLevel) { emit('update:authorizationLevel', level); closePanels(); }
function selectModel(id: string) { emit('update:modelId', id); closePanels(); }

function onDocumentClick(event: MouseEvent) {
  if (root.value && !root.value.contains(event.target as Node)) closePanels();
}
function onKeydown(event: KeyboardEvent) { if (event.key === 'Escape') closePanels(); }
onMounted(() => { document.addEventListener('click', onDocumentClick); document.addEventListener('keydown', onKeydown); });
onBeforeUnmount(() => { document.removeEventListener('click', onDocumentClick); document.removeEventListener('keydown', onKeydown); });
</script>

<template>
  <div ref="root" class="eva-control-bar" :class="{ compact }">
    <div class="eva-panel-anchor">
      <button type="button" class="eva-pill" :disabled="disabled" :aria-expanded="openPanel === 'context'" aria-label="选择诊疗数据范围" @click.stop="togglePanel('context')">
        <span class="eva-pill-icon" aria-hidden="true">◈</span>
        <span class="eva-pill-label">数据范围</span>
        <b>{{ contextScopes.length }}</b>
      </button>
      <section v-if="openPanel === 'context'" class="eva-popover" @click.stop>
        <header><strong>选择诊疗数据范围</strong><span>AI医助 EVA 仅访问勾选范围</span></header>
        <button v-for="item in scopeOptions" :key="item.code" type="button" :aria-pressed="contextScopes.includes(item.code)" @click="toggleScope(item.code)">
          <i>{{ contextScopes.includes(item.code) ? '✓' : '' }}</i><span><b>{{ item.label }}</b><small>{{ item.description }}</small></span>
        </button>
      </section>
    </div>

    <div class="eva-panel-anchor">
      <button type="button" class="eva-pill" :disabled="disabled" :aria-expanded="openPanel === 'mode'" aria-label="选择授权与运行模式" @click.stop="togglePanel('mode')">
        <span class="eva-pill-icon" aria-hidden="true">⚙</span>
        <span class="eva-pill-label">模式</span>
        <b>{{ authorizationShort }}</b>
      </button>
      <section v-if="openPanel === 'mode'" class="eva-popover" @click.stop>
        <header><strong>授权与运行模式</strong><span>影响 AI医助 EVA 可执行的操作范围</span></header>
        <button v-for="mode in modeOptions" :key="mode.code" type="button" :aria-pressed="authorizationLevel === mode.code" @click="selectMode(mode.code)">
          <i>{{ authorizationLevel === mode.code ? '✓' : '' }}</i><span><b>{{ mode.label }}</b><small>{{ mode.description }}</small></span>
        </button>
      </section>
    </div>

    <div class="eva-panel-anchor">
      <button type="button" class="eva-pill" :disabled="disabled || models.length === 0" :aria-expanded="openPanel === 'model'" aria-label="选择模型" @click.stop="togglePanel('model')">
        <span class="eva-pill-icon" aria-hidden="true">◮</span>
        <span class="eva-pill-label">模型</span>
        <b>{{ selectedModel?.display_name ?? '默认模型' }}</b>
      </button>
      <section v-if="openPanel === 'model'" class="eva-popover eva-popover-right" @click.stop>
        <header><strong>选择模型</strong><span>用于本任务的生成模型</span></header>
        <button v-for="model in models" :key="model.model_deployment_id" type="button" :aria-pressed="model.model_deployment_id === modelId" @click="selectModel(model.model_deployment_id)">
          <i>{{ model.model_deployment_id === modelId ? '✓' : '' }}</i><span><b>{{ model.display_name }}</b><small>{{ model.provider_code }}</small></span>
        </button>
        <p v-if="models.length === 0" class="eva-popover-empty">暂无可用模型</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.eva-control-bar { position: relative; display: flex; align-items: center; gap: 7px; min-width: 0; }
.eva-panel-anchor { position: relative; flex: 0 0 auto; }
.eva-pill { display: flex; align-items: center; gap: 6px; min-height: 32px; padding: 2px 9px; color: #334e68; border: 1px solid #c9d6e2; border-radius: 999px; background: #fff; cursor: pointer; }
.eva-pill:hover { border-color: #7da7cf; background: #f4f8fc; }
.eva-pill:focus-visible { outline: 3px solid rgb(23 105 224 / 18%); outline-offset: 2px; }
.eva-pill:disabled { opacity: .55; cursor: not-allowed; }
.eva-pill-icon { color: #1769e0; font-size: 12px; line-height: 1; }
.eva-pill-label { color: #334e68; font-size: 9px; font-weight: 800; }
.eva-pill > b { overflow: hidden; max-width: 96px; color: #1769e0; font-size: 8px; font-weight: 800; text-overflow: ellipsis; white-space: nowrap; }
.eva-popover { position: absolute; z-index: 20; left: 0; bottom: calc(100% + 8px); display: grid; width: 292px; overflow: hidden; border: 1px solid #cbd8e5; border-radius: 12px; background: #fff; box-shadow: 0 16px 40px rgb(26 51 78 / 18%); }
.eva-popover-right { left: auto; right: 0; }
.eva-popover header { display: grid; gap: 2px; padding: 12px; border-bottom: 1px solid #e2e9f0; }
.eva-popover header strong { color: #263f58; font-size: 12px; }
.eva-popover header span { color: #75879a; font-size: 9px; }
.eva-popover > button { display: grid; grid-template-columns: 22px minmax(0,1fr); align-items: center; gap: 8px; padding: 9px 12px; color: inherit; border: 0; border-bottom: 1px solid #eef2f6; background: #fff; text-align: left; cursor: pointer; }
.eva-popover > button:hover { background: #f4f8fc; }
.eva-popover > button i { display: grid; place-items: center; width: 19px; height: 19px; color: #fff; border: 1px solid #b7c6d4; border-radius: 5px; background: #fff; font-size: 10px; font-style: normal; }
.eva-popover > button[aria-pressed="true"] i { border-color: #1769e0; background: #1769e0; }
.eva-popover > button span { display: grid; gap: 2px; min-width: 0; }
.eva-popover > button b { overflow: hidden; color: #304a63; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.eva-popover > button small { color: #7d8c9b; font-size: 8px; }
.eva-popover-empty { margin: 0; padding: 14px 12px; color: #7d8c9b; font-size: 9px; text-align: center; }
.compact { gap: 5px; }
.compact .eva-pill-label { display: none; }
@media (max-width: 720px) { .eva-control-bar { flex-wrap: wrap; } .eva-popover { width: min(292px,calc(100vw - 34px)); } .eva-popover-right { right: auto; left: 0; } }
</style>
