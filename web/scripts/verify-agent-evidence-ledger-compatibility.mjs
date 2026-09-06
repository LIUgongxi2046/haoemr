import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/agent-evidence-ledger-compatibility');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const username = process.env.HAOEMR_AGENT_EVIDENCE_USER || 'linwei';
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

async function login() {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  const button = page.getByRole('button', { name: '登录系统', exact: true });
  await button.waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.locator('#system-login-password').fill(password);
  await button.click();
  await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
}

async function ledger(route) {
  await page.goto(`${baseUrl}/#/${route}`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '医助运行证据采集', exact: true }).waitFor({ timeout: 30_000 });
  await page.waitForFunction(() => !document.querySelector('main .clinical-page-state.loading'), undefined, { timeout: 30_000 });
  await page.waitForFunction(() => Boolean(
    document.querySelector('.ai-evidence-capture .admin-table tbody tr')
      || document.querySelector('.ai-evidence-capture .admin-empty'),
  ), undefined, { timeout: 30_000 });
  const body = await page.locator('[data-page-root]').innerText();
  if (body.includes('AIRunSnapshot') || body.includes('模拟运行')) {
    throw new Error('兼容页面暴露了已退役的模拟运行台账');
  }
  const rows = await page.locator('.ai-evidence-capture .admin-table tbody tr').allInnerTexts();
  const metrics = await page.locator('.ai-evidence-capture .admin-metrics').innerText();
  return { rows, metrics };
}

try {
  await check('historical-agent-route-reuses-the-real-medical-agent-evidence-ledger', async () => {
    await login();
    const historical = await ledger('agent');
    const maintained = await ledger('ai-capture');
    if (JSON.stringify(historical.rows) !== JSON.stringify(maintained.rows)
      || historical.metrics !== maintained.metrics) {
      throw new Error('历史 /agent 与 /ai-capture 的运行台账投影不一致');
    }
    return `两个 URL 的真实运行台账一致：${historical.rows.length} 条运行记录；未出现 legacy AIRunSnapshot 或前端模拟记录`;
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'agent-evidence-ledger-compatibility.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
