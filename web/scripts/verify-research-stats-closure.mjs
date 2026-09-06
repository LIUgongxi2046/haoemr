import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/research-stats-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const result = { run_at: new Date().toISOString(), checks: [], findings: [] };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) {
  try { console.log(JSON.stringify({ check: name, stage: 'started' })); result.checks.push({ name, status: 'PASS', detail: await action() }); console.log(JSON.stringify({ check: name, stage: 'passed' })); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); console.log(JSON.stringify({ check: name, stage: 'failed' })); }
}

async function calculateAndInspect() {
  await page.getByRole('button', { name: '按登记口径计算', exact: true }).click();
  await page.getByText('已按登记口径从队列、成员与数据申请事实表重算科研统计快照。', { exact: true }).waitFor({ timeout: 30_000 });
  const cards = page.locator('.research-metrics .metric');
  if (await cards.count() !== 4) throw new Error(`最新计算批次未显示完整四项指标：${await cards.count()}`);
  for (const label of ['队列快照', '纳入成员', '平均队列规模', '已输出研究集']) {
    const card = cards.filter({ hasText: label });
    await card.first().waitFor({ state: 'visible', timeout: 30_000 });
    const text = await card.first().innerText();
    if (text.includes('—')) throw new Error(`${label} 未读取到当前快照值：${text}`);
  }
  const ageBars = page.locator('.bar-chart > div');
  const trendPoints = page.locator('.trend-points > article');
  if (await ageBars.count() !== 4) throw new Error(`年龄分层未来自完整快照：${await ageBars.count()} 组`);
  if (await trendPoints.count() !== 5) throw new Error(`季度趋势未按物化账本展示五个周期：${await trendPoints.count()} 个点`);
  const header = await page.locator('.page-title').innerText();
  const suffix = header.match(/最新快照\s*…([0-9a-f]{8})/i)?.[1];
  if (!suffix) throw new Error(`页面未显示当前统计快照标识：${header}`);
  return { suffix, ageGroups: await ageBars.count(), quarters: await trendPoints.count() };
}

try {
  await check('compute-and-render-current-materialized-research-statistics', async () => {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    const login = page.getByRole('button', { name: '登录系统', exact: true });
    await login.waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await login.click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await page.goto(`${baseUrl}/#/research-stats`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '科研统计分析', exact: true }).waitFor({ timeout: 30_000 });
    const first = await calculateAndInspect();
    return `当前计算批次展示 4 项指标、${first.ageGroups} 个年龄分层及 ${first.quarters} 个季度趋势点，快照后缀 ${first.suffix}`;
  });

  await check('recompute-replaces-visible-calculation-batch-without-mixing-old-points', async () => {
    const second = await calculateAndInspect();
    const cards = page.locator('.research-metrics .metric');
    if (await cards.count() !== 4) throw new Error('二次计算后页面混入旧批次指标');
    return `二次计算仍只展示当前批次 4 项指标、${second.ageGroups} 个年龄分层与 ${second.quarters} 个趋势点，快照后缀 ${second.suffix}`;
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'research-stats-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
