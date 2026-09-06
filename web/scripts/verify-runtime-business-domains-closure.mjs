import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/runtime-business-domains-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const stamp = Date.now().toString();
const templateName = `E2E 运行时表单 ${stamp}`;
const templateCode = `E2E-RUNTIME-FORM-${stamp}`;
const medicationIndication = `E2E 运行时用药规则 ${stamp}`;
const labIndication = `E2E 危急值流程 ${stamp}`;
const criticalConclusion = `E2E 危急值报告 ${stamp}`;
const result = { run_at: new Date().toISOString(), stamp, checks: [], findings: [], evidence: {} };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined,
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
let switchingUser = false;
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));
page.on('response', (response) => {
  if (switchingUser || response.status() < 400 || !response.url().includes('/api/')) return;
  // Lab technicians deliberately do not have ward-summary access. The shared shell probes
  // these read-only inpatient widgets during navigation; they are not part of the lab flow.
  if (response.status() === 403 && response.request().method() === 'GET'
    && /\/api\/v1\/inpatient\/(admissions\/[^/]+\/overview|worklist)/.test(response.url())) return;
  result.findings.push({ name: 'unexpected-api-error', detail: `${response.request().method()} ${response.url()} -> ${response.status()}`, url: page.url() });
});

async function check(name, action) {
  try { result.checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); }
}
async function login(username) {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  const button = page.getByRole('button', { name: '登录系统', exact: true });
  await button.waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.locator('#system-login-password').fill(password);
  await button.click();
  await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
}
async function logout() {
  switchingUser = true;
  try {
    await page.getByRole('button', { name: '用户登录与账户', exact: true }).click();
    await page.getByRole('button', { name: '退出系统', exact: true }).click();
    await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 20_000 });
  } finally { switchingUser = false; }
}
async function switchUser(username) { await logout(); await login(username); }
async function route(id) {
  await page.goto(`${baseUrl}/#/${id}`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction((routeId) => document.documentElement.dataset.routeId === routeId, id, { timeout: 30_000 });
  await page.locator('main h1').waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForFunction(() => {
    const root = document.querySelector('main [data-page-root]');
    return root && !root.querySelector('.clinical-page-state.loading');
  }, undefined, { timeout: 30_000 }).catch(() => undefined);
}
async function apiResponse(path, method, action) {
  const responsePromise = page.waitForResponse((response) => response.url().includes(path)
    && response.request().method() === method, { timeout: 30_000 });
  await action();
  const response = await responsePromise;
  const text = await response.text();
  let payload;
  try { payload = JSON.parse(text); } catch { payload = null; }
  if (!response.ok()) throw new Error(`${method} ${path} -> ${response.status()} ${text.slice(0, 500)}`);
  return payload;
}
async function selectExecutionPatient(routeId, encounterId) {
  await route(routeId);
  const row = page.locator('[data-execution-patient-row]').filter({ hasText: encounterId });
  await row.waitFor({ state: 'visible', timeout: 60_000 });
  await row.getByRole('button', { name: '选择患者并下转', exact: true }).click();
  await page.locator('[data-execution-patient-detail]').waitFor({ state: 'visible', timeout: 60_000 });
}
async function firstEnabled(locator) {
  for (let index = 0; index < await locator.count(); index += 1) {
    const candidate = locator.nth(index);
    if (await candidate.isVisible() && !(await candidate.isDisabled())) return candidate;
  }
  throw new Error('没有找到可用操作按钮');
}

try {
  await check('published-form-is-bound-to-template-draft-and-author-cannot-self-publish', async () => {
    await login('linwei');
    await route('admin-templates');
    await page.getByRole('button', { name: '新建模板/规则', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '创建模板' });
    await dialog.getByLabel('模板编码（系统唯一）').fill(templateCode);
    await dialog.getByLabel('模板名称').fill(templateName);
    await dialog.getByLabel('文书类型').fill(`E2E.RUNTIME.FORM.${stamp}`);
    await dialog.getByLabel('结构化字段编码（逗号分隔）').fill('patient_name,encounter_no,department,recorded_at,chief_complaint,present_illness,allergies,primary_diagnosis,treatment_plan,followup_plan,author_signature,reviewer_signature');
    await dialog.getByLabel('必填字段编码（必须已在上方定义）').fill('chief_complaint');
    await dialog.getByLabel('已发布表单配置键（可选）').fill('form-outpatient-record-v1');
    const draft = await apiResponse('/admin/document-templates', 'POST', () => dialog.getByRole('button', { name: '保存 v1 草案', exact: true }).click());
    const required = new Set(draft.required_fields || []);
    for (const field of ['patient_name', 'encounter_no', 'department', 'recorded_at', 'chief_complaint', 'present_illness', 'allergies', 'primary_diagnosis', 'treatment_plan', 'author_signature', 'reviewer_signature']) {
      if (!required.has(field)) throw new Error(`已发布表单的必填字段未固化：${field}`);
    }
    if (draft.display_rules?.form_config_key !== 'form-outpatient-record-v1'
      || !draft.display_rules?.form_config_id || !draft.display_rules?.form_config_row_version) {
      throw new Error('模板草案未保存表单配置键、配置 ID 和行版本');
    }
    await page.getByLabel('模板状态').selectOption('DRAFT');
    const row = page.locator('.table tbody tr').filter({ hasText: templateCode });
    await row.waitFor({ state: 'visible', timeout: 30_000 });
    await row.click();
    const selfPublish = page.getByRole('button', { name: '创建人不能审批', exact: true });
    await selfPublish.waitFor({ state: 'visible', timeout: 30_000 });
    if (!(await selfPublish.isDisabled())) throw new Error('模板创建人仍可在界面自行发布');
    result.evidence.template_id = draft.template_id;
    result.evidence.template_version_id = draft.template_version_id;
    return `模板草案 ${draft.template_id} 已绑定已发布 form-outpatient-record-v1，且创建人发布按钮被禁用`;
  });

  await check('document-draft-uses-the-normalized-business-purpose-code', async () => {
    await switchUser('linwei');
    await route('record');
    await page.getByPlaceholder('患者、就诊号、病历类型、作者或科室').fill('018f0000-0000-7000-8000-000000000101');
    await (await firstEnabled(page.getByRole('button', { name: '同次新建', exact: true }))).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '同次就诊新建病历' });
    await dialog.getByLabel('文书类型编码').fill('WS445.2.OUTPATIENT_RECORD');
    await dialog.getByLabel('主诉', { exact: true }).fill(`E2E 范围配置验证主诉 ${stamp}`);
    await dialog.getByLabel('现病史', { exact: true }).fill(`E2E 范围配置验证现病史 ${stamp}`);
    await dialog.getByLabel('既往史', { exact: true }).fill('无特殊既往史。');
    await dialog.getByLabel('过敏史', { exact: true }).fill('否认已知药物过敏。');
    await dialog.getByLabel('体格检查', { exact: true }).fill('一般情况稳定。');
    await dialog.getByLabel('辅助检查', { exact: true }).fill('本次范围配置浏览器验收。');
    await dialog.getByLabel('诊断与评估', { exact: true }).fill('仅用于开发库范围配置验收。');
    await dialog.getByLabel('治疗计划', { exact: true }).fill('不产生临床执行指令。');
    await dialog.getByLabel('复诊与随访计划', { exact: true }).fill('开发验证完成后留存可追溯记录。');
    const document = await apiResponse('/documents', 'POST', () => dialog.getByRole('button', { name: '新建并纳入流程', exact: true }).click());
    if (!document.document_id) throw new Error('病历创建响应缺少 document_id');
    result.evidence.scope_document_id = document.document_id;
    return `文书草稿 ${document.document_id} 已在真实业务入口创建，并使用 DOCUMENT_DRAFT 业务用途租约`;
  });

  await check('medication-safety-runs-before-signing-and-keeps-server-evidence', async () => {
    await route('opd-orders');
    await page.getByRole('button', { name: '新增医嘱', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建医嘱草稿' });
    await dialog.getByLabel('项目类别').selectOption('MEDICATION');
    await dialog.getByLabel('目录编码').fill('MED-AMLODIPINE-5');
    await dialog.getByLabel('项目名称').fill('苯磺酸氨氯地平片');
    await dialog.getByLabel('数量').fill('3');
    await dialog.getByLabel('单位', { exact: true }).fill('片');
    await dialog.getByLabel('单次剂量').fill('5');
    await dialog.getByLabel('剂量单位').fill('mg');
    await dialog.getByLabel('给药途径').fill('PO');
    await dialog.getByLabel('频次编码').fill('QD');
    await dialog.getByLabel('临床指征').fill(medicationIndication);
    const created = await apiResponse('/orders', 'POST', () => dialog.getByRole('button', { name: '保存医嘱草稿', exact: true }).click());
    const order = page.locator('.order-card').filter({ hasText: medicationIndication });
    await order.waitFor({ state: 'visible', timeout: 30_000 });
    const evaluation = await apiResponse(`/orders/${created.order_id}/safety-check`, 'POST', () => order.getByRole('button', { name: '安全预检并签署生效', exact: true }).click());
    const configFinding = (evaluation.findings || []).find((finding) => finding.code === 'CONFIG_RULE_MED_DUPLICATE'
      && String(finding.evidence_source || '').startsWith('CONFIG_RUNTIME:'));
    if (evaluation.passed || evaluation.blocking_count < 1 || !configFinding) {
      throw new Error(`未得到运行时配置支撑的安全阻断：${JSON.stringify(evaluation)}`);
    }
    await order.getByText('硬规则阻断', { exact: false }).waitFor({ state: 'visible', timeout: 30_000 });
    if (await order.getByRole('button', { name: '取消医嘱', exact: true }).count()) {
      throw new Error('被安全阻断的医嘱仍出现已生效后的取消入口');
    }
    result.evidence.medication_order_id = created.order_id;
    result.evidence.medication_evaluation_id = evaluation.evaluation_id;
    return `医嘱 ${created.order_id} 被确定性重复用药规则阻断，证据来自 ${configFinding.evidence_source}`;
  });

  await check('critical-result-workflow-uses-separated-author-receiver-and-disposer-actions', async () => {
    await route('opd-orders');
    await page.getByRole('button', { name: '新增医嘱', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建医嘱草稿' });
    await dialog.getByLabel('项目类别').selectOption('LAB');
    await dialog.getByLabel('目录编码').fill(`LAB-T63-${stamp}`);
    await dialog.getByLabel('项目名称').fill(`E2E 血钾 ${stamp}`);
    await dialog.getByLabel('临床指征').fill(labIndication);
    const order = await apiResponse('/orders', 'POST', () => dialog.getByRole('button', { name: '保存医嘱草稿', exact: true }).click());
    const orderCard = page.locator('.order-card').filter({ hasText: labIndication });
    await orderCard.waitFor({ state: 'visible', timeout: 30_000 });
    await orderCard.getByRole('button', { name: '安全预检并签署生效', exact: true }).click();
    const taskText = await orderCard.locator('.execution-task-id').innerText({ timeout: 30_000 });
    const executionTaskId = taskText.match(/[0-9a-f]{8}-[0-9a-f-]{27,}/i)?.[0];
    const orderItemId = order.items?.[0]?.order_item_id;
    if (!executionTaskId || !order.encounter_id || !orderItemId) throw new Error('检验医嘱未生成可执行任务');
    await switchUser('ruifeng.cao');
    await selectExecutionPatient('lab-workbench', order.encounter_id);
    await page.getByRole('button', { name: '新增标本申请', exact: true }).click();
    const specimenDialog = page.getByRole('dialog').filter({ hasText: '新增标本申请' });
    await specimenDialog.getByLabel('检验医嘱项目').selectOption(orderItemId);
    const specimen = await apiResponse('/lab-specimens', 'POST', () => specimenDialog.getByRole('button', { name: '创建标本申请', exact: true }).click());
    let specimenRow = page.locator('.admin-table tbody tr').filter({ hasText: specimen.specimen_id.slice(-8) });
    await apiResponse(`/lab-specimens/${specimen.specimen_id}/collections`, 'POST', () => specimenRow.getByRole('button', { name: '采集', exact: true }).click());
    specimenRow = page.locator('.admin-table tbody tr').filter({ hasText: specimen.specimen_id.slice(-8) });
    await apiResponse(`/lab-specimens/${specimen.specimen_id}/receptions`, 'POST', () => specimenRow.getByRole('button', { name: '接收', exact: true }).click());
    specimenRow = page.locator('.admin-table tbody tr').filter({ hasText: specimen.specimen_id.slice(-8) });
    await apiResponse(`/executions/${executionTaskId}/events`, 'POST', async () => {
      await specimenRow.getByRole('button', { name: '完成检验', exact: true }).click();
      await page.getByRole('dialog').filter({ hasText: '确认检验执行完成' }).getByRole('button', { name: '确认完成检验执行', exact: true }).click();
    });
    await route('opd-results');
    await page.getByRole('button', { name: '录入结果', exact: true }).click();
    const resultDialog = page.getByRole('dialog').filter({ hasText: '录入已审核结果' });
    await resultDialog.getByLabel('已完成执行').selectOption(executionTaskId);
    await resultDialog.getByLabel('结果值').fill('6.8');
    await resultDialog.getByLabel('参考上限').fill('5.5');
    await resultDialog.getByLabel('异常标志').selectOption('CRITICAL_HIGH');
    await resultDialog.getByLabel('报告结论').fill(criticalConclusion);
    const clinicalResult = await apiResponse('/results', 'POST', () => resultDialog.getByRole('button', { name: '签发结果 v1', exact: true }).click());
    await switchUser('linwei');
    await route('opd-results');
    const resultCard = page.locator('.result-card').filter({ hasText: criticalConclusion });
    await resultCard.waitFor({ state: 'visible', timeout: 30_000 });
    await apiResponse('/critical-values/', 'POST', () => resultCard.getByRole('button', { name: '复读并确认接收', exact: true }).click());
    await resultCard.getByText('已接收·待处置', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    await resultCard.getByRole('button', { name: '填写处置结果', exact: true }).click();
    const disposeDialog = page.getByRole('dialog').filter({ hasText: '完成危急值处置' });
    await disposeDialog.getByLabel('临床评估').fill('复核危急高钾结果，结合当前病情启动人工评估。');
    await disposeDialog.getByLabel('采取措施').fill('通知经治团队并安排复查与必要处置。');
    await disposeDialog.getByLabel('处置结果').fill('已完成即时处置记录，等待复测确认。');
    await apiResponse('/critical-values/', 'POST', () => disposeDialog.getByRole('button', { name: '完成处置并留痕', exact: true }).click());
    await resultCard.getByText('已处置', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    result.evidence.critical_result_id = clinicalResult.result_id;
    result.evidence.critical_value_id = clinicalResult.critical_values?.[0]?.critical_value_id;
    result.evidence.critical_order_id = order.order_id;
    return `检验签发人、临床接收人和处置人分离；危急值 ${clinicalResult.critical_values?.[0]?.critical_value_id ?? '已创建'} 已处置`;
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'runtime-business-domains-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
