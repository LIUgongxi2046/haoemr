import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const contract = JSON.parse(await readFile(resolve(projectDir, 'contracts/generated/route-contract.generated.json'), 'utf8'));
const requestedRoutes = new Set((process.env.HAOEMR_BROWSER_ROUTES || '').split(',').filter(Boolean));
const routes = contract.routes.filter((route) => route.route_id !== 'login-context' && (requestedRoutes.size === 0 || requestedRoutes.has(route.route_id)));
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const loginUsername = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const loginPassword = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const viewportText = process.env.HAOEMR_UI_VIEWPORTS || '1280x800,390x844';
const viewports = viewportText.split(',').map((item) => {
  const [width, height] = item.split('x').map(Number);
  if (!Number.isFinite(width) || !Number.isFinite(height)) throw new Error(`Invalid viewport: ${item}`);
  return { width, height, label: `${width}x${height}` };
});
const outputDir = resolve(projectDir, 'output/playwright');
const outputFile = process.env.HAOEMR_UI_AUDIT_OUTPUT || 'comprehensive-ui-audit.json';
await mkdir(outputDir, { recursive: true });

const executablePath = process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined;
const browser = await chromium.launch({ headless: true, executablePath });
const findings = [];
const observations = [];

const pushFindings = (route, viewport, issues) => {
  for (const issue of issues) findings.push({ route, viewport, ...issue });
};

try {
  for (const viewport of viewports) {
    const page = await browser.newPage({ viewport });
    const consoleIssues = [];
    let currentRoute = 'bootstrap';
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) consoleIssues.push({ route: currentRoute, type: message.type(), text: message.text() });
    });
    page.on('response', (response) => {
      if (response.status() >= 400) {
        void response.text().then((body) => consoleIssues.push({ route: currentRoute, type: 'http', status: response.status(), url: response.url(), body: body.slice(0, 800) }));
      }
    });
    page.on('pageerror', (error) => consoleIssues.push({ route: currentRoute, type: 'pageerror', text: error.message }));

    currentRoute = 'login';
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded', timeout: 15_000 });
    await page.waitForFunction(() => Boolean(document.documentElement.dataset.routeId), undefined, { timeout: 8_000 });
    const loginButton = page.getByRole('button', { name: '登录系统', exact: true });
    if (await page.evaluate(() => document.documentElement.dataset.routeId === 'login-context')) {
      await loginButton.waitFor({ state: 'visible', timeout: 8_000 });
      await page.getByLabel('用户名', { exact: true }).fill(loginUsername);
      await page.locator('#system-login-password').fill(loginPassword);
      await loginButton.click();
      await page.waitForFunction(() => document.documentElement.dataset.routeId !== 'login-context', undefined, { timeout: 20_000 });
    }

    for (const route of routes) {
      currentRoute = route.route_id;
      await page.goto(`${baseUrl}/#/${route.route_id}`, { waitUntil: 'domcontentloaded', timeout: 15_000 }).catch(() => null);
      await page.waitForFunction((routeId) => document.documentElement.dataset.routeId === routeId, route.route_id, { timeout: 8_000 }).catch(() => {});
      await page.waitForTimeout(200);
      const audit = await page.evaluate(({ expectedRoute, width }) => {
        const issues = [];
        const visible = (element) => {
          const rect = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
        };
        const h1 = [...document.querySelectorAll('main h1')].filter(visible);
        const routeId = document.documentElement.dataset.routeId;
        if (routeId !== expectedRoute) issues.push({ issue: 'ROUTE_RENDER_MISMATCH', expected: expectedRoute, actual: routeId });
        if (h1.length !== 1) issues.push({ issue: 'VISIBLE_H1_COUNT', expected: 1, actual: h1.length });
        const pageTitle = h1[0];
        if (pageTitle) {
          const titleTop = pageTitle.getBoundingClientRect().top;
          const navigationLayers = [
            ['TOPBAR_COVERS_PAGE_TITLE', document.querySelector('.topbar')],
            ['SECONDARY_NAV_COVERS_PAGE_TITLE', document.querySelector('.main > .center-nav')],
          ];
          for (const [issue, navigation] of navigationLayers) {
            if (!navigation || !visible(navigation)) continue;
            const navigationBottom = navigation.getBoundingClientRect().bottom;
            if (navigationBottom > titleTop + 1) {
              issues.push({ issue, navigation_bottom_px: Math.round(navigationBottom), title_top_px: Math.round(titleTop) });
            }
          }
        }
        if (document.documentElement.scrollWidth > window.innerWidth + 1) {
          const overflowSamples = [...document.body.querySelectorAll('*')]
            .filter(visible)
            .map((element) => ({
              element,
              rect: element.getBoundingClientRect(),
              style: getComputedStyle(element),
            }))
            .filter(({ element, rect, style }) => !element.closest('.sidebar, .center-nav') && style.position !== 'fixed' && (rect.right > window.innerWidth + 1 || element.scrollWidth > element.clientWidth + 1))
            .sort((a, b) => Math.max(b.rect.right, b.rect.left + b.element.scrollWidth) - Math.max(a.rect.right, a.rect.left + a.element.scrollWidth))
            .slice(0, 5)
            .map(({ element, rect }) => ({ tag: element.tagName, class_name: element.className?.toString().slice(0, 100), right_px: Math.round(rect.right), width_px: Math.round(rect.width), scroll_width: element.scrollWidth, client_width: element.clientWidth }));
          issues.push({ issue: 'HORIZONTAL_OVERFLOW', scroll_width: document.documentElement.scrollWidth, viewport_width: window.innerWidth, samples: overflowSamples });
        }

        const mainContentIds = document.querySelectorAll('#main-content').length;
        if (mainContentIds !== 1) issues.push({ issue: 'MAIN_CONTENT_ID_COUNT', expected: 1, actual: mainContentIds });
        const nestedMains = document.querySelectorAll('main main').length;
        if (nestedMains) issues.push({ issue: 'NESTED_MAIN_LANDMARK', actual: nestedMains });

        const shellMain = document.querySelector('.main');
        if (shellMain) {
          const style = getComputedStyle(shellMain);
          const expectedPadding = width <= 600 ? 12 : 22;
          const left = Number.parseFloat(style.paddingLeft);
          const right = Number.parseFloat(style.paddingRight);
          if (Math.abs(left - expectedPadding) > 0.5 || Math.abs(right - expectedPadding) > 0.5) {
            issues.push({ issue: 'MAIN_INLINE_SPACING_DRIFT', expected_px: expectedPadding, actual_left_px: left, actual_right_px: right });
          }
        }
        const content = document.querySelector('.main > [data-page-root].content');
        if (content) {
          const style = getComputedStyle(content);
          if (Number.parseFloat(style.paddingLeft) !== 0 || Number.parseFloat(style.paddingRight) !== 0) {
            issues.push({ issue: 'DOUBLE_CONTENT_PADDING', left: style.paddingLeft, right: style.paddingRight });
          }
        }
        const prototypeHead = document.querySelector('.page-head');
        if (prototypeHead && width > 600 && prototypeHead.getBoundingClientRect().height < 58) {
          issues.push({ issue: 'PAGE_HEAD_HEIGHT_TOO_SHORT', expected_min_px: 58, actual_px: prototypeHead.getBoundingClientRect().height });
        }
        const prototypeTitle = document.querySelector('.page-title h1, .page-heading h1');
        if (prototypeTitle) {
          const size = Number.parseFloat(getComputedStyle(prototypeTitle).fontSize);
          if (size < 20 || size > 23.5) issues.push({ issue: 'PAGE_TITLE_SIZE_OUT_OF_RANGE', expected_range_px: [20, 23.5], actual_px: size });
        }
        const adminLayout = document.querySelector('.admin-layout');
        if (adminLayout && width > 1100) {
          const columns = getComputedStyle(adminLayout).gridTemplateColumns.split(' ').map(Number.parseFloat);
          if (columns.length === 2 && columns[0] < columns[1] * 1.5) issues.push({ issue: 'ADMIN_PRIMARY_COLUMN_COLLAPSED', columns });
        }

        const typographyViolations = [];
        const textNodes = [...document.querySelectorAll('p,li,td,th,label,button,a,small')].filter(visible).slice(0, 600);
        for (const element of textNodes) {
          const text = element.textContent?.trim();
          if (!text) continue;
          const style = getComputedStyle(element);
          const fontSize = Number.parseFloat(style.fontSize);
          const lineHeight = Number.parseFloat(style.lineHeight);
          if (Number.isFinite(fontSize) && Number.isFinite(lineHeight) && lineHeight / fontSize < 1.2) {
            typographyViolations.push({ tag: element.tagName, class_name: element.className?.toString().slice(0, 80), ratio: Number((lineHeight / fontSize).toFixed(2)), text: text.slice(0, 60) });
          }
        }
        if (typographyViolations.length) issues.push({ issue: 'LINE_HEIGHT_BELOW_1_2', count: typographyViolations.length, samples: typographyViolations.slice(0, 5) });

        const unnamed = [...document.querySelectorAll('button,input,textarea,select')].filter(visible).filter((element) => {
          if (element.getAttribute('aria-label') || element.getAttribute('aria-labelledby') || element.getAttribute('title')) return false;
          if (element instanceof HTMLInputElement && ['hidden', 'submit', 'button'].includes(element.type)) return false;
          if (element.matches('button') && element.textContent?.trim()) return false;
          if (element.getAttribute('placeholder')) return false;
          if (element.id && document.querySelector(`label[for="${CSS.escape(element.id)}"]`)) return false;
          if (element.closest('label')) return false;
          return true;
        });
        if (unnamed.length) issues.push({ issue: 'UNNAMED_FORM_CONTROLS', count: unnamed.length, samples: unnamed.slice(0, 5).map((element) => ({ tag: element.tagName, type: element.getAttribute('type'), class_name: element.className?.toString().slice(0, 80) })) });

        const aiLauncher = document.querySelector('[aria-label="打开 Eva 医助"]');
        if (!aiLauncher || !visible(aiLauncher)) issues.push({ issue: 'GLOBAL_AI_LAUNCHER_MISSING' });
        return {
          issues,
          h1: h1[0]?.textContent?.trim() ?? null,
          routeId,
          aiLauncherTag: aiLauncher?.tagName ?? null,
          bodyTextLength: document.body.innerText.length,
        };
      }, { expectedRoute: route.route_id, width: viewport.width });
      pushFindings(route.route_id, viewport.label, audit.issues);
      observations.push({ route: route.route_id, viewport: viewport.label, ...audit });
    }

    if (requestedRoutes.size === 0 || requestedRoutes.has('clinical')) {
      currentRoute = 'clinical';
      await page.goto(`${baseUrl}/#/clinical`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.documentElement.dataset.routeId === 'clinical', { timeout: 8_000 }).catch(() => {});
    const beforeUrl = page.url();
    const launcher = page.locator('[aria-label="打开 Eva 医助"]');
    if (await launcher.count()) {
      await launcher.click();
      await page.waitForTimeout(150);
      const dialogCount = await page.getByRole('dialog').count();
      const routePreserved = page.url() === beforeUrl;
      if (dialogCount !== 1) findings.push({ route: 'clinical', viewport: viewport.label, issue: 'AI_NOT_OPENED_AS_DIALOG', expected: 1, actual: dialogCount });
      if (!routePreserved) findings.push({ route: 'clinical', viewport: viewport.label, issue: 'AI_LAUNCH_CHANGED_ROUTE', before: beforeUrl, after: page.url() });
      if (dialogCount === 1 && routePreserved) {
        const dialog = page.getByRole('dialog');
        const composer = dialog.getByLabel('向 Eva 描述诊疗任务', { exact: true });
        if (await composer.count()) {
          await composer.fill('验证当前页面上下文');
        } else {
          findings.push({ route: 'clinical', viewport: viewport.label, issue: 'AI_DIALOG_COMPOSER_MISSING' });
        }
        if (page.url() !== beforeUrl) findings.push({ route: 'clinical', viewport: viewport.label, issue: 'AI_DIALOG_INTERACTION_CHANGED_ROUTE', before: beforeUrl, after: page.url() });
        await page.keyboard.press('Escape');
        await page.waitForTimeout(100);
        if (await page.getByRole('dialog').count()) findings.push({ route: 'clinical', viewport: viewport.label, issue: 'AI_DIALOG_ESCAPE_DID_NOT_CLOSE' });
        const focusReturned = await page.evaluate(() => document.activeElement?.getAttribute('aria-label') === '打开 Eva 医助');
        if (!focusReturned) findings.push({ route: 'clinical', viewport: viewport.label, issue: 'AI_DIALOG_FOCUS_NOT_RESTORED' });
      }
      } else {
        findings.push({ route: 'clinical', viewport: viewport.label, issue: 'AI_DIALOG_FLOW_UNTESTABLE_NO_LAUNCHER' });
      }
    }
    pushFindings('browser-console', viewport.label, consoleIssues.map((item) => ({ issue: 'CONSOLE_ISSUE', ...item })));
    await page.close();
  }
} finally {
  await browser.close();
}

const auditedRouteIds = new Set(routes.map((route) => route.route_id));
const affectedRoutes = new Set(findings.filter((item) => auditedRouteIds.has(item.route)).map((item) => `${item.viewport}:${item.route}`));
const result = {
  run_at: new Date().toISOString(),
  routes: routes.length,
  viewports,
  route_viewports: routes.length * viewports.length,
  verified_route_viewports: routes.length * viewports.length - affectedRoutes.size,
  findings,
  observations,
};
await writeFile(resolve(outputDir, outputFile), `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ routes: result.routes, route_viewports: result.route_viewports, verified_route_viewports: result.verified_route_viewports, findings: findings.length, artifact: resolve(outputDir, outputFile) }));
if (findings.length) process.exitCode = 1;
