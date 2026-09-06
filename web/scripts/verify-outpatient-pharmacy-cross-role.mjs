import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/outpatient-pharmacy-cross-role');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const preparer = process.env.HAOEMR_PHARMACY_PREPARER || 'qinghua.deng';
const verifier = process.env.HAOEMR_PHARMACY_VERIFIER || 'shuhan.jiang';
const preferredPatientId = process.env.HAOEMR_PHARMACY_PATIENT_ID || '018f0000-0000-7000-8000-000000000001';
let selectedPatientId = preferredPatientId;
const batchNumber = `OP-PHARM-E2E-${Date.now()}`;
const result = { run_at: new Date().toISOString(), batch_number: batchNumber, checks: [], findings: [] };
let prepared = false;

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined,
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();

page.on('pageerror', (error) => result.findings.push({ check: 'pageerror', detail: error.message }));

async function check(name, action) {
  try {
    result.checks.push({ name, status: 'PASS', detail: await action() });
  } catch (error) {
    result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() });
  }
}

async function login(username) {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  const loginButton = page.getByRole('button', { name: '登录系统', exact: true });
  await loginButton.waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.locator('#system-login-password').fill(password);
  await loginButton.click();
  await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
}

async function logout() {
  await page.getByRole('button', { name: '用户登录与账户', exact: true }).click();
  await page.getByRole('button', { name: '退出系统', exact: true }).click();
  await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 20_000 });
}

async function openPharmacy(requireDispensablePrescription = true) {
  await page.goto(`${baseUrl}/#/outpatient-pharmacy`, { waitUntil: 'domcontentloaded' });
  const queueHeading = page.getByRole('heading', { name: '门诊药房', exact: true });
  const detailHeading = page.getByRole('heading', { name: '门诊药房审方与调剂', exact: true });
  await Promise.race([
    queueHeading.waitFor({ timeout: 30_000 }),
    detailHeading.waitFor({ timeout: 30_000 }),
  ]);
  const startPreparing = page.getByRole('button', { name: '开始调配', exact: true });
  if (await detailHeading.isVisible().catch(() => false)) {
    await startPreparing.waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForFunction(
      () => !document.querySelector('main .clinical-page-state.loading'),
      undefined,
      { timeout: 30_000 },
    );
    await page.getByRole('button', { name: '返回患者列表', exact: true }).click();
    await queueHeading.waitFor({ timeout: 30_000 });
  }
  const queue = page.locator('[data-execution-patient-list]');
  const patientSearch = queue.getByLabel('患者检索', { exact: true });
  for (const query of [selectedPatientId, '']) {
    await patientSearch.fill(query);
    const candidates = query
      ? queue.locator('[data-execution-patient-row]').filter({ hasText: query })
      : queue.locator('[data-execution-patient-row]');
    await candidates.first().waitFor({ state: 'visible', timeout: 30_000 });
    const candidateCount = await candidates.count();
    for (let index = 0; index < candidateCount; index += 1) {
      const candidate = candidates.nth(index);
      await candidate.getByRole('button', { name: '选择患者并下转', exact: true }).click();
      await detailHeading.waitFor({ timeout: 30_000 });
      const selectedContext = await page.locator('[data-execution-patient-context]').innerText();
      const selectedPatientMatch = selectedContext.match(/患者\s+([0-9a-f-]{36})/i);
      if (!selectedPatientMatch) throw new Error('页面没有返回当前调剂患者标识');
      selectedPatientId = selectedPatientMatch[1];
      await startPreparing.waitFor({ state: 'visible', timeout: 30_000 });
      await page.waitForFunction(
        () => !document.querySelector('main .clinical-page-state.loading'),
        undefined,
        { timeout: 30_000 },
      );
      if (!requireDispensablePrescription || await startPreparing.isEnabled()) return;
      await page.getByRole('button', { name: '返回患者列表', exact: true }).click();
      await queueHeading.waitFor({ timeout: 30_000 });
    }
  }
  throw new Error('当前授权药房队列没有可调配的已签署药品处方');
}

async function dispensingRow() {
  const row = page.locator('.admin-table tbody tr').filter({ hasText: batchNumber });
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  return row;
}

try {
  await check('first-pharmacist-can-prepare-from-signed-prescription', async () => {
    await login(preparer);
    await openPharmacy();
    await page.getByRole('button', { name: '开始调配', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '按签署处方调配' });
    const prescription = dialog.locator('select').first();
    await prescription.locator('option').nth(1).waitFor({ state: 'attached', timeout: 30_000 });
    await prescription.selectOption({ index: 1 });
    await dialog.getByLabel('批次号', { exact: true }).fill(batchNumber);
    await dialog.getByLabel('数量', { exact: true }).fill('0.01');
    await dialog.getByRole('button', { name: '按处方调配并待核验', exact: true }).click();
    const row = await dispensingRow();
    await row.getByText('已调配', { exact: true }).waitFor({ timeout: 30_000 });
    prepared = true;
    return '已从当前患者的已签署处方项创建 PREPARED 调剂记录';
  });

  await check('same-pharmacist-cannot-verify-own-dispensing', async () => {
    if (!prepared) throw new Error('前置调剂未成功，不能验证同人核验门禁');
    const row = await dispensingRow();
    await row.getByRole('button', { name: '第二人核验', exact: true }).click();
    await page.getByText(/PHARMACY_SELF_VERIFICATION_FORBIDDEN/).waitFor({ timeout: 20_000 });
    await row.getByText('已调配', { exact: true }).waitFor();
    return '服务端拒绝调配人与核验人为同一用户';
  });

  await check('second-pharmacist-verifies-and-dispenses', async () => {
    if (!prepared) throw new Error('前置调剂未成功，不能验证第二药师发药');
    await logout();
    await login(verifier);
    await openPharmacy(false);
    await page.getByRole('button', { name: '刷新', exact: true }).first().click();
    const preparedRow = await dispensingRow();
    await preparedRow.getByRole('button', { name: '第二人核验', exact: true }).click();
    await preparedRow.getByText('已核验', { exact: true }).waitFor({ timeout: 30_000 });
    await preparedRow.getByRole('button', { name: '发药', exact: true }).click();
    await preparedRow.getByText('已发药', { exact: true }).waitFor({ timeout: 30_000 });
    return '第二名药师完成 VERIFY → DISPENSE；前端台账显示 DISPENSED';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'outpatient-pharmacy-cross-role.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
