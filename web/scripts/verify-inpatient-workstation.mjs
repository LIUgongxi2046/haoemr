import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/inpatient-workstation');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const credentials = {
  username: process.env.HAOEMR_BROWSER_USERNAME || 'linwei',
  password: process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!',
};
const routes = [
  { id: 'inpatient', heading: '住院医生工作站', open: '新增病程', minimumText: '病区患者' },
  { id: 'inpatient-overview', heading: '住院患者总览', minimumText: '当前住院' },
  { id: 'inpatient-course', heading: '住院病程与文书中心', open: '新增病程文书', minimumText: '任务驱动' },
  { id: 'ip-orders', heading: '住院医嘱与用药中心', open: '新增医嘱', minimumText: '医嘱总数' },
  { id: 'ip-results', heading: '住院检查检验与危急值', open: '录入结果', minimumText: '已签发报告' },
  { id: 'ip-consult', heading: '住院查房、会诊与协同', open: '新建会诊', minimumText: '当前住院会诊' },
  { id: 'ip-pathway', heading: '住院临床路径执行中心', open: '记录路径变异', minimumText: '心力衰竭住院标准路径' },
  { id: 'ward', heading: '心内科一病区 · 护理工作台', open: '新增交接班', minimumText: '床位与重点患者' },
  { id: 'inpatient-discharge', heading: '出院病历与病案归档闭环', open: '打开出院办理', minimumText: '出院准备度' },
];

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const failures = [];
const checks = [];
const apiResponses = [];

async function login(page) {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  const button = page.getByRole('button', { name: '登录系统', exact: true });
  await button.waitFor({ timeout: 20_000 });
  await page.getByLabel('用户名').fill(credentials.username);
  await page.getByLabel('密码', { exact: true }).fill(credentials.password);
  await button.click();
  await page.waitForURL(/#\/clinical$/, { timeout: 20_000 });
}

async function go(page, route) {
  try {
    await page.goto(`${baseUrl}/#/${route.id}`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, route.id, { timeout: 20_000 });
    await page.getByRole('heading', { name: route.heading, exact: true }).waitFor({ timeout: 20_000 });
    await page.waitForFunction((text) => {
      const root = document.querySelector('[data-page-root]');
      return root && (root.textContent || '').includes(text);
    }, route.minimumText, { timeout: 20_000 });
  } catch (error) {
    const text = await page.locator('[data-page-root]').first().innerText().catch(() => '');
    throw new Error(`${route.id} / ${route.minimumText}: ${error instanceof Error ? error.message : String(error)}; page=${text.slice(0, 500).replaceAll('\n', ' / ')}`);
  }
}

async function runCheck(name, action) {
  try { checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { failures.push(`${name}: ${error instanceof Error ? error.message : String(error)}`); }
}

const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => failures.push(`pageerror: ${error.message}`));
page.on('response', (response) => {
  if (response.url().includes('/api/v1/')) {
    apiResponses.push({ url: response.url(), status: response.status() });
    if (response.status() >= 500) failures.push(`${response.status()} ${response.url()}`);
  }
});

try {
  await login(page);
  await runCheck('nine-inpatient-routes-and-data', async () => {
    for (const route of routes) {
      await go(page, route);
      const links = page.locator('.center-nav.domain a');
      if (await links.count() !== 9) throw new Error(`${route.id} 二级导航不是 9 项`);
      if (await page.locator('.center-nav.domain a.router-link-active').count() !== 1) {
        throw new Error(`${route.id} 活动导航不唯一`);
      }
      const rightRailSelector = route.id === 'ip-pathway' ? '.pathway-variance-panel' : '.prototype-right-rail';
      if (await page.locator(rightRailSelector).count() !== 1) throw new Error(`${route.id} 缺少唯一原型责任右栏`);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
      if (overflow) throw new Error(`${route.id} 在 1440px 存在横向溢出`);
      await page.screenshot({ path: resolve(outputDir, `${route.id}-1440x1000.png`), fullPage: true });
    }
    return '9/9 路由标题、二级导航、真实数据标识与宽屏布局通过';
  });

  await runCheck('all-create-and-control-entry-points-use-dialogs', async () => {
    for (const route of routes.filter((item) => item.open)) {
      await go(page, route);
      const trigger = page.getByRole('button', { name: route.open, exact: true }).first();
      await trigger.waitFor({ timeout: 20_000 });
      if (await trigger.isDisabled()) throw new Error(`${route.id} 弹窗入口被禁用`);
      await trigger.click();
      const dialog = page.locator('dialog.business-dialog[open]');
      await dialog.waitFor({ timeout: 10_000 });
      if (await dialog.count() !== 1) throw new Error(`${route.id} 没有打开唯一业务弹窗`);
      const bounds = await dialog.boundingBox();
      if (!bounds || bounds.x < 0 || bounds.y < 0 || bounds.x + bounds.width > 1441 || bounds.y + bounds.height > 1001) {
        throw new Error(`${route.id} 弹窗超出桌面视口`);
      }
      await dialog.getByRole('button', { name: '取消', exact: true }).click();
      await dialog.waitFor({ state: 'hidden', timeout: 10_000 });
    }
    return '8 个可变更子菜单入口均打开唯一可关闭弹窗';
  });

  await runCheck('mobile-nine-route-layout', async () => {
    const mobile = page;
    await mobile.setViewportSize({ width: 390, height: 844 });
    try {
      for (const route of routes) {
        await go(mobile, route);
        const overflow = await mobile.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
        if (overflow) throw new Error(`${route.id} 在 390px 存在横向溢出`);
        if (await mobile.locator('.center-nav.domain a').count() !== 9) throw new Error(`${route.id} 移动端导航不完整`);
      }
      await go(mobile, routes.find((item) => item.id === 'ip-results'));
      await mobile.getByRole('button', { name: '录入结果', exact: true }).click();
      const dialog = mobile.locator('dialog.business-dialog[open]');
      await dialog.waitFor({ timeout: 10_000 });
      const bounds = await dialog.boundingBox();
      if (!bounds || bounds.x < -1 || bounds.x + bounds.width > 391) throw new Error('390px 弹窗横向越界');
      await mobile.screenshot({ path: resolve(outputDir, 'ip-results-dialog-390x844.png'), fullPage: true });
      return '9/9 路由在 390px 无页面横向溢出，弹窗不越界';
    } finally { await mobile.setViewportSize({ width: 1440, height: 1000 }); }
  });
} finally {
  await context.close();
  await browser.close();
}

const inpatientApiResponses = apiResponses.filter((item) => /inpatient|orders|results|ward|shift-handover/.test(item.url));
if (inpatientApiResponses.length < 9) failures.push(`住院 API 请求覆盖不足：${inpatientApiResponses.length}`);
const result = {
  run_at: new Date().toISOString(),
  base_url: baseUrl,
  passed: checks.length,
  failed: failures.length,
  checks,
  api_response_count: inpatientApiResponses.length,
  api_statuses: [...new Set(inpatientApiResponses.map((item) => item.status))],
  failures,
};
const artifact = resolve(outputDir, 'inpatient-workstation-acceptance.json');
await writeFile(artifact, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (failures.length) process.exitCode = 1;
