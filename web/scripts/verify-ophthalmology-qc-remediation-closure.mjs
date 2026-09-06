import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/ophthalmology-qc-remediation-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const defect = `E2E 眼科质控缺陷 ${Date.now()}`;
const result = { run_at: new Date().toISOString(), defect, checks: [], findings: [], evidence: {} };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));
page.on('response', (response) => {
  if (response.status() < 400 || !response.url().includes('/api/')) return;
  if (response.status() === 403 && response.request().method() === 'GET' && /\/api\/v1\/inpatient\/(admissions\/[^/]+\/overview|worklist)/.test(response.url())) return;
  void response.text().then((body) => result.findings.push({
    name: 'unexpected-api-error',
    detail: `${response.request().method()} ${response.url()} -> ${response.status()} ${body.slice(0, 500)}`,
    url: page.url(),
  }));
});

async function check(name, action) { try { result.checks.push({ name, status: 'PASS', detail: await action() }); } catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); } }
async function login() {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('用户名', { exact: true }).fill('linwei');
  await page.locator('#system-login-password').fill(password);
  await page.getByRole('button', { name: '登录系统', exact: true }).click();
  await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
}
async function route(id) {
  await page.goto(`${baseUrl}/#/${id}`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction((routeId) => document.documentElement.dataset.routeId === routeId, id, { timeout: 30_000 });
  await page.locator('main h1').waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('.clinical-page-state.loading').waitFor({ state: 'hidden', timeout: 30_000 }).catch(() => undefined);
}
async function post(path, action) {
  const pending = page.waitForResponse((response) => response.url().includes(path) && response.request().method() === 'POST', { timeout: 30_000 });
  await action(); const response = await pending; const text = await response.text();
  if (!response.ok()) throw new Error(`${path} -> ${response.status()} ${text.slice(0, 500)}`);
  return JSON.parse(text);
}

try {
  await check('real-preop-source-is-created-before-qc-review', async () => {
    await login(); await route('ophthalmology-treatment');
    await page.getByLabel('手术眼别').selectOption('OD');
    await page.getByLabel('见证者ID').fill('018f0000-0000-7000-8000-00000000aa06');
    await page.getByLabel('核对时间').fill(new Date().toISOString().slice(0, 16));
    const record = await post('/ophthalmology-preop-verifications', () => page.getByRole('button', { name: '创建术前核对', exact: true }).click());
    if (!record.verification_id || record.surgical_eye !== 'OD') throw new Error(`术前来源未正确创建：${JSON.stringify(record)}`);
    result.evidence.preop_verification_id = record.verification_id;
    return `术前核对 ${record.verification_id} 已由真实业务入口创建`;
  });
  await check('fail-qc-review-selects-real-source-and-creates-remediation-task', async () => {
    await route('ophthalmology-qc');
    await page.getByLabel('复核记录类型').selectOption('PREOP_VERIFICATION');
    const source = page.getByLabel('被复核记录');
    await source.locator(`option[value="${result.evidence.preop_verification_id}"]`).waitFor({ state: 'attached', timeout: 30_000 });
    await source.selectOption(result.evidence.preop_verification_id);
    if (await source.inputValue() !== result.evidence.preop_verification_id) throw new Error('质控表单未保留刚创建的术前核对记录');
    await page.getByLabel('复核结论').selectOption('FAIL');
    await page.getByLabel('复核时间').fill(new Date().toISOString().slice(0, 16));
    await page.getByLabel('缺陷描述').fill(defect);
    const submit = page.getByRole('button', { name: '创建质控复核', exact: true });
    if (await submit.isDisabled()) throw new Error('质控复核提交按钮仍被禁用；当前来源记录不可提交');
    const review = await post('/ophthalmology-qc-reviews', () => submit.click());
    if (review.reviewed_record_id !== result.evidence.preop_verification_id || review.review_conclusion !== 'FAIL') throw new Error(`质控记录未绑定真实来源：${JSON.stringify(review)}`);
    await page.getByText(defect, { exact: true }).waitFor({ timeout: 30_000 });
    result.evidence.qc_review_id = review.review_id;
    return `FAIL 复核 ${review.review_id} 已绑定术前核对`;
  });
  await check('remediation-task-is-visible-in-outpatient-task-center', async () => {
    await page.goto(`${baseUrl}/#/clinical-tasks?mode=outpatient`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.documentElement.dataset.routeId === 'clinical-tasks', undefined, { timeout: 30_000 });
    await page.locator('main h1').waitFor({ state: 'visible', timeout: 30_000 });
    await page.locator('.clinical-page-state.loading').waitFor({ state: 'hidden', timeout: 30_000 }).catch(() => undefined);
    await page.locator('.task-search input').fill('眼科质控不通过整改');
    const remediation = page.locator('.task-center-table tbody tr')
      .filter({ hasText: '眼科质控不通过整改' })
      .filter({ hasText: 'QC_REMEDIATION' })
      .filter({ hasText: 'SPECIALTY_QC_REVIEW' });
    await remediation.waitFor({ state: 'visible', timeout: 30_000 });
    await remediation.click();
    await page.getByText('SPECIALTY_QC_REVIEW', { exact: true }).last().waitFor({ state: 'visible', timeout: 30_000 });
    result.evidence.remediation_task_title = await remediation.locator('td').nth(1).locator('b').textContent();
    return '门诊任务中心展示高风险待处理的 QC_REMEDIATION，来源为 SPECIALTY_QC_REVIEW';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close(); await browser.close();
}
const artifact = resolve(outputDir, 'ophthalmology-qc-remediation-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
