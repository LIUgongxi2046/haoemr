import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/research-dataset-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const runId = Date.now();
const purpose = `E2E 去标识研究数据集 ${runId}`;
const cohortCode = `E2E-DATASET-${runId}`;
const result = { run_at: new Date().toISOString(), purpose, cohort_code: cohortCode, checks: [], findings: [] };
let requestCreated = false;

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) {
  try { console.log(JSON.stringify({ check: name, stage: 'started' })); result.checks.push({ name, status: 'PASS', detail: await action() }); console.log(JSON.stringify({ check: name, stage: 'passed' })); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); console.log(JSON.stringify({ check: name, stage: 'failed' })); }
}

async function requestRow() {
  const row = page.locator('.admin-table tbody tr').filter({ hasText: purpose });
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  return row;
}

async function createMaterializedCohort() {
  await page.goto(`${baseUrl}/#/cohort-builder`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '研究队列构建器', exact: true }).waitFor({ timeout: 30_000 });
  await page.getByRole('button', { name: '新建研究队列', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: '新建研究队列' });
  await dialog.getByLabel('队列编码', { exact: true }).fill(cohortCode);
  await dialog.getByLabel('队列名称', { exact: true }).fill(`研究数据集验收队列 ${runId}`);
  await dialog.getByLabel('入组判据', { exact: true }).fill('age_gte=18;sex=F');
  await dialog.getByRole('button', { name: '定义并生效', exact: true }).click();
  const row = page.locator('.admin-table tbody tr').filter({ hasText: cohortCode }).first();
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  await row.getByRole('button', { name: '详情', exact: true }).click();
  await page.getByRole('heading', { name: new RegExp(`快照与成员.*${cohortCode}`) }).waitFor({ timeout: 30_000 });
  await page.getByRole('button', { name: '全库重算', exact: true }).click();
  const notice = page.locator('.admin-notice');
  await notice.waitFor({ state: 'visible', timeout: 30_000 });
  if (!/命中\s*[1-9]\d*\s*名/.test(await notice.innerText())) throw new Error('独立队列全库重算未生成可导出的成员');
  await page.getByRole('heading', { name: '成员快照', exact: true }).locator('xpath=following-sibling::div[1]//tbody/tr').first()
    .waitFor({ state: 'visible', timeout: 30_000 });
}

try {
  await check('create-request-bound-to-materialized-cohort-snapshot', async () => {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    const login = page.getByRole('button', { name: '登录系统', exact: true });
    await login.waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await login.click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await createMaterializedCohort();
    await page.goto(`${baseUrl}/#/research-dataset`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '研究数据集请求', exact: true }).waitFor({ timeout: 30_000 });
    await page.getByRole('button', { name: '新建数据申请', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建科研数据申请' });
    await dialog.getByLabel('研究目的', { exact: true }).fill(purpose);
    await dialog.getByLabel('范围描述', { exact: true }).fill('仅限院内科研安全区的去标识队列统计复核与方法学评估。');
    const selects = dialog.getByRole('combobox');
    const cohortOption = (await selects.nth(0).locator('option').evaluateAll((options, code) => options
      .map((option) => ({ value: option.value, label: option.textContent?.trim() ?? '' }))
      .find((option) => option.label.includes(code)), cohortCode));
    if (!cohortOption?.value) throw new Error(`新建并物化的队列未出现在研究数据集申请选项中：${cohortCode}`);
    await selects.nth(0).selectOption(cohortOption.value);
    await selects.nth(1).locator('option').nth(1).waitFor({ state: 'attached', timeout: 30_000 });
    await selects.nth(1).selectOption({ index: 1 });
    await dialog.getByRole('button', { name: '创建请求', exact: true }).click();
    const row = await requestRow();
    await row.getByText('已请求', { exact: true }).waitFor({ timeout: 30_000 });
    if ((await row.innerText()).includes('旧请求未绑定')) throw new Error('新申请未绑定已物化成员快照');
    requestCreated = true;
    return `通过页面新建并物化 ${cohortCode}，再将研究数据申请绑定其成员快照并保持 REQUESTED 状态`;
  });

  await check('approve-export-and-download-deidentified-watermarked-csv', async () => {
    if (!requestCreated) throw new Error('前置研究数据申请未创建，无法继续审批和导出验收');
    let row = await requestRow();
    await row.getByRole('button', { name: '批准', exact: true }).click();
    const approve = page.getByRole('dialog').filter({ hasText: '批准' });
    await approve.getByRole('button', { name: '确认批准', exact: true }).click();
    await row.getByText('已批准', { exact: true }).waitFor({ timeout: 30_000 });
    await row.getByRole('button', { name: '导出', exact: true }).click();
    const exportDialog = page.getByRole('dialog').filter({ hasText: '受控导出' });
    const watermark = '仅供 E2E 研究安全复核，禁止外带';
    await exportDialog.getByLabel('导出水印', { exact: true }).fill(watermark);
    await exportDialog.getByRole('button', { name: '确认受控导出', exact: true }).click();
    await row.getByText('已导出', { exact: true }).waitFor({ timeout: 30_000 });
    if (!(await row.innerText()).includes(watermark)) throw new Error('导出水印未在受控请求台账中保留');
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      row.getByRole('button', { name: '下载 CSV', exact: true }).click(),
    ]);
    const stream = await download.createReadStream();
    if (!stream) throw new Error('浏览器未提供受控导出文件内容');
    const chunks = [];
    for await (const chunk of stream) chunks.push(chunk);
    const content = Buffer.concat(chunks).toString('utf8');
    if (!content.includes('watermarked_deidentified_dataset') || !content.includes(watermark)
      || !content.includes('subject_pseudonym,sex_code,birth_year') || content.includes('display_name') || content.includes('patient_id')) {
      throw new Error('下载内容不符合去标识和水印约束');
    }
    return '批准后受控导出并实际下载 CSV；文件包含水印和伪名字段，未出现患者姓名或 patient_id';
  });

  await check('destroy-export-and-retain-terminal-audit-ledger', async () => {
    if (!requestCreated) throw new Error('前置研究数据申请未创建，无法继续销毁验收');
    const row = await requestRow();
    await row.getByRole('button', { name: '销毁', exact: true }).click();
    const destroy = page.getByRole('dialog').filter({ hasText: '销毁' });
    await destroy.getByRole('button', { name: '确认销毁', exact: true }).click();
    await page.getByText('研究数据集请求', { exact: true }).waitFor({ timeout: 30_000 });
    await page.locator('.admin-panel header input[type="checkbox"]').check();
    const historicalRow = await requestRow();
    await historicalRow.getByText('已销毁', { exact: true }).waitFor({ timeout: 30_000 });
    if (await historicalRow.getByRole('button', { name: '下载 CSV', exact: true }).isEnabled()) throw new Error('已销毁请求仍允许下载');
    return '销毁后请求仅在历史台账可见，下载入口被禁用，终态审计证据保留';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'research-dataset-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
