import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/specialty-capability-gate-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const departmentId = process.env.HAOEMR_SPECIALTY_DEPARTMENT_ID || '018f0000-0000-7000-8000-00000000ab01';
const packReleaseId = process.env.HAOEMR_SPECIALTY_PACK_RELEASE_ID || '018f0000-0000-7000-8000-00000000c402';
const result = { run_at: new Date().toISOString(), department_id: departmentId, pack_release_id: packReleaseId, checks: [], findings: [] };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));
async function check(name, action) { try { result.checks.push({ name, status: 'PASS', detail: await action() }); } catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); } }

try {
  await check('positive-specialty-support-is-rejected-without-active-pack-release', async () => {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' }); const login = page.getByRole('button', { name: '登录系统', exact: true }); await login.waitFor({ state: 'visible', timeout: 30_000 }); await page.getByLabel('用户名', { exact: true }).fill(username); await page.locator('#system-login-password').fill(password); await login.click(); await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await page.goto(`${baseUrl}/#/specialty-coverage`, { waitUntil: 'domcontentloaded' }); await page.getByRole('heading', { name: '科室与专科适配工作台', exact: true }).waitFor({ timeout: 30_000 }); await page.getByRole('button', { name: '新建支持声明', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建科室支持声明' }); const inputs = dialog.locator('input'); await inputs.nth(0).fill(departmentId); await inputs.nth(1).fill('CARDIOLOGY'); await dialog.locator('select').selectOption('BASIC_CLOSED_LOOP'); await inputs.nth(2).fill(''); await inputs.nth(3).fill('a'.repeat(64)); await inputs.nth(4).fill('2030-01-01T00:00'); await dialog.locator('textarea').fill(''); await dialog.getByRole('button', { name: '验证并保存', exact: true }).click();
    await page.getByText('PACK_REQUIRED', { exact: false }).waitFor({ timeout: 30_000 }); return '正向支持声明缺少活动专科包发布时被服务端拒绝';
  });
  await check('active-pack-and-approved-composition-enable-positive-support-declaration', async () => {
    const dialog = page.getByRole('dialog').filter({ hasText: '新建科室支持声明' }); await dialog.locator('input').nth(2).fill(packReleaseId); await dialog.getByRole('button', { name: '验证并保存', exact: true }).click();
    await page.getByText('科室支持声明已新建', { exact: false }).waitFor({ timeout: 30_000 }); const row = page.locator('.scope-list > button').filter({ hasText: 'CARDIOLOGY' }).last(); await row.waitFor({ state: 'visible', timeout: 30_000 }); await row.getByText('基础闭环', { exact: true }).waitFor({ timeout: 30_000 }); return '活动专科包、经审批能力组合、有效证据与零缺口同时满足后，正向声明成功保存';
  });
} finally { await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {}); await context.close(); await browser.close(); }
const artifact = resolve(outputDir, 'specialty-capability-gate-closure.json'); await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`); console.log(JSON.stringify({ ...result, artifact }, null, 2)); if (result.findings.length) process.exitCode = 1;
