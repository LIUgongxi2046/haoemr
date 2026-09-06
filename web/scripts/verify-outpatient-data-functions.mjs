import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/outpatient-final-audit');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const routes = ['outpatient', 'opd-record', 'opd-diagnosis', 'opd-orders', 'opd-results', 'opd-consult', 'opd-followup'];
const navigationLabels = ['门诊工作台', '门诊病历', '诊断', '医嘱处方', '检查检验', '会诊转诊', '随访终诊'];

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const checks = [];
const findings = [];
let currentRoute = 'bootstrap';

page.on('console', (message) => {
  if (message.type() === 'error') findings.push({ route: currentRoute, check: 'console', detail: message.text() });
});
page.on('pageerror', (error) => findings.push({ route: currentRoute, check: 'pageerror', detail: error.message }));
page.on('response', (response) => {
  if (response.status() >= 400 && response.url().includes('/api/v1/')) {
    findings.push({ route: currentRoute, check: 'api-response', status: response.status(), url: response.url() });
  }
});

async function check(name, action) {
  try {
    checks.push({ route: currentRoute, name, status: 'PASS', ...((await action()) || {}) });
  } catch (error) {
    findings.push({ route: currentRoute, check: name, detail: error instanceof Error ? error.message : String(error) });
  }
}

async function login(loginUsername = username) {
  currentRoute = 'login';
  await page.goto(`${baseUrl}/#/outpatient`, { waitUntil: 'domcontentloaded' });
  const submit = page.getByRole('button', { name: '登录系统' });
  if (await submit.isVisible().catch(() => false)) {
    await page.getByLabel('用户名').fill(loginUsername);
    await page.locator('#system-login-password').fill(password);
    await submit.click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
  }
  await page.goto(`${baseUrl}/#/outpatient`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.documentElement.dataset.routeId === 'outpatient', undefined, { timeout: 30_000 });
}

async function switchUser(loginUsername) {
  await page.getByRole('button', { name: '用户登录与账户' }).click();
  await page.getByRole('button', { name: '退出系统', exact: true }).click();
  await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible' });
  await page.getByLabel('用户名').fill(loginUsername);
  await page.locator('#system-login-password').fill(password);
  await page.getByRole('button', { name: '登录系统', exact: true }).click();
  await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
}

async function openRoute(id) {
  currentRoute = id;
  await page.goto(`${baseUrl}/#/${id}`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction((routeId) => document.documentElement.dataset.routeId === routeId, id, { timeout: 15_000 });
  await page.locator('main h1').waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const root = document.querySelector('main [data-page-root]');
    return root && !root.querySelector('.clinical-page-state.loading');
  }, undefined, { timeout: 30_000 }).catch(() => {});
  await page.waitForTimeout(250);
}

async function expectDialogFromButton(buttonName, dialogTitle) {
  const buttons = page.getByRole('button', { name: buttonName, exact: true });
  let button;
  for (let index = 0; index < await buttons.count(); index += 1) {
    const candidate = buttons.nth(index);
    if (await candidate.isVisible() && !(await candidate.isDisabled())) {
      button = candidate;
      break;
    }
  }
  if (!button) throw new Error(`没有可用操作按钮：${buttonName}`);
  await button.click();
  const dialog = page.getByRole('dialog').filter({ hasText: dialogTitle });
  await dialog.waitFor({ state: 'visible' });
  const box = await dialog.boundingBox();
  const viewport = page.viewportSize();
  if (!box || !viewport || box.x < 0 || box.y < 0 || box.x + box.width > viewport.width + 1 || box.y + box.height > viewport.height + 1) {
    throw new Error(`弹窗超出视口：${dialogTitle}`);
  }
  await dialog.getByRole('button', { name: '关闭弹窗' }).click();
  await dialog.waitFor({ state: 'hidden' });
}

async function verifyLayout(viewportLabel) {
  const result = await page.evaluate(() => {
    const root = document.documentElement;
    const body = document.body;
    const pageOverflow = Math.max(root.scrollWidth, body.scrollWidth) - root.clientWidth;
    const parents = [...document.querySelectorAll('.toolbar-actions,.head-actions,.inline-actions')];
    const overlaps = [];
    for (const parent of parents) {
      const items = [...parent.querySelectorAll(':scope > button,:scope > a')].filter((element) => {
        const style = getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
      });
      for (let left = 0; left < items.length; left += 1) {
        for (let right = left + 1; right < items.length; right += 1) {
          const a = items[left].getBoundingClientRect();
          const b = items[right].getBoundingClientRect();
          const intersectionWidth = Math.min(a.right, b.right) - Math.max(a.left, b.left);
          const intersectionHeight = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
          if (intersectionWidth > 2 && intersectionHeight > 2) overlaps.push(`${items[left].textContent?.trim()} / ${items[right].textContent?.trim()}`);
        }
      }
    }
    return { pageOverflow, overlaps };
  });
  if (result.pageOverflow > 2) throw new Error(`${viewportLabel} 页面横向溢出 ${result.pageOverflow}px`);
  if (result.overlaps.length) throw new Error(`${viewportLabel} 操作按钮相互遮挡：${result.overlaps.join('；')}`);
  return result;
}

try {
  await login();

  await openRoute('outpatient');
  await check('seven-secondary-navigation-items', async () => {
    for (const label of navigationLabels) await page.getByRole('link', { name: label, exact: true }).first().waitFor({ state: 'visible' });
  });
  await check('outpatient-summary-has-realistic-workflow-data', async () => {
    await page.locator('.encounter-editor').waitFor({ state: 'visible' });
    await page.locator('.patient-context').waitFor({ state: 'visible' });
    const patientName = (await page.locator('.outpatient-patient-strip strong').first().textContent())?.trim() ?? '';
    if (!/^[一-龥]{2,4}$/.test(patientName) || /(合成|测试|患者)/.test(patientName)) {
      throw new Error(`当前患者姓名不符合自然模拟数据规范：${patientName}`);
    }
    await page.locator('.previsit-summary').waitFor({ state: 'visible' });
  });

  await openRoute('opd-record');
  await check('record-editor-and-workflow-actions', async () => {
    await page.locator('textarea').first().waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '运行质控' }).waitFor({ state: 'visible' });
    await page.getByRole('button', { name: /立即保存|保存中/ }).waitFor({ state: 'visible' });
  });

  await openRoute('opd-diagnosis');
  await check('diagnosis-data-and-filter', async () => {
    const cards = page.locator('.diagnosis-card');
    await cards.first().waitFor({ state: 'visible' });
    if (await cards.count() < 2) throw new Error('诊断数据少于 2 条');
    await page.getByLabel('状态').selectOption('PROVISIONAL');
    if (await cards.count() < 1) throw new Error('待确认诊断筛选无结果');
    await page.getByLabel('状态').selectOption('ALL');
  });
  await check('diagnosis-create-modal', () => expectDialogFromButton('新增诊断', '新增诊断'));
  await check('diagnosis-edit-modal', () => expectDialogFromButton('更正', '更正诊断'));
  await check('diagnosis-delete-lifecycle-modal', () => expectDialogFromButton('停止', '停止诊断'));

  await openRoute('opd-orders');
  await check('orders-data-and-filter', async () => {
    const cards = page.locator('.order-card');
    await cards.first().waitFor({ state: 'visible' });
    if (await cards.count() < 3) throw new Error('医嘱数据少于 3 条');
    await page.getByLabel('状态').selectOption('ACTIVE');
    if (await cards.count() < 1) throw new Error('活动医嘱筛选无结果');
    await page.getByLabel('状态').selectOption('ALL');
  });
  await check('orders-create-modal', () => expectDialogFromButton('新增医嘱', '新建医嘱草稿'));
  await check('orders-edit-modal', async () => {
    if (!await page.getByRole('button', { name: '编辑草稿', exact: true }).count()) {
      await page.getByRole('button', { name: '新增医嘱', exact: true }).click();
      const createDialog = page.getByRole('dialog').filter({ hasText: '新建医嘱草稿' });
      await createDialog.getByRole('button', { name: '保存医嘱草稿', exact: true }).click();
      await createDialog.waitFor({ state: 'hidden' });
      await page.getByRole('button', { name: '编辑草稿', exact: true }).first().waitFor({ state: 'visible' });
    }
    await expectDialogFromButton('编辑草稿', '编辑医嘱草稿');
  });
  await check('orders-delete-lifecycle-modal', async () => {
    const stop = page.getByRole('button', { name: '停止医嘱', exact: true });
    const cancel = page.getByRole('button', { name: '取消医嘱', exact: true });
    if (await stop.count()) return expectDialogFromButton('停止医嘱', '停止医嘱');
    if (await cancel.count()) return expectDialogFromButton('取消医嘱', '取消医嘱');
    throw new Error('没有可验证的医嘱停止/取消操作');
  });

  await openRoute('opd-results');
  await check('results-data-and-filter', async () => {
    const cards = page.locator('.result-card');
    await cards.first().waitFor({ state: 'visible' });
    if (await cards.count() < 2) throw new Error('检查检验报告少于 2 份');
    await page.getByLabel('分类').selectOption('ABNORMAL');
    if (await cards.count() < 1) throw new Error('异常结果筛选无结果');
    await page.getByLabel('分类').selectOption('ALL');
  });
  await check('clinician-cannot-author-results', async () => {
    if (await page.getByRole('button', { name: '录入结果', exact: true }).count()) throw new Error('临床医师不应拥有检验或影像报告签发入口');
    await page.getByText('医生只读报告 / 处置危急值', { exact: true }).waitFor({ state: 'visible' });
  });
  await switchUser('chengyu.xie');
  await openRoute('opd-results');
  await check('radiologist-results-create-modal', () => expectDialogFromButton('录入结果', '录入已审核结果'));
  await check('radiologist-results-edit-modal', () => expectDialogFromButton('更正报告', '追加结果更正版本'));
  await switchUser(username);

  await openRoute('opd-consult');
  await check('consult-data-and-actions', async () => {
    const rows = page.locator('.admin-table tbody tr');
    await rows.first().waitFor({ state: 'visible' });
    if (await rows.count() < 2) throw new Error('会诊转诊数据少于 2 条');
  });
  await check('consult-create-modal', () => expectDialogFromButton('新建会诊 / 转诊', '新建会诊 / 转诊'));
  await check('consult-edit-modal', () => expectDialogFromButton('编辑', '编辑会诊 / 转诊草稿'));
  await check('consult-delete-lifecycle-modal', () => expectDialogFromButton('删除', '删除会诊 / 转诊草稿'));

  await openRoute('opd-followup');
  await check('followup-data-and-actions', async () => {
    const rows = page.locator('.admin-table tbody tr');
    await rows.first().waitFor({ state: 'visible' });
    if (await rows.count() < 4) throw new Error('随访终诊数据少于 4 条');
  });
  await check('followup-create-modal', () => expectDialogFromButton('登记随访', '登记随访计划'));
  await check('followup-edit-modal', () => expectDialogFromButton('编辑', '编辑随访计划'));
  await check('followup-complete-modal', () => expectDialogFromButton('填写结局', '填写随访结局'));
  await check('followup-delete-lifecycle-modal', () => expectDialogFromButton('删除', '删除随访计划'));

  for (const viewport of [{ width: 1440, height: 1000, label: 'desktop' }, { width: 390, height: 844, label: 'mobile' }]) {
    await page.setViewportSize(viewport);
    for (const id of routes) {
      await openRoute(id);
      await check(`${id}-${viewport.label}-layout`, () => verifyLayout(viewport.label));
      await page.screenshot({ path: resolve(outputDir, `${id}-${viewport.label}.png`), fullPage: true });
    }
  }
} finally {
  await browser.close();
}

const result = {
  run_at: new Date().toISOString(),
  routes,
  checks: checks.length + findings.length,
  passed: checks.length,
  failed: findings.length,
  findings,
  observations: checks,
};
const outputPath = resolve(outputDir, 'outpatient-data-function-audit.json');
await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ checks: result.checks, passed: result.passed, failed: result.failed, artifact: outputPath }));
if (findings.length) process.exitCode = 1;
