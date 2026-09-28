import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/record-center-navigation');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const routes = [
  ['record', '病历工作台'], ['record-editor', '专注编辑'], ['record-sources', '来源证据'],
  ['record-versions', '版本证据'],
  ['lis-report', 'LIS 报告'], ['pacs-viewer', 'PACS 影像'],
];

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const checks = [];
const findings = [];

async function login(page) {
  await page.goto(`${baseUrl}/#/record`, { waitUntil: 'domcontentloaded' });
  const submit = page.getByRole('button', { name: '登录系统', exact: true });
  await submit.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => undefined);
  if (await submit.isVisible().catch(() => false)) {
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await submit.click();
  }
  await page.waitForFunction(() => document.documentElement.dataset.routeId === 'record', undefined, { timeout: 30_000 });
}

async function check(name, action) {
  try { checks.push({ name, status: 'PASS', ...((await action()) || {}) }); }
  catch (error) { findings.push({ name, detail: error instanceof Error ? error.message : String(error) }); }
}

try {
  const desktop = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await login(desktop);
  await check('desktop-position-and-name', async () => {
    const sidebar = desktop.getByRole('link', { name: /全院病历中心/ }).first();
    const nav = desktop.getByRole('navigation', { name: '全院病历中心二级导航' });
    await sidebar.waitFor({ state: 'visible' });
    await nav.waitFor({ state: 'visible' });
    const [topbarBox, navBox, headingBox] = await Promise.all([
      desktop.locator('.topbar').boundingBox(), nav.boundingBox(), desktop.locator('main h1').first().boundingBox(),
    ]);
    if (!topbarBox || !navBox || !headingBox) throw new Error('无法读取导航布局坐标');
    if (Math.abs(navBox.y - (topbarBox.y + topbarBox.height)) > 2) throw new Error('二级导航未紧贴主导航栏下方');
    if (headingBox.y < navBox.y + navBox.height) throw new Error('页面标题与二级导航发生重叠');
    return { topbarBox, navBox, headingBox };
  });
  for (const [routeId, label] of routes) {
    await check(`route-${routeId}`, async () => {
      await desktop.evaluate((id) => { window.location.hash = `#/${id}`; }, routeId);
      await desktop.waitForFunction((id) => document.documentElement.dataset.routeId === id, routeId, { timeout: 30_000 });
      const nav = desktop.getByRole('navigation', { name: '全院病历中心二级导航' });
      if (await nav.count() !== 1) throw new Error(`${routeId} 二级导航数量不为 1`);
      await nav.getByRole('link', { name: label, exact: true }).and(desktop.locator('[aria-current="page"]')).waitFor();
      return { active: label };
    });
  }
  await check('record-qc-deep-link', async () => {
    await desktop.evaluate(() => { window.location.hash = '#/record-qc'; });
    await desktop.waitForFunction(() => document.documentElement.dataset.routeId === 'record-qc', undefined, { timeout: 30_000 });
    const nav = desktop.getByRole('navigation', { name: '全院病历中心二级导航' });
    await nav.getByRole('link', { name: '病历工作台', exact: true }).and(desktop.locator('[aria-current="page"]')).waitFor();
    if (await nav.getByRole('link', { name: '质控审签', exact: true }).count()) throw new Error('质控入口仍占用二级导航');
    return { active: '病历工作台' };
  });
  await desktop.evaluate(() => { window.location.hash = '#/record'; });
  await desktop.waitForFunction(() => document.documentElement.dataset.routeId === 'record');
  await desktop.screenshot({ path: resolve(outputDir, 'record-navigation-1440x1000.png'), fullPage: true });
  await desktop.close();

  const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await login(mobile);
  await check('mobile-scrollable-navigation', async () => {
    const nav = mobile.getByRole('navigation', { name: '全院病历中心二级导航' });
    await nav.waitFor({ state: 'visible' });
    const metrics = await nav.evaluate((element) => ({ clientWidth: element.clientWidth, scrollWidth: element.scrollWidth, overflowX: getComputedStyle(element).overflowX }));
    if (!['auto', 'scroll'].includes(metrics.overflowX)) throw new Error('窄屏二级导航未启用横向滚动');
    if (metrics.scrollWidth <= metrics.clientWidth) throw new Error('窄屏二级导航未形成可滚动区域');
    return metrics;
  });
  await mobile.screenshot({ path: resolve(outputDir, 'record-navigation-390x844.png'), fullPage: true });
  await mobile.close();
} finally {
  await browser.close();
}

const result = { run_at: new Date().toISOString(), checks, findings, passed: findings.length === 0 };
const artifact = resolve(outputDir, 'record-navigation-audit.json');
await writeFile(artifact, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ checks: checks.length, findings: findings.length, passed: result.passed, artifact }));
if (findings.length) process.exitCode = 1;
