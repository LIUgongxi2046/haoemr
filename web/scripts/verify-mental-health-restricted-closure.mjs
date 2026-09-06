import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/mental-health-restricted-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const providerId = '018f0000-0000-7000-8000-00000000aa06';
const suffix = Date.now();
const localNow = new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
const result = { run_at: new Date().toISOString(), checks: [], findings: [], evidence: {} };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));
page.on('response', (response) => {
  if (response.status() < 400 || !response.url().includes('/api/')) return;
  void response.text().then((body) => result.findings.push({ name: 'unexpected-api-error', detail: `${response.request().method()} ${response.url()} -> ${response.status()} ${body.slice(0, 400)}`, url: page.url() }));
});

async function check(name, action) {
  try { result.checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); }
}
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
  await action();
  const response = await pending;
  const body = await response.text();
  if (!response.ok()) throw new Error(`${path} -> ${response.status()} ${body.slice(0, 500)}`);
  return JSON.parse(body);
}
async function selectAvailable(selector, label, value) {
  try { await selector.locator(`option[value="${value}"]`).waitFor({ state: 'attached', timeout: 30_000 }); }
  catch {
    const options = await selector.locator('option').evaluateAll((items) => items.map((item) => ({ value: item.value, text: item.textContent })));
    throw new Error(`${label} 未展示来源 ${value}；当前选项：${JSON.stringify(options)}`);
  }
  await selector.selectOption(value);
}
function requireRestricted(label, record) {
  if (record.data_classification !== 'RESTRICTED') throw new Error(`${label} 密级不正确：${JSON.stringify(record)}`);
}

try {
  await login();
  await check('mental-treatment-is-restricted', async () => {
    await route('mental-treatment');
    await page.getByLabel('治疗前评估').fill(`E2E 精神心理治疗评估 ${suffix}：风险、适应证和观察计划完整记录`);
    await page.getByLabel('治疗方案与执行').fill(`E2E 精神心理治疗干预 ${suffix}：医护观察、心理支持与复评计划`);
    await page.getByLabel('记录时间').fill(localNow);
    const treatment = await post('/mental-health-treatment-records', () => page.getByRole('button', { name: '保存治疗记录', exact: true }).click());
    requireRestricted('精神心理治疗记录', treatment);
    result.evidence.treatment_id = treatment.note_id;
    return '通过真实页面创建的精神心理治疗记录返回 RESTRICTED';
  });
  await check('mental-evidence-is-restricted', async () => {
    await route('mental-evidence');
    await page.getByLabel('证据评估').fill(`E2E 精神心理诊疗证据 ${suffix}：量表、精神检查和风险判断完整记录`);
    await page.getByLabel('处置与结论').fill(`E2E 精神心理证据处置 ${suffix}：复核要求、干预结论与随访计划`);
    await page.getByLabel('记录时间').fill(localNow);
    const evidence = await post('/mental-health-evidence-records', () => page.getByRole('button', { name: '保存诊疗证据', exact: true }).click());
    requireRestricted('精神心理诊疗证据', evidence);
    result.evidence.evidence_id = evidence.note_id;
    return '通过真实页面创建的精神心理诊疗证据返回 RESTRICTED';
  });
  await check('mental-qc-review-is-restricted', async () => {
    await route('mental-care');
    await page.getByLabel('接诊提供者ID').fill(providerId);
    await page.getByLabel('危机原因').fill(`E2E 受限质控来源危机交接 ${suffix}`);
    await page.getByLabel('风险等级').selectOption('HIGH');
    await page.getByLabel('保护措施').fill('E2E 持续陪护、环境安全核验与交接复评措施');
    await page.getByLabel('交接时间').fill(localNow);
    const handover = await post('/mental-health-crisis-handovers', () => page.getByRole('button', { name: '创建危机交接', exact: true }).click());
    requireRestricted('精神心理危机交接', handover);
    await route('mental-qc');
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await selectAvailable(page.locator('form select').nth(1), '危机交接', handover.crisis_handover_id);
    await page.locator('form select').nth(2).selectOption('PASS');
    await page.getByLabel('复核时间').fill(localNow);
    const review = await post('/mental-health-qc-reviews', () => page.getByRole('button', { name: '创建质控复核', exact: true }).click());
    requireRestricted('精神心理质控复核', review);
    if (review.reviewed_record_id !== handover.crisis_handover_id) throw new Error(`质控复核未引用真实危机交接：${JSON.stringify(review)}`);
    result.evidence.qc_review_id = review.review_id;
    return '通过真实页面创建的精神心理质控复核返回 RESTRICTED，并绑定真实危机交接';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'mental-health-restricted-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
