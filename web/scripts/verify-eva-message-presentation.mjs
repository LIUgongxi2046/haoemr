import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

// Deterministic presentation tests; model calls are intercepted, never sent.
const out = '../output/playwright/eva-message-presentation';
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const runs = new Map();
let current;
const summary = '## 本次任务摘要\n\n已整理 **两项资料**，请按以下步骤核对。\n\n1. 核对记录来源与时间。\n2. 补充缺失信息。\n\n| 核对项目 | 当前状态 | 下一步 |\n| --- | --- | --- |\n| 记录来源 | 已整理 | 人工复核 |\n| 缺失资料 | 待确认 | 补充后再判断 |\n\n> 本条为界面排版测试内容。\n\n[参考说明](https://example.com/reference)\n\n<script>window.evaUnsafe = true</script>';
function fixture(body) {
  const id = randomUUID();
  return { run_id: id, context_lease_id: body.context_lease_id, root_agent_code: 'EVA', root_agent_version: '1', composition_code: 'UI_FIXTURE', composition_version: '1', requested_stage: body.stage_code || 'UI', patient_id: body.patient_id, encounter_id: body.encounter_id, target_type: body.target_type, target_id: body.target_id, objective: body.objective, state: 'RUNNING', sequence: 1, output: { summary }, created_at: new Date().toISOString(), completed_at: null, row_version: 1, attempt: 1, max_attempts: 3, cancel_requested_at: null, failure_code: null, child_runs: [], events: [{ sequence: 1, event_type: 'ModelGenerationStarted', child_run_id: null, payload: {}, occurred_at: new Date().toISOString() }] };
}
await page.route('**/api/v1/medical-agents/runs**', async route => {
  const request = route.request();
  const path = new URL(request.url()).pathname;
  if (path.endsWith('/runs') && request.method() === 'POST') {
    current = fixture(request.postDataJSON()); runs.set(current.run_id, current);
    await route.fulfill({ json: current }); return;
  }
  const match = path.match(/\/runs\/([^/]+)(\/cancellations)?$/);
  if (!match || !runs.has(match[1])) { await route.continue(); return; }
  const run = runs.get(match[1]);
  if (match[2]) run.state = 'CANCELLED';
  await route.fulfill({ json: run });
});
async function send(surface, button) {
  await surface.getByLabel('向 AI医助 EVA 描述诊疗任务', { exact: true }).fill('请整理这次任务的资料核对清单。');
  const sendButton = surface.getByRole('button', { name: button, exact: true });
  await sendButton.click();
  await surface.locator('.eva-processing').waitFor();
}
async function completed(surface) {
  current.state = 'WAITING_FOR_REVIEW'; current.completed_at = new Date().toISOString();
  await surface.locator('.eva-processing').waitFor({ state: 'hidden' });
  const content = surface.locator('.eva-markdown').last();
  await content.getByRole('heading', { name: '本次任务摘要', exact: true }).waitFor();
  assert.equal(await content.locator('table tbody tr').count(), 2);
  assert.equal(await content.locator('ol li').count(), 2);
  assert.equal(await content.locator('script').count(), 0);
  assert.equal(await page.evaluate(() => window.evaUnsafe), undefined);
  assert.ok(await content.evaluate(el => el.scrollWidth <= el.clientWidth + 1));
}
try {
  await page.goto('http://127.0.0.1:4177/#/login');
  await page.getByLabel('用户名', { exact: true }).fill('linwei');
  await page.locator('#system-login-password').fill('HaoEMR-dev!');
  await page.getByRole('button', { name: '登录系统', exact: true }).click();
  await page.waitForURL(url => !url.hash.includes('/login'), { timeout: 60000 });
  await page.goto('http://127.0.0.1:4177/#/ai-assistant');
  const workbench = page.locator('.xiaonan-harness-page');
  await workbench.locator('.xiaonan-team-list button').first().waitFor();
  const avatar = workbench.locator('.eva-workbench-brand .eva-avatar-motion');
  assert.equal(await avatar.evaluate(el => getComputedStyle(el).animationName), 'eva-avatar-breathe');
  const before = await avatar.evaluate(el => getComputedStyle(el).transform);
  await page.waitForTimeout(350);
  assert.notEqual(await avatar.evaluate(el => getComputedStyle(el).transform), before);
  assert.equal(await workbench.locator('.eva-illustration-motion').evaluate(el => getComputedStyle(el).animationName), 'eva-illustration-float');
  await send(workbench, '发送任务');
  assert.equal(await avatar.evaluate(el => getComputedStyle(el).animationName), 'eva-avatar-working');
  assert.ok((await workbench.locator('.eva-processing-orbit').evaluate(el => getComputedStyle(el, '::before').animationName)).includes('eva-orbit'));
  await workbench.getByText(/已等待 [1-9]\d* 秒/).waitFor();
  await page.screenshot({ path: `${out}/workbench-loading.png` });
  await completed(workbench);
  assert.equal(await avatar.evaluate(el => getComputedStyle(el).animationName), 'eva-avatar-breathe');
  await page.screenshot({ path: `${out}/workbench-result.png` });

  await page.getByRole('button', { name: '打开 AI医助 EVA', exact: true }).click();
  let popup = page.locator('.eva-popup-root');
  await popup.getByRole('button', { name: '中窗', exact: true }).click();
  await send(popup, '发送');
  assert.equal(await popup.locator('.global-ai-mascot').evaluate(el => getComputedStyle(el).animationName), 'eva-avatar-working');
  await page.screenshot({ path: `${out}/center-loading.png` });
  await popup.getByRole('button', { name: '右侧窗', exact: true }).click();
  await popup.locator('.eva-processing').waitFor();
  await page.screenshot({ path: `${out}/side-loading.png` });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  assert.equal(await popup.locator('.eva-processing-orbit').evaluate(el => getComputedStyle(el, '::before').animationName), 'none');
  assert.equal(await popup.locator('.global-ai-mascot').evaluate(el => getComputedStyle(el).animationName), 'none');
  await completed(popup);
  await page.screenshot({ path: `${out}/side-result.png` });
  await page.evaluate(() => document.documentElement.style.setProperty('--assistant-side-width', '380px'));
  const narrowContent = popup.locator('.eva-markdown').last();
  assert.ok(await narrowContent.evaluate(el => el.scrollWidth <= el.clientWidth + 1));
  const bounds = await narrowContent.boundingBox();
  const popupBounds = await popup.boundingBox();
  assert.ok(bounds.x + bounds.width <= popupBounds.x + popupBounds.width + 1, 'message must fit inside the narrow window');
  await page.screenshot({ path: `${out}/side-narrow-result.png` });
  await page.evaluate(() => document.documentElement.style.removeProperty('--assistant-side-width'));
  await popup.getByRole('button', { name: '中窗', exact: true }).click();
  await popup.locator('.eva-markdown table').waitFor();
  await page.screenshot({ path: `${out}/center-result.png` });

  await popup.getByRole('button', { name: '新任务', exact: true }).click();
  await send(popup, '发送'); current.state = 'FAILED';
  await popup.locator('.eva-processing').waitFor({ state: 'hidden' });
  await popup.getByRole('button', { name: '重试', exact: true }).waitFor();
  await popup.getByRole('button', { name: '新任务', exact: true }).click();
  await send(popup, '发送');
  await popup.getByRole('button', { name: '取消', exact: true }).click();
  await popup.locator('.eva-processing').waitFor({ state: 'hidden' });
  await popup.getByText('任务已取消，未产生可采用的诊疗结果。', { exact: false }).first().waitFor();
  assert.deepEqual(errors, []);
  await writeFile(`${out}/result.json`, JSON.stringify({ test_mode: 'UI_FIXTURE_NO_MODEL_CALL', surfaces: ['workbench', 'center', 'side'], avatar_idle_motion: 'PASS', avatar_working_motion: 'PASS', markdown: 'PASS', loading: 'PASS', elapsed_timer: 'PASS', narrow_side_window: 'PASS', mode_switch_keeps_message: 'PASS', failure_stops_loading: 'PASS', cancellation_stops_loading: 'PASS', reduced_motion: 'PASS', errors }, null, 2));
  console.log('EVA presentation: all three surfaces, Markdown, loading, failure, cancellation and reduced motion PASS');
} finally { await browser.close(); }
