import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/surgical-timeout-cross-role');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const scheduler = process.env.HAOEMR_SURGERY_SCHEDULER || 'linwei';
const verifier = process.env.HAOEMR_SURGERY_VERIFIER || 'jiahui.xu';
const preferredPatientId = process.env.HAOEMR_SURGERY_PATIENT_ID || '018f0000-0000-7000-8000-000000000001';
const procedureName = `E2E Time-Out 独立核验 ${Date.now()}`;
const result = { run_at: new Date().toISOString(), procedure_name: procedureName, checks: [], findings: [] };
let selectedPatientId = preferredPatientId;
let scheduled = false;
let verified = false;

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined,
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) {
  try { result.checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); }
}

async function login(username) {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  const button = page.getByRole('button', { name: '登录系统', exact: true });
  await button.waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.locator('#system-login-password').fill(password);
  await button.click();
  await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
}

async function logout() {
  await page.getByRole('button', { name: '用户登录与账户', exact: true }).click();
  await page.getByRole('button', { name: '退出系统', exact: true }).click();
  await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 20_000 });
}

async function openSurgeryWorkbench() {
  await page.goto(`${baseUrl}/#/surgery-schedule`, { waitUntil: 'domcontentloaded' });
  const queue = page.locator('[data-execution-patient-list]');
  await queue.waitFor({ state: 'visible', timeout: 30_000 });
  const search = queue.getByLabel('患者检索', { exact: true });
  for (const query of [selectedPatientId, '']) {
    await search.fill(query);
    const rows = query
      ? queue.locator('[data-execution-patient-row]').filter({ hasText: query })
      : queue.locator('[data-execution-patient-row]');
    if (!await rows.first().isVisible().catch(() => false)) continue;
    const count = await rows.count();
    for (let index = 0; index < count; index += 1) {
      const row = rows.nth(index);
      await row.getByRole('button', { name: '选择患者并下转', exact: true }).click();
      await page.locator('[data-execution-patient-detail]').waitFor({ state: 'visible', timeout: 30_000 });
      await page.getByRole('heading', { name: '围手术期排程与安全核查', exact: true }).waitFor({ timeout: 30_000 });
      await page.waitForFunction(
        () => !document.querySelector('main .clinical-page-state.loading'),
        undefined,
        { timeout: 30_000 },
      );
      const contextText = await page.locator('[data-execution-patient-context]').innerText();
      const match = contextText.match(/患者\s+([0-9a-f-]{36})/i);
      if (!match) throw new Error('页面没有返回当前手术患者标识');
      selectedPatientId = match[1];
      return;
    }
  }
  throw new Error('当前授权手术队列没有可下转患者');
}

async function procedureRow() {
  const row = page.locator('.admin-table tbody tr').filter({ hasText: procedureName }).first();
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  return row;
}

async function expectNotice(code) {
  const notice = page.locator('.inline-notice');
  await notice.filter({ hasText: code }).waitFor({ state: 'visible', timeout: 30_000 });
  return notice.innerText();
}

try {
  await check('scheduler-creates-procedure-and-cannot-time-out-before-independent-verification', async () => {
    await login(scheduler);
    await openSurgeryWorkbench();
    await page.getByRole('button', { name: '新增手术排程', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新增手术排程' });
    await dialog.getByLabel('术式名称', { exact: true }).fill(procedureName);
    await dialog.locator('select').nth(0).selectOption('ABDOMEN');
    await dialog.locator('select').nth(1).selectOption('NONE');
    await dialog.getByRole('button', { name: '排程手术', exact: true }).click();
    const row = await procedureRow();
    await row.getByText('已排程', { exact: true }).waitFor({ timeout: 30_000 });
    await row.getByRole('button', { name: '安全核查', exact: true }).click();
    const notice = await expectNotice('SURGICAL_TIME_OUT_SECOND_PERSON_REQUIRED');
    scheduled = true;
    return `排程已创建；缺少第二人核验时服务端拒绝 Time-Out：${notice}`;
  });

  await check('independent-user-verifies-and-cannot-perform-own-final-time-out-transition', async () => {
    if (!scheduled) throw new Error('排程前置步骤未成功');
    await logout();
    await login(verifier);
    await openSurgeryWorkbench();
    const row = await procedureRow();
    await row.getByRole('button', { name: '第二人核验', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '独立第二人 Time-Out 核验' });
    await dialog.getByLabel('核验说明', { exact: true }).fill('独立核对患者身份、术式、部位侧别、器械与影像资料。');
    await dialog.getByRole('button', { name: '提交独立核验', exact: true }).click();
    const verifiedRow = await procedureRow();
    await verifiedRow.getByText('待独立核验', { exact: true }).waitFor({ state: 'detached', timeout: 30_000 });
    await verifiedRow.getByRole('button', { name: '安全核查', exact: true }).click();
    const notice = await expectNotice('SURGICAL_TIME_OUT_SELF_VERIFICATION_FORBIDDEN');
    verified = true;
    return `第二名用户已形成不可覆盖的核验证据；同一核验账号被拒绝最终迁移：${notice}`;
  });

  await check('another-user-completes-time-out-and-terminal-procedure-through-ui', async () => {
    if (!verified) throw new Error('独立第二人核验前置步骤未成功');
    await logout();
    await login(scheduler);
    await openSurgeryWorkbench();
    let row = await procedureRow();
    await row.getByRole('button', { name: '安全核查', exact: true }).click();
    await row.getByText('安全核查完成', { exact: true }).waitFor({ timeout: 30_000 });
    row = await procedureRow();
    await row.getByRole('button', { name: '完成', exact: true }).click();
    await row.getByText('已完成', { exact: true }).waitFor({ timeout: 30_000 });
    if (await row.getByRole('button', { name: '完成', exact: true }).count()) {
      throw new Error('已完成手术仍显示可重复完成动作');
    }
    return '非核验账号完成 Time-Out 并完成手术；终态不再暴露重复完成操作';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'surgical-timeout-cross-role.json');
await writeFile(artifact, `${JSON.stringify({ ...result, selected_patient_id: selectedPatientId, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, selected_patient_id: selectedPatientId, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
