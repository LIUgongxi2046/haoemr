import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const requestedRoutes = new Set((process.env.HAOEMR_MENU_SEED_ROUTES || '').split(',').filter(Boolean));
const allTargets = [
  ['admin-dictionaries', '创建并生效'], ['admin-permissions', '生成待审草案'],
  ['ai-reminder-detail', '创建提醒'],
  ['anesthesia-workbench', '运行场景'], ['device-monitoring', '运行场景'],
  ['integration-messages', '运行场景'], ['model-connection', '运行场景'],
  ['model-routing', '运行场景'], ['pathology-workbench', '运行场景'], ['therapy-workbench', '运行场景'],
  ['care-operations', '记录体征'], ['data-center', '按登记口径计算'],
  ['department-qc', '按登记口径计算'], ['quality-center', '按登记口径计算'],
  ['research', '按登记口径计算'], ['research-stats', '按登记口径计算'],
  ['infection-events', '上报线索'], ['imaging-workbench', '申请检查'],
  ['inpatient-pharmacy', '摆药并待核验'], ['outpatient-pharmacy', '调配并待核验'],
  ['model-evaluation', '记录并判定'], ['opd-followup', '登记随访'],
  ['surgery-schedule', '排程手术'], ['transfusion', '双人核验并开始输注'],
  ['lab-workbench', '创建标本申请'], ['er-handoff', '加入交接清单'],
];
const targets = requestedRoutes.size ? allTargets.filter(([route]) => requestedRoutes.has(route)) : allTargets;

function valueFor(label, index) {
  const text = label.toLowerCase();
  if (/患者id|患者 id/.test(text)) return '018f0000-0000-7000-8000-000000000003';
  if (/uuid|核对者|见证者/.test(text)) return '018f0000-0000-7000-8000-00000000aa06';
  if (/病原体/.test(text)) return 'MRSA-SYNTHETIC';
  if (/单位号/.test(text)) return 'UNIT-SYN-20260825-001';
  if (/哈希/.test(text)) return '8c3f7c8d34c524313f289a928573dbb84d0333d24f3c6b01a6f901d1a9c58176';
  if (/存放位置/.test(text)) return '病案库 A 区 03 柜 SYN-20260825';
  if (/策略编码|编码/.test(text)) return `SYN-COMPLEX-${20260825 + index}`;
  if (/摘要|原因|问题|结论|说明|评估|措施|计划|指征|备注|消息/.test(text)) return '合成复杂病例：多系统合并风险，已完成身份、过敏史与关键检查复核，需跨科协同并持续追踪处置闭环。';
  if (/科室/.test(text)) return '心血管内科';
  if (/药品|项目名称/.test(text)) return '复杂病例联合用药核验项目';
  if (/批次/.test(text)) return 'BATCH-SYN-20260825-A17';
  if (/单位/.test(text)) return '次';
  if (/指标名|名称/.test(text)) return '复杂病例全过程闭环率';
  return `合成验收数据-${index + 1}`;
}

function numberFor(label) {
  if (/体温/.test(label)) return 38.6;
  if (/脉搏|心率/.test(label)) return 112;
  if (/呼吸/.test(label)) return 24;
  if (/收缩压/.test(label)) return 168;
  if (/舒张压/.test(label)) return 108;
  if (/血氧/.test(label)) return 93;
  if (/数量|枚数/.test(label)) return 2;
  if (/金额|价格/.test(label)) return 268.5;
  return 1;
}

async function openRoute(page, route) {
  await page.goto(`${baseUrl}/#/${route}`, { waitUntil: 'domcontentloaded', timeout: 20_000 });
  await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, route, { timeout: 8_000 });
  await page.waitForFunction(() => {
    const root = document.querySelector('main [data-page-root]');
    return root && !root.querySelector('.clinical-page-state.loading,.state-page:not(.error)');
  }, undefined, { timeout: 20_000 }).catch(() => {});
  await page.waitForTimeout(180);
}

async function fillScope(scope) {
  const controls = scope.locator('input:not([type="hidden"]), textarea, select');
  for (let index = 0; index < await controls.count(); index += 1) {
    const control = controls.nth(index);
    if (!await control.isVisible() || await control.isDisabled()) continue;
    const meta = await control.evaluate((element) => ({
      tag: element.tagName, type: element.getAttribute('type') ?? '', value: element.value,
      label: element.closest('label')?.textContent?.replace(/\s+/g, ' ').trim() ?? element.getAttribute('aria-label') ?? '',
      required: element.required,
    }));
    if (meta.tag === 'SELECT') {
      if (!meta.value) {
        const values = await control.locator('option:not([disabled])').evaluateAll((options) => options.map((option) => option.value).filter(Boolean));
        if (values[0]) await control.selectOption(values[0]);
      }
      continue;
    }
    if (meta.type === 'checkbox') {
      if (/风险|异常|高危|复核|确认/.test(meta.label)) await control.check();
      continue;
    }
    if (meta.type === 'number') {
      await control.fill(String(numberFor(meta.label)));
      continue;
    }
    if (meta.value && !['search'].includes(meta.type)) continue;
    if (meta.type === 'datetime-local') {
      const offset = /期望|计划|排程|手术/.test(meta.label) ? 86_400_000 : -60 * 60_000;
      await control.fill(new Date(Date.now() + offset).toISOString().slice(0, 16));
    }
    else if (meta.type === 'date') await control.fill(new Date().toISOString().slice(0, 10));
    else if (meta.type === 'time') await control.fill('10:30');
    else if (meta.required || !/可选/.test(meta.label)) await control.fill(valueFor(meta.label, index));
  }
}

async function submitAction(page, route, actionLabel) {
  await openRoute(page, route);
  const beforeRows = await page.locator('main tbody tr').count();
  const beforeEmpty = await page.locator('main .admin-empty,main .empty-state,main .clinical-empty-state').count();
  const button = page.getByRole('button', { name: actionLabel, exact: true }).last();
  if (!await button.count()) return { route, action: actionLabel, status: 'ACTION_MISSING' };
  if (actionLabel === '运行场景' && await page.locator('.simulation-evidence').count()) return { route, action: actionLabel, status: 'EXISTING' };
  const form = button.locator('xpath=ancestor::form[1]');
  await fillScope(await form.count() ? form : page.locator('main [data-page-root]'));
  await page.waitForTimeout(100);
  if (actionLabel === '运行场景') await button.waitFor({ state: 'visible', timeout: 8_000 }).catch(() => {});
  if (await button.isDisabled()) return { route, action: actionLabel, status: 'ACTION_DISABLED' };
  const responsePromise = page.waitForResponse((response) => response.url().includes('/api/v1/') && response.request().method() !== 'GET', { timeout: 15_000 }).catch(() => null);
  await button.click();
  const response = await responsePromise;
  await page.waitForTimeout(700);
  const afterRows = await page.locator('main tbody tr').count();
  const afterEmpty = await page.locator('main .admin-empty,main .empty-state,main .clinical-empty-state').count();
  const notices = await page.locator('main [role="status"],main .inline-notice,main .admin-notice').allTextContents();
  return {
    route, action: actionLabel,
    status: response?.ok() || afterRows > beforeRows || afterEmpty < beforeEmpty ? 'SEEDED' : 'NEEDS_ATTENTION',
    httpStatus: response?.status() ?? null,
    beforeRows, afterRows, beforeEmpty, afterEmpty,
    notice: notices.join(' ').replace(/\s+/g, ' ').slice(0, 300),
    response: response ? (await response.text().catch(() => '')).slice(0, 500) : '',
    request: response?.request().postData()?.slice(0, 700) ?? '',
  };
}

async function createInpatientConsultation(page) {
  await openRoute(page, 'ip-consult');
  if (await page.locator('.consult-queue-list > button').count()) return { route: 'ip-consult', status: 'EXISTING' };
  await page.getByRole('button', { name: '新建会诊', exact: true }).click();
  const form = page.locator('.consult-create-card form');
  await fillScope(form);
  const submit = page.getByRole('button', { name: '提交会诊申请', exact: true });
  const responsePromise = page.waitForResponse((response) => response.url().includes('/api/v1/') && response.request().method() === 'POST', { timeout: 15_000 }).catch(() => null);
  await submit.click();
  const response = await responsePromise;
  await page.waitForTimeout(700);
  return { route: 'ip-consult', status: response?.ok() ? 'SEEDED' : 'NEEDS_ATTENTION', httpStatus: response?.status() ?? null, response: response ? (await response.text()).slice(0, 500) : '' };
}

async function seedInpatientDocument(page) {
  await openRoute(page, 'inpatient-course');
  const existing = page.getByRole('link', { name: /(进入书写|查看版本证据)/ }).first();
  if (await existing.count()) return { route: 'inpatient-doc-editor', status: 'EXISTING' };
  const start = page.getByRole('button', { name: '开始书写', exact: true }).first();
  if (!await start.count() || await start.isDisabled()) return { route: 'inpatient-doc-editor', status: 'NO_STARTABLE_TASK' };
  const createResponse = page.waitForResponse((response) => response.url().includes('/api/v1/') && response.request().method() === 'POST', { timeout: 15_000 }).catch(() => null);
  await start.click();
  const created = await createResponse;
  await page.waitForFunction(() => document.documentElement.dataset.routeId === 'inpatient-doc-editor', undefined, { timeout: 15_000 });
  await page.waitForFunction(() => Boolean(document.querySelector('.inpatient-document-editor')), undefined, { timeout: 20_000 });
  const fields = page.locator('.inpatient-section-form label');
  for (const field of await fields.all()) {
    const input = field.locator('textarea:not([readonly])');
    if (await input.count()) await input.fill(`合成验收内容：${await field.locator('span').textContent()}，已由作者核对。`);
  }
  const save = page.getByRole('button', { name: '保存新版本', exact: true });
  let saved = null;
  if (await save.count() && !await save.isDisabled()) {
    const saveResponse = page.waitForResponse((response) => response.url().includes('/api/v1/') && ['POST', 'PUT', 'PATCH'].includes(response.request().method()), { timeout: 15_000 }).catch(() => null);
    await save.click();
    saved = await saveResponse;
    await page.waitForTimeout(650);
  }
  const quality = page.getByRole('button', { name: '运行确定性质控', exact: true });
  if (await quality.count() && !await quality.isDisabled()) {
    await quality.click();
    await page.waitForTimeout(650);
  }
  return {
    route: 'inpatient-doc-editor',
    status: (created?.ok() && (!saved || saved.ok())) ? 'SEEDED' : 'NEEDS_ATTENTION',
    httpStatus: saved?.status() ?? created?.status() ?? null,
  };
}

async function seedArchiveAsset(page) {
  await openRoute(page, 'archive-catalog');
  if (await page.locator('.archive-table tbody tr').count()) return { route: 'archive-catalog', status: 'EXISTING' };
  await page.getByRole('button', { name: '新增资产编目', exact: true }).click();
  const form = page.locator('.archive-catalog-create');
  await form.locator('select').selectOption('SCAN');
  await form.locator('label').filter({ hasText: '存放位置' }).locator('input').fill('病案库 A 区 03 柜 SYN-20260825');
  await form.locator('label').filter({ hasText: '内容哈希' }).locator('input').fill('8c3f7c8d34c524313f289a928573dbb84d0333d24f3c6b01a6f901d1a9c58176');
  await page.waitForTimeout(100);
  const submit = page.getByRole('button', { name: '完成资产编目', exact: true });
  const responsePromise = page.waitForResponse((response) => response.url().includes('/api/v1/medical-record-assets') && response.request().method() === 'POST', { timeout: 15_000 }).catch(() => null);
  await submit.click();
  const response = await responsePromise;
  await page.waitForTimeout(650);
  return { route: 'archive-catalog', status: response?.ok() ? 'SEEDED' : 'NEEDS_ATTENTION', httpStatus: response?.status() ?? null, response: response ? (await response.text()).slice(0, 500) : '' };
}

async function seedEmergencyAccess(page) {
  await openRoute(page, 'emergency-access');
  if (await page.locator('.emergency-history article').count()) return { route: 'emergency-access', status: 'EXISTING' };
  const form = page.locator('.emergency-form');
  await form.locator('textarea').fill('合成危急患者需立即核对过敏史与最近病历，常规授权无法在抢救时限内建立。');
  await form.locator('select').selectOption('15');
  await form.locator('input[type="checkbox"]').check();
  await page.waitForFunction(() => {
    const button = [...document.querySelectorAll('button')].find((item) => item.textContent?.includes('二次认证并建立紧急访问'));
    return button instanceof HTMLButtonElement && !button.disabled;
  }, undefined, { timeout: 5_000 }).catch(() => {});
  const submit = page.getByRole('button', { name: '二次认证并建立紧急访问', exact: true });
  if (await submit.isDisabled()) {
    const state = await form.evaluate((node) => ({ reason: node.querySelector('textarea')?.value, checked: node.querySelector('input[type="checkbox"]')?.checked }));
    return { route: 'emergency-access', status: 'ACTION_DISABLED', state };
  }
  const responsePromise = page.waitForResponse((response) => response.url().includes('/api/v1/emergency-access') && response.request().method() === 'POST', { timeout: 15_000 }).catch(() => null);
  await submit.click();
  const response = await responsePromise;
  await page.waitForTimeout(650);
  return { route: 'emergency-access', status: response?.ok() ? 'SEEDED' : 'NEEDS_ATTENTION', httpStatus: response?.status() ?? null, response: response ? (await response.text()).slice(0, 500) : '' };
}

async function seedOutpatientDocumentVersion(page) {
  await openRoute(page, 'record-diff');
  if (await page.locator('.record-diff-card').count()) return { route: 'record-diff', status: 'EXISTING' };
  await openRoute(page, 'record-editor');
  const editor = page.locator('.record-form textarea:not([readonly])').first();
  if (!await editor.count()) return { route: 'record-diff', status: 'NO_EDITABLE_DOCUMENT' };
  const previous = await editor.inputValue();
  await editor.fill(`${previous}\n合成版本追加：2026-08-25 多系统风险复核与随访闭环已记录。`.trim());
  const save = page.getByRole('button', { name: '立即保存', exact: true });
  if (await save.isDisabled()) return { route: 'record-diff', status: 'SAVE_DISABLED' };
  const responsePromise = page.waitForResponse((response) => response.url().includes('/api/v1/') && response.request().method() !== 'GET', { timeout: 15_000 }).catch(() => null);
  await save.click();
  const response = await responsePromise;
  await page.waitForTimeout(700);
  return { route: 'record-diff', status: response?.ok() ? 'SEEDED' : 'NEEDS_ATTENTION', httpStatus: response?.status() ?? null };
}

async function createWorkspaceOrder(page, route, itemType, suffix) {
  await openRoute(page, route);
  await page.getByRole('button', { name: '新增医嘱', exact: true }).click();
  const panel = page.locator('.order-create-panel');
  await panel.locator('label').filter({ hasText: '项目类别' }).locator('select').selectOption(itemType);
  const inputs = panel.locator('input,textarea');
  for (let index = 0; index < await inputs.count(); index += 1) {
    const input = inputs.nth(index);
    const label = await input.evaluate((element) => element.closest('label')?.textContent?.replace(/\s+/g, ' ').trim() ?? '');
    if (inputType(await input.getAttribute('type')) === 'number') await input.fill('1');
    else if (/目录编码/.test(label)) await input.fill(`${itemType}-SYN-${suffix}`);
    else if (/项目名称/.test(label)) await input.fill(itemType === 'LAB' ? '高敏肌钙蛋白与电解质复核' : '胸部增强CT与三维重建');
    else if (/单位/.test(label)) await input.fill('次');
    else if (/临床指征/.test(label)) await input.fill('复杂病例多系统风险评估，需与既往结果纵向比较并形成闭环。');
    else if (/执行说明/.test(label)) await input.fill('完成身份核验、过敏史复核与检查前风险评估。');
  }
  const create = page.getByRole('button', { name: '保存医嘱草稿', exact: true });
  const createResponse = page.waitForResponse((response) => response.url().includes('/api/v1/') && response.request().method() === 'POST', { timeout: 15_000 }).catch(() => null);
  await create.click();
  const created = await createResponse;
  await page.waitForTimeout(500);
  const sign = page.getByRole('button', { name: '安全预检并签署生效', exact: true }).last();
  if (await sign.count()) await sign.click();
  await page.waitForTimeout(650);
  const complete = page.getByRole('button', { name: '完成剩余', exact: true }).last();
  if (await complete.count()) await complete.click();
  await page.waitForTimeout(650);
  return { route: `${route}:${itemType}`, status: created?.ok() ? 'SEEDED' : 'NEEDS_ATTENTION', httpStatus: created?.status() ?? null };
}

async function seedBilling(page) {
  await openRoute(page, 'billing');
  if (await page.locator('.charge-card,.billing-charge-row').count() || await page.locator('main tbody tr').count()) return { route: 'billing', status: 'EXISTING' };
  const itemCode = `CHARGE-SYN-${Date.now()}`;
  const priceForm = page.getByRole('button', { name: '创建价格版本', exact: true }).locator('xpath=ancestor::form[1]');
  await priceForm.locator('label').filter({ hasText: '目录编码' }).locator('input').fill('CATALOG-SYN-COMPLEX');
  await priceForm.locator('label').filter({ hasText: '项目编码' }).locator('input').fill(itemCode);
  await priceForm.locator('label').filter({ hasText: '项目名称' }).locator('input').fill('复杂病例多学科联合诊疗费');
  await priceForm.locator('input[type="number"]').fill('268.50');
  await priceForm.locator('label').filter({ hasText: '单位' }).locator('input').fill('次');
  await priceForm.locator('label').filter({ hasText: '发布版本' }).locator('input').fill('2026.08-SYN');
  await page.getByRole('button', { name: '创建价格版本', exact: true }).click();
  await page.waitForTimeout(600);
  const chargeForm = page.getByRole('button', { name: '记费入账', exact: true }).locator('xpath=ancestor::form[1]');
  await chargeForm.locator('label').filter({ hasText: '项目编码' }).locator('input').fill(itemCode);
  await chargeForm.locator('input[type="number"]').fill('2');
  const responsePromise = page.waitForResponse((response) => response.url().includes('/api/v1/') && response.request().method() === 'POST', { timeout: 15_000 }).catch(() => null);
  await page.getByRole('button', { name: '记费入账', exact: true }).click();
  const response = await responsePromise;
  await page.waitForTimeout(650);
  return { route: 'billing', status: response?.ok() ? 'SEEDED' : 'NEEDS_ATTENTION', httpStatus: response?.status() ?? null, response: response ? (await response.text()).slice(0, 500) : '' };
}

function inputType(value) { return value ?? ''; }

async function createWorkspaceResult(page, route) {
  await openRoute(page, route);
  const before = await page.locator('.result-card').count();
  await page.getByRole('button', { name: '录入结果', exact: true }).click();
  const panel = page.locator('.result-form');
  const execution = panel.locator('select').first();
  const options = await execution.locator('option').evaluateAll((items) => items.map((item) => item.value).filter(Boolean));
  if (!options.length) return { route, status: 'NO_ELIGIBLE_EXECUTION' };
  await execution.selectOption(options[0]);
  await panel.locator('input').nth(0).fill('SYN-RESULT-20260825');
  await panel.locator('input').nth(1).fill('复杂病例关键指标');
  await panel.locator('input[type="number"]').nth(0).fill('86.5');
  await panel.locator('input').nth(3).fill('ng/L');
  await panel.locator('input[type="number"]').nth(1).fill('0');
  await panel.locator('input[type="number"]').nth(2).fill('14');
  await panel.locator('select').nth(1).selectOption('CRITICAL_HIGH');
  await panel.locator('textarea').fill('复杂病例关键指标危急升高，已电话复读并启动临床处置与复测闭环。');
  const responsePromise = page.waitForResponse((response) => response.url().includes('/api/v1/') && response.request().method() === 'POST', { timeout: 15_000 }).catch(() => null);
  await page.getByRole('button', { name: '签发结果 v1', exact: true }).click();
  const response = await responsePromise;
  await page.waitForTimeout(650);
  const after = await page.locator('.result-card').count();
  return { route, status: response?.ok() || after > before ? 'SEEDED' : 'NEEDS_ATTENTION', httpStatus: response?.status() ?? null, response: response ? (await response.text()).slice(0, 500) : '' };
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const results = [];
try {
  if (!requestedRoutes.size || requestedRoutes.has('archive-catalog') || requestedRoutes.has('asset-detail') || requestedRoutes.has('archive-borrow') || requestedRoutes.has('archive-integrity')) {
    results.push(await seedArchiveAsset(page));
  }
  if (!requestedRoutes.size || requestedRoutes.has('emergency-access')) results.push(await seedEmergencyAccess(page));
  if (!requestedRoutes.size || requestedRoutes.has('inpatient-doc-editor') || requestedRoutes.has('inpatient-doc-qc') || requestedRoutes.has('inpatient-doc-versions')) {
    results.push(await seedInpatientDocument(page));
  }
  if (!requestedRoutes.size || requestedRoutes.has('record-diff')) results.push(await seedOutpatientDocumentVersion(page));
  for (const [route, action] of targets) {
    const result = await submitAction(page, route, action);
    results.push(result);
    console.log(`${route}: ${result.status} ${result.httpStatus ?? ''} ${result.notice ?? ''}`);
  }
  if (!requestedRoutes.size || requestedRoutes.has('billing')) results.push(await seedBilling(page));
  if (!requestedRoutes.size || requestedRoutes.has('ip-consult')) results.push(await createInpatientConsultation(page));
  if (!requestedRoutes.size || requestedRoutes.has('ip-orders') || requestedRoutes.has('ip-results')) {
    results.push(await createWorkspaceOrder(page, 'ip-orders', 'LAB', 'A'));
    results.push(await createWorkspaceResult(page, 'ip-results'));
    results.push(await createWorkspaceOrder(page, 'ip-orders', 'IMAGING', 'B'));
    results.push(await createWorkspaceResult(page, 'ip-results'));
  }
  if (!requestedRoutes.size || requestedRoutes.has('pacs-viewer')) {
    results.push(await createWorkspaceOrder(page, 'opd-orders', 'IMAGING', 'PACS'));
    results.push(await createWorkspaceResult(page, 'opd-results'));
  }
} finally {
  await browser.close();
}

const outputDir = resolve(projectDir, 'output/playwright');
await mkdir(outputDir, { recursive: true });
const outputPath = resolve(outputDir, 'all-menu-data-seed.json');
await writeFile(outputPath, `${JSON.stringify({ run_at: new Date().toISOString(), results }, null, 2)}\n`);
const failed = results.filter((item) => !['SEEDED', 'EXISTING'].includes(item.status));
console.log(JSON.stringify({ actions: results.length, passed: results.length - failed.length, needs_attention: failed.length, artifact: outputPath }));
if (failed.length) process.exitCode = 1;
