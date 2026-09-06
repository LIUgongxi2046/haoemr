import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/clinical-task-automatic-lifecycle');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const author = 'linwei';
const approver = 'guoqiang.xiao';
const stamp = Date.now().toString();
const configKey = `e2e-treatment-task-sla-${stamp}`;
const orderCode = `E2E-TASK-LIFECYCLE-${stamp}`;
const indication = `E2E 自动任务生命周期 ${stamp}`;
const result = { run_at: new Date().toISOString(), config_key: configKey, checks: [], findings: [], evidence: {} };
let configId = '';

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined,
});
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
async function switchUser(username) {
  await page.getByRole('button', { name: '用户登录与账户', exact: true }).click();
  await page.getByRole('button', { name: '退出系统', exact: true }).click();
  await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 20_000 });
  await login(username);
}
async function route(hash, heading) {
  await page.goto(`${baseUrl}/#/${hash}`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: heading, exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
}
async function post(path, action) {
  const waiting = page.waitForResponse((response) => response.url().includes(path)
    && response.request().method() === 'POST', { timeout: 30_000 });
  await action();
  const response = await waiting;
  const body = await response.text();
  if (!response.ok()) throw new Error(`POST ${path} -> ${response.status()} ${body.slice(0, 500)}`);
  try { return JSON.parse(body); } catch { return {}; }
}
function ruleCard() { return page.locator('.config-card-grid article').filter({ hasText: configKey }).first(); }

async function archiveRule() {
  try {
    await route('clinical-tasks?view=rules', '任务中心');
    if (configId) await ruleCard().waitFor({ state: 'visible', timeout: 30_000 });
    const isolatedRules = page.locator('.config-card-grid article').filter({ hasText: 'e2e-treatment-task-sla-' });
    const archived = [];
    while (await isolatedRules.count()) {
      const row = isolatedRules.first();
      await row.getByRole('button', { name: '删除·停用', exact: true }).click();
      const dialog = page.getByRole('dialog').filter({ hasText: '停用任务规则' });
      const response = await post('/lifecycle', () => dialog.getByRole('button', { name: '确认停用', exact: true }).click());
      if (response.status !== 'ARCHIVED') throw new Error(`隔离规则未归档：${JSON.stringify(response)}`);
      archived.push({ config_id: response.config_id, row_version: response.row_version });
    }
    if (archived.length) result.evidence.archived = archived;
  } catch (error) {
    result.findings.push({ name: 'cleanup-isolated-rule', detail: error instanceof Error ? error.message : String(error), url: page.url() });
  }
}

try {
  await check('author-creates-valid-short-sla-rule-and-submits-for-independent-approval', async () => {
    await login(author);
    await route('clinical-tasks?view=rules', '任务中心');
    await page.getByRole('button', { name: '新建任务规则', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建任务规则' });
    await dialog.getByLabel('配置键', { exact: true }).fill(configKey);
    await dialog.getByLabel('显示名称', { exact: true }).fill(`E2E 治疗执行自动时限 ${stamp}`);
    await dialog.getByLabel('任务类型', { exact: true }).fill('TREATMENT_EXECUTION');
    await dialog.locator('select').first().selectOption('HIGH');
    await dialog.getByLabel('处理时限（分钟）', { exact: true }).fill('2');
    await dialog.getByLabel('升级提前量（分钟）', { exact: true }).fill('1');
    const draft = await post('/configurations', () => dialog.getByRole('button', { name: '保存草案', exact: true }).click());
    configId = draft.config_id;
    if (draft.status !== 'DRAFT' || draft.payload?.task_type !== 'TREATMENT_EXECUTION') throw new Error(`草案未按预期保存：${JSON.stringify(draft)}`);
    let row = ruleCard();
    await row.waitFor({ state: 'visible', timeout: 30_000 });
    const validated = await post(`/configurations/${configId}/lifecycle`, () => row.getByRole('button', { name: '校验', exact: true }).click());
    if (validated.validation_state !== 'VALID') throw new Error(`任务规则静态校验失败：${JSON.stringify(validated.validation_errors)}`);
    row = ruleCard();
    const pending = await post(`/configurations/${configId}/lifecycle`, () => row.getByRole('button', { name: '提交审批', exact: true }).click());
    if (pending.status !== 'PENDING_APPROVAL') throw new Error(`规则未进入独立审批：${JSON.stringify(pending)}`);
    result.evidence.draft = { config_id: configId, validation_state: validated.validation_state, pending_status: pending.status };
    return '页面创建 2 分钟到期、1 分钟升级的治疗执行规则，并提交独立审批。';
  });

  await check('independent-approver-publishes-rule-then-a-real-signed-order-freezes-it-on-task', async () => {
    await switchUser(approver);
    await route('clinical-tasks?view=rules', '任务中心');
    let row = ruleCard();
    await row.waitFor({ state: 'visible', timeout: 30_000 });
    const approved = await post(`/configurations/${configId}/lifecycle`, () => row.getByRole('button', { name: '独立批准', exact: true }).click());
    if (approved.status !== 'APPROVED') throw new Error(`独立批准失败：${JSON.stringify(approved)}`);
    row = ruleCard();
    const active = await post(`/configurations/${configId}/lifecycle`, () => row.getByRole('button', { name: '发布', exact: true }).click());
    if (active.status !== 'ACTIVE') throw new Error(`规则发布失败：${JSON.stringify(active)}`);
    await switchUser(author);
    await route('opd-orders', '门诊医嘱与处方');
    await page.getByRole('button', { name: '新增医嘱', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建医嘱草稿' });
    await dialog.locator('select').nth(1).selectOption('TREATMENT');
    const inputs = dialog.locator('input');
    await inputs.nth(0).fill(orderCode);
    await inputs.nth(1).fill(`E2E 治疗执行 ${stamp}`);
    await inputs.nth(2).fill('1');
    await inputs.nth(3).fill('次');
    const textareas = dialog.locator('textarea');
    await textareas.nth(0).fill(indication);
    await textareas.nth(1).fill('隔离环境自动任务生命周期验收；不执行临床治疗。');
    const order = await post('/orders', () => dialog.getByRole('button', { name: '保存医嘱草稿', exact: true }).click());
    const card = page.locator('.order-card').filter({ hasText: indication }).first();
    await card.waitFor({ state: 'visible', timeout: 30_000 });
    const signed = await post(`/orders/${order.order_id}/sign`, () => card.getByRole('button', { name: '安全预检并签署生效', exact: true }).click());
    const executionTaskId = signed.execution_tasks?.[0]?.execution_task_id;
    if (!executionTaskId) throw new Error(`签署医嘱缺少执行任务：${JSON.stringify(signed)}`);
    result.evidence.published = { status: active.status, row_version: active.row_version };
    result.evidence.order = { order_id: order.order_id, execution_task_id: executionTaskId };
    return `独立审批发布后，真实门诊治疗医嘱签署生成执行任务 ${executionTaskId}。`;
  });

  await check('scheduler-escalates-and-expires-without-a-browser-command-and-history-remains-readable', async () => {
    const executionTaskId = result.evidence.order?.execution_task_id;
    if (!executionTaskId) throw new Error('前置真实医嘱签署没有成功');
    await route('clinical-tasks?view=overview&mode=outpatient', '任务中心');
    const row = page.locator('.task-center-table tbody tr').filter({ hasText: executionTaskId.slice(-8) }).first();
    await row.waitFor({ state: 'visible', timeout: 30_000 });
    const refresh = page.getByRole('button', { name: '筛选并刷新', exact: true });
    const waitForState = async (text, timeout) => {
      const deadline = Date.now() + timeout;
      while (Date.now() < deadline) {
        if (await row.getByText(text, { exact: true }).count()) return;
        await page.waitForTimeout(5_000);
        await refresh.click();
      }
      throw new Error(`自动任务在 ${timeout / 1000} 秒内未进入 ${text}`);
    };
    await waitForState('已升级', 110_000);
    const escalatedText = await row.innerText();
    await page.getByRole('checkbox', { name: '显示已过期历史', exact: true }).check();
    await waitForState('已过期', 110_000);
    const expiredText = await row.innerText();
    await row.getByRole('button', { name: '查看', exact: true }).click();
    await page.getByText('任务状态', { exact: true }).waitFor({ state: 'visible', timeout: 20_000 });
    result.evidence.lifecycle = { escalated: escalatedText.includes('已升级'), expired: expiredText.includes('已过期') };
    return '后台调度器自动写入 ESCALATED 后再写入 EXPIRED；页面切换只读历史后仍可查看来源和审计证据。';
  });
} finally {
  await login(approver).catch(() => undefined);
  await archiveRule();
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'clinical-task-automatic-lifecycle.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
