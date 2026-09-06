import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const contract = JSON.parse(await readFile(resolve(projectDir, 'contracts/generated/route-contract.generated.json'), 'utf8'));
const semantics = JSON.parse(await readFile(resolve(projectDir, 'docs/process/testing/route-semantic-contract.json'), 'utf8'));
const semanticById = new Map(semantics.routes.map((route) => [route.route_id, route]));
const requested = new Set((process.env.HAOEMR_BROWSER_ROUTES || '').split(',').filter(Boolean));
const routes = contract.routes.filter((route) => requested.size === 0 || requested.has(route.route_id));
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const outputDir = resolve(projectDir, 'output/playwright');
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const observations = [];
const findings = [];
const failedResponses = [];
let currentRoute = 'bootstrap';
page.on('response', (response) => {
  if (response.status() >= 400 && response.url().includes('/api/v1/')) failedResponses.push({ route: currentRoute, status: response.status(), url: response.url() });
});
page.on('pageerror', (error) => findings.push({ route: currentRoute, severity: 'P0', issue: 'PAGE_ERROR', detail: error.message }));

try {
  for (const route of routes) {
    currentRoute = route.route_id;
    await page.goto(`${baseUrl}/#/${route.route_id}`, { waitUntil: 'domcontentloaded', timeout: 15_000 });
    await page.waitForFunction((routeId) => document.documentElement.dataset.routeId === routeId, route.route_id, { timeout: 8_000 }).catch(() => {});
    await page.waitForFunction(() => {
      const root = document.querySelector('main [data-page-root]');
      return root && !root.querySelector('.clinical-page-state.loading,.state-page:not(.error)');
    }, undefined, { timeout: 20_000 }).catch(() => {});
    await page.waitForFunction(() => !document.querySelector('main')?.textContent?.includes('正在生成成功场景的合成证据'), undefined, { timeout: 8_000 }).catch(() => {});
    await page.waitForTimeout(120);
    const snapshot = await page.evaluate((expectedRoute) => {
      const visible = (element) => { const rect = element.getBoundingClientRect(); const style = getComputedStyle(element); return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden'; };
      const root = document.querySelector('main [data-page-root]');
      const controls = [...document.querySelectorAll('main button,main a[href],main input,main select,main textarea')].filter(visible);
      const enabledControls = controls.filter((element) => !(element instanceof HTMLButtonElement || element instanceof HTMLInputElement || element instanceof HTMLSelectElement || element instanceof HTMLTextAreaElement) || !element.disabled);
      const populatedFields = [...document.querySelectorAll('main input:not([type="hidden"]),main textarea')].filter(visible)
        .filter((element) => element.value?.trim()).length;
      const emptyNodes = [...document.querySelectorAll('main .empty-state,main .admin-empty,main .clinical-empty-state,main .migration-notice')].filter(visible)
        .filter((node) => !/尚无未处理发现|AI 不会直接改写/.test(node.textContent ?? ''));
      const errorNodes = [...document.querySelectorAll('main [role="alert"],main .state-page.error,main .clinical-page-state.error')].filter(visible);
      const dataRows = [...document.querySelectorAll('main tbody tr,main article,main .queue-item,main .folder-row,main .metric,main .queue-list > *,main .admin-list > *,main .hub-module,main .domain-card,main .patient-strip dl > div,main .record-diff-card header > div')].filter(visible);
      const navLinks = [...document.querySelectorAll('main nav a[href],main [role="tablist"] a[href]')].filter(visible);
      const navTargets = navLinks.map((link) => link.getAttribute('href')).filter(Boolean);
      const duplicateNavTargets = [...new Set(navTargets.filter((target, index) => navTargets.indexOf(target) !== index))];
      const topLevel = root ? [...root.children].filter((element) => visible(element) && !['fixed', 'absolute'].includes(getComputedStyle(element).position)) : [];
      const layoutOverlaps = [];
      for (let index = 0; index < topLevel.length - 1; index += 1) {
        const current = topLevel[index].getBoundingClientRect();
        const next = topLevel[index + 1].getBoundingClientRect();
        if (current.bottom > next.top + 2) layoutOverlaps.push({ first: topLevel[index].className, second: topLevel[index + 1].className, pixels: Math.round(current.bottom - next.top) });
      }
      const textOverflows = [...document.querySelectorAll('main h1,main h2,main button,main th,main td')].filter(visible)
        .filter((element) => element.scrollWidth > element.clientWidth + 3 || element.scrollHeight > element.clientHeight + 3)
        .slice(0, 12).map((element) => element.textContent?.replace(/\s+/g, ' ').trim().slice(0, 80));
      const weakEmptyStates = emptyNodes.filter((node) => {
        const text = node.textContent?.replace(/\s+/g, ' ').trim() ?? '';
        const hasNextStep = Boolean(node.querySelector('button,a[href],strong,p')) || /新增|填写|进入|选择|重试|录入|登记|创建|刷新/.test(text);
        return text.length < 16 || !hasNextStep;
      }).map((node) => node.textContent?.replace(/\s+/g, ' ').trim().slice(0, 180));
      const body = root?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
      return {
        renderedRoute: document.documentElement.dataset.routeId ?? null,
        h1: document.querySelector('main h1')?.textContent?.trim() ?? null,
        rootClass: root?.className?.toString() ?? null,
        visibleControls: controls.length,
        enabledControls: enabledControls.length,
        populatedFields,
        buttonLabels: [...document.querySelectorAll('main button')].filter(visible).map((button) => button.textContent?.replace(/\s+/g, ' ').trim()).filter(Boolean),
        dataRows: dataRows.length,
        emptyStates: emptyNodes.map((node) => node.textContent?.replace(/\s+/g, ' ').trim().slice(0, 180)),
        errorStates: errorNodes.map((node) => node.textContent?.replace(/\s+/g, ' ').trim().slice(0, 180)),
        placeholder: body.includes('生产功能尚未开放'),
        bodyTextLength: body.length,
        routeMismatch: document.documentElement.dataset.routeId !== expectedRoute,
        duplicateNavTargets,
        layoutOverlaps,
        textOverflows,
        weakEmptyStates,
      };
    }, route.route_id);
    const semantic = semanticById.get(route.route_id);
    const routeFindings = [];
    if (snapshot.routeMismatch || !snapshot.h1 || snapshot.errorStates.length) routeFindings.push({ severity: 'P0', issue: snapshot.routeMismatch ? 'ROUTE_MISMATCH' : !snapshot.h1 ? 'H1_MISSING' : 'ERROR_STATE', detail: snapshot.errorStates });
    if (snapshot.placeholder) routeFindings.push({ severity: 'P0', issue: 'UNIMPLEMENTED_PLACEHOLDER' });
    if (snapshot.enabledControls === 0 && (semantic?.primary_actions?.length ?? 0) > 0) routeFindings.push({ severity: 'P1', issue: 'NO_ENABLED_PRIMARY_INTERACTION' });
    if (snapshot.duplicateNavTargets.length) routeFindings.push({ severity: 'P1', issue: 'DUPLICATE_NAVIGATION_TARGET', detail: snapshot.duplicateNavTargets });
    if (snapshot.layoutOverlaps.length) routeFindings.push({ severity: 'P1', issue: 'TOP_LEVEL_LAYOUT_OVERLAP', detail: snapshot.layoutOverlaps });
    // A populated page may legitimately contain a contextual empty sub-list (for example, one
    // handover batch without patients while the handover ledger itself has many rows). Only flag
    // the route when the visible empty state describes the page's overall data surface.
    if (snapshot.emptyStates.length && snapshot.dataRows === 0 && snapshot.populatedFields < 2) {
      routeFindings.push({ severity: 'P2', issue: 'VISIBLE_EMPTY_STATE', detail: snapshot.emptyStates });
    }
    if (snapshot.weakEmptyStates.length) routeFindings.push({ severity: 'P2', issue: 'WEAK_EMPTY_STATE', detail: snapshot.weakEmptyStates });
    if (snapshot.textOverflows.length) routeFindings.push({ severity: 'P2', issue: 'TEXT_OVERFLOW', detail: snapshot.textOverflows });
    for (const finding of routeFindings) findings.push({ route: route.route_id, ...finding });
    observations.push({ route: route.route_id, title: route.title, dataSource: semantic?.data_source ?? null, ...snapshot, findingCount: routeFindings.length });
  }
} finally {
  await browser.close();
}

for (const failed of failedResponses) findings.push({ ...failed, severity: 'P0', issue: 'FAILED_API_RESPONSE' });
const severityCounts = findings.reduce((counts, finding) => ({ ...counts, [finding.severity]: (counts[finding.severity] ?? 0) + 1 }), {});
const routesWithData = observations.filter((item) => (item.dataRows > 0 || item.populatedFields >= 2) && item.errorStates.length === 0).length;
const result = {
  run_at: new Date().toISOString(), routes: routes.length, routes_with_data: routesWithData,
  routes_with_visible_empty_state: observations.filter((item) => item.emptyStates.length).length,
  routes_with_enabled_interactions: observations.filter((item) => item.enabledControls > 0).length,
  severity_counts: severityCounts, findings, failed_responses: failedResponses, observations,
};
const outputPath = resolve(outputDir, 'page-data-function-audit.json');
await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ routes: result.routes, routes_with_data: result.routes_with_data, routes_with_visible_empty_state: result.routes_with_visible_empty_state, routes_with_enabled_interactions: result.routes_with_enabled_interactions, severity_counts: result.severity_counts, artifact: outputPath }));
if ((severityCounts.P0 ?? 0) > 0 || (severityCounts.P1 ?? 0) > 0) process.exitCode = 1;
