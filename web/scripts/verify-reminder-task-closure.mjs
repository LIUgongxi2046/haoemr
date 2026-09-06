import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/reminder-task-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const message = `REMINDER-TASK-E2E-${Date.now()}：请人工核查提醒来源和后续处置。`;
const result = { run_at: new Date().toISOString(), reminder_message: message, checks: [], findings: [] };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined,
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message }));

async function check(name, action) {
  try {
    console.log(JSON.stringify({ check: name, stage: 'started' }));
    result.checks.push({ name, status: 'PASS', detail: await action() });
    console.log(JSON.stringify({ check: name, stage: 'passed' }));
  } catch (error) {
    result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() });
    console.log(JSON.stringify({ check: name, stage: 'failed' }));
  }
}

try {
  await check('create-pending-reminder-through-ui', async () => {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    const loginButton = page.getByRole('button', { name: '登录系统', exact: true });
    await loginButton.waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await loginButton.click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await page.goto(`${baseUrl}/#/ai-reminder-detail`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '主动提醒详情', exact: true }).waitFor({ timeout: 30_000 });
    const form = page.locator('form.admin-form');
    await form.locator('select').nth(0).selectOption('OTHER');
    await form.locator('select').nth(1).selectOption('WARNING');
    await form.locator('textarea').fill(message);
    await form.locator('button').click();
    const row = page.locator('.admin-table tbody tr').filter({ hasText: message });
    await row.waitFor({ state: 'visible', timeout: 30_000 });
    await row.getByText('待处理', { exact: true }).waitFor({ timeout: 30_000 });
    return '通过真实提醒页面创建 PENDING 的 OTHER/WARNING 提醒';
  });

  await check('convert-reminder-to-single-traceable-task', async () => {
    const row = page.locator('.admin-table tbody tr').filter({ hasText: message });
    await row.locator('button').first().click();
    await page.getByText(/已创建临床任务/).waitFor({ timeout: 30_000 });
    const taskLink = row.locator('a');
    await taskLink.waitFor({ state: 'visible', timeout: 30_000 });
    if ((await row.locator('button').allTextContents()).some((text) => text.trim() === '转为任务')) {
      throw new Error('已转换提醒仍显示“转为任务”操作，未满足单次转换门禁');
    }
    return '提醒转换为一条可追溯任务，页面不再提供重复转换入口';
  });

  await check('task-center-filters-and-shows-reminder-source', async () => {
    const row = page.locator('.admin-table tbody tr').filter({ hasText: message });
    await row.locator('a').click();
    await page.getByRole('heading', { name: '任务中心', exact: true }).waitFor({ timeout: 30_000 });
    await page.waitForFunction(() => {
      const query = new URLSearchParams(location.hash.split('?')[1] || '');
      return query.get('source') === 'REMINDER' && query.get('mode') === 'outpatient';
    }, undefined, { timeout: 10_000 });
    const taskRow = page.locator('.task-center-table tbody tr').filter({ hasText: message });
    await taskRow.waitFor({ state: 'visible', timeout: 30_000 });
    if (!(await taskRow.innerText()).includes('REMINDER')) {
      throw new Error('任务中心未把回跳任务标识为 REMINDER 来源');
    }
    return '任务中心按 REMINDER 来源筛选并展示同一提醒消息，临床终态未被页面伪造';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'reminder-task-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
