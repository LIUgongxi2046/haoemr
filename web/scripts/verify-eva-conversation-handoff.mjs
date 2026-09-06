import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const out = '../output/playwright/eva-conversation-handoff';
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = []; page.on('pageerror', error => errors.push(error.message));
const runs = new Map(); const submissions = []; const reads = [];
let current, holdCreation = false, releaseCreation;
function fixture(body) {
  return { run_id: randomUUID(), context_lease_id: body.context_lease_id, root_agent_code: 'EVA', root_agent_version: '1', composition_code: 'UI_HANDOFF', composition_version: '1', requested_stage: body.stage_code || 'UI', patient_id: body.patient_id, encounter_id: body.encounter_id, target_type: body.target_type, target_id: body.target_id, objective: body.objective, state: 'RUNNING', sequence: 1, output: { summary: `## 任务结果\n\n${body.objective}` }, created_at: new Date().toISOString(), completed_at: null, row_version: 1, attempt: 1, max_attempts: 3, cancel_requested_at: null, failure_code: null, child_runs: [], events: [] };
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
  await send(popup, '保留第一轮对话'); await finish(popup);
  await send(popup, '中窗正在处理的第二轮');
  const centerRun = current;
  await popup.getByRole('button', { name: '完整工作台', exact: true }).click();
  await popup.waitFor({ state: 'hidden' });
  await workbench.locator('.eva-processing').waitFor({ timeout: 60000 });
  assert.equal(await workbench.locator('.eva-agent-message.user').count(), 2);
  await workbench.locator('.eva-current-patient').getByText('陈国栋', { exact: true }).waitFor();
  await workbench.locator('.eva-agent-message.assistant').last().getByText(`任务 …${centerRun.run_id.slice(-8)}`, { exact: true }).waitFor();
  const currentHeader = await workbench.locator('.eva-agent-message.assistant').last().locator('header').boundingBox();
  const threadBounds = await workbench.locator('.eva-agent-thread').boundingBox();
  assert.ok(currentHeader.y >= threadBounds.y && currentHeader.y < threadBounds.y + threadBounds.height, 'the current turn must be visible on arrival');
  await finish(workbench);
  assert.equal(submissions.length, 2, 'opening the workbench must not resubmit');
  await page.screenshot({ path: `${out}/center-to-workbench.png` });
  await send(workbench, '完整工作台继续第三轮', '发送任务'); await finish(workbench);
  assert.equal(submissions[2].patient_id, submissions[1].patient_id);
  assert.equal(submissions[2].encounter_id, submissions[1].encounter_id);
  assert.equal(submissions[2].model_deployment_id, submissions[1].model_deployment_id);
  assert.deepEqual(submissions[2].context_scopes, submissions[1].context_scopes);

  // Already on the workbench: the one-use bridge must still replace the old chat.
  await open('中窗');
  await popup.getByRole('button', { name: '取消绑定', exact: true }).click();
  await popup.getByRole('button', { name: '右侧窗', exact: true }).click();
  holdCreation = true;
  await send(popup, '侧窗刚发出但尚未取得任务编号');
  await page.waitForTimeout(200);
  const beforeTransfer = submissions.length;
  await popup.getByRole('button', { name: '完整工作台', exact: true }).click();
  await popup.waitFor({ state: 'hidden' });
  await workbench.locator('.eva-processing').waitFor();
  assert.equal(await workbench.locator('.eva-agent-message.user').count(), 1);
  await workbench.locator('.eva-agent-message.user').getByText('侧窗刚发出但尚未取得任务编号', { exact: true }).waitFor();
  releaseCreation(); holdCreation = false;
  await workbench.locator('.eva-agent-message.assistant').last().getByText(`任务 …${current.run_id.slice(-8)}`, { exact: true }).waitFor();
  await finish(workbench);
  assert.equal(submissions.length, beforeTransfer);
  await workbench.locator('.eva-current-patient').getByText('未选择患者', { exact: true }).waitFor();
  await page.screenshot({ path: `${out}/side-pending-to-workbench.png` });

  // Opening an empty popup must not erase the existing workbench conversation.
  await open('右侧窗');
  await popup.getByRole('button', { name: '完整工作台', exact: true }).click();
  await popup.waitFor({ state: 'hidden' });
  assert.equal(await workbench.locator('.eva-agent-message.user').count(), 1);
  assert.deepEqual(errors, []);
  assert.ok(reads.some(read => read.id === centerRun.run_id), 'the original task must continue to be polled');
  await writeFile(`${out}/result.json`, JSON.stringify({ test_mode: 'UI_FIXTURE_NO_MODEL_CALL', center_running_handoff: 'PASS', multiple_messages: 'PASS', patient_and_model_preserved: 'PASS', continue_next_turn: 'PASS', side_pending_creation: 'PASS', same_route_handoff: 'PASS', unbound_context_preserved: 'PASS', no_duplicate_submissions: 'PASS', empty_popup_preserves_workbench: 'PASS', errors }, null, 2));
  console.log('EVA conversation handoff: PASS');
} catch (error) {
  await writeFile(`${out}/failure.txt`, `${error}\n${await page.locator('body').innerText()}`);
  throw error;
} finally { releaseCreation?.(); await browser.close(); }
