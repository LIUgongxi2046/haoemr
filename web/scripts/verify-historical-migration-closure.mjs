import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/historical-migration-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const runId = Date.now();
const sourceCode = `E2E-MIGRATION-${runId}`;
const sourceName = `历史迁移闭环验证源 ${runId}`;
const sourcePatientIdentifier = `E2E-PATIENT-${runId}`;
// This fixture is read-only selected from the dev-synthetic patient master before the browser run.
// It deliberately has one exact active match so the UI's required human confirmation can be exercised.
const csvContent = [
  'source_patient_identifier,display_name,sex_code,birth_date',
  `${sourcePatientIdentifier},张慧敏,F,1978-04-16`,
].join('\n');
const result = {
  run_at: new Date().toISOString(),
  source_code: sourceCode,
  source_patient_identifier: sourcePatientIdentifier,
  checks: [],
  findings: [],
};

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined,
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) {
  try {
    console.log(JSON.stringify({ check: name, stage: 'started' }));
    result.checks.push({ name, status: 'PASS', detail: await action() });
    console.log(JSON.stringify({ check: name, stage: 'passed' }));
  } catch (error) {
    result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() });
    console.log(JSON.stringify({ check: name, stage: 'failed' }));
  }
}

async function migrationPage() {
  await page.goto(`${baseUrl}/#/migration`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '历史数据迁移与上线切换', exact: true }).waitFor({ timeout: 30_000 });
}

async function sourceRow() {
  const row = page.locator('.admin-table tbody tr').filter({ hasText: sourceCode });
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  return row;
}

async function batchRow() {
  const row = page.locator('.admin-table tbody tr').filter({ hasText: sourceCode }).last();
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  return row;
}

async function expectStatus(row, status) {
  await row.getByText(status, { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
}

try {
  await check('create-and-activate-isolated-source-through-ui', async () => {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    const login = page.getByRole('button', { name: '登录系统', exact: true });
    await login.waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await login.click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await migrationPage();

    await page.getByRole('button', { name: '注册源系统', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '注册迁移源系统' });
    await dialog.getByLabel('源编码', { exact: true }).fill(sourceCode);
    await dialog.getByLabel('显示名', { exact: true }).fill(sourceName);
    await dialog.getByRole('button', { name: '注册', exact: true }).click();
    let row = await sourceRow();
    await expectStatus(row, '已注册');
    await row.getByRole('button', { name: '配置', exact: true }).click();
    await expectStatus(row, '已配置');
    await row.getByRole('button', { name: '激活', exact: true }).click();
    await expectStatus(row, '激活');
    return '已通过源系统盘点页面完成注册、配置和激活';
  });

  await check('map-import-and-human-confirm-controlled-patient-input', async () => {
    const row = await sourceRow();
    await row.getByRole('button').first().click();
    await page.getByRole('heading', { name: '源字段映射', exact: true }).waitFor({ timeout: 30_000 });
    await page.getByRole('button', { name: '新建映射', exact: true }).click();
    let dialog = page.getByRole('dialog').filter({ hasText: '新建源字段映射' });
    await dialog.getByLabel('源字段', { exact: true }).fill('PATIENT_NO');
    await dialog.getByLabel('目标实体', { exact: true }).fill('Patient');
    await dialog.getByLabel('目标字段', { exact: true }).fill('identifier');
    await dialog.getByRole('button', { name: '登记', exact: true }).click();
    await page.getByText('PATIENT_NO', { exact: true }).waitFor({ timeout: 30_000 });

    await page.getByRole('button', { name: '导入 CSV', exact: true }).click();
    dialog = page.getByRole('dialog').filter({ hasText: '导入受控迁移 CSV' });
    await dialog.locator('input[type="file"]').setInputFiles({
      name: `historical-migration-${runId}.csv`,
      mimeType: 'text/csv',
      buffer: Buffer.from(csvContent, 'utf8'),
    });
    await dialog.getByRole('button', { name: '导入并固化', exact: true }).click();
    await page.getByText(`已导入并固化 1 条源患者记录，可据此开始可续迁批次。`, { exact: true }).waitFor({ timeout: 30_000 });

    const candidate = page.locator('.admin-table tbody tr').filter({ hasText: sourcePatientIdentifier });
    await candidate.waitFor({ state: 'visible', timeout: 30_000 });
    await candidate.getByText('待复核', { exact: true }).waitFor({ timeout: 30_000 });
    await candidate.getByRole('button', { name: '确认匹配', exact: true }).click();
    await candidate.getByText('已确认', { exact: true }).waitFor({ timeout: 30_000 });
    return 'CSV 受控导入已固定 1 条输入；唯一候选经过页面人工确认后进入已确认状态';
  });

  await check('reconcile-switch-observe-and-archive-through-ui', async () => {
    await page.getByRole('button', { name: '开始试迁', exact: true }).click();
    let dialog = page.getByRole('dialog').filter({ hasText: '开始历史迁移批次' });
    const selects = dialog.getByRole('combobox');
    await selects.nth(0).selectOption(sourceCode);
    await selects.nth(1).waitFor({ state: 'visible', timeout: 30_000 });
    await selects.nth(1).selectOption({ index: 1 });
    await dialog.getByRole('button', { name: '开始试迁', exact: true }).click();
    let row = await batchRow();
    await expectStatus(row, '试迁');
    await row.getByRole('button', { name: '对账', exact: true }).click();
    await expectStatus(row, '已对账');
    if (!(await row.innerText()).includes('0')) throw new Error('对账完成后批次未显示零差异，切换应被阻断');
    await row.getByRole('button', { name: '切换', exact: true }).click();
    await expectStatus(row, '已切换');
    await row.getByRole('button', { name: '开始观察', exact: true }).click();
    await expectStatus(row, '观察中');
    await row.getByRole('button', { name: '归档观察结论', exact: true }).click();
    dialog = page.getByRole('dialog').filter({ hasText: '归档迁移观察结论' });
    await dialog.getByLabel('观察结论', { exact: true }).fill('受控输入、人工匹配和零差异对账均已复核，观察期未发现新增异常。');
    await dialog.getByRole('button', { name: '固化并归档', exact: true }).click();
    await expectStatus(row, '已归档');
    await page.getByText(/观察结论已固化，迁移证据已归档/).waitFor({ timeout: 30_000 });
    return '批次经零差异对账后依次完成切换、观察和不可变结论归档';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'historical-migration-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
