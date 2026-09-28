import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(process.env.HAOEMR_TEST_OUTPUT_DIR || resolve(projectDir, 'output/playwright/record-center-write-regression'));
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const runId = `E2E-${new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)}`;
const primaryType = 'WS445.2.OUTPATIENT_RECORD';
const voidType = 'WS445.2.OUTPATIENT_RECORD';
const primaryEncounterId = process.env.HAOEMR_E2E_ENCOUNTER_ID || '018f0000-0000-7000-8000-000000000101';

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const checks = [];
const findings = [];
let currentRoute = 'bootstrap';
let switchingUser = false;

page.on('console', (message) => {
  if (message.type() !== 'error') return;
  if (switchingUser && message.text().includes('401')) return;
  // The HTTP listener below reports API failures with exact URL/status; the browser's
  // generic "Failed to load resource" line carries no URL, so it is skipped here.
  if (message.text().includes('Failed to load resource')) return;
  findings.push({ route: currentRoute, check: 'console', detail: message.text() });
});
page.on('pageerror', (error) => findings.push({ route: currentRoute, check: 'pageerror', detail: error.message }));
page.on('response', (response) => {
  if (response.status() < 400 || !response.url().includes('/api/')) return;
  if (switchingUser && response.status() === 401) return;
  // Benign: the outpatient orders/results seeding pages fetch with an empty
  // encounter_id before a patient is selected; these are not record-center writes.
  if (response.status() === 400 && /[?&]encounter_id=(&|$)/.test(response.url())) return;
  findings.push({ route: currentRoute, check: 'http-status', detail: `${response.request().method()} ${response.url()} -> ${response.status()}` });
});

async function check(name, action) {
  try {
    const evidence = (await action()) || {};
    checks.push({ route: currentRoute, name, status: 'PASS', ...evidence });
    return evidence;
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    findings.push({ route: currentRoute, check: name, detail });
    throw error;
  }
}

async function login(loginUsername = username) {
  currentRoute = 'login';
  await page.goto(`${baseUrl}/#/record`, { waitUntil: 'domcontentloaded' });
  const submit = page.getByRole('button', { name: '登录系统', exact: true });
  await submit.waitFor({ state: 'visible', timeout: 30_000 }).catch(() => undefined);
  if (await submit.isVisible().catch(() => false)) {
    await page.getByLabel('用户名', { exact: true }).fill(loginUsername);
    await page.locator('#system-login-password').fill(password);
    await submit.click();
  }
  await waitRoute('record');
  await page.locator('.record-metrics').waitFor({ state: 'visible', timeout: 60_000 });
}

async function switchUser(loginUsername) {
  switchingUser = true;
  await page.evaluate(async () => {
    const raw = sessionStorage.getItem('haoemr.clinical-session');
    const token = raw ? JSON.parse(raw)?.token : '';
    if (token) await fetch('/api/v1/session/logout', { method: 'POST', headers: { Authorization: `Bearer ${token}` } }).catch(() => undefined);
    sessionStorage.clear();
  });
  try {
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByLabel('用户名', { exact: true }).fill(loginUsername);
    await page.locator('#system-login-password').fill(password);
    await page.getByRole('button', { name: '登录系统', exact: true }).click();
    await page.locator('.shell').waitFor({ state: 'visible', timeout: 30_000 });
  } finally {
    switchingUser = false;
  }
}

async function selectExecutionPatient(routeId, encounterId) {
  await navigateHash(routeId);
  const patientRow = page.locator('[data-execution-patient-row]').filter({ hasText: encounterId });
  await patientRow.waitFor({ state: 'visible', timeout: 60_000 });
  await patientRow.getByRole('button', { name: '选择患者并下转', exact: true }).click();
  await page.locator('[data-execution-patient-detail]').waitFor({ state: 'visible', timeout: 60_000 });
}

async function ensureSessionFresh(minimumRemainingMs = 10 * 60 * 1000) {
  const routeId = currentRoute;
  const renewed = await page.evaluate(async ({ username, password, minimumRemainingMs }) => {
    const storageKey = 'haoemr.clinical-session';
    const stored = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
    const expiresAt = Date.parse(stored?.user?.expires_at || '');
    if (stored?.token && Number.isFinite(expiresAt) && expiresAt - Date.now() > minimumRemainingMs) return false;
    const response = await fetch('/api/v1/session/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }),
    });
    if (!response.ok) throw new Error(`会话续期失败：HTTP ${response.status}`);
    const session = await response.json();
    sessionStorage.setItem(storageKey, JSON.stringify({ token: session.bearer_token, user: session.user }));
    return true;
  }, { username, password, minimumRemainingMs });
  if (renewed) {
    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitRoute(routeId);
  }
  return renewed;
}

async function waitRoute(routeId) {
  currentRoute = routeId;
  await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, routeId, { timeout: 30_000 });
  await page.locator('main h1').waitFor({ state: 'visible' });
  // Give Vue one render turn to mount the query state before deciding that loading is complete.
  await page.waitForTimeout(100);
  await page.waitForFunction(() => {
    const root = document.querySelector('main [data-page-root]');
    return root && !root.querySelector('.clinical-page-state.loading');
  }, undefined, { timeout: 30_000 });
}

async function navigate(label, routeId) {
  await page.getByRole('link', { name: label, exact: true }).first().click();
  await waitRoute(routeId);
}

async function navigateHash(routeId) {
  await page.evaluate((id) => { window.location.hash = `#/${id}`; }, routeId);
  await waitRoute(routeId);
}

async function submitWrite(name, urlPart, method, action) {
  return check(name, async () => {
    let finish;
    let fail;
    const responsePromise = new Promise((resolve, reject) => { finish = resolve; fail = reject; });
    // Attach a rejection handler immediately. The UI action may still be waiting for a
    // dialog transition when the network timeout fires; without this guard Node treats
    // the timeout as an unhandled rejection before the promise is awaited below.
    void responsePromise.catch(() => undefined);
    const timer = setTimeout(() => fail(new Error(`等待响应超时：${method} ${urlPart}`)), 30_000);
    const listener = (response) => {
      if (response.url().includes(urlPart) && response.request().method() === method) finish(response);
    };
    page.on('response', listener);
    try { await action(); }
    catch (error) {
      clearTimeout(timer); page.off('response', listener); throw error;
    }
    const response = await responsePromise.finally(() => { clearTimeout(timer); page.off('response', listener); });
    const body = await response.text();
    if (response.status() < 200 || response.status() >= 300) {
      throw new Error(`${method} ${urlPart} -> ${response.status()} ${body.slice(0, 500)}`);
    }
    let payload = null;
    try { payload = JSON.parse(body); } catch { payload = body || null; }
    return { method, url: response.url(), status: response.status(), payload };
  });
}

async function createCompletedExecution(reportType) {
  const type = reportType.toLowerCase();
  const indication = `${runId} ${reportType} 引用回归前置医嘱`;
  await navigateHash('opd-orders');
  await page.locator('.orders-workspace').waitFor({ state: 'visible', timeout: 60_000 });
  const created = await submitWrite(`seed-${type}-order-create`, '/api/v1/orders', 'POST', async () => {
    await page.getByRole('button', { name: '新增医嘱', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建医嘱草稿' });
    await dialog.getByLabel('项目类别').selectOption(reportType);
    await dialog.getByLabel('目录编码').fill(`${reportType}-${runId.slice(-8)}`);
    await dialog.getByLabel('项目名称').fill(`${runId} ${reportType} 项目`);
    await dialog.getByLabel('临床指征').fill(indication);
    await dialog.getByLabel('执行说明').fill('病历中心真实写入回归前置执行');
    await dialog.getByRole('button', { name: '保存医嘱草稿', exact: true }).click();
  });
  const orderId = created.payload.order_id;
  if (!orderId) throw new Error(`${reportType} 前置医嘱响应缺少 order_id`);
  let order = page.locator('.order-card').filter({ hasText: orderId.slice(-8) });
  await order.waitFor({ state: 'visible' });
  const signed = await submitWrite(`seed-${type}-order-sign`, `/api/v1/orders/${orderId}/sign`, 'POST',
    () => order.getByRole('button', { name: '安全预检并签署生效', exact: true }).click());
  const taskId = signed.payload.execution_tasks?.[0]?.execution_task_id;
  const orderItemId = signed.payload.execution_tasks?.[0]?.order_item_id;
  if (!taskId) throw new Error(`${reportType} 签署医嘱未生成执行任务`);
  if (!orderItemId) throw new Error(`${reportType} 执行任务缺少 order_item_id`);

  if (reportType === 'LAB') {
    await switchUser('ruifeng.cao');
    await selectExecutionPatient('lab-workbench', created.payload.encounter_id);
    const specimen = await submitWrite('seed-lab-specimen-create', '/api/v1/lab-specimens', 'POST', async () => {
      await page.getByRole('button', { name: '新增标本申请', exact: true }).click();
      const specimenDialog = page.getByRole('dialog').filter({ hasText: '新增标本申请' });
      await specimenDialog.getByLabel('检验医嘱项目').selectOption(orderItemId);
      await specimenDialog.getByRole('button', { name: '创建标本申请', exact: true }).click();
    });
    const specimenId = specimen.payload.specimen_id;
    let specimenRow = page.locator('.admin-table tbody tr').filter({ hasText: specimenId.slice(-8) });
    await submitWrite('seed-lab-specimen-collect', `/api/v1/lab-specimens/${specimenId}/collections`, 'POST',
      () => specimenRow.getByRole('button', { name: '采集', exact: true }).click());
    specimenRow = page.locator('.admin-table tbody tr').filter({ hasText: specimenId.slice(-8) });
    await submitWrite('seed-lab-specimen-receive', `/api/v1/lab-specimens/${specimenId}/receptions`, 'POST',
      () => specimenRow.getByRole('button', { name: '接收', exact: true }).click());
    specimenRow = page.locator('.admin-table tbody tr').filter({ hasText: specimenId.slice(-8) });
    await submitWrite('seed-lab-execution-complete', `/api/v1/executions/${taskId}/events`, 'POST', async () => {
      await specimenRow.getByRole('button', { name: '完成检验', exact: true }).click();
      await page.getByRole('dialog').filter({ hasText: '确认检验执行完成' })
        .getByRole('button', { name: '确认完成检验执行', exact: true }).click();
    });
  } else {
    await switchUser('chengyu.xie');
    await selectExecutionPatient('imaging-workbench', created.payload.encounter_id);
    const taskRow = page.locator('.admin-table tbody tr').filter({ hasText: taskId.slice(-8) });
    await submitWrite('seed-imaging-execution-complete', `/api/v1/executions/${taskId}/events`, 'POST', async () => {
      await taskRow.getByRole('button', { name: '完成影像执行', exact: true }).click();
      await page.getByRole('dialog').filter({ hasText: '确认影像执行完成' })
        .getByRole('button', { name: '确认完成影像执行', exact: true }).click();
    });
  }
  return taskId;
}

async function ensureResultType(reportType, documentId) {
  const type = reportType.toLowerCase();
  const taskId = await createCompletedExecution(reportType);
  await navigateHash('opd-results');
  await page.getByRole('button', { name: '录入结果', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: '录入已审核结果' });
  const execution = dialog.getByLabel('已完成执行');
  await page.waitForFunction((expectedTaskId) => Array.from(document.querySelectorAll('select option'))
    .some((item) => item.value === expectedTaskId || item.textContent?.includes(expectedTaskId.slice(-8))), taskId, { timeout: 15_000 });
  const candidate = (await execution.locator('option').evaluateAll((items) => items.map((item) => ({ value: item.value, text: item.textContent })))).find(
    (item) => item.value === taskId || item.text?.includes(taskId.slice(-8)),
  );
  if (!candidate?.value) throw new Error(`找不到 ${reportType} 前置执行任务 …${taskId.slice(-8)}`);
  await execution.selectOption(candidate.value);
  const conclusion = `${runId} ${reportType} 端到端报告`;
  await dialog.getByLabel('报告结论').fill(conclusion);
  const created = await submitWrite(`seed-${type}-result-create`, '/api/v1/results', 'POST',
    () => dialog.getByRole('button', { name: '签发结果 v1', exact: true }).click());
  if (created.payload.report_type !== reportType) throw new Error(`期望 ${reportType} 报告，实际为 ${created.payload.report_type}`);
  await switchUser(username);
  await navigateHash('record');
  await page.getByPlaceholder('患者、就诊号、病历类型、作者或科室').fill(documentId);
  const documentRow = page.locator('.record-prototype-table-wrap tbody tr').filter({ hasText: documentId.slice(-8) });
  await documentRow.waitFor({ state: 'visible', timeout: 60_000 });
  await documentRow.getByRole('button', { name: '处理', exact: true }).click();
  await waitRoute('record-editor');
  return { conclusion, resultId: created.payload.result_id };
}

async function fillRecordDialog(dialog, suffix) {
  await dialog.getByLabel('主诉', { exact: true }).fill(`${runId} 主诉 ${suffix}`);
  await dialog.getByLabel('现病史', { exact: true }).fill(`${runId} 现病史完整 ${suffix}`);
  await dialog.getByLabel('既往史', { exact: true }).fill(`${runId} 既往史 ${suffix}`);
  await dialog.getByLabel('过敏史', { exact: true }).fill('否认已知药物过敏');
  await dialog.getByLabel('体格检查', { exact: true }).fill(`${runId} 体格检查 ${suffix}`);
  await dialog.getByLabel('辅助检查', { exact: true }).fill(`${runId} 辅助检查待引用来源 ${suffix}`);
  await dialog.getByLabel('诊断与评估', { exact: true }).fill(`${runId} 评估完整 ${suffix}`);
  await dialog.getByLabel('治疗计划', { exact: true }).fill(`${runId} 治疗计划完整 ${suffix}`);
  await dialog.getByLabel('复诊与随访计划', { exact: true }).fill(`${runId} 随访计划 ${suffix}`);
}

async function firstEnabled(locator) {
  for (let index = 0; index < await locator.count(); index += 1) {
    const candidate = locator.nth(index);
    if (await candidate.isVisible() && !(await candidate.isDisabled())) return candidate;
  }
  throw new Error('没有找到可用操作按钮');
}

try {
  await login();

  const created = await submitWrite('record-create', '/api/v1/documents', 'POST', async () => {
    await page.getByPlaceholder('患者、就诊号、病历类型、作者或科室').fill(primaryEncounterId);
    await (await firstEnabled(page.getByRole('button', { name: '同次新建', exact: true }))).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '同次就诊新建病历' });
    await dialog.getByLabel('文书类型编码').fill(primaryType);
    await fillRecordDialog(dialog, '初始');
    await dialog.getByRole('button', { name: '新建并纳入流程', exact: true }).click();
  });
  const documentId = created.payload.document_id;
  if (!documentId) throw new Error('新建文书响应缺少 document_id');
  await page.getByPlaceholder('患者、就诊号、病历类型、作者或科室').fill(documentId);
  const row = page.locator('.record-prototype-table-wrap tbody tr').filter({ hasText: documentId.slice(-8) });
  await row.waitFor({ state: 'visible' });

  await submitWrite('record-edit', `/api/v1/documents/${documentId}/draft`, 'PUT', async () => {
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '保存会追加新的不可变草稿版本' });
    await dialog.getByLabel('诊断与评估', { exact: true }).fill(`${runId} 工作台编辑后的完整评估`);
    await dialog.getByRole('button', { name: '保存新版本', exact: true }).click();
  });

  await row.getByRole('button', { name: '处理', exact: true }).click();
  await waitRoute('record-editor');
  const chiefComplaint = page.locator('.document-sheet textarea').first();
  await chiefComplaint.fill(`${runId} 编辑器再次修改的主诉`);
  await submitWrite('editor-save', `/api/v1/documents/${documentId}/draft`, 'PUT', async () => {
    await page.getByRole('button', { name: '保存新版本', exact: true }).click();
    await page.getByRole('dialog').filter({ hasText: '保存病历编辑' })
      .getByRole('button', { name: '确认保存新版本', exact: true }).click();
  });
  await submitWrite('editor-quality-check', `/api/v1/documents/${documentId}/quality-checks`, 'POST',
    () => page.getByRole('button', { name: '签署前检查', exact: true }).click());

  await navigate('来源证据', 'record-sources');
  const attachmentCreated = await submitWrite('attachment-create', `/api/v1/documents/${documentId}/attachments`, 'POST', async () => {
    await page.getByRole('button', { name: '新建附件证据', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建附件证据' });
    await dialog.locator('input[type=file]').setInputFiles({
      name: `${runId}-original.txt`, mimeType: 'text/plain', buffer: Buffer.from(`${runId} original attachment`),
    });
    await dialog.getByRole('button', { name: '校验并写入证据', exact: true }).click();
  });
  const originalAttachmentId = attachmentCreated.payload.attachment_id;

  await submitWrite('attachment-replace', `/api/v1/documents/${documentId}/attachments`, 'POST', async () => {
    const attachment = page.locator('.record-attachment-list article').filter({ hasText: `${runId}-original.txt` });
    await attachment.getByRole('button', { name: '替换', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '替换附件证据' });
    await dialog.locator('input[type=file]').setInputFiles({
      name: `${runId}-replacement.txt`, mimeType: 'text/plain', buffer: Buffer.from(`${runId} replacement attachment`),
    });
    await dialog.getByLabel('替换原因（至少 4 字）').fill('端到端回归验证附件替换生命周期');
    await dialog.getByRole('button', { name: '上传替换并留痕', exact: true }).click();
  });
  const originalAfterReplace = page.locator('.record-attachment-list article').filter({ hasText: `${runId}-original.txt` });
  await originalAfterReplace.getByText('SUPERSEDED', { exact: false }).waitFor();
  if (!originalAttachmentId) throw new Error('附件响应缺少 attachment_id');

  const replacement = page.locator('.record-attachment-list article').filter({ hasText: `${runId}-replacement.txt` });
  await submitWrite('attachment-void', '/voids', 'POST', async () => {
    await replacement.getByRole('button', { name: '作废', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '作废附件证据' });
    await dialog.getByLabel('作废原因（至少 4 字）').fill('端到端回归验证附件业务作废');
    await dialog.getByRole('button', { name: '确认作废并留痕', exact: true }).click();
  });
  await replacement.getByText('VOID', { exact: false }).waitFor();

  const labResult = await ensureResultType('LAB', documentId);
  await ensureSessionFresh();
  await navigateHash('lis-report');
  await submitWrite('lis-reference-create', `/api/v1/documents/${documentId}/source-references`, 'POST', async () => {
    await page.getByRole('button', { name: '引用到病历', exact: true }).click();
    await page.getByRole('dialog').filter({ hasText: '引用 LIS 报告到病历' })
      .getByRole('button', { name: '确认并固化引用', exact: true }).click();
  });

  const imagingResult = await ensureResultType('IMAGING', documentId);
  await ensureSessionFresh();
  await navigateHash('pacs-viewer');
  await submitWrite('pacs-reference-create', `/api/v1/documents/${documentId}/source-references`, 'POST', async () => {
    await page.getByRole('button', { name: '引用关键结论', exact: true }).click();
    await page.getByRole('dialog').filter({ hasText: '引用 PACS 报告到病历' })
      .getByRole('button', { name: '确认并固化报告引用', exact: true }).click();
  });

  await navigate('来源证据', 'record-sources');
  const correctedReference = {
    row: page.locator('table tbody tr').filter({ hasText: labResult.conclusion }),
  };
  await submitWrite('source-reference-correct', '/corrections', 'POST', async () => {
    await correctedReference.row.getByRole('button', { name: '更正', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '更正来源引用' });
    await dialog.getByLabel('有效目标字段').selectOption('sections.treatment_plan');
    await dialog.getByLabel('更正后的引用摘要').fill(`${runId} 更正后的来源摘要`);
    await dialog.getByLabel('更正原因（至少 4 字）').fill('端到端回归验证来源目标字段更正');
    await dialog.getByRole('button', { name: '追加更正证据', exact: true }).click();
  });
  await correctedReference.row.getByText('CORRECTED', { exact: false }).waitFor();

  const revokedReference = {
    row: page.locator('table tbody tr').filter({ hasText: imagingResult.conclusion }),
  };
  await submitWrite('source-reference-revoke', '/revocations', 'POST', async () => {
    await revokedReference.row.getByRole('button', { name: '撤销', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '撤销来源引用' });
    await dialog.getByLabel('撤销原因（至少 4 字）').fill('端到端回归验证来源引用撤销');
    await dialog.getByRole('button', { name: '确认撤销并留痕', exact: true }).click();
  });
  await revokedReference.row.getByText('REVOKED', { exact: false }).waitFor();

  await navigate('专注编辑', 'record-editor');
  await ensureSessionFresh();
  await submitWrite('editor-quality-after-source-lifecycle', `/api/v1/documents/${documentId}/quality-checks`, 'POST',
    () => page.getByRole('button', { name: '签署前检查', exact: true }).click());
  await navigate('查看完整质控与签名证据', 'record-qc');
  await submitWrite('governance-quality-check', `/api/v1/documents/${documentId}/quality-checks`, 'POST',
    () => page.getByRole('button', { name: '运行确定性质控', exact: true }).click());
  await navigate('专注编辑', 'record-editor');
  await submitWrite('editor-sign', `/api/v1/documents/${documentId}/signatures`, 'POST', async () => {
    await page.getByRole('button', { name: '提交审签', exact: true }).click();
    await page.getByRole('dialog').filter({ hasText: '签署当前不可变版本' })
      .getByRole('button', { name: '确认签署并留存证据', exact: true }).click();
  });

  await check('editor-sign-readback-ready', async () => {
    await page.getByText('已签署只读', { exact: false }).waitFor();
    await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
      .some(button => button.textContent?.trim() === '签署前检查' && !button.disabled));
    return { signedReadbackCompleted: true };
  });
  await navigate('版本证据', 'record-versions');
  await submitWrite('signature-verification-run', `/api/v1/documents/${documentId}/signature-verifications`, 'POST',
    () => page.getByRole('button', { name: '批量验签', exact: true }).click());
  await page.waitForFunction(() => {
    const button = Array.from(document.querySelectorAll('button'))
      .find((item) => item.textContent?.trim() === '批量验签');
    return button && !button.disabled;
  }, undefined, { timeout: 30_000 });
  await submitWrite('version-correction-create', `/api/v1/documents/${documentId}/corrections`, 'POST', async () => {
    await page.getByRole('button', { name: '发起依法更正', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建依法更正 / 补记' });
    await dialog.getByLabel('更正/补记原因（至少 4 字）').fill('端到端回归验证已签病历依法更正');
    await dialog.getByLabel('评估').fill(`${runId} 依法更正后的评估`);
    await dialog.getByLabel('诊疗计划').fill(`${runId} 依法更正后的诊疗计划`);
    await dialog.getByRole('button', { name: '创建更正草稿', exact: true }).click();
  });
  await page.getByRole('dialog').filter({ hasText: '新建依法更正 / 补记' }).waitFor({ state: 'hidden', timeout: 30_000 });

  await navigate('专注编辑', 'record-editor');
  await submitWrite('correction-quality-check', `/api/v1/documents/${documentId}/quality-checks`, 'POST',
    () => page.getByRole('button', { name: '签署前检查', exact: true }).click());
  await navigate('查看完整质控与签名证据', 'record-qc');
  await submitWrite('governance-sign-correction', `/api/v1/documents/${documentId}/signatures`, 'POST', async () => {
    await page.getByRole('button', { name: '预览最终文书并签署', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '确认签署当前不可变版本' });
    await dialog.getByLabel('警告处置说明').fill('端到端回归已核对全部质控警告');
    await dialog.getByRole('button', { name: '确认签署并留存证据', exact: true }).click();
  });

  await navigate('版本证据', 'record-versions');
  await page.locator('.record-real-versions .version-row').first().waitFor({ state: 'visible', timeout: 60_000 });
  const retryButton = page.getByRole('button', { name: '重试传播', exact: true });
  if (await retryButton.count()) {
    await submitWrite('correction-propagation-retry', '/retry', 'POST', () => retryButton.first().click());
  }
  await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
    .some((button) => button.textContent?.trim() === '撤销签名' && !button.disabled), undefined, { timeout: 30_000 });
  await submitWrite('signature-revoke', `/api/v1/documents/${documentId}/signature-revocations`, 'POST', async () => {
    await (await firstEnabled(page.getByRole('button', { name: '撤销签名', exact: true }))).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '确认撤销并留痕' });
    await dialog.getByLabel('撤销原因（至少 4 字）').fill('端到端回归验证签名撤销证据');
    await dialog.getByRole('button', { name: '确认撤销并留痕', exact: true }).click();
  });

  const compare = page.getByRole('link', { name: '比较最近两个版本', exact: true });
  await compare.click();
  await waitRoute('record-diff');
  await check('version-diff-readback', async () => {
    await page.getByRole('heading', { name: '病历版本差异', exact: true }).waitFor();
    return { changedRows: await page.locator('.record-diff-row').count() };
  });

  await navigateHash('record');
  const voidCreated = await submitWrite('record-create-for-void', '/api/v1/documents', 'POST', async () => {
    await page.getByPlaceholder('患者、就诊号、病历类型、作者或科室').fill(primaryEncounterId);
    await (await firstEnabled(page.getByRole('button', { name: '同次新建', exact: true }))).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '同次就诊新建病历' });
    await dialog.getByLabel('文书类型编码').fill(voidType);
    await fillRecordDialog(dialog, '作废场景');
    await dialog.getByRole('button', { name: '新建并纳入流程', exact: true }).click();
  });
  const voidDocumentId = voidCreated.payload.document_id;
  await page.getByPlaceholder('患者、就诊号、病历类型、作者或科室').fill(voidDocumentId);
  const voidRow = page.locator('.record-prototype-table-wrap tbody tr').filter({ hasText: voidDocumentId.slice(-8) });
  await submitWrite('record-void', `/api/v1/documents/${voidDocumentId}/voids`, 'POST', async () => {
    await voidRow.getByRole('button', { name: '作废', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '作废病历草稿' });
    await dialog.getByLabel('作废原因（至少 4 字）').fill('端到端回归验证病历业务作废');
    await dialog.getByRole('button', { name: '确认作废并留痕', exact: true }).click();
  });
  // The status cell renders `已作废` alongside a signature-evidence label, so it is
  // not an exact-text node; assert the voided status appears within the row instead.
  await voidRow.getByText('已作废', { exact: false }).waitFor();

  await page.screenshot({ path: resolve(outputDir, `${runId}-final.png`), fullPage: true });
} catch (error) {
  const pageState = await page.evaluate(() => ({
    href: window.location.href,
    routeId: document.documentElement.dataset.routeId ?? null,
    body: document.body.innerText.slice(0, 2000),
  })).catch(() => null);
  findings.push({ route: currentRoute, check: 'page-state', detail: JSON.stringify(pageState) });
  await page.screenshot({ path: resolve(outputDir, `${runId}-failure.png`), fullPage: true }).catch(() => undefined);
  findings.push({ route: currentRoute, check: 'unhandled', detail: error instanceof Error ? error.stack || error.message : String(error) });
} finally {
  await browser.close();
}

const result = {
  run_at: new Date().toISOString(), run_id: runId, routes: [...new Set(checks.map((item) => item.route))],
  checks: checks.length + findings.length, passed: checks.length, failed: findings.length,
  observations: checks, findings,
};
const outputPath = resolve(outputDir, 'record-center-write-regression.json');
await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ runId, checks: result.checks, passed: result.passed, failed: result.failed, artifact: outputPath }));
if (findings.length) process.exitCode = 1;
