import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const outputDir = resolve(webDir, '../output/playwright/inpatient-workstation');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const stamp = Date.now().toString().slice(-8);
const orderName = `验收血常规-${stamp}`;
const orderNameEdited = `${orderName}-已编辑`;
const indication = `住院CRUD闭环验收-${stamp}`;
const handoverSummary = `护理交接CRUD验收-${stamp}`;
const result = { run_at: new Date().toISOString(), checks: [], failures: [] };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.failures.push(`pageerror: ${error.message}`));

async function login() {
  await page.goto(`${baseUrl}/#/login`);
  await page.getByLabel('用户名').fill('linwei');
  await page.getByLabel('密码', { exact: true }).fill('HaoEMR-dev!');
  await page.getByRole('button', { name: '登录系统', exact: true }).click();
  await page.waitForURL(/#\/clinical$/);
}

async function check(name, action) {
  try { await action(); result.checks.push({ name, status: 'PASS' }); }
  catch (error) { result.failures.push(`${name}: ${error instanceof Error ? error.message : String(error)}`); }
}

await login();
await check('order-create-edit-sign-cancel', async () => {
  await page.goto(`${baseUrl}/#/ip-orders`);
  await page.getByRole('heading', { name: '住院医嘱与用药中心', exact: true }).waitFor();
  await page.getByRole('button', { name: '新增医嘱', exact: true }).click();
  const createDialog = page.locator('dialog.business-dialog[open]');
  await createDialog.getByLabel('项目名称').fill(orderName);
  await createDialog.getByLabel('临床指征').fill(indication);
  await createDialog.getByRole('button', { name: '保存医嘱草稿', exact: true }).click();
  const card = page.locator('.order-card').filter({ hasText: indication });
  await card.waitFor();
  await card.getByRole('button', { name: '编辑草稿', exact: true }).click();
  const editDialog = page.locator('dialog.business-dialog[open]');
  await editDialog.getByLabel('项目名称').fill(orderNameEdited);
  await editDialog.getByRole('button', { name: '保存草稿修改', exact: true }).click();
  await card.getByText(orderNameEdited, { exact: true }).waitFor();
  await card.getByRole('button', { name: '安全预检并签署生效', exact: true }).click();
  await card.getByRole('button', { name: '取消医嘱', exact: true }).waitFor();
  await card.getByRole('button', { name: '取消医嘱', exact: true }).click();
  const cancelDialog = page.locator('dialog.business-dialog[open]');
  await cancelDialog.getByLabel('原因').fill(`验收作废-${stamp}`);
  await cancelDialog.getByRole('button', { name: '确认并留痕', exact: true }).click();
  await card.locator('.task-state.cancelled').waitFor();
});

await check('ward-create-and-void', async () => {
  await page.goto(`${baseUrl}/#/ward`);
  await page.getByRole('heading', { name: '心内科一病区 · 护理工作台', exact: true }).waitFor();
  await page.getByRole('button', { name: '新增交接班', exact: true }).click();
  const createDialog = page.locator('dialog.business-dialog[open]');
  await createDialog.getByLabel('交班时间').fill('2099-08-30T08:00');
  await createDialog.getByLabel('接班时间').fill('2099-08-30T16:00');
  await createDialog.getByLabel('交接摘要').fill(handoverSummary);
  await createDialog.getByRole('button', { name: '创建交接班', exact: true }).click();
  const row = page.locator('.admin-table tbody tr').filter({ hasText: handoverSummary });
  await row.waitFor();
  await row.getByRole('button', { name: '作废', exact: true }).click();
  const voidDialog = page.locator('dialog.business-dialog[open]');
  await voidDialog.getByLabel('作废原因').fill(`验收完成后作废-${stamp}`);
  await voidDialog.getByRole('button', { name: '确认作废并留痕', exact: true }).click();
  await row.getByText('已作废', { exact: true }).waitFor();
});

await context.close();
await browser.close();
const artifact = resolve(outputDir, 'inpatient-crud-acceptance.json');
await writeFile(artifact, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.failures.length) process.exitCode = 1;
