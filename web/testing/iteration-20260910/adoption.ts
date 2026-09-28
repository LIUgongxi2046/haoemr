// Test-only entry: mounts the production undo dialog with deterministic, nonclinical text.
import { createApp, h, ref } from 'vue';
import AgentAdoptionUndo from '../../src/vue/components/AgentAdoptionUndo.vue';
import { captureAgentAdoption } from '../../src/vue/agent-adoption-undo';
createApp({ setup() {
  const sections = ref<Record<string, unknown>>({ chief: '医生继续修改', assessment: '采用后的评估', plan: '医生独立修改计划' });
  const snapshot = ref(captureAgentAdoption('test-document', 'test-run', { chief: '采用前主诉', assessment: '采用前评估', plan: '原计划' }, { chief: '采用主诉', assessment: '采用后的评估' }));
  return () => h('main', [h('h1', '本地组件验收：以下为确定性测试文本'), h('p', '未调用模型或临床接口。使用与病历编辑页相同的撤销组件。'),
    h(AgentAdoptionUndo, { snapshot: snapshot.value, documentId: 'test-document', sections: sections.value, labels: { chief: '主诉', assessment: '评估' }, disabled: false,
      onUndo: (next: Record<string, unknown>) => { sections.value = next; } }),
    h('pre', { 'aria-label': '撤销结果' }, JSON.stringify(sections.value, null, 2))]);
} }).mount('#app');
