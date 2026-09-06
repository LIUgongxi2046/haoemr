import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/outbox-external-delivery-gate');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
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

try {
  await check('integration-page-shows-fail-closed-hospital-delivery-state', async () => {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill('linwei');
    await page.locator('#system-login-password').fill(password);
    await page.getByRole('button', { name: '登录系统', exact: true }).click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });

    const responsePromise = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/api/v1/outbox/reconciliation'), { timeout: 30_000 });
    await page.goto(`${baseUrl}/#/integration`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '外部系统集成与互操作中心', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    const response = await responsePromise;
    if (response.status() !== 200) throw new Error(`Outbox status endpoint returned ${response.status()}`);
    const status = await response.json();
    if (status.external_delivery_configured !== false || status.scheduled_dispatch_enabled !== false) {
      throw new Error(`Expected the isolated instance to be fail-closed without an external channel: ${JSON.stringify(status)}`);
    }
    const card = page.locator('[aria-label="医院外部投递状态"]');
    await card.waitFor({ state: 'visible', timeout: 30_000 });
    await card.getByText('医院外部通道未配置', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    await card.getByText('为避免误报送达，新的事件会保持“待投递”；历史已关闭记录若没有外部回执，会单列为未证实。', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    result.evidence.outbox_reconciliation = status;
    return '集成页以服务端租户状态明确显示：医院通道未配置，事件将安全保持待投递';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'outbox-external-delivery-gate.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
