import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/specialty-execution-cross-role');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const creator = process.env.HAOEMR_EXECUTION_CREATOR || 'linwei';
const reviewer = process.env.HAOEMR_EXECUTION_REVIEWER || 'jiahui.xu';
const pathologyPatientId = '018f0000-0000-7000-8000-000000000001';
const pathologySpecimenId = 'df93edb6-f62e-4070-8ca9-4dae8a7ce607';
const anesthesiaPatientId = '018f0000-0000-7000-8000-000000000002';
const anesthesiaProcedureId = '57a998af-a43e-5177-83b8-1b733c19a4b6';
const runId = Date.now();
const pathologyTitle = `E2E 病理真实标本 ${runId}`;
const anesthesiaTitle = `E2E 麻醉真实手术 ${runId}`;
const therapyOrderCode = `E2E-THERAPY-${runId}`;
const therapyTitle = `E2E 治疗医嘱回写 ${runId}`;
const result = { run_at: new Date().toISOString(), checks: [], findings: [] };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

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
  await page.getByRole('button', { name: '用户登录与账户', exact: true }).click();
  await page.getByRole('button', { name: '退出系统', exact: true }).click();
  await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 20_000 });
}

async function openWorkbench(route, patientId) {
  await page.goto(`${baseUrl}/#/${route}`, { waitUntil: 'domcontentloaded' });
  const queue = page.locator('[data-execution-patient-list]');
  await queue.waitFor({ state: 'visible', timeout: 30_000 });
  await queue.getByLabel('患者检索', { exact: true }).fill(patientId);
  const row = queue.locator('[data-execution-patient-row]').filter({ hasText: patientId }).first();
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  await row.getByRole('button', { name: '选择患者并下转', exact: true }).click();
  await page.locator('[data-execution-patient-detail]').waitFor({ state: 'visible', timeout: 30_000 });
}

async function createPathology() {
  await page.getByRole('button', { name: '新建病理病例', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: '新建病理病例' });
  await dialog.getByLabel('业务标题', { exact: true }).fill(pathologyTitle);
  const fields = {
    '患者标识一': 'MZ-E2E-001', '患者标识二': '1978-04-16', '双标识核对方式': '门诊号加出生日期',
    '病理号': `BL-E2E-${runId}`, '关联组织标本 ID': pathologySpecimenId, '标本类型': '手术切除标本',
    '取材部位': '左肺上叶', '固定液与固定时间': '10%中性福尔马林固定12小时',
  };
  for (const [label, value] of Object.entries(fields)) await dialog.getByLabel(label, { exact: true }).fill(value);
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click();
  return caseRow(pathologyTitle);
}

async function createAnesthesia() {
  await page.getByRole('button', { name: '新建麻醉病例', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: '新建麻醉病例' });
  await dialog.getByLabel('业务标题', { exact: true }).fill(anesthesiaTitle);
  const fields = {
    '患者标识一': 'ZY-E2E-002', '患者标识二': 'WB-OR-E2E-002', '双标识核对方式': '住院号加腕带扫码',
    '关联手术申请/排程号': anesthesiaProcedureId, 'ASA 分级': 'ASA II', '麻醉方式': '全身麻醉',
    '术中监护与异常处置': '连续监测心电、血压与血氧，无异常。', '复苏评分与去向': 'Aldrete 9分，转回病房。',
  };
  for (const [label, value] of Object.entries(fields)) await dialog.getByLabel(label, { exact: true }).fill(value);
  await dialog.getByLabel('禁食禁饮确认', { exact: true }).check();
  await dialog.getByLabel('麻醉知情同意确认', { exact: true }).check();
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click();
  return caseRow(anesthesiaTitle);
}

async function createSignedTherapyOrder() {
  await page.goto(`${baseUrl}/#/opd-orders`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '门诊医嘱与处方', exact: true }).waitFor({ timeout: 30_000 });
  await page.getByRole('button', { name: '新增医嘱', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: '新建医嘱草稿' });
  await dialog.locator('select').nth(1).selectOption('TREATMENT');
  const inputs = dialog.locator('input');
  await inputs.nth(0).fill(therapyOrderCode);
  await inputs.nth(1).fill('术后呼吸康复训练');
  await inputs.nth(2).fill('1');
  await inputs.nth(3).fill('次');
  await dialog.locator('textarea').nth(0).fill('术后呼吸康复治疗，需完成一次受控执行记录。');
  await dialog.locator('textarea').nth(1).fill('执行前核对患者、治疗卡与治疗参数。');
  await dialog.getByRole('button', { name: '保存医嘱草稿', exact: true }).click();
  const order = page.locator('.order-card').filter({ hasText: therapyOrderCode }).first();
  await order.waitFor({ state: 'visible', timeout: 30_000 });
  await order.getByRole('button', { name: '安全预检并签署生效', exact: true }).click();
  const taskId = await order.locator('.execution-task-id').innerText({ timeout: 30_000 });
  const match = taskId.match(/[0-9a-f]{8}-[0-9a-f-]{27,}/i);
  if (!match) throw new Error(`已签署治疗医嘱未展示可绑定的真实执行任务 ID：${taskId}`);
  return match[0];
}

async function createTherapy(taskId) {
  await page.getByRole('button', { name: '新建治疗任务', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: '新建治疗任务' });
  await dialog.getByLabel('业务标题', { exact: true }).fill(therapyTitle);
  const fields = {
    '患者标识一': 'MZ-E2E-001', '患者标识二': '1978-04-16', '双标识核对方式': '门诊号加出生日期',
    '治疗项目编码': therapyOrderCode, '关联治疗医嘱执行任务 ID': taskId, '疗程号': '1',
    '本疗程第几次': '1', '医嘱与项目核对': '医嘱条码和治疗卡双核对',
  };
  for (const [label, value] of Object.entries(fields)) await dialog.getByLabel(label, { exact: true }).fill(value);
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click();
  return caseRow(therapyTitle);
}

async function caseRow(title) {
  const row = page.locator('.case-table-wrap tbody tr').filter({ hasText: title }).first();
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  return row;
}

async function transition(row, name) {
  await row.getByRole('button', { name, exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: name });
  await dialog.getByLabel('操作说明 / 取消原因', { exact: true }).fill(`${name}：已核对患者、就诊与上游业务证据`);
  await dialog.getByRole('button', { name, exact: true }).click();
  const status = name === '标记就绪' ? '待执行' : name === '开始执行' ? '执行中' : name === '提交复核' ? '待复核' : '已完成';
  try {
    await row.getByText(status, { exact: true }).waitFor({ timeout: 30_000 });
  } catch (error) {
    const notice = page.locator('.inline-notice');
    if (await notice.isVisible().catch(() => false)) throw new Error(`状态迁移被拒绝：${await notice.innerText()}`);
    throw error;
  }
}

let pathologyPrepared = false;
let anesthesiaPrepared = false;
try {
  await check('pathology-binds-received-tissue-and-requires-independent-completion', async () => {
    await login(creator);
    await openWorkbench('pathology-workbench', pathologyPatientId);
    let row = await createPathology();
    await transition(row, '标记就绪');
    row = await caseRow(pathologyTitle); await transition(row, '开始执行');
    row = await caseRow(pathologyTitle); await transition(row, '提交复核');
    row = await caseRow(pathologyTitle);
    await row.getByRole('button', { name: '复核完成', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '复核完成' });
    await dialog.getByLabel('操作说明 / 取消原因', { exact: true }).fill('创建人尝试自行完成复核');
    await dialog.getByRole('button', { name: '复核完成', exact: true }).click();
    await page.getByText(/INDEPENDENT_REVIEW_REQUIRED/).waitFor({ timeout: 30_000 });
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    pathologyPrepared = true;
    return '页面新建病例绑定已接收组织标本；创建人无法自行完成病理复核';
  });

  await check('independent-user-completes-pathology-and-keeps-auditable-terminal-state', async () => {
    if (!pathologyPrepared) throw new Error('病理前置流程未完成');
    await logout(); await login(reviewer);
    await openWorkbench('pathology-workbench', pathologyPatientId);
    const row = await caseRow(pathologyTitle);
    await transition(row, '复核完成');
    return '第二名用户完成病理复核，病例进入 COMPLETED 终态';
  });

  await check('anesthesia-binds-timeout-completed-procedure-and-requires-independent-completion', async () => {
    await logout(); await login(creator);
    await openWorkbench('anesthesia-workbench', anesthesiaPatientId);
    let row = await createAnesthesia();
    await transition(row, '标记就绪');
    row = await caseRow(anesthesiaTitle); await transition(row, '开始执行');
    row = await caseRow(anesthesiaTitle); await transition(row, '提交复核');
    anesthesiaPrepared = true;
    return '页面新建麻醉病例绑定已完成 Time-Out 的真实手术并进入待独立复核';
  });

  await check('independent-user-completes-anesthesia-and-writes-back-surgical-terminal-state', async () => {
    if (!anesthesiaPrepared) throw new Error('麻醉前置流程未完成');
    await logout(); await login(reviewer);
    await openWorkbench('anesthesia-workbench', anesthesiaPatientId);
    const row = await caseRow(anesthesiaTitle);
    await transition(row, '复核完成');
    return '第二名用户完成麻醉记录，服务端同步完成关联手术并保留终态事件';
  });

  await check('therapy-order-signing-creates-bindable-task-and-completion-writes-back-once', async () => {
    await logout(); await login(creator);
    const taskId = await createSignedTherapyOrder();
    await openWorkbench('therapy-workbench', anesthesiaPatientId);
    let row = await createTherapy(taskId);
    await transition(row, '标记就绪');
    row = await caseRow(therapyTitle); await transition(row, '开始执行');
    row = await caseRow(therapyTitle); await transition(row, '提交复核');
    row = await caseRow(therapyTitle); await transition(row, '复核完成');
    row = await caseRow(therapyTitle);
    if (await row.getByRole('button', { name: '复核完成', exact: true }).count()) throw new Error('已完成治疗病例仍允许重复完成');
    await page.goto(`${baseUrl}/#/opd-orders`, { waitUntil: 'domcontentloaded' });
    const order = page.locator('.order-card').filter({ hasText: therapyOrderCode }).first();
    await order.waitFor({ state: 'visible', timeout: 30_000 });
    if (await order.locator('.execution-task-id').count() !== 1 || !(await order.innerText()).includes('COMPLETED')) {
      throw new Error('治疗完成未将唯一执行任务回写为 COMPLETED');
    }
    return '页面签署治疗医嘱生成唯一执行任务；治疗病例完成后回写任务 COMPLETED，终态不再允许重复完成';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close(); await browser.close();
}

const artifact = resolve(outputDir, 'specialty-execution-cross-role.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
