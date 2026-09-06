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
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const observations = [];
const findings = [];
let currentRoute = 'bootstrap';
let activeClick = false;
page.on('pageerror', (error) => findings.push({ route: currentRoute, severity: 'P0', issue: 'BUTTON_PAGE_ERROR', detail: error.message }));
page.on('response', (response) => {
  if (activeClick && response.url().includes('/api/v1/') && response.status() >= 500) {
    findings.push({ route: currentRoute, severity: 'P0', issue: 'SAFE_BUTTON_API_FAILURE', detail: `${response.status()} ${response.url()}` });
  }
});

const safeLabel = /^(刷新|重新连接|重试|展开|收起|新建|新增|录入|切换|门诊|住院|生命体征|护理计划|给药执行|交接班|出院闭环|床旁记录|中窗|右侧)(\s*\([^)]*\))?$/;
const mutatingLabel = /完成|确认|删除|停用|发布|批准|拒绝|执行|上报|创建|保存|提交|签署|记费|核验|发药|排程|调配|摆药|接手|转派|委托|升级|调用|运行|计算|登记|申请|记录|开始|冲正|静默|退回|取消医嘱|停止医嘱/;

try {
  for (const route of routes) {
    currentRoute = route.route_id;
    await page.goto(`${baseUrl}/#/${route.route_id}`, { waitUntil: 'domcontentloaded', timeout: 20_000 });
    await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, route.route_id, { timeout: 8_000 }).catch(() => {});
    await page.waitForFunction(() => {
      const root = document.querySelector('main [data-page-root]');
      return root && !root.querySelector('.clinical-page-state.loading,.state-page:not(.error)');
    }, undefined, { timeout: 20_000 }).catch(() => {});
    await page.waitForTimeout(100);

    const inventory = await page.locator('main button').evaluateAll((buttons) => buttons.map((button, index) => {
      const rect = button.getBoundingClientRect();
      const style = getComputedStyle(button);
      const visible = rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
      const label = (button.getAttribute('aria-label') || button.textContent || '').replace(/\s+/g, ' ').trim();
      return {
        index, label, visible, disabled: button.disabled, inForm: Boolean(button.closest('form')),
        role: button.getAttribute('role'), type: button.getAttribute('type') || 'submit',
        width: Math.round(rect.width), height: Math.round(rect.height),
      };
    }));
    const visible = inventory.filter((button) => button.visible);
    const nameless = visible.filter((button) => !button.label);
    const undersized = visible.filter((button) => !button.disabled && (button.width < 24 || button.height < 24));
    if (nameless.length) findings.push({ route: route.route_id, severity: 'P1', issue: 'BUTTON_ACCESSIBLE_NAME_MISSING', detail: nameless });
    if (undersized.length) findings.push({ route: route.route_id, severity: 'P2', issue: 'BUTTON_HIT_TARGET_UNDER_24PX', detail: undersized.slice(0, 12) });

    const candidates = visible.filter((button) => !button.disabled && !button.inForm
      && (button.role === 'tab' || (safeLabel.test(button.label) && !mutatingLabel.test(button.label)))).slice(0, 3);
    const clicks = [];
    for (const candidate of candidates) {
      const locator = page.locator('main button').nth(candidate.index);
      if (!await locator.isVisible().catch(() => false) || await locator.isDisabled().catch(() => true)) continue;
      const beforeRoute = await page.evaluate(() => document.documentElement.dataset.routeId);
      activeClick = true;
      try {
        await locator.click({ timeout: 3_000 });
        await page.waitForTimeout(180);
        const afterRoute = await page.evaluate(() => document.documentElement.dataset.routeId);
        clicks.push({ label: candidate.label, status: beforeRoute === afterRoute ? 'PASS' : 'ROUTE_CHANGED', beforeRoute, afterRoute });
        if (beforeRoute !== afterRoute) findings.push({ route: route.route_id, severity: 'P1', issue: 'SAFE_BUTTON_UNEXPECTED_ROUTE_CHANGE', detail: candidate.label });
      } catch (error) {
        clicks.push({ label: candidate.label, status: 'FAILED', detail: error instanceof Error ? error.message : String(error) });
        findings.push({ route: route.route_id, severity: 'P1', issue: 'SAFE_BUTTON_CLICK_FAILED', detail: `${candidate.label}: ${error instanceof Error ? error.message : String(error)}` });
      } finally {
        activeClick = false;
      }
    }
    observations.push({
      route: route.route_id, buttons: visible.length, enabled: visible.filter((button) => !button.disabled).length,
      named: visible.length - nameless.length, safeCandidates: candidates.length, clicked: clicks.length, clicks,
      mutatingOrWorkflowButtons: visible.filter((button) => mutatingLabel.test(button.label)).map((button) => button.label),
    });
  }
} finally {
  await browser.close();
}

const severityCounts = findings.reduce((counts, finding) => ({ ...counts, [finding.severity]: (counts[finding.severity] ?? 0) + 1 }), {});
const result = {
  run_at: new Date().toISOString(), routes: routes.length,
  total_buttons: observations.reduce((sum, item) => sum + item.buttons, 0),
  enabled_buttons: observations.reduce((sum, item) => sum + item.enabled, 0),
  named_buttons: observations.reduce((sum, item) => sum + item.named, 0),
  safe_buttons_clicked: observations.reduce((sum, item) => sum + item.clicked, 0),
  routes_with_safe_clicks: observations.filter((item) => item.clicked > 0).length,
  severity_counts: severityCounts, findings, observations,
};
const outputPath = resolve(outputDir, 'button-level-coverage.json');
await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({
  routes: result.routes, total_buttons: result.total_buttons, named_buttons: result.named_buttons,
  safe_buttons_clicked: result.safe_buttons_clicked, routes_with_safe_clicks: result.routes_with_safe_clicks,
  severity_counts: result.severity_counts, artifact: outputPath,
}));
if ((severityCounts.P0 ?? 0) || (severityCounts.P1 ?? 0)) process.exitCode = 1;
