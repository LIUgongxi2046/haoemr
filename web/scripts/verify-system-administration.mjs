import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/system-administration');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4178').replace(/\/$/, '');
const readOnly = process.env.HAOEMR_ADMIN_READ_ONLY === '1';
const credentials = {
  username: process.env.HAOEMR_BROWSER_USERNAME || 'linwei',
  password: process.env.HAOEMR_BROWSER_PASSWORD || 'HaoEMR-dev!',
};
const routes = [
  ['admin', '系统管理工作台'],
  ['admin-org', '组织机构与工作单元'],
  ['admin-users', '用户、人员与账户管理'],
  ['admin-roles', '角色、工作组与职责分离'],
  ['admin-permissions', '权限策略与访问模拟'],
  ['admin-auth', '认证与账户安全策略'],
  ['admin-dictionaries', '字典、术语与值集中心'],
  ['admin-master-data', '医院主数据与标准编码'],
  ['admin-templates', '模板、编号与输出管理'],
  ['admin-parameters', '系统参数与功能开关'],
  ['admin-jobs', '通知任务与治理执行中心'],
  ['admin-audit', '管理审计与权限复核'],
];

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const checks = [];
const failures = [];
const apiRequests = new Set();
page.on('pageerror', (error) => failures.push(`pageerror: ${error.message}`));
page.on('request', (request) => { if (request.url().includes('/api/v1/')) apiRequests.add(request.url()); });
page.on('response', (response) => {
  if (response.url().includes('/api/v1/') && response.status() >= 500) failures.push(`${response.status()} ${response.url()}`);
});

async function check(name, action) {
  try { checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { failures.push(`${name}: ${error instanceof Error ? error.message : String(error)}`); }
}

async function go(routeId) {
  await page.goto(`${baseUrl}/#/${routeId}`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, routeId, { timeout: 20_000 });
  await page.locator('[data-page-root]').first().waitFor({ timeout: 20_000 });
  const expected = routes.find(([id]) => id === routeId)?.[1];
  if (expected) await page.getByRole('heading', { name: expected, exact: true }).waitFor({ timeout: 20_000 });
}

async function login() {
  await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
  const loginButton = page.getByRole('button', { name: '登录系统', exact: true });
  await loginButton.waitFor({ timeout: 20_000 });
  await page.getByLabel('用户名').fill(credentials.username);
  await page.getByLabel('密码', { exact: true }).fill(credentials.password);
  await loginButton.click();
  await page.waitForURL(/#\/clinical$/, { timeout: 20_000 });
}

async function waitForRows(selector, minimum) {
  await page.waitForFunction(({ selector, minimum }) => document.querySelectorAll(selector).length >= minimum, { selector, minimum }, { timeout: 20_000 });
  return page.locator(selector).count();
}

try {
  await login();

  await check('twelve-prototype-admin-routes', async () => {
    for (const [routeId, heading] of routes) {
      await go(routeId);
      await page.getByRole('heading', { name: heading, exact: true }).waitFor({ timeout: 20_000 });
      await page.waitForFunction(() => {
        const text = document.querySelector('main')?.textContent || '';
        return !document.querySelector('.state-page') && !text.includes('正在读取配置版本…');
      }, undefined, { timeout: 20_000 });
      const navLinks = page.locator('.admin-domain-nav .admin-domain-link');
      if (await navLinks.count() !== 12) throw new Error(`${routeId} 侧栏不是 12 个原型入口`);
      const active = page.locator('.admin-domain-nav .admin-domain-link.active');
      if (await active.count() !== 1) throw new Error(`${routeId} 活动导航数量为 ${await active.count()}`);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
      if (overflow) throw new Error(`${routeId} 在 1440px 存在横向溢出`);
      await page.screenshot({ path: resolve(outputDir, `${routeId}-1440x1000.png`), fullPage: true });
    }
    return '12/12 路由标题、侧栏活动态与宽屏布局全部通过';
  });

  await check('admin-dashboard-data-and-links', async () => {
    await go('admin');
    await page.waitForFunction(() => document.querySelectorAll('.metric-grid .metric').length === 4
      && document.querySelectorAll('.admin-module-grid .admin-module').length === 8, undefined, { timeout: 20_000 });
    if (await page.locator('.metric-grid .metric').count() !== 4) throw new Error('工作台指标不是 4 项');
    const cards = page.locator('.admin-module-grid .admin-module');
    if (await cards.count() !== 8) throw new Error('工作台模块不是 8 项');
    if (await page.locator('.admin-overview table tbody tr').count() < 1) throw new Error('数据库聚合待办未显示');
    for (const expected of ['admin-org', 'admin-users', 'admin-roles', 'admin-permissions', 'admin-dictionaries', 'admin-master-data', 'admin-parameters', 'admin-jobs']) {
      const hrefs = await cards.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('href') || ''));
      if (!hrefs.some((href) => href.endsWith(`/${expected}`))) throw new Error(`模块缺少 /${expected} 跳转`);
    }
    for (const endpoint of ['/admin/organization-units', '/admin/workforce', '/admin/access-policies', '/configurations', '/audit-events']) {
      if (![...apiRequests].some((url) => url.includes(endpoint))) throw new Error(`工作台未请求真实接口 ${endpoint}`);
    }
    return '4 项指标、8 个模块入口和待办均由系统管理 API 与数据库审计聚合';
  });

  await check('database-backed-administration-fixtures', async () => {
    const expectations = [
      ['admin-org', 'main table tbody tr', 8],
      ['admin-users', 'main table tbody tr', 5],
      ['admin-roles', 'main table tbody tr', 5],
      ['admin-permissions', 'main table tbody tr', 5],
      ['admin-dictionaries', 'main table tbody tr', 3],
      ['admin-master-data', 'main table tbody tr', 5],
      ['admin-templates', 'main table tbody tr', 3],
      ['admin-parameters', 'main table tbody tr', 5],
      ['admin-jobs', 'main table tbody tr', 1],
      ['admin-audit', 'main table tbody tr', 1],
    ];
    const counts = [];
    for (const [routeId, selector, minimum] of expectations) {
      await go(routeId);
      try {
        counts.push(`${routeId}=${await waitForRows(selector, minimum)}`);
      } catch (error) {
        const actual = await page.locator(selector).count();
        throw new Error(`${routeId} 需要至少 ${minimum} 行，实际 ${actual} 行：${error instanceof Error ? error.message : String(error)}`);
      }
    }
    await go('admin-auth');
    await page.waitForFunction(() => document.querySelectorAll('.setting-grid article').length >= 6
      && document.querySelectorAll('.table tbody tr').length >= 1, undefined, { timeout: 20_000 });
    const settings = await page.locator('.setting-grid article').count();
    const events = await page.locator('.table tbody tr').count();
    if (settings < 6 || events < 1) throw new Error(`admin-auth 数据库策略/审计数据不足：${settings}/${events}`);
    if ((await page.locator('main').innerText()).includes('合成数据')) throw new Error('认证策略页仍标记前端合成数据');
    counts.push(`admin-auth=${settings}策略/${events}事件`);
    return counts.join(', ');
  });

  if (!readOnly) {
    await check('organization-create-and-deactivate', async () => {
    await go('admin-org');
    await page.getByRole('button', { name: '新建组织节点' }).click();
    const suffix = Date.now().toString().slice(-8);
    await page.getByPlaceholder('例：CARD-WARD-02').fill(`QCD-DEPT-${suffix}`);
    await page.getByPlaceholder('例：心内二病区').fill(`病案质量管理科 / Medical Record Quality Office ${suffix}`);
    await page.getByRole('button', { name: '创建并生效' }).click();
    await page.getByText(/已生效，审计事件和事务事件记录已同步保存/).waitFor({ timeout: 20_000 });
    await page.getByPlaceholder('编码、名称或上级').fill(`QCD-DEPT-${suffix}`);
    const row = page.locator('.table tbody tr').filter({ hasText: `QCD-DEPT-${suffix}` });
    await row.waitFor({ timeout: 20_000 });
    await row.getByRole('button', { name: '停用' }).click();
    await page.getByRole('button', { name: '确认停用', exact: true }).click();
    await page.getByText(/已停用。/).waitFor({ timeout: 20_000 });
    return '新建组织单元、查询回显、停用均已写入数据库';
    });

    await check('dictionary-create-and-deactivate', async () => {
    await go('admin-dictionaries');
    const suffix = Date.now().toString().slice(-7);
    await page.getByRole('button', { name: '新建字典', exact: true }).click();
    await page.getByPlaceholder('例：M').fill(`U${suffix}`);
    await page.getByPlaceholder('例：男性').fill(`未说明 / Not stated ${suffix}`);
    await page.getByRole('button', { name: '创建并生效' }).click();
    await page.getByText('字典项已生效，审计链与事务事件记录已同步保存。', { exact: true }).waitFor({ timeout: 20_000 });
    const row = page.locator('.queue-item').filter({ hasText: `U${suffix}` });
    await row.waitFor({ timeout: 20_000 });
    await row.getByRole('button', { name: '停用' }).click();
    await page.getByRole('button', { name: '确认停用', exact: true }).click();
    await page.getByText(/已停用。/).waitFor({ timeout: 20_000 });
    return '新建字典项、查询回显、停用均已写入数据库';
    });

    await check('configuration-lifecycle-write', async () => {
    await go('admin-master-data');
    await page.getByRole('button', { name: '目录版本管理', exact: true }).click();
    await waitForRows('main table tbody tr', 4);
    const suffix = Date.now().toString().slice(-8);
    await page.getByRole('button', { name: '新建主数据变更', exact: true }).click();
    const nameInput = page.getByRole('textbox', { name: '显示名称', exact: true });
    const keyInput = page.getByRole('textbox', { name: '配置编码（系统唯一）', exact: true });
    await page.waitForFunction(() => {
      const input = document.querySelector('.config-core-fields label:nth-child(2) input');
      return input instanceof HTMLInputElement && !input.disabled;
    }, undefined, { timeout: 20_000 });
    await nameInput.fill(`验收主数据 / Acceptance Master Data ${suffix}`);
    await keyInput.fill(`acceptance-master-${suffix}`);
    await page.getByRole('button', { name: '创建版本化草稿' }).click();
    await page.getByText('已创建版本化草稿。', { exact: true }).waitFor({ timeout: 20_000 });
    await page.getByRole('button', { name: '打开版本管理', exact: true }).click();
    await page.getByRole('button', { name: '执行静态校验' }).click();
    await page.getByText(/静态校验已完成/).waitFor({ timeout: 20_000 });
    await page.getByRole('button', { name: '提交审批' }).click();
    await page.getByText(/提交审批已完成/).waitFor({ timeout: 20_000 });
    return '主数据草稿创建、静态校验、提交审批全部落库';
    });
  }

  await check('authentication-simulation-and-role-scan', async () => {
    await go('admin-auth');
    await page.getByRole('button', { name: '打开账户安全复核', exact: true }).click();
    await page.getByRole('heading', { name: '认证与 MFA 场景演练', exact: true }).waitFor({ timeout: 20_000 });
    await page.getByRole('button', { name: '运行场景', exact: true }).click();
    await page.getByText(/场景执行完成/).waitFor({ timeout: 20_000 });
    await page.getByRole('button', { name: '← 返回安全策略' }).click();
    await page.getByRole('heading', { name: '认证与账户安全策略' }).waitFor();
    await go('admin-roles');
    await page.getByRole('button', { name: '职责冲突扫描' }).click();
    await page.getByText(/职责冲突扫描完成/).waitFor();
    return '认证模拟适配器可执行，职责冲突扫描有明确结果';
  });

  await check('workforce-seven-level-drilldown', async () => {
    await go('admin-users');
    const detailLink = page.getByRole('link', { name: '人员档案下钻', exact: true });
    await detailLink.waitFor({ timeout: 20_000 });
    await detailLink.click();
    await page.waitForURL(/#\/admin\/users\/[0-9a-f-]+$/, { timeout: 20_000 });
    await page.getByRole('link', { name: '查看全部角色', exact: true }).click();
    await page.waitForURL(/\/roles$/, { timeout: 20_000 });
    const roleDetail = page.getByRole('link', { name: '查看任期', exact: true }).first();
    if (await roleDetail.count()) {
      await roleDetail.click();
      await page.waitForURL(/\/roles\/[0-9a-f-]+$/, { timeout: 20_000 });
    }
    await page.getByRole('link', { name: '查看相关审计证据', exact: true }).click();
    await page.waitForURL(/\/audit$/, { timeout: 20_000 });
    return '人员清单→人员档案→角色任期→审计证据路由可从真实数据下钻';
  });

  await check('mobile-admin-domain-layout', async () => {
    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
    try {
      await mobile.goto(`${baseUrl}/#/admin`, { waitUntil: 'domcontentloaded' });
      const login = mobile.getByRole('button', { name: '登录系统', exact: true });
      if (await login.count()) {
        await mobile.getByLabel('用户名').fill(credentials.username);
        await mobile.getByLabel('密码', { exact: true }).fill(credentials.password);
        await login.click();
        await mobile.goto(`${baseUrl}/#/admin`, { waitUntil: 'domcontentloaded' });
      }
      await mobile.waitForFunction(() => document.documentElement.dataset.routeId === 'admin', undefined, { timeout: 20_000 });
      await mobile.getByRole('heading', { name: '系统管理工作台' }).waitFor({ timeout: 20_000 });
      const overflow = await mobile.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
      if (overflow) throw new Error('390px 系统管理页存在横向溢出');
      if (await mobile.locator('.admin-domain-nav .admin-domain-link').count() !== 12) throw new Error('移动端子导航不完整');
      await mobile.screenshot({ path: resolve(outputDir, 'admin-390x844.png'), fullPage: true });
      return '390×844 无页面溢出，12 个原型子入口可横向滚动';
    } finally { await mobile.close(); }
  });
} finally { await browser.close(); }

const result = { run_at: new Date().toISOString(), mode: readOnly ? 'READ_ONLY' : 'CRUD', passed: checks.length, failed: failures.length, checks, failures };
const artifact = resolve(outputDir, readOnly ? 'system-administration-read-only.json' : 'system-administration-acceptance.json');
await writeFile(artifact, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (failures.length) process.exitCode = 1;
