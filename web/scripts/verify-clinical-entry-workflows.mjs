import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4178').replace(/\/$/, '');
await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const checks = [];
const failures = [];
page.on('pageerror', (error) => failures.push(`pageerror: ${error.message}`));
page.on('response', (response) => {
  if (response.url().includes('/api/v1/') && response.status() >= 500) failures.push(`${response.status()} ${response.url()}`);
});

async function check(name, action) {
  try { checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { failures.push(`${name}: ${error instanceof Error ? error.message : String(error)}`); }
}

async function route(id) {
  await page.goto(`${baseUrl}/#/${id}`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction((value) => document.documentElement.dataset.routeId === value, id, { timeout: 15_000 });
}

try {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.documentElement.dataset.routeId === 'login-context', undefined, { timeout: 15_000 });
  await check('standalone-system-login-page', async () => {
    const shellCount = await page.locator('.vue-clinical-shell,.topbar,.sidebar').count();
    if (shellCount) throw new Error(`登录页仍渲染了 ${shellCount} 个业务壳元素`);
    await page.locator('main.system-login-page').waitFor({ timeout: 10_000 });
    if (!page.url().includes('#/login')) throw new Error(`非系统登录路由：${page.url()}`);
    await page.getByRole('heading', { name: '电子病历系统', exact: true }).waitFor();
    if ((await page.title()) !== '电子病历系统') throw new Error(`浏览器标题仍为：${await page.title()}`);
    const loginLogo = page.getByTestId('system-login-logo');
    if (!((await loginLogo.getAttribute('src')) || '').endsWith('/brand/haonan-medical-ai-logo.png')) {
      throw new Error('登录页未复用首页左上角 Logo');
    }
    await page.screenshot({ path: resolve(outputDir, 'system-login-1440x1000.png'), fullPage: true });
    return '独立 /login 页面，复用首页 Logo，系统中文名为电子病历系统，无业务门户壳';
  });
  await check('database-login', async () => {
    await page.getByLabel('用户名').fill('linwei');
    await page.getByLabel('密码', { exact: true }).fill('HaoEMR-dev!');
    await page.getByRole('button', { name: '登录系统', exact: true }).click();
    await page.waitForURL(/#\/clinical$/, { timeout: 15_000 });
    const header = (await page.locator('.topbar .user-meta').innerText()).replace(/\s+/g, ' ');
    if (!header.includes('林伟 / William Lin') || !header.includes('今日 08:00–17:00')) throw new Error(header);
    return header;
  });

  await route('appointment-registration');
  await check('patient-first-appointment-flow', async () => {
    await page.getByPlaceholder('身份证 / 就诊卡 / 姓名').fill('张慧敏');
    await page.getByRole('button', { name: '检索', exact: true }).click();
    await page.getByRole('button', { name: /张慧敏/ }).first().click();
    await page.waitForTimeout(2_000);
    const text = (await page.locator('main').innerText()).replace(/\s+/g, ' ');
    for (const expected of ['江城大学附属医院本部', '心血管内科', '林伟 / William Lin']) {
      if (!text.includes(expected)) throw new Error(`缺少 ${expected}；页面：${text.slice(0, 800)}`);
    }
    if (text.includes('号源 ID') || text.includes('创建班次号源')) throw new Error('预约页面仍暴露号源 UUID 或号源维护');

    await page.getByRole('button', { name: '新建预约' }).click();
    const appointmentDialog = page.getByRole('dialog', { name: '新建预约' });
    const slotSelect = appointmentDialog.getByLabel('可预约班次');
    if (!(await slotSelect.inputValue())) await slotSelect.selectOption({ index: 1 });
    await page.getByRole('button', { name: '新建预约' }).click();
    const secondSlotSelect = page.getByRole('dialog', { name: '新建预约' }).getByLabel('可预约班次');
    if (!(await secondSlotSelect.inputValue())) await secondSlotSelect.selectOption({ index: 1 });
    await page.getByRole('button', { name: '确认预约挂号' }).click();
    await page.getByText('预约挂号已写入数据库。', { exact: true }).waitFor({ timeout: 15_000 });

    let appointmentRow = page.locator('.admin-table').first().locator('tbody tr').first();
    await appointmentRow.getByRole('button', { name: '报到' }).click();
    await page.waitForTimeout(1_000);
    const reportNotice = await page.locator('.admin-notice').innerText();
    if (reportNotice !== '报到成功，已进入候诊队列。') throw new Error(`报到后提示：${reportNotice}`);

    const callButton = page.locator('.admin-table').nth(1).locator('button:not([disabled])', { hasText: '叫号' }).first();
    await callButton.click();
    await page.getByText(/^已叫号 #/, { exact: false }).waitFor({ timeout: 15_000 });

    appointmentRow = page.locator('.admin-table').first().locator('tbody tr').first();
    await appointmentRow.getByRole('button', { name: '接诊' }).click();
    await page.getByText('接诊已开始。', { exact: true }).waitFor({ timeout: 15_000 });

    await page.getByRole('button', { name: '确认预约挂号' }).click();
    await page.getByText('预约挂号已写入数据库。', { exact: true }).waitFor({ timeout: 15_000 });
    const enabledCancellation = page.locator('.admin-table').first().locator('button:not([disabled])', { hasText: '退号' }).first();
    await enabledCancellation.click();
    const cancelDialog = page.getByRole('dialog', { name: '删除预约·退号' });
    await cancelDialog.getByLabel('退号原因').fill('患者行程变更');
    await cancelDialog.getByRole('button', { name: '确认退号' }).click();
    await page.getByText('退号完成，号源已释放，取消理由已留痕。', { exact: true }).waitFor({ timeout: 15_000 });

    await page.screenshot({ path: resolve(outputDir, 'appointment-registration-v2-1440x1000.png'), fullPage: true });
    return '患者/医院/科室/医生/班次可见；预约、报到、叫号、接诊、退号全部写库成功';
  });

  await route('workflow');
  await check('schedule-configuration-moved', async () => {
    const panel = page.getByRole('heading', { name: '班次号源配置' });
    await panel.waitFor({ timeout: 15_000 });
    await page.waitForFunction(() => document.querySelector('main')?.textContent?.includes('林伟 / William Lin'), undefined, { timeout: 15_000 });
    const text = (await panel.locator('xpath=ancestor::section[1]').innerText()).replace(/\s+/g, ' ');
    if (!text.includes('心血管内科') || !text.includes('林伟 / William Lin')) throw new Error(text.slice(0, 400));
    return '业务配置已显示科室、医生与号源列表';
  });

  await route('admission-bed');
  await check('realistic-bed-board-and-complete-form', async () => {
    await page.getByRole('heading', { name: '当前病区床位图' }).waitFor({ timeout: 15_000 });
    const beds = await page.locator('.bed-card').count();
    if (beds < 12) throw new Error(`仅 ${beds} 张床`);
    const text = (await page.locator('main').innerText()).replace(/\s+/g, ' ');
    for (const expected of ['心血管内科-01床', '入院途径', '入院病情', '入院诊断', '付费方式', '身份核验', '联系人姓名', '联系人电话']) {
      if (!text.includes(expected)) throw new Error(`缺少 ${expected}`);
    }
    await page.screenshot({ path: resolve(outputDir, 'admission-bed-v2-1440x1000.png'), fullPage: true });
    return `${beds} 张科室-床号床位，完整入院登记字段可见`;
  });

  await check('database-logout', async () => {
    await route('clinical');
    await page.getByRole('button', { name: '用户登录与账户' }).click();
    await page.getByRole('region', { name: '用户账户' }).getByRole('button', { name: '退出系统' }).click();
    await page.getByRole('button', { name: '登录系统', exact: true }).waitFor();
    if (await page.locator('.vue-clinical-shell,.topbar,.sidebar').count()) throw new Error('退出后登录页仍显示业务壳');
    const response = await page.request.get(`${baseUrl}/api/v1/session/current`);
    if (response.status() !== 401) throw new Error(`退出后 current 返回 ${response.status()}`);
    return '退出后回到独立系统登录页，旧会话不可用';
  });
  await check('mobile-system-login-layout', async () => {
    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
    try {
      await mobile.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
      await mobile.locator('main.system-login-page').waitFor({ timeout: 10_000 });
      const overflow = await mobile.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
      if (overflow) throw new Error('移动端登录页存在横向溢出');
      if (await mobile.locator('.vue-clinical-shell,.topbar,.sidebar').count()) throw new Error('移动端登录页出现业务壳');
      await mobile.screenshot({ path: resolve(outputDir, 'system-login-390x844.png'), fullPage: true });
      return '390×844 无溢出，无业务壳';
    } finally { await mobile.close(); }
  });
} finally { await browser.close(); }

const result = { run_at: new Date().toISOString(), passed: checks.length, failed: failures.length, checks, failures };
const artifact = resolve(outputDir, 'clinical-entry-workflows-acceptance.json');
await writeFile(artifact, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }));
if (failures.length) process.exitCode = 1;
