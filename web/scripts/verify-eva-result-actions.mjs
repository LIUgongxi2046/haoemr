import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const out = '../output/playwright/eva-result-actions';
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = []; page.on('pageerror', error => errors.push(error.message));
const runs = new Map(); const submissions = []; const reads = [];
let current, holdCreation = false, releaseCreation;
function fixture(body) {
  return { run_id: randomUUID(), context_lease_id: body.context_lease_id, root_agent_code: 'EVA', root_agent_version: '1', composition_code: 'UI_HANDOFF', composition_version: '1', requested_stage: body.stage_code || 'UI', patient_id: body.patient_id, encounter_id: body.encounter_id, target_type: body.target_type, target_id: body.target_id, objective: body.objective, state: 'RUNNING', sequence: 1, output: { execution_mode: 'LIVE_MODEL', summary: `## 任务结果\n\n${body.objective}` }, created_at: new Date().toISOString(), completed_at: null, row_version: 1, attempt: 1, max_attempts: 3, cancel_requested_at: null, failure_code: null, child_runs: [], events: [] };
}
await page.route('**/api/v1/medical-agents/runs**', async route => {
  const request = route.request(); const path = new URL(request.url()).pathname;
  if (path.endsWith('/runs') && request.method() === 'POST') {
    const body = request.postDataJSON(); submissions.push(body);
    current = fixture(body); runs.set(current.run_id, current);
    const response = current;
    if (holdCreation) await new Promise(resolve => { releaseCreation = resolve; });
    await route.fulfill({ json: response }); return;
  }
  const id = path.split('/').at(-1);
  if (runs.has(id)) {
    reads.push({ id, patient: request.headers()['x-patient-context'], encounter: request.headers()['x-encounter-context'] });
    await route.fulfill({ json: runs.get(id) }); return;
  }
  await route.continue();
});
const workbench = page.locator('.xiaonan-harness-page');
const popup = page.locator('.eva-popup-root');
async function send(surface, text, button = '发送') {
  const before = submissions.length;
  await surface.getByLabel('向 AI医助 EVA 描述诊疗任务', { exact: true }).fill(text);
  await surface.getByRole('button', { name: button, exact: true }).click();
  await surface.locator('.eva-processing').waitFor();
  for (let attempt = 0; submissions.length === before && attempt < 100; attempt += 1) await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(submissions.length, before + 1, 'exactly one task must be submitted');
}
async function finish(surface) {
  current.state = 'WAITING_FOR_REVIEW'; current.completed_at = new Date().toISOString();
  await surface.locator('.eva-processing').waitFor({ state: 'hidden', timeout: 30000 });
}
async function open(mode) {
  await page.getByRole('button', { name: '打开 AI医助 EVA', exact: true }).click();
  await popup.getByRole('button', { name: mode, exact: true }).click();
  await popup.locator('.xiaonan-team-list button').first().waitFor();
}
try {
  await page.goto('http://127.0.0.1:4177/#/login', { waitUntil: 'domcontentloaded' });
  await page.getByLabel('用户名', { exact: true }).fill('linwei');
  await page.locator('#system-login-password').fill('HaoEMR-dev!');
  await page.getByRole('button', { name: '登录系统', exact: true }).click();
  await page.getByRole('button', { name: '打开 AI医助 EVA', exact: true }).waitFor({ timeout: 60000 });

  await open('中窗');
  await popup.locator('.eva-default-patients button').filter({ hasText: '陈国栋' }).click();
  await send(popup, '中窗闭环操作验收');
  assert.equal(await popup.getByRole('button', { name: '打开患者病历复核', exact: true }).count(), 0);
  await finish(popup);
  await popup.getByRole('button', { name: '打开患者病历复核', exact: true }).waitFor();
  await popup.getByRole('button', { name: '复制候选内容', exact: true }).isEnabled().then(value => assert.ok(value));
  await popup.getByRole('button', { name: '转为待办提醒', exact: true }).click();
  const reminder = page.getByRole('dialog', { name: '确认待办提醒', exact: true });
  await reminder.waitFor();
  assert.ok((await reminder.locator('textarea').inputValue()).includes('中窗闭环操作验收'));
  await reminder.getByRole('button', { name: '取消', exact: true }).click();
  await page.screenshot({ path: `${out}/center-actions.png` });
  await popup.getByRole('button', { name: '打开患者病历复核', exact: true }).click();
  await page.waitForURL(url => url.hash === '#/er-record', { waitUntil: 'domcontentloaded' });
  await page.locator('aside.eva-popup-root').waitFor();
  await popup.getByText('中窗闭环操作验收', { exact: true }).first().waitFor();
  await popup.getByRole('button', { name: '转为待办提醒', exact: true }).waitFor();
  await popup.getByRole('button', { name: '打开患者病历复核', exact: true }).click();
  await popup.waitFor(); // Same record route must keep the conversation usable.
  await page.screenshot({ path: `${out}/side-record-review.png` });

  // Switch to inpatient in the center picker, then complete from the 400px side panel.
  await popup.getByRole('button', { name: '中窗', exact: true }).click();
  await popup.locator('.eva-default-patients button').filter({ hasText: '李沐晴' }).click();
  await popup.getByRole('button', { name: '右侧窗', exact: true }).click();
  await send(popup, '侧窗住院闭环验收'); await finish(popup);
  const review = popup.getByRole('button', { name: '打开患者病历复核', exact: true });
  await review.scrollIntoViewIfNeeded();
  const bounds = await review.boundingBox(), panel = await popup.boundingBox();
  assert.ok(bounds.x >= panel.x && bounds.x + bounds.width <= panel.x + panel.width);
  await review.click();
  await page.waitForURL(url => url.hash === '#/inpatient-overview', { waitUntil: 'domcontentloaded' });
  await popup.locator('.eva-markdown').getByText('侧窗住院闭环验收', { exact: true }).waitFor();
  assert.equal(submissions.length, 2, 'review navigation must not create a model task');

  await popup.getByRole('button', { name: '中窗', exact: true }).click();
  await popup.getByRole('button', { name: '取消绑定', exact: true }).click();
  await send(popup, '通用问答无病历入口'); await finish(popup);
  assert.equal(await popup.getByRole('button', { name: '打开患者病历复核', exact: true }).count(), 0);
  assert.equal(await popup.getByRole('button', { name: '转为待办提醒', exact: true }).count(), 0);
  // Review an older workbench answer, retaining its actionable run in the popup.
  await popup.getByRole('button', { name: '关闭 AI医助 EVA', exact: true }).click();
  await page.goto('http://127.0.0.1:4177/#/ai-assistant', { waitUntil: 'domcontentloaded' });
  await workbench.locator('.eva-default-patients button').filter({ hasText: '张慧敏' }).click();
  await send(workbench, '第一轮文书复核', '发送任务');
  const firstRun = current;
  current.child_runs = [{ child_run_id: randomUUID(), child_agent_code: 'OPD_SUMMARY', display_name: '文书医助', display_role: '整理资料', current_action: '汇总记录', contribution_label: '文书草稿', state: 'COMPLETED', critical: true, contribution: { draft_sections: { chief_complaint: '第一轮待医生核对的草稿' } }, source_references: [], started_at: new Date().toISOString(), completed_at: new Date().toISOString() }];
  await finish(workbench);
  await send(workbench, '第二轮稍后复核', '发送任务'); await finish(workbench);
  await workbench.getByRole('button', { name: '打开患者病历复核', exact: true }).first().click();
  await page.waitForURL(url => url.hash === '#/opd-record', { waitUntil: 'domcontentloaded' });
  await popup.locator('.eva-popup-message.user').getByText('第一轮文书复核', { exact: true }).waitFor();
  assert.equal(await popup.locator('.eva-popup-message.user').count(), 1);
  await popup.getByRole('button', { name: '打开病历编辑区', exact: true }).waitFor();
  await popup.getByRole('button', { name: '转为待办提醒', exact: true }).waitFor();
  await popup.getByRole('button', { name: '中窗', exact: true }).click();
  await popup.getByRole('button', { name: '打开病历编辑区', exact: true }).click();
  await page.waitForURL(url => url.hash.includes('/record-editor?agent_run=' + firstRun.run_id), { waitUntil: 'domcontentloaded' });
  await popup.waitFor({ state: 'hidden' });
  assert.equal(submissions.length, 5);
  assert.deepEqual(errors, []);
  await writeFile(`${out}/result.json`, JSON.stringify({ test_mode: 'UI_FIXTURE_NO_MODEL_CALL', center_actions: 'PASS', reminder_confirmation: 'PASS', emergency_record_review: 'PASS', same_route_review: 'PASS', side_inpatient_review: 'PASS', narrow_button_layout: 'PASS', no_duplicate_submissions: 'PASS', unbound_context: 'PASS', older_answer_review: 'PASS', draft_editor_handoff: 'PASS', errors }, null, 2));
  console.log('EVA result actions: PASS');
} catch (error) {
  await writeFile(`${out}/failure.txt`, `${error}\n${await page.locator('body').innerText()}`);
  throw error;
} finally { releaseCreation?.(); await browser.close(); }
