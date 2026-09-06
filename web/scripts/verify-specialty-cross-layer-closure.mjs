import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/specialty-cross-layer-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const suffix = Date.now();
const providerId = '018f0000-0000-7000-8000-00000000aa06';
const today = new Date().toISOString().slice(0, 10);
const now = new Date().toISOString().slice(0, 16);
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
  await action();
  const response = await pending;
  const body = await response.text();
  if (!response.ok()) throw new Error(`${path} -> ${response.status()} ${body.slice(0, 500)}`);
  return JSON.parse(body);
}
async function selectAvailable(selector, label, value) {
  try {
    await selector.locator(`option[value="${value}"]`).waitFor({ state: 'attached', timeout: 30_000 });
  } catch {
    const options = await selector.locator('option').evaluateAll((items) => items.map((item) => ({ value: item.value, text: item.textContent })));
    throw new Error(`${label} 未展示来源 ${value}；当前选项：${JSON.stringify(options)}`);
  }
  await selector.selectOption(value);
}

try {
  await login();
  await check('ophthalmology-preop-to-postop-keeps-real-reference', async () => {
    await route('ophthalmology-treatment');
    await page.getByLabel('手术眼别').selectOption('OD');
    await page.getByLabel('见证者ID').fill(providerId);
    await page.getByLabel('核对时间').fill(now);
    const preop = await post('/ophthalmology-preop-verifications', () => page.getByRole('button', { name: '创建术前核对', exact: true }).click());
    await route('ophthalmology-followup');
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await page.getByLabel('手术眼别').selectOption('OD');
    await selectAvailable(page.locator('form select').nth(1), '术前核对', preop.verification_id);
    await page.getByLabel('随访日期').fill(today);
    await page.getByLabel('眼压（mmHg）').fill('16.5');
    await page.getByLabel('记录时间').fill(now);
    const followup = await post('/ophthalmology-postop-followups', () => page.getByRole('button', { name: '创建术后随访', exact: true }).click());
    if (followup.preop_verification_id !== preop.verification_id || followup.surgical_eye !== 'OD') throw new Error(`眼科随访未绑定真实同眼别术前核对：${JSON.stringify(followup)}`);
    result.evidence.ophthalmology = { preop_id: preop.verification_id, followup_id: followup.followup_id };
    return '眼科术后随访已绑定同眼别的真实术前核对';
  });
  await check('mental-crisis-handover-to-followup-keeps-real-reference-and-restricted-classification', async () => {
    await route('mental-care');
    await page.getByLabel('接诊提供者ID').fill(providerId);
    await page.getByLabel('危机原因').fill(`E2E 心理危机交接 ${suffix}`);
    await page.getByLabel('风险等级').selectOption('HIGH');
    await page.getByLabel('交接时间').fill(now);
    await page.getByLabel('保护措施').fill('E2E 当班医护观察与陪护措施');
    const handover = await post('/mental-health-crisis-handovers', () => page.getByRole('button', { name: '创建危机交接', exact: true }).click());
    await route('mental-followup');
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await selectAvailable(page.locator('form select').first(), '危机交接', handover.crisis_handover_id);
    await page.getByLabel('随访日期').fill(today);
    await page.getByLabel('风险等级').selectOption('MODERATE');
    await page.getByLabel('记录时间').fill(now);
    const followup = await post('/mental-health-crisis-followups', () => page.getByRole('button', { name: '创建危机随访', exact: true }).click());
    if (followup.crisis_handover_id !== handover.crisis_handover_id || followup.data_classification !== 'RESTRICTED') throw new Error(`心理危机随访引用或密级错误：${JSON.stringify(followup)}`);
    result.evidence.mental_health = { handover_id: handover.crisis_handover_id, followup_id: followup.followup_id };
    return '精神心理危机随访已绑定真实危机交接，并返回 RESTRICTED';
  });
  await check('ent-airway-handover-to-followup-keeps-real-reference', async () => {
    await route('ent-care');
    await page.getByLabel('气道注意事项').fill(`E2E 气道注意事项 ${suffix}`);
    await page.getByLabel('接诊提供者ID').fill(providerId);
    await page.getByLabel('交接时间').fill(now);
    const handover = await post('/ent-airway-risk-handovers', () => page.getByRole('button', { name: '创建气道风险交接', exact: true }).click());
    await route('ent-followup');
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await selectAvailable(page.locator('form select').first(), '气道风险交接', handover.handover_id);
    await page.getByLabel('随访原因').fill(`E2E 气道随访 ${suffix}`);
    await page.getByLabel('预约日期').fill(today);
    await page.getByLabel('记录时间').fill(now);
    await page.locator('form input[type="checkbox"]').check();
    const followup = await post('/ent-followup-records', () => page.getByRole('button', { name: '创建随访记录', exact: true }).click());
    if (followup.airway_handover_id !== handover.handover_id) throw new Error(`耳鼻喉随访未绑定真实气道交接：${JSON.stringify(followup)}`);
    result.evidence.ent = { handover_id: handover.handover_id, followup_id: followup.followup_id };
    return '耳鼻喉随访已绑定真实气道风险交接';
  });
  await check('dental-treatment-completes-matching-active-dental-record', async () => {
    await route('dental-record');
    const occupiedTeeth = new Set(await page.locator('.admin-table tbody tr td:first-child code')
      .evaluateAll((items) => items.map((item) => item.textContent?.trim()).filter(Boolean)));
    const tooth = ['11', '12', '13', '14', '15', '16', '17', '18', '21', '22', '23', '24',
      '25', '26', '27', '28', '31', '32', '33', '34', '35', '36', '37', '38', '41', '42',
      '43', '44', '45', '46', '47', '48'].find((candidate) => !occupiedTeeth.has(candidate));
    if (!tooth) throw new Error('当前隔离患者已占用全部恒牙牙位，无法创建新的原子闭环验收数据');
    await page.getByLabel('牙位记法').fill(tooth);
    const dentalRecord = await post('/dental-records', () => page.getByRole('button', { name: '创建口腔记录', exact: true }).click());
    await route('dental-treatment');
    await page.getByLabel('牙位').fill(tooth);
    await page.getByLabel('治疗类型').selectOption('FILLING');
    await page.getByLabel('治疗时间').fill(now);
    await page.getByLabel('材料批次').fill(`E2E-BATCH-${suffix}`);
    const treatment = await post('/dental-treatment-records', () => page.getByRole('button', { name: '创建口腔治疗记录', exact: true }).click());
    if (treatment.tooth_notation !== tooth) throw new Error(`口腔治疗未保留牙位：${JSON.stringify(treatment)}`);
    await route('dental-record');
    await page.locator('.admin-table tbody tr').filter({ hasText: tooth }).filter({ hasText: '已完成' }).waitFor({ state: 'visible', timeout: 30_000 });
    result.evidence.dental = { dental_record_id: dentalRecord.dental_record_id, treatment_id: treatment.dental_treatment_record_id };
    return '同牙位的 ACTIVE 口腔记录已由真实治疗记录原子迁移为已完成';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => undefined);
  await context.close(); await browser.close();
}
const artifact = resolve(outputDir, 'specialty-cross-layer-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
