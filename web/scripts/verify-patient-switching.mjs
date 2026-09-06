import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const sequences = [101, 102, 103, 104, 105, 106];
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const failedResponses = [];
const observations = [];
const findings = [];
page.on('response', (response) => {
  if (response.status() >= 400 && response.url().includes('/api/v1/')) {
    failedResponses.push({ status: response.status(), url: response.url() });
  }
});

try {
  await page.goto(`${baseUrl}/#/login-context`, { waitUntil: 'domcontentloaded', timeout: 15_000 });
  const loginButton = page.getByRole('button', { name: '登录系统', exact: true });
  if (await loginButton.count()) {
    await page.getByLabel('用户名').fill('linwei');
    await page.getByLabel('密码', { exact: true }).fill('HaoEMR-dev!');
    await loginButton.click();
    await page.waitForURL(/#\/clinical$/, { timeout: 20_000 });
  }
  await page.goto(`${baseUrl}/#/outpatient`, { waitUntil: 'domcontentloaded', timeout: 15_000 });
  await page.locator('[data-select-outpatient-patient]').first().waitFor({ state: 'visible', timeout: 25_000 });

  for (const sequence of sequences) {
    try {
      const queueRow = page.locator(`[data-queue-sequence="${sequence}"]`);
      await queueRow.waitFor({ state: 'attached', timeout: 10_000 });
      await queueRow.locator('[data-select-outpatient-patient]').click();
      await page.waitForFunction((expectedSequence) => {
        const active = document.querySelector(`[data-queue-sequence="${expectedSequence}"].active`);
        return Boolean(active);
      }, sequence, { timeout: 20_000 });
      await page.locator('.encounter-editor').waitFor({ state: 'visible', timeout: 20_000 });
      const snapshot = await page.evaluate(() => {
      const patient = document.querySelector('.patient-strip');
      const summary = document.querySelector('.previsit-summary p')?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
      return {
        patient_name: patient?.querySelector('strong')?.textContent?.trim() ?? '',
        patient_context: patient?.textContent?.replace(/\s+/g, ' ').trim() ?? '',
        document: document.querySelector('.save-state')?.textContent?.replace(/\s+/g, ' ').trim() ?? '',
        diagnosis: document.querySelector('.outpatient-patient-strip dl > div:nth-child(3) dd')?.textContent?.trim() ?? '',
        order_items: summary,
        results: summary,
      };
      });
      if (!/^[一-龥]{2,4}$/.test(snapshot.patient_name)
          || /(合成|测试|患者)/.test(snapshot.patient_name)) {
        throw new Error(`患者姓名不符合自然模拟姓名规范：${snapshot.patient_name}`);
      }
      if (!snapshot.document || snapshot.document.includes('尚未建立')) throw new Error('当前文书未加载');
      if (!snapshot.diagnosis || snapshot.diagnosis.includes('待完成诊断')) throw new Error('主诊断未加载');
      if (!/\d+ 条活动医嘱/.test(snapshot.order_items)) throw new Error(`医嘱摘要未加载：${snapshot.order_items}`);
      if (!/\d+ 份结果/.test(snapshot.results)) throw new Error(`结果摘要未加载：${snapshot.results}`);
      observations.push({ sequence_no: sequence, ...snapshot });
    } catch (error) {
      const diagnostic = await page.evaluate(() => ({
        body: document.querySelector('main')?.textContent?.replace(/\s+/g, ' ').trim().slice(0, 1_500) ?? '',
        error: document.querySelector('.clinical-page-state.error,.state-page.error')?.textContent?.replace(/\s+/g, ' ').trim() ?? '',
        active: document.querySelector('.queue-list article.active')?.textContent?.replace(/\s+/g, ' ').trim() ?? '',
      }));
      findings.push({ sequence_no: sequence, detail: error instanceof Error ? error.message : String(error), diagnostic });
      break;
    }
  }

  const contexts = new Set(observations.map((item) => item.patient_context));
  const patientNames = new Set(observations.map((item) => item.patient_name));
  const diagnoses = new Set(observations.map((item) => item.diagnosis));
  if (contexts.size !== sequences.length) findings.push({ detail: `患者上下文未全部切换：${contexts.size}/${sequences.length}` });
  if (patientNames.size !== sequences.length) findings.push({ detail: `患者姓名未全部体现差异：${patientNames.size}/${sequences.length}` });
  if (diagnoses.size !== sequences.length) findings.push({ detail: `疾病数据未体现差异：${diagnoses.size}/${sequences.length}` });
  if (failedResponses.length) findings.push({ detail: `患者切换期间出现 ${failedResponses.length} 个 API 失败` });
  await page.screenshot({ path: resolve(outputDir, 'patient-switching-1440x1000.png'), fullPage: true });
} finally {
  await browser.close();
}

const result = {
  run_at: new Date().toISOString(),
  attempted: sequences.length,
  passed: observations.length,
  failed_responses: failedResponses,
  findings,
  observations,
};
const outputPath = resolve(outputDir, 'patient-switching-audit.json');
await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ attempted: result.attempted, passed: result.passed, failed_responses: failedResponses.length, findings, artifact: outputPath }));
if (findings.length) process.exitCode = 1;
