import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const checks = [];
const findings = [];
let currentRoute = 'bootstrap';

page.on('pageerror', (error) => findings.push({ route: currentRoute, check: 'pageerror', detail: error.message }));
page.on('response', (response) => {
  if (response.url().includes('/api/v1/') && response.status() >= 500) {
    findings.push({ route: currentRoute, check: 'api-response', detail: `${response.status()} ${response.url()}` });
  }
});

async function route(id) {
  currentRoute = id;
  await page.goto(`${baseUrl}/#/${id}`, { waitUntil: 'domcontentloaded', timeout: 20_000 });
  await page.waitForFunction((routeId) => document.documentElement.dataset.routeId === routeId, id, { timeout: 10_000 });
  await page.locator('main [data-page-root]').waitFor({ state: 'visible', timeout: 10_000 });
  await page.waitForFunction(() => {
    const root = document.querySelector('main [data-page-root]');
    return root && !root.querySelector('.clinical-page-state.loading,.state-page:not(.error)');
  }, undefined, { timeout: 20_000 }).catch(() => {});
  await page.waitForTimeout(150);
}

async function check(name, action) {
  try {
    const observation = await action();
    checks.push({ name, status: 'PASS', ...(observation || {}) });
  } catch (error) {
    findings.push({ route: currentRoute, check: name, detail: error instanceof Error ? error.message : String(error) });
  }
}

function requireText(text, expected, message) {
  if (!text.includes(expected)) throw new Error(message || `未找到文本：${expected}`);
}

try {
  await route('clinical');
  await check('doctor-and-shift-visible', async () => {
    const doctor = page.locator('.topbar .user-meta');
    await doctor.waitFor({ state: 'visible' });
    const text = (await doctor.innerText()).replace(/\s+/g, ' ');
    requireText(text, '林医生');
    requireText(text, '今日 08:00–17:00');
    return { text };
  });

  await check('clinical-foundation-menu-keeps-unified-home', async () => {
    const text = (await page.locator('.center-nav').innerText()).replace(/\s+/g, ' ');
    for (const expected of ['业务门户', '统一首页', '预约挂号', '入院床位', '紧急访问']) requireText(text, expected);
    const unifiedHome = page.locator('.center-nav a', { hasText: '统一首页' });
    if (await unifiedHome.count() !== 1) throw new Error('统一首页必须作为唯一的临床通用入口显示');
    const href = await unifiedHome.getAttribute('href');
    if (href !== '#/unified-home') throw new Error(`统一首页入口地址错误：${href}`);
    return { text };
  });

  await route('unified-home');
  await check('unified-home-aggregates-real-authorized-work', async () => {
    if (await page.locator('.clinical-page-state.error').count()) throw new Error('统一首页聚合数据加载失败，未允许静态数字回退');
    const cards = page.locator('.unified-home-fact');
    if (await cards.count() !== 6) throw new Error(`预期 6 张聚合事实卡，实际 ${await cards.count()} 张`);
    const values = await cards.locator('b').allInnerTexts();
    if (values.some((value) => !/^\d+$/.test(value.trim()))) throw new Error(`聚合卡必须展示服务端计算出的数字，实际：${values.join(', ')}`);
    const score = (await page.locator('.hub-score b').innerText()).trim();
    if (!/^\d+$/.test(score)) throw new Error(`统一首页待办总数无效：${score}`);
    return { values, score };
  });

  await check('all-portal-buttons-route-correctly', async () => {
    const inventory = await page.locator('main [data-route-target]').evaluateAll((elements) => elements.map((element) => ({
      target: element.getAttribute('data-route-target'),
      label: (element.textContent || '').replace(/\s+/g, ' ').trim(),
    })));
    if (inventory.length !== 26) throw new Error(`预期 26 个业务入口，实际 ${inventory.length} 个`);
    for (let index = 0; index < inventory.length; index += 1) {
      if (currentRoute !== 'clinical') await route('clinical');
      const locator = page.locator('main [data-route-target]').nth(index);
      const target = await locator.getAttribute('data-route-target');
      const label = (await locator.innerText()).replace(/\s+/g, ' ').trim();
      await locator.click();
      await page.waitForFunction((routeId) => document.documentElement.dataset.routeId === routeId, target, { timeout: 8_000 });
      const actual = await page.evaluate(() => document.documentElement.dataset.routeId);
      if (actual !== target) throw new Error(`${label} 期望进入 ${target}，实际进入 ${actual}`);
      currentRoute = actual;
    }
    return { routes_checked: inventory.length, inventory };
  });

  await route('clinical');
  await page.screenshot({ path: resolve(outputDir, 'clinical-portal-1440x1000.png'), fullPage: true });

  await route('opd-orders');
  await check('portal-destination-loads-real-data', async () => {
    await page.locator('.orders-workspace').waitFor({ state: 'visible', timeout: 10_000 });
    if (await page.locator('.clinical-page-state.error').count()) throw new Error('医嘱处方入口打开后显示错误态');
    const count = await page.locator('.order-card').count();
    if (!count) throw new Error('医嘱处方入口未加载数据库数据');
    return { order_cards: count };
  });

  await route('appointment-registration');
  await check('appointment-patient-data-and-chinese-status', async () => {
    const root = page.locator('main [data-page-root]');
    const text = (await root.innerText()).replace(/\s+/g, ' ');
    if (!/[一-龥]{2,4}/.test(text) || /(合成患者|Synthetic Patient|测试患者)/i.test(text)) {
      throw new Error('预约/就诊列表缺少自然的模拟患者姓名，或仍含占位患者名');
    }
    if (/\b(BOOKED|CHECKED_IN|CANCELLED|NO_SHOW|COMPLETED|WAITING|CALLED|IN_CONSULTATION|SKIPPED|WALK_IN|APPOINTMENT)\b/.test(text)) {
      throw new Error('预约页面仍显示英文状态代码');
    }
    return { text_sample: text.slice(0, 500) };
  });
  await check('appointment-forms-open-on-demand', async () => {
    if (await page.locator('.appointment-form-panel').count()) throw new Error('表单应默认隐藏');
    await page.getByRole('button', { name: '班次号源', exact: true }).click();
    await page.getByRole('heading', { name: '班次号源', exact: true }).waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '预约/现场挂号', exact: true }).click();
    await page.getByRole('heading', { name: '预约 / 现场挂号', exact: true }).waitFor({ state: 'visible' });
    if (await page.getByRole('heading', { name: '班次号源', exact: true }).count()) throw new Error('切换后应只显示一个表单');
  });
  await check('appointment-lists-are-paginated', async () => {
    const appointmentRows = await page.locator('.admin-panel .admin-table').first().locator('tbody tr').count();
    const queueRows = await page.locator('.admin-panel .admin-table').nth(1).locator('tbody tr').count();
    if (appointmentRows > 10 || queueRows > 10) throw new Error(`分页后单页记录过多：${appointmentRows}/${queueRows}`);
    const appointmentPager = page.getByRole('navigation', { name: '预约与就诊分页' });
    const queuePager = page.getByRole('navigation', { name: '候诊队列分页' });
    await appointmentPager.waitFor({ state: 'visible' });
    await queuePager.waitFor({ state: 'visible' });
    if (!(await appointmentPager.getByRole('button', { name: '下一页' }).isDisabled())) {
      await appointmentPager.getByRole('button', { name: '下一页' }).click();
      await appointmentPager.getByText(/第 2 \/ \d+ 页/).waitFor({ state: 'visible' });
    }
    if (!(await queuePager.getByRole('button', { name: '下一页' }).isDisabled())) {
      await queuePager.getByRole('button', { name: '下一页' }).click();
      await queuePager.getByText(/第 2 \/ \d+ 页/).waitFor({ state: 'visible' });
    }
    return { appointment_rows: appointmentRows, queue_rows: queueRows };
  });
  await page.screenshot({ path: resolve(outputDir, 'appointment-registration-1440x1000.png'), fullPage: true });

  await route('emergency-access');
  await check('emergency-access-labels-are-chinese', async () => {
    const text = (await page.locator('main [data-page-root]').innerText()).replace(/\s+/g, ' ');
    if (/\b(ACTIVE|EXPIRED|REVOKED|REVIEWED|APPROPRIATE|INAPPROPRIATE|ESCALATED|CLINICAL_CONTEXT|DOCUMENT|LEASE_ISSUE|READ)\b/.test(text)) {
      throw new Error('紧急访问页面仍显示英文代码');
    }
    return { text_sample: text.slice(0, 500) };
  });
  await page.screenshot({ path: resolve(outputDir, 'emergency-access-1440x1000.png'), fullPage: true });
} finally {
  await browser.close();
}

const result = {
  run_at: new Date().toISOString(),
  checks: checks.length + findings.length,
  passed: checks.length,
  failed: findings.length,
  findings,
  observations: checks,
};
const outputPath = resolve(outputDir, 'clinical-portal-acceptance.json');
await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ checks: result.checks, passed: result.passed, failed: result.failed, artifact: outputPath }));
if (findings.length) process.exitCode = 1;
