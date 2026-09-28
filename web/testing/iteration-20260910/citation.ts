// Test entry only: no model run or clinical facts are created. Citation retrieval uses the real local API.
import { createApp, h } from 'vue';
import { createPinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import AgentCandidateReview from '../../src/vue/components/AgentCandidateReview.vue';
import type { MedicalAgentRunWire } from '../../src/generated/contracts';
import source from './citation-fixture.json';
const run = { run_id: 'local-citation-component-test', state: 'COMPLETED', output: { execution_mode: 'LOCAL_TEST' },
  child_runs: [{ state: 'COMPLETED', contribution: {}, source_references: [source] }] } as unknown as MedicalAgentRunWire;
createApp({ render: () => h('main', [h('h1', '本地组件验收：非临床引用样例'), h('p', '本页未调用模型；引用按钮使用生产组件并访问本机知识接口。'), h(AgentCandidateReview, { run })]) })
  .use(createPinia()).use(createRouter({ history: createMemoryHistory(), routes: [] })).mount('#app');
