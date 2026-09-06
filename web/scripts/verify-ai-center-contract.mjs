import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const base = process.env.AI_CENTER_URL || 'http://127.0.0.1:4177';
const out = '../output/playwright/ai-center-contract';
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
try {
  await page.goto(`${base}/#/login`);
  await page.getByLabel('用户名', { exact: true }).fill('linwei');
  await page.locator('#system-login-password').fill(process.env.AI_CENTER_PASSWORD || 'HaoEMR-dev!');
  await page.getByRole('button', { name: '登录系统', exact: true }).click();
  await page.waitForURL(url => !url.hash.includes('/login'), { timeout: 60000 });

  const modelsResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/model-deployments' && response.request().method() === 'GET');
  await page.goto(`${base}/#/models`);
  const response = await modelsResponse;
  assert.equal(response.status(), 200);
  const models = await response.json();
  assert.ok(models.length > 0);
  for (const model of models) assert.equal(typeof model.test_only, 'boolean', model.model_code);
  await page.getByRole('heading', { name: '模型服务与 API 配置', exact: true }).waitFor();
  await page.locator('tbody tr').filter({ hasText: models[0].display_name }).first().waitFor();
  const body = await page.locator('body').innerText();
  assert.ok(body.includes('AI中心'));
  assert.ok(!body.includes('临床辅助中心'));
  assert.ok(!body.includes('CONTRACT_MISMATCH'));
  await page.screenshot({ path: `${out}/models.png`, fullPage: true });

  await page.goto(`${base}/#/ai-assistant`);
  await page.getByRole('heading', { name: 'AI医助 EVA', exact: true }).waitFor();
  await page.locator('.xiaonan-team-list button').first().waitFor();
  assert.ok(!(await page.locator('body').innerText()).includes('CONTRACT_MISMATCH'));
  const retiredRoutes = ['ai-action-review', 'ai-assistant-policy', 'clinical-specialty-ai'];
  for (const route of retiredRoutes) {
    assert.equal(await page.locator(`a[href="#/${route}"]`).count(), 0);
  }
  for (const [route, label, title] of [
    ['agent-catalog', '医助agent', '医助agent设计与编排'],
    ['skill-catalog', '医助skill', '医助skill库'],
    ['tool-catalog', '医助tool', '医助tool库'],
  ]) {
    await page.getByRole('link', { name: label, exact: true }).click();
    await page.getByRole('heading', { name: title, exact: true }).waitFor();
    await page.locator('.admin-table tbody tr').first().waitFor({ timeout: 60000 });
    assert.equal(new URL(page.url()).hash, `#/${route}`);
  }
  await page.screenshot({ path: `${out}/ai-center-navigation.png`, fullPage: true });
  for (const route of retiredRoutes) {
    await page.goto(`${base}/#/${route}`);
    await page.getByRole('heading', { name: '页面不存在或尚未登记', exact: true }).waitFor();
  }
  assert.deepEqual(errors, []);
  await writeFile(`${out}/result.json`, JSON.stringify({
    base, menu: 'AI中心', model_count: models.length,
    test_only_present: true, models_page: 'PASS', assistant_page: 'PASS',
    renamed_catalog_pages: 'PASS', retired_routes: retiredRoutes, retired_routes_unavailable: true, errors,
  }, null, 2));
  console.log('AI center menu, model contract and assistant page: PASS');
} finally {
  await browser.close();
}
