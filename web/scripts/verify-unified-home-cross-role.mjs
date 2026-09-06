import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/unified-home-cross-role');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const password = process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!';
const result = { run_at: new Date().toISOString(), checks: [], findings: [], evidence: {} };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });

async function check(name, action) {
  try { result.checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error) }); }
}

async function loadHome(username) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  try {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: '登录系统', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await page.getByRole('button', { name: '登录系统', exact: true }).click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await page.goto(`${baseUrl}/#/unified-home`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '统一首页与任务中心', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForFunction(() => !((document.querySelector('.portal-safety .status')?.textContent) || '').includes('正在刷新'), undefined, { timeout: 30_000 });
    return {
      status: (await page.locator('.portal-safety .status').innerText()).trim(),
      errorCards: await page.locator('.clinical-page-state.error').count(),
      values: await page.locator('.unified-home-fact b').allInnerTexts(),
      details: await page.locator('.unified-home-fact small').allInnerTexts(),
    };
  } finally {
    await page.screenshot({ path: resolve(outputDir, `${username}-final-state.png`), fullPage: true }).catch(() => undefined);
    await context.close();
  }
}

try {
  await check('clinician-gets-complete-authorized-home-aggregation', async () => {
    const observation = await loadHome('linwei');
    if (observation.errorCards !== 0 || observation.status !== '聚合数据已加载') throw new Error(JSON.stringify(observation));
    if (observation.values.length !== 6 || observation.values.some((value) => !/^\d+$/.test(value.trim()))) throw new Error(JSON.stringify(observation));
    result.evidence.clinician = observation;
    return '临床医生读取到六项实际授权聚合，未发生页面错误或静态回退';
  });

  await check('pharmacist-keeps-authorized-facts-and-labels-denied-sources', async () => {
    const observation = await loadHome('qinghua.deng');
    if (observation.errorCards !== 0 || observation.status !== '部分来源无读取范围（2）') throw new Error(JSON.stringify(observation));
    if (observation.values[4] !== '8') throw new Error(`药师可读候诊事实异常：${JSON.stringify(observation)}`);
    if (observation.values.filter((value) => value === '—').length !== 5) throw new Error(`未授权来源必须显示为未计入而非 0：${JSON.stringify(observation)}`);
    if (!observation.details.some((detail) => detail.includes('TEAM_MEMBERSHIP_REQUIRED'))
      || !observation.details.some((detail) => detail.includes('WARD_SCOPE_DENIED'))) {
      throw new Error(`拒绝原因未在页面明确：${JSON.stringify(observation)}`);
    }
    result.evidence.pharmacist = observation;
    return '药师保留可读候诊事实，团队与病区拒绝均明确为未计入汇总';
  });
} finally {
  await browser.close();
}

const artifact = resolve(outputDir, 'unified-home-cross-role.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
