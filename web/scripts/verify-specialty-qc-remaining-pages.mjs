import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/specialty-qc-remaining-pages');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const providerId = '018f0000-0000-7000-8000-00000000aa06';
const specialtyReferenceMotherId = '018f0000-0000-7000-8000-000000000006';
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
  void response.text()
    .then((body) => result.findings.push({ name: 'unexpected-api-error', detail: `${response.request().method()} ${response.url()} -> ${response.status()} ${body.slice(0, 400)}`, url: page.url() }))
    .catch(() => undefined);
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
async function review({ label, routeId, sourceType, sourceId, endpoint }) {
  await route(routeId);
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  const selects = page.locator('form select');
  await selects.first().selectOption(sourceType);
  await selectAvailable(selects.nth(1), label, sourceId);
  await selects.nth(2).selectOption('PASS');
  await page.getByLabel('复核时间').fill(localNow);
  const reviewRecord = await post(endpoint, () => page.getByRole('button', { name: '创建质控复核', exact: true }).click());
  if (reviewRecord.reviewed_record_id !== sourceId) throw new Error(`${label} 质控复核未绑定真实来源：${JSON.stringify(reviewRecord)}`);
  result.evidence[label] = { source_id: sourceId, review_id: reviewRecord.review_id };
  return `${label} 的真实来源已在质控页面出现并成功创建 PASS 复核`;
}

try {
  await login();
  await check('ent-qc-real-airway-handover', async () => {
    await route('ent-care');
    await page.getByLabel('气道注意事项').fill(`E2E QC 气道风险交接 ${suffix}`);
    await page.getByLabel('接诊提供者ID').fill(providerId);
    await page.getByLabel('交接时间').fill(localNow);
    const source = await post('/ent-airway-risk-handovers', () => page.getByRole('button', { name: '创建气道风险交接', exact: true }).click());
    return review({ label: '耳鼻喉气道风险交接', routeId: 'ent-qc', sourceType: 'AIRWAY_RISK_HANDOVER', sourceId: source.handover_id, endpoint: '/ent-qc-reviews' });
  });
  await check('dental-qc-real-treatment-record', async () => {
    await route('dental-record');
    const occupied = new Set(await page.locator('.admin-table tbody tr td:first-child code').evaluateAll((items) => items.map((item) => item.textContent?.trim()).filter(Boolean)));
    const tooth = ['11', '12', '13', '14', '15', '16', '17', '18', '21', '22', '23', '24', '25', '26', '27', '28', '31', '32', '33', '34', '35', '36', '37', '38', '41', '42', '43', '44', '45', '46', '47', '48'].find((candidate) => !occupied.has(candidate));
    if (!tooth) throw new Error('当前隔离患者已没有可用于口腔质控验收的恒牙牙位');
    await page.getByLabel('牙位记法').fill(tooth);
    await post('/dental-records', () => page.getByRole('button', { name: '创建口腔记录', exact: true }).click());
    await route('dental-treatment');
    await page.getByLabel('牙位').fill(tooth);
    await page.getByLabel('治疗类型').selectOption('FILLING');
    await page.getByLabel('治疗时间').fill(localNow);
    await page.getByLabel('材料批次').fill(`E2E-QC-${suffix}`);
    const source = await post('/dental-treatment-records', () => page.getByRole('button', { name: '创建口腔治疗记录', exact: true }).click());
    return review({ label: '口腔治疗记录', routeId: 'dental-qc', sourceType: 'TREATMENT_RECORD', sourceId: source.dental_treatment_record_id, endpoint: '/dental-qc-reviews' });
  });
  await check('dermatology-qc-real-biologic-screening', async () => {
    await route('dermatology-treatment');
    await page.getByLabel('生物制剂名称').fill(`E2E-QC-生物制剂-${suffix}`);
    await page.getByLabel('筛查时间').fill(localNow);
    const source = await post('/dermatology-biologic-screenings', () => page.getByRole('button', { name: '创建生物制剂筛查', exact: true }).click());
    return review({ label: '皮肤生物制剂筛查', routeId: 'dermatology-qc', sourceType: 'BIOLOGIC_SCREENING', sourceId: source.screening_id, endpoint: '/dermatology-qc-reviews' });
  });
  await check('neonatal-qc-real-wristband-verification', async () => {
    await route('neonatal-care');
    await page.getByLabel('母亲ID').fill(specialtyReferenceMotherId);
    await page.getByLabel('腕带码').fill(`WB-QC-${suffix}`);
    await page.getByLabel('标本码').fill(`SP-QC-${suffix}`);
    await page.getByLabel('见证者ID').fill(providerId);
    await page.getByLabel('核对时间').fill(localNow);
    const source = await post('/neonatal-wristband-verifications', () => page.getByRole('button', { name: '创建腕带核对', exact: true }).click());
    return review({ label: '新生儿腕带核对', routeId: 'neonatal-qc', sourceType: 'WRISTBAND_VERIFICATION', sourceId: source.verification_id, endpoint: '/neonatal-qc-reviews' });
  });
  await check('obstetric-qc-real-antenatal-exam', async () => {
    await route('obgyn-evidence');
    await page.getByLabel('孕周').fill('28');
    await page.getByLabel('收缩压').fill('118');
    await page.getByLabel('舒张压').fill('76');
    await page.getByLabel('检查时间').fill(localNow);
    const source = await post('/obstetric-antenatal-exams', () => page.getByRole('button', { name: '创建产前检查', exact: true }).click());
    return review({ label: '妇产产前检查', routeId: 'obgyn-qc', sourceType: 'ANTENATAL_EXAM', sourceId: source.exam_id, endpoint: '/obstetric-qc-reviews' });
  });
  await check('pediatric-qc-real-growth-record', async () => {
    await route('pediatrics-care');
    await page.getByLabel('身高（cm）').fill('110.2');
    await page.getByLabel('体重（kg）').fill('18.4');
    await page.getByLabel('测量时间').fill(localNow);
    const source = await post('/pediatric-growth-records', () => page.getByRole('button', { name: '创建生长发育记录', exact: true }).click());
    return review({ label: '儿科生长发育记录', routeId: 'pediatrics-qc', sourceType: 'GROWTH_RECORD', sourceId: source.growth_record_id, endpoint: '/pediatric-qc-reviews' });
  });
  await check('reproductive-qc-real-art-cycle', async () => {
    await route('reproductive-record');
    const sourceId = await page.locator('.admin-table tbody tr td:first-child code').first().textContent();
    if (!sourceId) throw new Error('当前就诊没有可复用的真实 ART 周期来源；该就诊仅允许一个周期，验收不会创建重复记录');
    return review({ label: '生殖 ART 周期', routeId: 'reproductive-qc', sourceType: 'ART_CYCLE', sourceId: sourceId.trim(), endpoint: '/reproductive-qc-reviews' });
  });
  await check('tcm-qc-real-herbal-prescription', async () => {
    await route('tcm-treatment');
    await page.getByLabel('方名').fill(`E2E-QC-调和方-${suffix}`);
    await page.getByLabel('药味').fill('当归 10g，川芎 6g，白芍 12g');
    await page.getByLabel('处方时间').fill(localNow);
    const source = await post('/tcm-herbal-prescriptions', () => page.getByRole('button', { name: '创建草药处方', exact: true }).click());
    return review({ label: '中医草药处方', routeId: 'tcm-qc', sourceType: 'HERBAL_PRESCRIPTION', sourceId: source.prescription_id, endpoint: '/tcm-qc-reviews' });
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'specialty-qc-remaining-pages.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
