import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/clinical-portal-cross-role');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const result = { run_at: new Date().toISOString(), checks: [], findings: [], evidence: {} };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });

async function check(name, action) {
  try { result.checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error) }); }
}

async function loadPortal(username) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  try {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await page.getByRole('button', { name: '登录系统', exact: true }).click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await page.goto(`${baseUrl}/#/clinical`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '临床业务门户', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForFunction(() => !Array.from(document.querySelectorAll('.domain-metrics b')).some((item) => item.textContent?.trim() === '…'), undefined, { timeout: 30_000 });
    return {
      role: (await page.getByRole('button', { name: '选择角色' }).innerText()).replace(/\s+/g, ' ').trim(),
      outpatient: await page.locator('.outpatient-domain .domain-metrics b').allInnerTexts(),
      emergency: await page.locator('.emergency-domain .domain-metrics b').allInnerTexts(),
      inpatient: await page.locator('.inpatient-domain .domain-metrics b').allInnerTexts(),
      inpatientAlert: (await page.locator('.inpatient-domain .domain-alerts').innerText()).replace(/\s+/g, ' ').trim(),
      inpatientScope: (await page.locator('.inpatient-domain .domain-footer').innerText()).replace(/\s+/g, ' ').trim(),
    };
  } finally {
    await page.screenshot({ path: resolve(outputDir, `${username}-portal.png`), fullPage: true }).catch(() => undefined);
    await context.close();
  }
}

try {
  await check('clinician-portal-metrics-are-server-facts', async () => {
    const observation = await loadPortal('linwei');
    if (!observation.role.includes('CLINICIAN')) throw new Error(JSON.stringify(observation));
    if (observation.outpatient.length !== 6 || observation.inpatient.length !== 6
      || observation.outpatient.some((value) => !/^\d+$/.test(value.trim()))
      || observation.inpatient.some((value) => !/^\d+$/.test(value.trim()))) throw new Error(JSON.stringify(observation));
    result.evidence.clinician = observation;
    return '临床医生读取门诊、急诊、住院三域的服务端指标，未使用静态或加载回退值';
  });

  await check('pharmacist-portal-labels-denied-inpatient-scope', async () => {
    const observation = await loadPortal('qinghua.deng');
    if (!observation.role.includes('PHARMACIST')) throw new Error(JSON.stringify(observation));
    if (observation.outpatient.length !== 6 || observation.outpatient.some((value) => !/^\d+$/.test(value.trim()))) throw new Error(JSON.stringify(observation));
    if (observation.inpatient.length !== 6 || observation.inpatient.some((value) => value.trim() !== '—')) throw new Error(JSON.stringify(observation));
    if (!observation.inpatientAlert.includes('WARD_SCOPE_DENIED') || !observation.inpatientScope.includes('WARD_SCOPE_DENIED')) throw new Error(JSON.stringify(observation));
    result.evidence.pharmacist = observation;
    return '药师保留可授权门诊事实；无病区范围时以拒绝码和未回退说明代替零值或历史值';
  });
} finally {
  await browser.close();
}

const artifact = resolve(outputDir, 'clinical-portal-cross-role.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
