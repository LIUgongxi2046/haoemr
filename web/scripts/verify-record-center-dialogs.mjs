import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/record-center-dialogs');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const checks = [];
const findings = [];

async function login(page) {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  const submit = page.getByRole('button', { name: '登录系统', exact: true });
  if (await submit.count()) {
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await submit.click();
  }
  await page.waitForFunction(() => document.documentElement.dataset.routeId !== 'login-context', undefined, { timeout: 20_000 });
}

async function check(name, action) {
  try {
    checks.push({ name, status: 'PASS', ...((await action()) || {}) });
  } catch (error) {
    findings.push({ name, detail: error instanceof Error ? error.message : String(error) });
  }
}

async function assertDialogFits(page, dialog) {
  await dialog.waitFor({ state: 'visible' });
  const box = await dialog.boundingBox();
  const viewport = page.viewportSize();
  if (!box || !viewport || box.x < 0 || box.y < 0
    || box.x + box.width > viewport.width + 1 || box.y + box.height > viewport.height + 1) {
    throw new Error(`弹窗越界: ${JSON.stringify({ box, viewport })}`);
  }
  return box;
}

async function firstEnabled(locator) {
  for (let index = 0; index < await locator.count(); index += 1) {
    if (!(await locator.nth(index).isDisabled())) return locator.nth(index);
  }
  throw new Error('没有可用的草稿操作按钮');
}

try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('console', (message) => {
    if (message.type() === 'error') findings.push({ name: 'console', detail: message.text() });
  });
  page.on('pageerror', (error) => findings.push({ name: 'pageerror', detail: error.message }));
  await login(page);
  await page.goto(`${baseUrl}/#/record`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.documentElement.dataset.routeId === 'record', undefined, { timeout: 20_000 });
  await page.getByRole('heading', { name: '全院病历中心' }).waitFor();

  await check('create-dialog', async () => {
    await page.getByRole('button', { name: '创建病历抽查', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建病历草稿' });
    const box = await assertDialogFits(page, dialog);
    await dialog.getByLabel('主诉', { exact: true }).fill('仅用于验证弹窗交互，不提交数据');
    await dialog.getByRole('button', { name: '关闭弹窗' }).click();
    await dialog.waitFor({ state: 'hidden' });
    return { box };
  });

  await check('edit-dialog', async () => {
    const button = await firstEnabled(page.getByRole('button', { name: '编辑', exact: true }));
    await button.click();
    const dialog = page.getByRole('dialog').filter({ hasText: '保存会生成新的不可变草稿版本' });
    const box = await assertDialogFits(page, dialog);
    await dialog.getByLabel('诊断与评估', { exact: true }).fill('仅验证编辑弹窗，不保存');
    await dialog.getByRole('button', { name: '关闭弹窗' }).click();
    await dialog.waitFor({ state: 'hidden' });
    return { box };
  });

  await check('void-dialog', async () => {
    const button = await firstEnabled(page.getByRole('button', { name: '作废', exact: true }));
    await button.click();
    const dialog = page.getByRole('dialog').filter({ hasText: '仅草稿可作废' });
    const box = await assertDialogFits(page, dialog);
    await dialog.getByLabel('作废原因（至少 4 字）').fill('仅验证作废弹窗，不提交');
    await page.screenshot({ path: resolve(outputDir, 'record-center-void-dialog-1440x1000.png'), fullPage: true });
    await dialog.getByRole('button', { name: '关闭弹窗' }).click();
    await dialog.waitFor({ state: 'hidden' });
    return { box };
  });
  await page.close();

  const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await login(mobile);
  await mobile.goto(`${baseUrl}/#/record`, { waitUntil: 'domcontentloaded' });
  await mobile.waitForFunction(() => document.documentElement.dataset.routeId === 'record', undefined, { timeout: 20_000 });
  await check('mobile-create-dialog', async () => {
    await mobile.getByRole('button', { name: '新建病历', exact: true }).first().click();
    const dialog = mobile.getByRole('dialog').filter({ hasText: '新建病历草稿' });
    const box = await assertDialogFits(mobile, dialog);
    await mobile.screenshot({ path: resolve(outputDir, 'record-center-create-dialog-390x844.png'), fullPage: true });
    await dialog.getByRole('button', { name: '关闭弹窗' }).click();
    return { box };
  });
  await mobile.close();
} finally {
  await browser.close();
}

const result = { run_at: new Date().toISOString(), checks, findings, passed: findings.length === 0 };
await writeFile(resolve(outputDir, 'record-center-dialog-audit.json'), `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ checks: checks.length, findings: findings.length, passed: result.passed,
  artifact: resolve(outputDir, 'record-center-dialog-audit.json') }));
if (findings.length) process.exitCode = 1;
