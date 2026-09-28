// Test entry only: no patients created. Existing workspace components receive an unselected encounter context.
import { createApp, h, ref } from 'vue';
import { createPinia } from 'pinia';
import { VueQueryPlugin } from '@tanstack/vue-query';
import { createRouter, createMemoryHistory } from 'vue-router';
import { clinicalContext } from '../../src/clinical-api';
import OutpatientWorkspacePage from '../../src/vue/views/OutpatientWorkspacePage.vue';
import InpatientWorkspacePage from '../../src/vue/views/InpatientWorkspacePage.vue';
import '../../src/styles.css';
import '../../src/prototype.css';
import '../../src/align-prototype.css';
import '../../src/vue/vue-shell.css';
Object.assign(clinicalContext, { patientId: '', encounterId: '', documentId: '', inpatientPatientId: '', inpatientEncounterId: '', inpatientAdmissionId: '', inpatientWardId: '' });
createApp({ setup() {
  const mode = ref('outpatient');
  return () => h('main', [h('h1', '本地空态组件验收：未选择就诊，未创建患者'),
    h('button', { onClick: () => { mode.value = 'outpatient'; } }, '测试门诊空态'),
    h('button', { onClick: () => { mode.value = 'inpatient'; } }, '测试住院空态'),
    h(mode.value === 'outpatient' ? OutpatientWorkspacePage : InpatientWorkspacePage)]);
} }).use(createPinia()).use(VueQueryPlugin).use(createRouter({ history: createMemoryHistory(), routes: [] })).mount('#app');
