import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const contract = JSON.parse(await readFile(resolve(projectDir, 'contracts/generated/route-contract.generated.json'), 'utf8'));
const requested = new Set((process.env.HAOEMR_BROWSER_ROUTES || '').split(',').filter(Boolean));
const routes = contract.routes.filter((route) => !requested.size || requested.has(route.route_id));
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const outputDir = resolve(projectDir, 'output/playwright');
const viewports = process.env.HAOEMR_UI_CONSISTENCY_VIEWPORTS === 'desktop'
  ? [{ name: 'desktop', width: 1280, height: 800 }]
  : [{ name: 'desktop', width: 1280, height: 800 }, { name: 'compact', width: 390, height: 844 }];
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const findings = [];
const observations = [];

try {
  for (const viewport of viewports) {
    const page = await browser.newPage({ viewport });
    for (const route of routes) {
      await page.goto(`${baseUrl}/#/${route.route_id}`, { waitUntil: 'domcontentloaded', timeout: 20_000 });
      await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, route.route_id, { timeout: 8_000 }).catch(() => {});
      await page.waitForFunction(() => {
        const root = document.querySelector('main [data-page-root]');
        return root && !root.querySelector('.clinical-page-state.loading,.state-page:not(.error)');
      }, undefined, { timeout: 20_000 }).catch(() => {});
      await page.waitForTimeout(80);

      const snapshot = await page.evaluate(() => {
        const visible = (element) => {
          if (!(element instanceof HTMLElement)) return false;
          const rect = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
        };
        const root = document.querySelector('main [data-page-root]');
        const header = root?.querySelector(':scope > .page-heading,:scope > .page-head') ?? null;
        const rootRect = root?.getBoundingClientRect();
        const headerRect = header?.getBoundingClientRect();
        const topLabels = [...(header?.querySelectorAll('.eyebrow') ?? [])].filter(visible).map((element) => ({
          text: element.textContent?.replace(/\s+/g, ' ').trim() ?? '',
          color: getComputedStyle(element).color,
        }));
        const actions = [...(header?.querySelectorAll('button,a.button') ?? [])].filter(visible).map((element) => {
          const rect = element.getBoundingClientRect();
          return { label: element.textContent?.replace(/\s+/g, ' ').trim() ?? '', width: Math.round(rect.width), height: Math.round(rect.height) };
        });
        const actionUndersized = actions.filter((item) => item.width < 34 || item.height < 34);
        return {
          hasRoot: Boolean(root),
          headerKind: header?.classList.contains('page-heading') ? 'page-heading' : header?.classList.contains('page-head') ? 'page-head' : null,
          topGap: rootRect && headerRect ? Math.round((headerRect.top - rootRect.top) * 10) / 10 : null,
          topLabels,
          actions,
          actionUndersized,
          h1: root?.querySelector('h1')?.textContent?.replace(/\s+/g, ' ').trim() ?? null,
        };
      });

      const routeFindings = [];
      if (!snapshot.hasRoot || !snapshot.h1) routeFindings.push({ severity: 'P0', issue: 'PAGE_ROOT_OR_TITLE_MISSING' });
      if (snapshot.topLabels.length) routeFindings.push({ severity: 'P1', issue: 'VISIBLE_PAGE_HIERARCHY_LABEL', detail: snapshot.topLabels });
      const minimumGap = viewport.name === 'compact' ? 10 : 14;
      if (snapshot.headerKind && (snapshot.topGap ?? 0) < minimumGap) {
        routeFindings.push({ severity: 'P1', issue: 'PAGE_HEADER_TOP_GAP_TOO_SMALL', detail: { actual: snapshot.topGap, minimum: minimumGap } });
      }
      if (snapshot.actionUndersized.length) routeFindings.push({ severity: 'P2', issue: 'PAGE_HEADER_ACTION_UNDERSIZED', detail: snapshot.actionUndersized });
      routeFindings.forEach((finding) => findings.push({ route: route.route_id, viewport: viewport.name, ...finding }));
      observations.push({ route: route.route_id, viewport: viewport.name, ...snapshot, findingCount: routeFindings.length });
    }
    await page.close();
  }
} finally {
  await browser.close();
}

const severityCounts = findings.reduce((counts, finding) => ({ ...counts, [finding.severity]: (counts[finding.severity] ?? 0) + 1 }), {});
const result = {
  run_at: new Date().toISOString(), routes: routes.length, viewports: viewports.length,
  route_viewports: observations.length,
  routes_with_standard_header: new Set(observations.filter((item) => item.headerKind).map((item) => item.route)).size,
  visible_page_hierarchy_labels: findings.filter((item) => item.issue === 'VISIBLE_PAGE_HIERARCHY_LABEL').length,
  insufficient_top_gaps: findings.filter((item) => item.issue === 'PAGE_HEADER_TOP_GAP_TOO_SMALL').length,
  severity_counts: severityCounts, findings, observations,
};
const outputPath = resolve(outputDir, 'ui-consistency-audit.json');
await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({
  routes: result.routes, route_viewports: result.route_viewports,
  routes_with_standard_header: result.routes_with_standard_header,
  visible_page_hierarchy_labels: result.visible_page_hierarchy_labels,
  insufficient_top_gaps: result.insufficient_top_gaps,
  severity_counts: result.severity_counts, artifact: outputPath,
}));
if ((severityCounts.P0 ?? 0) || (severityCounts.P1 ?? 0)) process.exitCode = 1;
