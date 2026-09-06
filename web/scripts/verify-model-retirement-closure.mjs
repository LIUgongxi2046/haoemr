import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/model-retirement-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const username = process.env.HAOEMR_MODEL_ADMIN_USER || 'linwei';
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

async function stable(title) {
  await page.getByRole('heading', { name: title, exact: true }).waitFor({ timeout: 30_000 });
  await page.waitForFunction(() => !document.querySelector('main .clinical-page-state.loading'), undefined, { timeout: 30_000 });
}

try {
  await check('model-registration-offers-only-real-provider-options', async () => {
    await login();
    await page.goto(`${baseUrl}/#/models`, { waitUntil: 'domcontentloaded' });
    await stable('模型服务与 API 配置');
    await page.getByRole('button', { name: '新建模型 API 配置', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建模型 API 配置' });
    const options = await dialog.locator('select').first().locator('option').allTextContents();
    if (options.some((option) => /SYNTHETIC|模拟/.test(option))) throw new Error('新建模型入口仍提供退役模拟模型');
    if (!(await page.locator('.model-api-guide').innerText()).includes('仅可新建真实模型提供方配置')) {
      throw new Error('模型页未明确真实提供方与退役配置边界');
    }
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    return `新建入口仅提供 ${options.filter(Boolean).length - 1} 个真实 Provider；未提供 SYNTHETIC 或模拟配置`;
  });

  await check('retired-example-configurations-are-visible-but-cannot-be-tested-or-routed', async () => {
    await page.goto(`${baseUrl}/#/model-connection`, { waitUntil: 'domcontentloaded' });
    await stable('模型连接与凭据状态');
    const retiredRows = page.locator('.admin-table tbody tr').filter({ hasText: '.example' });
    const count = await retiredRows.count();
    if (count === 0) {
      await page.getByText(/开发验收.*示例地址.*已退役.*不可检测或路由/).waitFor({ timeout: 30_000 });
      return '当前台账未暴露 .example 配置；页面明确其已退役且不可检测或路由';
    }
    for (let index = 0; index < count; index += 1) {
      const row = retiredRows.nth(index);
      const button = row.getByRole('button', { name: '已退役', exact: true });
      if (await button.count() !== 1 || await button.isEnabled()) throw new Error('退役示例地址仍可发起连接检测');
    }
    await page.goto(`${baseUrl}/#/model-routing`, { waitUntil: 'domcontentloaded' });
    await stable('医助模型候选与数据边界');
    const routingRows = page.locator('.admin-table tbody tr').filter({ hasText: '.example' });
    const routingCount = await routingRows.count();
    if (routingCount > count) throw new Error('模型路由出现未在连接台账登记的退役示例地址');
    for (let index = 0; index < routingCount; index += 1) {
      await routingRows.nth(index).getByText('开发验收配置已退役', { exact: true }).waitFor({ timeout: 30_000 });
    }
    return `${count} 条历史 .example 配置均保持可审计可见、连接检测禁用；模型路由仅展示 ${routingCount} 条，未展示的退役配置不进入医助候选集`;
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close(); await browser.close();
}

const artifact = resolve(outputDir, 'model-retirement-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
