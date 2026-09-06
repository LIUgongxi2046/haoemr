import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/pathway-knowledge-approval-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const author = 'linwei';
const reviewer = 'jiahui.xu';
const code = `E2E_PATH_${Date.now()}`;
const name = `E2E 路径知识 ${Date.now()}`;
const result = { run_at: new Date().toISOString(), code, name, checks: [], findings: [] };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) { try { result.checks.push({ name, status: 'PASS', detail: await action() }); } catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); } }
async function login(username) { await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' }); const button = page.getByRole('button', { name: '登录系统', exact: true }); await button.waitFor({ state: 'visible', timeout: 30_000 }); await page.getByLabel('用户名', { exact: true }).fill(username); await page.locator('#system-login-password').fill(password); await button.click(); await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 }); }
async function logout() { await page.getByRole('button', { name: '用户登录与账户', exact: true }).click(); await page.getByRole('button', { name: '退出系统', exact: true }).click(); await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 20_000 }); }

try {
  await check('author-creates-pathway-and-draft-version', async () => {
    await login(author); await page.goto(`${baseUrl}/#/knowledge-center`, { waitUntil: 'domcontentloaded' });
    await page.locator('button[title="新建路径"]').click();
    const form = page.locator('form.ob-pathway-create'); await form.locator('input').nth(0).fill(code); await form.locator('input').nth(1).fill(name); await form.locator('input').nth(2).fill('NEPHROLOGY'); await form.locator('input').nth(3).fill('N04'); await form.getByRole('button', { name: '创建路径知识', exact: true }).click();
    await page.getByText(`路径“${name}”已创建`, { exact: false }).waitFor({ timeout: 30_000 }); await page.getByRole('heading', { name, exact: true }).waitFor({ timeout: 30_000 });
    await page.getByRole('button', { name: '＋ 新建版本', exact: true }).click(); const version = page.locator('.ob-create'); await version.locator('input').nth(0).fill('入院评估'); await version.locator('input').nth(1).fill('完成入院评估与肾功能复核'); await version.getByRole('button', { name: '创建', exact: true }).click();
    await page.getByText('版本 v1 已创建', { exact: false }).waitFor({ timeout: 30_000 }); await page.getByRole('button', { name: '提交审核', exact: true }).click(); await page.getByText('已提交审核', { exact: true }).waitFor({ timeout: 30_000 }); return '作者创建路径及版本并提交审核';
  });
  await check('independent-reviewer-reviews-and-author-approves-after-separation', async () => {
    await logout(); await login(reviewer); await page.goto(`${baseUrl}/#/pathway-review`, { waitUntil: 'domcontentloaded' }); const card = page.locator('.board-card').filter({ hasText: name }); await card.waitFor({ state: 'visible', timeout: 30_000 }); await card.getByRole('button', { name: '审核', exact: true }).click(); await page.getByText(`${name} 已审核`, { exact: true }).waitFor({ timeout: 30_000 });
    await logout(); await login(author); await page.goto(`${baseUrl}/#/pathway-review`, { waitUntil: 'domcontentloaded' }); const approved = page.locator('.board-card').filter({ hasText: name }); await approved.waitFor({ state: 'visible', timeout: 30_000 }); await approved.getByRole('button', { name: '审批发布', exact: true }).click(); await page.getByText(`${name} 已审批发布`, { exact: true }).waitFor({ timeout: 30_000 }); return '独立用户复核后，非复核用户完成审批发布';
  });
} finally { await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {}); await context.close(); await browser.close(); }
const artifact = resolve(outputDir, 'pathway-knowledge-approval-closure.json'); await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`); console.log(JSON.stringify({ ...result, artifact }, null, 2)); if (result.findings.length) process.exitCode = 1;
