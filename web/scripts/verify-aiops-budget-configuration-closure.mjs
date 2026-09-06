import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/aiops-budget-configuration-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const stamp = Date.now().toString();
const result = { run_at: new Date().toISOString(), checks: [], findings: [], evidence: {} };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) {
  try { result.checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); }
}
async function login() {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByLabel('用户名', { exact: true }).fill('linwei');
  await page.locator('#system-login-password').fill(password);
  await page.getByRole('button', { name: '登录系统', exact: true }).click();
  await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
}
async function openAiOps() {
  await page.goto(`${baseUrl}/#/aiops`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '医助运行监测', exact: true }).waitFor({ timeout: 30_000 });
  await page.waitForFunction(() => Boolean(document.querySelector('.admin-table tbody tr') || document.querySelector('.admin-empty')), undefined, { timeout: 30_000 });
}
async function waitApi(method, suffix, action, expected = 200) {
  const responsePromise = page.waitForResponse((response) => response.request().method() === method
    && (typeof suffix === 'string'
      ? new URL(response.url()).pathname.endsWith(suffix)
      : suffix.test(new URL(response.url()).pathname)), { timeout: 30_000 });
  await action();
  const response = await responsePromise;
  const body = await response.text();
  if (response.status() !== expected) throw new Error(`${method} ${suffix} -> ${response.status()} ${body.slice(0, 500)}`);
  return body ? JSON.parse(body) : null;
}

try {
  await check('budget-editor-rejects-an-unregistered-medical-agent-code', async () => {
    await login();
    await openAiOps();
    await page.getByRole('button', { name: '新建处理额度', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '新建处理额度' });
    await dialog.waitFor({ state: 'visible', timeout: 20_000 });
    await dialog.getByLabel('方案编码').fill(`BUDGET_NOT_REGISTERED_${stamp}`);
    await dialog.getByLabel('方案名称').fill('不应生效的自由额度');
    await dialog.getByLabel('最大生成额度').fill('100');
    await dialog.getByLabel('最长处理时间（秒）').fill('10');
    const response = await waitApi('POST', '/api/v1/agent-run-budgets', () => dialog.getByRole('button', { name: '定义并生效', exact: true }).click(), 400);
    if (response.error?.code !== 'AGENT_RUN_BUDGET_REQUEST_INVALID') throw new Error(`未登记医助额度返回了意外错误：${JSON.stringify(response)}`);
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await dialog.waitFor({ state: 'hidden', timeout: 10_000 });
    result.evidence.unregistered_error = response.error.code;
    return '页面提交未登记医助额度被服务端拒绝；没有生成自由额度配置';
  });

  await check('active-agent-budget-can-be-versioned-and-restored-through-the-page', async () => {
    const row = page.locator('.admin-table tbody tr').filter({ hasText: 'BUDGET_ENCOUNTER_SUMMARIZER' });
    await row.waitFor({ state: 'visible', timeout: 30_000 });
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '编辑处理额度' });
    await dialog.waitFor({ state: 'visible', timeout: 20_000 });
    const original = { name: '就诊摘要医助单次处理上限', tokens: '16000', duration: '90' };
    const current = {
      name: await dialog.getByLabel('方案名称').inputValue(),
      tokens: await dialog.getByLabel('最大生成额度').inputValue(),
      duration: await dialog.getByLabel('最长处理时间（秒）').inputValue(),
    };
    if (current.name !== original.name || current.tokens !== original.tokens || current.duration !== original.duration) {
      if (!/^就诊摘要医助单次处理上限（隔离验收 \d+）$/u.test(current.name)
        || current.tokens !== '16001' || current.duration !== '91') {
        throw new Error(`受测合成额度不是已知基线，拒绝覆盖：${JSON.stringify(current)}`);
      }
      await dialog.getByLabel('方案名称').fill(original.name);
      await dialog.getByLabel('最大生成额度').fill(original.tokens);
      await dialog.getByLabel('最长处理时间（秒）').fill(original.duration);
      await waitApi('PUT', /\/api\/v1\/agent-run-budgets\/[0-9a-f-]+$/u, () => dialog.getByRole('button', { name: '保存额度变更', exact: true }).click());
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.getByRole('heading', { name: '医助运行监测', exact: true }).waitFor({ timeout: 30_000 });
      await row.getByRole('button', { name: '编辑', exact: true }).click();
      await dialog.waitFor({ state: 'visible', timeout: 20_000 });
    }
    const changed = {
      name: `${original.name}（隔离验收 ${stamp}）`,
      tokens: String(Number(original.tokens) + 1),
      duration: String(Number(original.duration) + 1),
    };
    let restored = false;
    try {
      await dialog.getByLabel('方案名称').fill(changed.name);
      await dialog.getByLabel('最大生成额度').fill(changed.tokens);
      await dialog.getByLabel('最长处理时间（秒）').fill(changed.duration);
      const updated = await waitApi('PUT', /\/api\/v1\/agent-run-budgets\/[0-9a-f-]+$/u, () => dialog.getByRole('button', { name: '保存额度变更', exact: true }).click());
      if (updated.max_tokens !== Number(changed.tokens) || updated.max_duration_seconds !== Number(changed.duration)) {
        throw new Error(`额度变更未由服务端持久化：${JSON.stringify(updated)}`);
      }
      await row.getByRole('button', { name: '编辑', exact: true }).click();
      await dialog.getByLabel('方案名称').fill(original.name);
      await dialog.getByLabel('最大生成额度').fill(original.tokens);
      await dialog.getByLabel('最长处理时间（秒）').fill(original.duration);
      const restoredPayload = await waitApi('PUT', /\/api\/v1\/agent-run-budgets\/[0-9a-f-]+$/u, () => dialog.getByRole('button', { name: '保存额度变更', exact: true }).click());
      if (restoredPayload.max_tokens !== Number(original.tokens) || restoredPayload.max_duration_seconds !== Number(original.duration)) {
        throw new Error(`额度未恢复：${JSON.stringify(restoredPayload)}`);
      }
      restored = true;
      result.evidence.versioned_budget = { code: 'BUDGET_ENCOUNTER_SUMMARIZER', original, restored_row_version: restoredPayload.row_version };
      return `有效医助额度完成页面变更并恢复原值；服务端版本递增至 ${restoredPayload.row_version}`;
    } finally {
      if (!restored && await dialog.isVisible().catch(() => false)) {
        await dialog.getByRole('button', { name: '取消', exact: true }).click().catch(() => undefined);
      }
    }
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'aiops-budget-configuration-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
