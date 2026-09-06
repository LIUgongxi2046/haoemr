import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const outputDir = resolve(webDir, '../output/playwright/inpatient-nested-crud');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4178').replace(/\/$/, '');
const stamp = Date.now().toString().slice(-8);
const batch = `IP-BATCH-${stamp}`;
const correctedBatch = `${batch}-R1`;
const checks = [];
const failures = [];
const apiResponses = [];

const routes = [
  { id: 'admission-bed', heading: '入院、病区与床位管理' },
  { id: 'inpatient-doc-editor', heading: '住院病历 · 专注编辑' },
  { id: 'inpatient-doc-qc', heading: '住院病历 · 质控与审签' },
  { id: 'inpatient-doc-versions', heading: '住院文书版本与查房证据' },
  { id: 'inpatient-pharmacy', heading: '住院药房、配液与床旁给药' },
];

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => failures.push(`pageerror: ${error.message}`));
page.on('response', (response) => {
  if (!response.url().includes('/api/v1/')) return;
  apiResponses.push({ url: response.url(), status: response.status() });
  if (response.status() >= 500) failures.push(`${response.status()} ${response.url()}`);
});

async function check(name, action) {
  try { checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { failures.push(`${name}: ${error instanceof Error ? error.message : String(error)}`); }
}

async function login() {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('用户名').fill('linwei');
  await page.getByLabel('密码', { exact: true }).fill('HaoEMR-dev!');
  await page.getByRole('button', { name: '登录系统', exact: true }).click();
  await page.waitForURL(/#\/clinical$/, { timeout: 20_000 });
}

async function go(route) {
  await page.goto(`${baseUrl}/#/${route.id}`, { waitUntil: 'domcontentloaded' });
  try {
    if (route.id === 'inpatient-pharmacy') {
      const detailHeading = page.getByRole('heading', { name: route.heading, exact: true });
      if (!(await detailHeading.isVisible().catch(() => false))) {
        const select = page.getByRole('button', { name: '选择患者并下转', exact: true }).first();
        await select.waitFor({ timeout: 20_000 });
        await select.click();
      }
    }
    await page.getByRole('heading', { name: route.heading, exact: true }).waitFor({ timeout: 20_000 });
    await page.locator('[data-page-root]').waitFor({ timeout: 20_000 });
    if (route.id === 'inpatient-pharmacy') {
      const reconnect = page.getByRole('button', { name: '重新连接', exact: true });
      if (await reconnect.isVisible().catch(() => false)) await reconnect.click();
      await page.locator('.admin-panel').waitFor({ timeout: 20_000 });
    }
  } catch (error) {
    const text = await page.locator('body').innerText().catch(() => '');
    throw new Error(`${route.id}: ${error instanceof Error ? error.message : String(error)}; url=${page.url()}; body=${text.slice(0, 700).replaceAll('\n', ' / ')}`);
  }
}

async function assertDialog(triggerName) {
  const trigger = page.getByRole('button', { name: triggerName, exact: true }).first();
  await trigger.waitFor({ timeout: 20_000 });
  if (await trigger.isDisabled()) throw new Error(`${triggerName} 入口被禁用`);
  await trigger.click();
  const dialog = page.locator('dialog.business-dialog[open]');
  try { await dialog.waitFor({ timeout: 10_000 }); }
  catch (error) { throw new Error(`${triggerName} 点击后未打开业务弹窗：${error instanceof Error ? error.message : String(error)}`); }
  const bounds = await dialog.boundingBox();
  if (!bounds || bounds.x < -1 || bounds.x + bounds.width > 1441) throw new Error(`${triggerName} 弹窗横向越界`);
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await dialog.waitFor({ state: 'hidden', timeout: 10_000 });
}

try {
  await login();

  await check('nested-routes-desktop-layout', async () => {
    for (const route of routes) {
      await go(route);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
      if (overflow) throw new Error(`${route.id} 在 1440px 存在横向溢出`);
      await page.screenshot({ path: resolve(outputDir, `${route.id}-1440x1000.png`), fullPage: true });
    }
    return '5/5 个住院二级与三级入口在桌面宽度无横向溢出';
  });

  await check('nested-create-and-edit-dialogs', async () => {
    await go(routes[0]);
    await assertDialog('办理新入院');

    await go(routes[1]);
    const field = page.locator('.inpatient-section-form textarea:not([readonly])').first();
    if (await field.count()) {
      const previous = await field.inputValue();
      await field.fill(`${previous}\n合成浏览器验收内容-${stamp}`);
      await assertDialog('保存新版本');
    }

    await go(routes[4]);
    await assertDialog('新增摆药');
    return '入院、文书版本保存与住院摆药的新建/编辑入口均打开统一业务弹窗';
  });

  await check('pharmacy-create-edit-void-flow', async () => {
    await go(routes[4]);
    await page.getByRole('button', { name: '新增摆药', exact: true }).click();
    let dialog = page.locator('dialog.business-dialog[open]');
    const orderSelect = dialog.getByLabel('有效药品医嘱');
    const orderOptions = await orderSelect.locator('option').count();
    if (orderOptions < 2) throw new Error('当前住院就诊缺少已签署有效药品医嘱，不能执行摆药闭环');
    await orderSelect.selectOption({ index: 1 });
    const drugCode = await dialog.getByLabel('药品编码').inputValue();
    if (!drugCode) throw new Error('选择医嘱后未自动带入药品编码');
    await dialog.getByLabel('批次号').fill(batch);
    await dialog.getByLabel('数量').fill('1');
    await dialog.getByRole('button', { name: '确认摆药', exact: true }).click();

    const row = page.locator('.admin-table tbody tr').filter({ hasText: drugCode });
    await row.waitFor({ timeout: 20_000 });
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    dialog = page.locator('dialog.business-dialog[open]');
    await dialog.getByLabel('批次号').fill(correctedBatch);
    await dialog.getByRole('button', { name: '保存更正', exact: true }).click();
    await row.getByText(correctedBatch, { exact: true }).waitFor({ timeout: 20_000 });

    await row.getByRole('button', { name: '作废', exact: true }).click();
    dialog = page.locator('dialog.business-dialog[open]');
    await dialog.getByLabel('作废原因（至少 4 字）').fill(`浏览器闭环验收作废-${stamp}`);
    await dialog.getByRole('button', { name: '确认作废', exact: true }).click();
    await row.getByText('已作废', { exact: true }).waitFor({ timeout: 20_000 });
    return `${drugCode} 已完成新增、编辑、作废，且作废记录仍显示在台账`;
  });

  await check('nested-routes-mobile-layout', async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    for (const route of routes) {
      await go(route);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
      if (overflow) throw new Error(`${route.id} 在 390px 存在横向溢出`);
    }
    await go(routes[4]);
    await page.getByRole('button', { name: '新增摆药', exact: true }).click();
    const dialog = page.locator('dialog.business-dialog[open]');
    await dialog.waitFor();
    const bounds = await dialog.boundingBox();
    if (!bounds || bounds.x < -1 || bounds.x + bounds.width > 391) throw new Error('住院摆药弹窗在 390px 横向越界');
    await page.screenshot({ path: resolve(outputDir, 'inpatient-pharmacy-dialog-390x844.png'), fullPage: true });
    return '5/5 个入口在 390px 无横向溢出，业务弹窗未越界';
  });
} finally {
  await context.close();
  await browser.close();
}

const result = {
  run_at: new Date().toISOString(), base_url: baseUrl,
  passed: checks.length, failed: failures.length, checks,
  api_response_count: apiResponses.length,
  api_statuses: [...new Set(apiResponses.map((item) => item.status))],
  failures,
};
const artifact = resolve(outputDir, 'inpatient-nested-crud-acceptance.json');
await writeFile(artifact, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (failures.length) process.exitCode = 1;
