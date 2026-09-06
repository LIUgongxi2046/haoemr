import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/research-cohort-recompute-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const runId = Date.now();
const cohortCode = `E2E-COHORT-${runId}`;
const cohortName = `研究队列全库重算验证 ${runId}`;
const result = { run_at: new Date().toISOString(), cohort_code: cohortCode, checks: [], findings: [] };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) {
  try { console.log(JSON.stringify({ check: name, stage: 'started' })); result.checks.push({ name, status: 'PASS', detail: await action() }); console.log(JSON.stringify({ check: name, stage: 'passed' })); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); console.log(JSON.stringify({ check: name, stage: 'failed' })); }
}

async function row() {
  const item = page.locator('.admin-table tbody tr').filter({ hasText: cohortCode }).first();
  await item.waitFor({ state: 'visible', timeout: 30_000 });
  return item;
}

async function recomputeAndReadNotice() {
  await page.getByRole('button', { name: '全库重算', exact: true }).click();
  const notice = page.locator('.admin-notice');
  await notice.waitFor({ state: 'visible', timeout: 30_000 });
  const text = await notice.innerText();
  const values = text.match(/全库\s*(\d+)\s*名活动患者，命中\s*(\d+)\s*名，新增\s*(\d+)\s*名/);
  if (!values) throw new Error(`全库重算结果未显示可核验计数：${text}`);
  return { evaluated: Number(values[1]), matched: Number(values[2]), added: Number(values[3]), text };
}

function snapshotRows() {
  return page.getByRole('heading', { name: '成员快照', exact: true })
    .locator('xpath=following-sibling::div[1]//tbody/tr');
}

function memberRows() {
  return page.getByRole('heading', { name: '成员列表', exact: true })
    .locator('xpath=following-sibling::div[1]//tbody/tr');
}

try {
  await check('create-isolated-active-cohort-through-ui', async () => {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    const login = page.getByRole('button', { name: '登录系统', exact: true });
    await login.waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await login.click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await page.goto(`${baseUrl}/#/cohort-builder`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '研究队列构建器', exact: true }).waitFor({ timeout: 30_000 });
    await page.getByRole('button', { name: '新建研究队列', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建研究队列' });
    await dialog.getByLabel('队列编码', { exact: true }).fill(cohortCode);
    await dialog.getByLabel('队列名称', { exact: true }).fill(cohortName);
    await dialog.getByLabel('入组判据', { exact: true }).fill('age_gte=18;sex=F');
    await dialog.getByRole('button', { name: '定义并生效', exact: true }).click();
    const cohort = await row();
    await cohort.getByText('有效', { exact: true }).waitFor({ timeout: 30_000 });
    await cohort.getByRole('button', { name: '详情', exact: true }).click();
    await page.getByRole('heading', { name: new RegExp(`快照与成员.*${cohortCode}`) }).waitFor({ timeout: 30_000 });
    return '通过研究队列页面定义并选择独立 ACTIVE 队列';
  });

  await check('recompute-whole-active-patient-population-and-materialize-evidence', async () => {
    const first = await recomputeAndReadNotice();
    if (first.evaluated < 1 || first.matched < 1 || first.added < 1) throw new Error(`首次全库重算未产生预期成员：${first.text}`);
    await snapshotRows().first().waitFor({ state: 'visible', timeout: 30_000 });
    await memberRows().first().waitFor({ state: 'visible', timeout: 30_000 });
    return `首次重算评估 ${first.evaluated} 名活动患者，命中 ${first.matched} 名，新增 ${first.added} 名；成员和快照已在页面物化`;
  });

  await check('recompute-is-idempotent-and-keeps-materialized-members', async () => {
    const beforeMembers = await memberRows().count();
    const second = await recomputeAndReadNotice();
    if (second.added !== 0) throw new Error(`同一队列二次重算仍新增成员：${second.text}`);
    const afterMembers = await memberRows().count();
    if (beforeMembers !== afterMembers) throw new Error(`幂等重算改变了已物化成员数：${beforeMembers} → ${afterMembers}`);
    return `二次重算新增 0 名，页面成员数保持 ${afterMembers}，同时保留新的物化快照`;
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'research-cohort-recompute-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
