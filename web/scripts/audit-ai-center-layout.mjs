import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/ai-center-layout');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4178/app/index.html').replace(/#.*$/, '');
const loginPassword = process.env.HAOEMR_DEV_LOGIN_PASSWORD;
const routes = ['ai-center', 'ai-assistant', 'models', 'agent-catalog', 'skill-catalog', 'tool-catalog', 'agent-evals', 'aiops'];
const viewports = [
  { name: 'desktop', width: 1440, height: 1000 },
  { name: 'compact', width: 1280, height: 900 },
  { name: 'mobile', width: 390, height: 844 },
];

if (!loginPassword) throw new Error('HAOEMR_DEV_LOGIN_PASSWORD is required');
await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: viewports[0] });
const failures = [];
const observations = [];
page.on('pageerror', (error) => failures.push({ route: 'runtime', viewport: 'all', issue: 'PAGE_ERROR', detail: error.message }));

try {
  await page.goto(`${baseUrl}#/login`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.getByLabel('用户名').fill('linwei');
  await page.getByLabel('密码', { exact: true }).fill(loginPassword);
  await page.getByRole('button', { name: '登录系统' }).click();
  await page.waitForFunction(() => document.documentElement.dataset.routeId !== 'login-context', { timeout: 20_000 });

  for (const viewport of viewports) {
    await page.setViewportSize(viewport);
    for (const route of routes) {
      await page.goto(`${baseUrl}#${route}`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
      await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, route, { timeout: 15_000 });
      await page.waitForTimeout(900);
      const layout = await page.evaluate(() => {
        const viewportWidth = window.innerWidth;
        const root = document.querySelector('[data-page-root]');
        const escaped = [...document.querySelectorAll('[data-page-root] .card,[data-page-root] .admin-panel,[data-page-root] .hub-module,[data-page-root] .xiaonan-context-strip')]
          .filter((element) => {
            const rect = element.getBoundingClientRect();
            const scrollParent = element.closest('.admin-table-wrap,.table-wrap,.center-nav');
            return !scrollParent && (rect.left < -1 || rect.right > viewportWidth + 1);
          })
          .slice(0, 8)
          .map((element) => ({ className: element.className, ...element.getBoundingClientRect().toJSON() }));
        return {
          documentOverflow: document.documentElement.scrollWidth > viewportWidth + 1,
          bodyOverflow: document.body.scrollWidth > viewportWidth + 1,
          pageWidth: root?.getBoundingClientRect().width ?? 0,
          escaped,
        };
      });
      observations.push({ route, viewport: viewport.name, ...layout });
      if (layout.documentOverflow || layout.bodyOverflow || layout.escaped.length) {
        failures.push({ route, viewport: viewport.name, issue: 'LAYOUT_OVERFLOW', detail: layout });
      }
      if (viewport.name === 'desktop') {
        await page.screenshot({ path: resolve(outputDir, `${route}-desktop.png`), fullPage: true });
      } else if (route === 'ai-assistant') {
        await page.screenshot({ path: resolve(outputDir, `ai-assistant-${viewport.name}.png`), fullPage: true });
      }
    }
  }

  await page.setViewportSize(viewports[0]);
  await page.goto(`${baseUrl}#ai-assistant`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.documentElement.dataset.routeId === 'ai-assistant');
  const contextButton = page.getByRole('button', { name: '添加诊疗上下文' }).first();
  await contextButton.click();
  const contextOptionCount = await page.locator('.eva-agent-composer .eva-context-menu > button').count();
  const authorizationOptionCount = await page.locator('.eva-agent-composer .eva-control-select.auth option').count();
  const modelOptionCount = await page.locator('.eva-agent-composer .eva-control-select.model option').count();
  await contextButton.click();
  const patientPicker = page.locator('.eva-harness-shell .eva-patient-picker');
  await patientPicker.getByLabel('搜索患者').fill('王');
  await patientPicker.getByRole('button', { name: '搜索', exact: true }).click();
  await patientPicker.locator('.eva-search-results button').first().waitFor({ timeout: 15_000 });
  const patientResultCount = await patientPicker.locator('.eva-search-results button').count();
  await page.getByRole('button', { name: '新建医助任务', exact: true }).click();
  const blankTaskVisible = await page.locator('.eva-session-head').getByText('空白任务', { exact: true }).isVisible();
  const controls = { contextOptionCount, authorizationOptionCount, modelOptionCount, patientResultCount, blankTaskVisible };
  observations.push({ route: 'ai-assistant-controls', viewport: 'desktop', ...controls });
  if (contextOptionCount !== 5 || authorizationOptionCount !== 3 || modelOptionCount < 1 || patientResultCount < 1 || !blankTaskVisible) {
    failures.push({ route: 'ai-assistant-controls', viewport: 'desktop', issue: 'EVA_CONTROL_FLOW_INCOMPLETE', detail: controls });
  }
  await page.getByRole('button', { name: '打开AI医助Eva' }).click();
  await page.getByRole('button', { name: '右侧窗', exact: true }).click();
  await page.waitForTimeout(500);
  const sidePanel = await page.evaluate(() => {
    const panel = document.querySelector('.global-ai-side-panel');
    const shell = document.querySelector('.shell');
    if (!panel || !shell) return null;
    const panelRect = panel.getBoundingClientRect();
    const shellRect = shell.getBoundingClientRect();
    const panelStyle = getComputedStyle(panel);
    return {
      panel: panelRect.toJSON(), shell: shellRect.toJSON(),
      panelStyle: { display: panelStyle.display, visibility: panelStyle.visibility, position: panelStyle.position },
      panelOverflow: panel.scrollWidth > panel.clientWidth + 1 || panel.scrollHeight > panel.clientHeight + 1,
      documentOverflow: document.documentElement.scrollWidth > window.innerWidth + 1,
    };
  });
  observations.push({ route: 'ai-assistant-side-panel', viewport: 'desktop', ...sidePanel });
  if (!sidePanel || sidePanel.panel.width < 400 || sidePanel.panel.height < 300 || sidePanel.panelOverflow || sidePanel.documentOverflow) {
    failures.push({ route: 'ai-assistant-side-panel', viewport: 'desktop', issue: 'SIDE_PANEL_OVERFLOW', detail: sidePanel });
  }
  await page.screenshot({ path: resolve(outputDir, 'ai-assistant-side-panel-desktop.png'), fullPage: false });
} finally {
  await browser.close();
}

const result = { checkedRoutes: routes.length, checkedViewports: viewports.length, failures, observations };
await writeFile(resolve(outputDir, 'result.json'), `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ checkedRoutes: result.checkedRoutes, checkedViewports: result.checkedViewports, failures: failures.length }));
if (failures.length) process.exitCode = 1;
