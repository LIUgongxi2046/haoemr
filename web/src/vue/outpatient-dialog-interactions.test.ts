import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const source = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('outpatient workspace interaction contract', () => {
  it('keeps all seven outpatient secondary routes visible', () => {
    const shell = source('./components/ClinicalShell.vue');
    for (const route of ['outpatient', 'opd-record', 'opd-diagnosis', 'opd-orders', 'opd-results', 'opd-consult', 'opd-followup']) {
      expect(shell).toContain(`'${route}'`);
    }
    for (const label of ['门诊工作台', '门诊病历', '诊断', '医嘱处方', '检查检验', '会诊转诊', '随访终诊']) {
      expect(shell).toContain(`'${label}'`);
    }
  });

  it('hosts the whole-hospital record navigation once below the main header', () => {
    const shell = source('./components/ClinicalShell.vue');
    expect(shell).toContain("label: '全院病历中心'");
    expect(shell).toContain("title: '全院病历中心'");
    for (const route of ['record', 'record-editor', 'record-sources', 'record-versions', 'lis-report', 'pacs-viewer']) {
      expect(shell).toContain(`['${route}'`);
    }
    expect(shell).not.toContain("['record-qc',");
    expect(source('./views/RecordEditorPage.vue')).toContain('to="/record-qc">查看完整质控与签名证据');
    for (const page of [
      './views/RecordCenterPage.vue', './views/RecordEditorPage.vue', './views/RecordSourcesPage.vue',
      './views/RecordGovernancePage.vue', './views/RecordVersionsPage.vue', './views/LisReportPage.vue',
      './views/PacsViewerPage.vue',
    ]) {
      expect(source(page)).not.toContain('record-subnav');
    }
  });

  it.each([
    ['./views/OutpatientWorkspacePage.vue', ['叫号并切换患者']],
    ['./views/DiagnosisWorkspacePage.vue', ['新增诊断', '更正诊断', '停止诊断']],
    ['./views/OrdersWorkspacePage.vue', ['新建医嘱草稿', '编辑医嘱草稿', '停止医嘱', '取消医嘱']],
    ['./views/ResultsWorkspacePage.vue', ['录入已审核结果', '追加结果更正版本', '完成危急值处置']],
    ['./views/OutpatientConsultPage.vue', ['新建会诊 / 转诊', '编辑会诊 / 转诊草稿', '删除会诊 / 转诊草稿', '拒绝会诊 / 转诊']],
    ['./views/OpdFollowupPage.vue', ['登记随访计划', '编辑随访计划', '填写随访结局', '删除随访计划']],
  ])('%s uses shared modal interactions', (path, titles) => {
    const component = source(path);
    expect(component).toContain('BusinessActionDialog');
    expect(component).not.toMatch(/window\.(?:prompt|confirm)\s*\(/);
    for (const title of titles) expect(component).toContain(title);
  });

  it('describes clinical deletion as an auditable lifecycle transition', () => {
    expect(source('./views/DiagnosisWorkspacePage.vue')).toContain('停止代替物理删除');
    expect(source('./views/ResultsWorkspacePage.vue')).toContain('原报告不可覆盖或删除');
    expect(source('./views/OutpatientConsultPage.vue')).toContain('带审计的逻辑取消');
    expect(source('./views/OpdFollowupPage.vue')).toContain('医疗记录不做物理删除');
  });

  it.each([
    ['./views/AppointmentRegistrationPage.vue', ['新建预约', '编辑预约·改约', '删除预约·退号']],
    ['./views/RecordSourcesPage.vue', ['新建附件证据', '新建临床来源引用', '更正来源引用', '撤销来源引用', '替换附件证据', '作废附件证据']],
    ['./views/RecordVersionsPage.vue', ['新建依法更正 / 补记', '确认撤销并留痕']],
    ['./views/RecordGovernancePage.vue', ['确认签署当前不可变版本']],
    ['./views/ClinicalTasksPage.vue', ['新建团队队列项', '新建任务通知', '停用']],
  ])('%s keeps deeper outpatient create, edit and void actions in dialogs', (path, titles) => {
    const component = source(path);
    expect(component).toMatch(/(?:Business|Admin)ActionDialog/);
    expect(component).not.toMatch(/window\.(?:prompt|confirm)\s*\(/);
    for (const title of titles) expect(component).toContain(title);
  });

  it('connects appointment edit to the reschedule API instead of local-only state', () => {
    const page = source('./views/AppointmentRegistrationPage.vue');
    const api = source('../api/emergency.ts');
    expect(page).toContain('rescheduleAppointment');
    expect(api).toContain('/reschedules');
    expect(api).toContain('appointmentRescheduleRequestWireSchema');
  });

  it('scopes every deep outpatient query to the selected patient or encounter', () => {
    expect(source('./composables/use-current-document.ts')).toContain('contextStore.encounterId');
    expect(source('./views/DiagnosisWorkspacePage.vue')).toContain("clinicalContext.encounterId");
    expect(source('./views/OrdersWorkspacePage.vue')).toContain("clinicalContext.patientId");
    expect(source('./views/ResultsWorkspacePage.vue')).toContain("clinicalContext.patientId");
    expect(source('./views/OutpatientConsultPage.vue')).toContain("clinicalContext.encounterId");
    expect(source('./views/OpdFollowupPage.vue')).toContain("clinicalContext.encounterId");
    expect(source('./execution-patient-flow.ts')).toContain('selectOutpatientContext');
  });

  it('uses append-only correction, replacement, revocation and void instead of destructive evidence mutation', () => {
    const sources = source('./views/RecordSourcesPage.vue');
    expect(sources).toContain('更新采用替换，删除采用业务作废');
    expect(sources).toContain('correctDocumentSourceReference');
    expect(sources).toContain('revokeDocumentSourceReference');
    expect(sources).toContain('voidDocumentAttachment');
    expect(source('./views/RecordDiffPage.vue')).toContain('只读比较');
    expect(source('./views/PatientTimelinePage.vue')).toContain('授权患者纵向时间线');
  });
});
