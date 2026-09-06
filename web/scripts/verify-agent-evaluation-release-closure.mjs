import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/agent-evaluation-release-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const author = 'linwei';
const approver = 'guoqiang.xiao';
const stamp = Date.now().toString();
const configKey = `e2e-agent-eval-${stamp}`;
const displayName = `E2E 医助评测发布 ${stamp}`;
const targetAgent = `E2E_AGENT_EVAL_${stamp}`;
const result = { run_at: new Date().toISOString(), config_key: configKey, target_agent: targetAgent, checks: [], findings: [], evidence: {} };

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
  // This administrator is intentionally outside the inpatient ward scope. The shared shell
  // preloads these read-only widgets while the assessment page itself remains available.
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
async function route(routeId) {
  await page.goto(`${baseUrl}/#/${routeId}`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, routeId, { timeout: 30_000 });
  await page.locator('main h1').waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('.ai-center-config-ledger tbody tr').first().waitFor({ state: 'visible', timeout: 30_000 });
}
async function apiResponse(path, action) {
  const responsePromise = page.waitForResponse((response) => response.url().includes(path)
    && response.request().method() === 'POST', { timeout: 30_000 });
  await action();
  const response = await responsePromise;
  const text = await response.text();
  let payload;
  try { payload = JSON.parse(text); } catch { payload = null; }
  if (!response.ok()) throw new Error(`POST ${path} -> ${response.status()} ${text.slice(0, 500)}`);
  return payload;
}

try {
  await check('author-creates-valid-evaluation-submits-it-and-cannot-self-approve', async () => {
    await login(author);
    await route('agent-evals');
    await page.getByRole('button', { name: '新建草稿', exact: true }).click();
    const editor = page.locator('dialog.admin-action-dialog[open]');
    await editor.waitFor({ state: 'visible', timeout: 30_000 });
    await editor.getByLabel('显示名称').fill(displayName);
    await editor.getByLabel('配置编码（系统唯一）').fill(configKey);
    await editor.getByLabel('说明').fill('隔离开发库的医助评测发布链路验收；目标编码不属于任何已登记医助。');
    await editor.getByLabel(/目标医助编码/).fill(targetAgent);
    await editor.getByLabel(/临床用例集版本/).fill(`e2e-golden-${stamp}`);
    await editor.getByLabel(/用例数/).fill('120');
    await editor.getByLabel(/通过标准/).fill('0.95');
    await editor.getByLabel(/实测分值/).fill('0.97');
    await editor.getByLabel(/发布门禁/).selectOption('PASSED');
    await editor.getByLabel(/对抗测试方案/).fill('越权诱导、提示词注入、未确认临床操作与敏感数据泄露。');
    const draft = await apiResponse('/configurations', () => editor.getByRole('button', { name: '创建版本化草稿', exact: true }).click());
    if (draft.status !== 'DRAFT' || draft.payload?.target_agent !== targetAgent) throw new Error(`草稿字段未正确保存：${JSON.stringify(draft)}`);
    const row = page.locator('.ai-center-config-ledger tbody tr').filter({ hasText: configKey });
    await row.waitFor({ state: 'visible', timeout: 30_000 });
    await row.getByRole('button', { name: '编辑 / 版本管理', exact: true }).click();
    const lifecycle = page.getByRole('dialog').filter({ hasText: '校验、审批、发布与回退' });
    const selfApprove = lifecycle.getByRole('button', { name: '职责分离批准', exact: true });
    if (!(await selfApprove.isDisabled())) throw new Error('配置作者仍可从界面批准自己的评测');
    await lifecycle.getByLabel('操作原因').fill('验证通过后提交独立审批并保留评测证据');
    const validated = await apiResponse(`/configurations/${draft.config_id}/lifecycle`, () => lifecycle.getByRole('button', { name: '执行静态校验', exact: true }).click());
    if (validated.validation_state !== 'VALID') throw new Error(`有效评测未通过静态校验：${JSON.stringify(validated.validation_errors)}`);
    const pending = await apiResponse(`/configurations/${draft.config_id}/lifecycle`, () => lifecycle.getByRole('button', { name: '提交审批', exact: true }).click());
    if (pending.status !== 'PENDING_APPROVAL' || pending.approval_state !== 'PENDING') throw new Error(`评测未进入待独立审批：${JSON.stringify(pending)}`);
    result.evidence.config_id = draft.config_id;
    result.evidence.author_draft = { status: draft.status, validation_state: validated.validation_state, pending_status: pending.status };
    await editor.getByRole('button', { name: '关闭弹窗', exact: true }).click();
    await editor.waitFor({ state: 'hidden', timeout: 10_000 });
    return `作者创建并提交 ${configKey}；目标为未登记的 ${targetAgent}，不会影响现有医助运行`;
  });

  await check('independent-approver-approves-and-publishes-the-passed-evaluation', async () => {
    await switchUser(approver);
    await route('agent-evals');
    const row = page.locator('.ai-center-config-ledger tbody tr').filter({ hasText: configKey });
    await row.waitFor({ state: 'visible', timeout: 30_000 });
    await row.getByRole('button', { name: '编辑 / 版本管理', exact: true }).click();
    const lifecycle = page.getByRole('dialog').filter({ hasText: '校验、审批、发布与回退' });
    await lifecycle.getByLabel('操作原因').fill('独立审批人核对通过分值、门禁和对抗测试证据');
    const approved = await apiResponse(`/configurations/${result.evidence.config_id}/lifecycle`, () => lifecycle.getByRole('button', { name: '职责分离批准', exact: true }).click());
    if (approved.status !== 'APPROVED' || approved.approval_state !== 'APPROVED') throw new Error(`独立批准未完成：${JSON.stringify(approved)}`);
    const active = await apiResponse(`/configurations/${result.evidence.config_id}/lifecycle`, () => lifecycle.getByRole('button', { name: '发布', exact: true }).click());
    if (active.status !== 'ACTIVE' || active.validation_state !== 'VALID' || active.payload?.release_gate !== 'PASSED') {
      throw new Error(`通过评测未成为有效配置：${JSON.stringify(active)}`);
    }
    if (active.payload?.target_agent !== targetAgent || active.payload?.measured_score < active.payload?.pass_threshold) {
      throw new Error(`有效评测的门禁字段不一致：${JSON.stringify(active.payload)}`);
    }
    result.evidence.published = { status: active.status, approval_state: active.approval_state, row_version: active.row_version };
    return `独立审批后发布为 ACTIVE（v${active.row_version}）；仅写入隔离配置台账，未触发模型请求`;
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'agent-evaluation-release-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
