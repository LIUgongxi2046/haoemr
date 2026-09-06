import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const contract = JSON.parse(await readFile(resolve(projectDir, 'contracts/generated/route-contract.generated.json'), 'utf8'));
const semanticContract = JSON.parse(await readFile(resolve(projectDir, 'docs/process/testing/route-semantic-contract.json'), 'utf8'));
const semanticById = new Map(semanticContract.routes.map((route) => [route.route_id, route]));
const requestedRoutes = new Set((process.env.HAOEMR_BROWSER_ROUTES || '').split(',').filter(Boolean));
const routes = contract.routes
  .map((route) => ({ id: route.route_id, title: route.title, semantics: semanticById.get(route.route_id) }))
  .filter((route) => requestedRoutes.size === 0 || requestedRoutes.has(route.id));
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const evidenceDir = resolve(projectDir, 'artifacts/playwright-ci');
const viewport = {
  width: Number(process.env.HAOEMR_BROWSER_WIDTH || 1440),
  height: Number(process.env.HAOEMR_BROWSER_HEIGHT || 1000),
};
const evidenceLabel = (process.env.HAOEMR_BROWSER_LABEL || `${viewport.width}x${viewport.height}`)
  .replace(/[^a-zA-Z0-9_-]/g, '-');
await mkdir(evidenceDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport });
const failures = [];
const consoleIssues = [];
const failedResponses = [];
const pendingApiRequests = new Set();
const observations = [];
let currentRoute = 'bootstrap';
page.on('console', (message) => {
  if (message.type() !== 'error' && message.type() !== 'warning') return;
  // 浏览器对非 2xx fetch 的通用 "Failed to load resource" 已由 failedResponses
  // 按状态码/URL 精确归因，这里跳过以避免重复计数。
  if (message.text().includes('Failed to load resource')) return;
  consoleIssues.push({ route: currentRoute, type: message.type(), text: message.text() });
});
page.on('pageerror', (error) => consoleIssues.push({ route: currentRoute, type: 'pageerror', text: error.message }));
const requestRoute = new WeakMap();
page.on('response', (response) => {
  // 403 是「当前审计账号无该模块权限」的预期结果（如临床医生访问病案资产），
  // 不是路由缺陷；其余 ≥400 视为失败。
  if (response.status() >= 400 && response.status() !== 403) {
    // 按「发起请求时的路由」归因，避免前一路由未 settle 的请求在跨路由后被归到当前路由
    failedResponses.push({ route: requestRoute.get(response.request()) ?? currentRoute, status: response.status(), url: response.url() });
  }
});
page.on('request', (request) => {
  if (request.url().includes('/api/v1/')) {
    pendingApiRequests.add(request);
    requestRoute.set(request, currentRoute);
  }
});
page.on('requestfinished', (request) => pendingApiRequests.delete(request));
page.on('requestfailed', (request) => pendingApiRequests.delete(request));
const specialtyPrefixes = ['dental-', 'dermatology-', 'ent-', 'mental-', 'neonatal-', 'obgyn-', 'ophthalmology-', 'pediatrics-', 'reproductive-', 'tcm-'];

async function waitForApiIdle(timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  let idleChecks = 0;
  while (Date.now() < deadline) {
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 25));
    idleChecks = pendingApiRequests.size === 0 ? idleChecks + 1 : 0;
    if (idleChecks >= 2) return true;
  }
  return false;
}

try {
  // 登录：路由审计需要已认证会话，否则所有路由都会被重定向到登录页，
  // 导致后续 H1/语义/导航断言全部误报。
  const loginUsername = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
  const loginPassword = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
  await page.goto(`${baseUrl}/#/clinical`, { waitUntil: 'domcontentloaded' });
  const loginSubmit = page.getByRole('button', { name: '登录系统', exact: true });
  await loginSubmit.waitFor({ state: 'visible', timeout: 30_000 }).catch(() => undefined);
  if (await loginSubmit.isVisible().catch(() => false)) {
    await page.getByLabel('用户名', { exact: true }).fill(loginUsername);
    await page.locator('#system-login-password').fill(loginPassword);
    await loginSubmit.click();
  }
  await page.locator('main').waitFor({ state: 'visible', timeout: 30_000 });

  for (const route of routes) {
    currentRoute = route.id;
    await page.goto(`${baseUrl}/#/${route.id}`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(
      (routeId) => document.documentElement.dataset.routeId === routeId,
      route.id,
      { timeout: 5_000 },
    ).catch(() => {});
    if (route.id === 'specialty-center' || specialtyPrefixes.some((prefix) => route.id.startsWith(prefix))) {
      // Shell 保留唯一 main landmark；已实现的原生专科页渲染 [data-page-root].vue-native-page。
      // 未实现的门禁页渲染 .migration-notice / [role="alert"]，二者取其一即视为已挂载。
      await page.waitForFunction(
        () => document.querySelector('main [data-page-root].vue-native-page') != null
          || document.querySelector('main .migration-notice') != null
          || document.querySelector('main [role="alert"]') != null,
        { timeout: 5_000 },
      ).catch(() => {});
    }
    const h1 = await page.locator('main h1').first().textContent({ timeout: 5_000 }).catch(() => null);
    const apiIdle = await waitForApiIdle();
    // Vue Query may settle the network before its final success/error branch has painted.
    await page.waitForTimeout(250);
    const activePrimaryNavigation = await page.locator('[aria-label="一级导航"] [aria-current="page"]').count();
    const horizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    // The shell and page may both use <main>; read the document body to avoid
    // strict-locator failure while still checking route-specific critical text.
    const mainText = await page.locator('body').innerText().catch(() => '');
    if (process.env.HAOEMR_BROWSER_DEBUG === '1') {
      observations.push({ route: route.id, h1, renderedRoute: await page.getAttribute('html', 'data-route-id'), mainText: mainText.slice(0, 1000) });
    }
    if (!h1) failures.push({ route: route.id, issue: 'H1_MISSING' });
    if (!route.semantics) failures.push({ route: route.id, issue: 'SEMANTIC_CONTRACT_MISSING' });
    for (const text of route.semantics?.critical_text ?? []) {
      if (!mainText.includes(text)) failures.push({ route: route.id, issue: 'CRITICAL_SEMANTIC_TEXT_MISSING', text });
    }
    if (!apiIdle) failures.push({ route: route.id, issue: 'API_REQUESTS_DID_NOT_SETTLE', pending: pendingApiRequests.size });
    if (activePrimaryNavigation !== 1) failures.push({ route: route.id, issue: 'PRIMARY_NAV_ACTIVE_COUNT', actual: activePrimaryNavigation });
    if (horizontalOverflow) failures.push({ route: route.id, issue: 'HORIZONTAL_OVERFLOW' });
  }
  currentRoute = 'unknown';
  await page.goto(`${baseUrl}/#/route-that-does-not-exist`, { waitUntil: 'domcontentloaded' });
  const unknownHeadingVisible = await page.getByRole('heading', { level: 1, name: '页面不存在或尚未登记' })
    .waitFor({ state: 'visible', timeout: 5_000 }).then(() => true).catch(() => false);
  await waitForApiIdle();
  const unknownBody = await page.locator('body').innerText();
  const unknownSafe = unknownHeadingVisible && unknownBody.includes('页面不存在或尚未登记') && !unknownBody.includes('合成患者');
  if (!unknownSafe) failures.push({ route: 'unknown', issue: 'UNKNOWN_ROUTE_NOT_FAIL_CLOSED' });

  const result = {
    routes: routes.length,
    verified: routes.length - new Set(failures.filter((failure) => failure.route !== 'unknown').map((failure) => failure.route)).size,
    failures,
    consoleIssues,
    failedResponses,
    unknownSafe,
    ...(process.env.HAOEMR_BROWSER_DEBUG === '1' ? { observations } : {}),
  };
  await writeFile(resolve(evidenceDir, `route-audit-${evidenceLabel}.json`), `${JSON.stringify(result, null, 2)}\n`);
  await writeFile(resolve(evidenceDir, 'route-audit.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(JSON.stringify(result));
  if (failures.length || consoleIssues.length || failedResponses.length) {
    await page.screenshot({ path: resolve(evidenceDir, `route-audit-failure-${evidenceLabel}.png`), fullPage: true });
    process.exitCode = 1;
  }
} finally {
  await browser.close();
}
