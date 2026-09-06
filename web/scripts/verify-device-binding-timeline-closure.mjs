import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/device-binding-timeline-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
let patientId = process.env.HAOEMR_DEVICE_PATIENT_ID || '018f0000-0000-7000-8000-000000000001';
let encounterId = process.env.HAOEMR_DEVICE_ENCOUNTER_ID || '018f0000-0000-7000-8000-000000000101';
const deviceCode = `DEVICE-TIMELINE-E2E-${Date.now()}`;
const result = { run_at: new Date().toISOString(), device_code: deviceCode, patient_id: patientId, encounter_id: encounterId, checks: [], findings: [] };
let projectedItemCount = 0;

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined,
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) {
  try {
    console.log(JSON.stringify({ check: name, stage: 'started' }));
    result.checks.push({ name, status: 'PASS', detail: await action() });
    console.log(JSON.stringify({ check: name, stage: 'passed' }));
  } catch (error) {
    result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() });
    console.log(JSON.stringify({ check: name, stage: 'failed' }));
  }
}

async function login() {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  const loginButton = page.getByRole('button', { name: '登录系统', exact: true });
  await loginButton.waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.locator('#system-login-password').fill(password);
  await loginButton.click();
  await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
}

async function openMonitoring() {
  await page.goto(`${baseUrl}/#/device-monitoring`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '设备监护', exact: true }).waitFor({ timeout: 30_000 });
  const queue = page.locator('[data-execution-patient-list]');
  const search = queue.getByLabel('患者检索', { exact: true });
  let row = queue.locator('[data-execution-patient-row]').filter({ hasText: patientId });
  await search.fill(patientId);
  if (!await row.first().isVisible().catch(() => false)) {
    await search.fill('');
    row = queue.locator('[data-execution-patient-row]');
  }
  await row.first().waitFor({ state: 'visible', timeout: 30_000 });
  const ids = (await row.first().innerText()).match(/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}/ig);
  if (!ids || ids.length < 2) throw new Error('设备监护队列未提供可核验的患者与就诊标识');
  patientId = ids[0];
  encounterId = ids[1];
  result.patient_id = patientId;
  result.encounter_id = encounterId;
  await row.first().getByRole('button', { name: '选择患者并下转', exact: true }).click();
  await page.getByRole('heading', { name: '设备接入与遥测监测', exact: true }).waitFor({ timeout: 30_000 });
}

async function collectCurrentDevice() {
  await page.getByRole('button', { name: '采集遥测', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({ hasText: '采集设备遥测' });
  await dialog.getByRole('combobox').first().selectOption(deviceCode);
  await dialog.getByRole('spinbutton').fill('12');
  await dialog.getByRole('button', { name: '采集', exact: true }).click();
  await page.getByText('已采集 12 条遥测并刷新设备状态。', { exact: true }).waitFor({ timeout: 30_000 });
}

async function deviceRow() {
  const row = page.locator('.admin-table tbody tr').filter({ hasText: deviceCode });
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  return row;
}

async function showOnlyDeviceTimeline() {
  await page.goto(`${baseUrl}/#/patient-timeline`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '授权患者纵向时间线', exact: true }).waitFor({ timeout: 30_000 });
  await page.getByLabel('患者 ID', { exact: true }).fill(patientId);
  for (const label of ['就诊', '文书', '诊断', '医嘱', '结果', '任务']) {
    await page.locator('.timeline-source-picker button').filter({ hasText: label }).click();
  }
  await page.getByRole('button', { name: '应用筛选', exact: true }).click();
  await page.getByText('设备', { exact: true }).first().waitFor({ timeout: 30_000 });
  await page.getByText('DEVICE_GATEWAY_SIMULATED', { exact: false }).first().waitFor({ timeout: 30_000 });
  const currentDeviceItems = page.locator('.timeline-stream > article').filter({ hasText: deviceCode });
  await currentDeviceItems.first().waitFor({ state: 'visible', timeout: 30_000 });
  return currentDeviceItems.count();
}

try {
  await check('register-isolated-active-device-through-ui', async () => {
    await login();
    await page.goto(`${baseUrl}/#/devices`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '医疗设备目录与接入状态', exact: true }).waitFor({ timeout: 30_000 });
    await page.getByRole('button', { name: '新建设备', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '新建设备' });
    await dialog.getByLabel('设备编码', { exact: true }).fill(deviceCode);
    await dialog.getByLabel('显示名', { exact: true }).fill('设备绑定时间线闭环验证仪');
    await dialog.getByLabel('责任科室', { exact: true }).fill('闭环验证');
    await dialog.getByLabel('接入网关', { exact: true }).fill('DEVICE_GATEWAY');
    await dialog.getByRole('button', { name: '登记', exact: true }).click();
    await page.locator('.admin-table tbody tr').filter({ hasText: deviceCode }).waitFor({ state: 'visible', timeout: 30_000 });
    return '通过设备目录页面登记了独立的活动测试设备';
  });

  await check('bind-verified-encounter-and-project-post-bind-telemetry', async () => {
    await openMonitoring();
    await collectCurrentDevice();
    const row = await deviceRow();
    await row.getByRole('button', { name: '绑定患者', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '绑定设备与患者就诊' });
    const fields = dialog.locator('input');
    await fields.nth(0).fill(patientId);
    await fields.nth(1).fill(encounterId);
    await dialog.getByRole('button', { name: '确认绑定', exact: true }).click();
    const bindingNotice = page.locator('.admin-notice');
    await bindingNotice.waitFor({ state: 'visible', timeout: 30_000 });
    const bindingNoticeText = await bindingNotice.innerText();
    if (!bindingNoticeText.includes('已绑定到已核验患者就诊')) {
      throw new Error(`设备绑定未完成：${bindingNoticeText}`);
    }
    await collectCurrentDevice();
    projectedItemCount = await showOnlyDeviceTimeline();
    if (projectedItemCount < 1) throw new Error('绑定后时间线没有显示设备遥测或告警投影');
    return `绑定后时间线显示本次设备的 ${projectedItemCount} 条证据，并标明模拟网关来源`;
  });

  await check('unbind-preserves-history-and-stops-new-projection', async () => {
    await openMonitoring();
    const row = await deviceRow();
    await row.getByRole('button', { name: '解绑', exact: true }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '解绑设备与患者就诊' });
    await dialog.getByLabel('解绑原因', { exact: true }).fill('闭环验证完成，设备退出患者监测上下文');
    await dialog.getByRole('button', { name: '确认解绑', exact: true }).click();
    await page.getByText('已解绑；解绑前的遥测和告警仍保留在原患者时间线中。', { exact: false }).waitFor({ timeout: 30_000 });
    const historyCount = await showOnlyDeviceTimeline();
    if (historyCount !== projectedItemCount) throw new Error(`解绑后历史投影数量变化：预期 ${projectedItemCount}，实际 ${historyCount}`);
    await openMonitoring();
    await collectCurrentDevice();
    const afterUnboundCount = await showOnlyDeviceTimeline();
    if (afterUnboundCount !== projectedItemCount) throw new Error(`解绑后的新遥测仍被投影：预期 ${projectedItemCount}，实际 ${afterUnboundCount}`);
    return `解绑保留 ${historyCount} 条历史设备证据，解绑后的新遥测未进入患者时间线`;
  });
} finally {
  await page.goto(`${baseUrl}/#/devices`, { waitUntil: 'domcontentloaded' }).catch(() => {});
  const deviceRowForCleanup = page.locator('.admin-table tbody tr').filter({ hasText: deviceCode });
  if (await deviceRowForCleanup.isVisible().catch(() => false)) {
    await deviceRowForCleanup.getByRole('button', { name: '停用', exact: true }).click().catch(() => {});
    const confirmation = page.getByRole('dialog').filter({ hasText: '停用设备' });
    await confirmation.getByRole('button', { name: '确认停用', exact: true }).click().catch(() => {});
  }
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'device-binding-timeline-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
