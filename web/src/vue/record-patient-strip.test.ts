import { describe, expect, it, vi } from 'vitest';
import { createSSRApp } from 'vue';
import { renderToString } from '@vue/server-renderer';
import { clinicalContext } from '../clinical-api';
import RecordPatientStrip from './components/RecordPatientStrip.vue';
vi.mock('../clinical-api', () => ({
  clinicalContext: { patientId: 'patient-old', encounterId: 'encounter-old', patientDisplayName: '旧患者', documentId: null },
  selectOutpatientContext: vi.fn(), selectOutpatientDocument: vi.fn(),
}));
async function render() {
  const app = createSSRApp(RecordPatientStrip);
  app.component('RouterLink', { template: '<a><slot /></a>' });
  return renderToString(app);
}
describe('病历患者条使用当前选中上下文', () => {
  it('切换患者后姓名与标识同步，不沿用固定样例或风险标签', async () => {
    Object.assign(clinicalContext, { patientId: 'patient-new123', encounterId: 'encounter-new456', patientDisplayName: '新患者' });
    const html = await render();
    expect(html).toContain('新患者'); expect(html).toContain('new123'); expect(html).toContain('new456');
    expect(html).not.toContain('旧患者'); expect(html).not.toContain('张慧敏');
    expect(html).not.toContain('心内科'); expect(html).not.toContain('跌倒风险待核');
  });
  it('上下文清空后不显示上一位患者姓名', async () => {
    Object.assign(clinicalContext, { patientId: '', encounterId: '', patientDisplayName: '' });
    const html = await render();
    expect(html).toContain('患者信息待核对'); expect(html).not.toContain('旧患者');
  });
});
