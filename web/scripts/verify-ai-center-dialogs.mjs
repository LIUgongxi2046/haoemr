import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/ai-center-dialogs');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4178/app/index.html').replace(/#.*$/, '');
const loginPassword = process.env.HAOEMR_DEV_LOGIN_PASSWORD;
const routes = [
  { id: 'models', create: '新建模型 API 配置', createTitle: '新建模型 API 配置', editLabel: '编辑', deleteLabel: '删除', dataText: 'DeepSeek V3 临床综合主模型' },
  { id: 'agent-catalog', create: '新建医助agent', createTitle: '新建医助agent', editLabel: '编辑', deleteLabel: '删除', dataText: '就诊摘要' },
  { id: 'skill-catalog', create: '新建医助skill', createTitle: '新建医助skill', editLabel: '编辑', deleteLabel: '删除', dataText: '入院风险摘要' },
  { id: 'tool-catalog', create: '新建医助tool', createTitle: '新建医助tool', editLabel: '编辑', deleteLabel: '删除', dataText: '手术排程只读工具' },
  { id: 'agent-evals', create: '新建草稿', createTitle: '新建配置草稿', editLabel: '编辑 / 版本管理', deleteLabel: '删除', dataText: '急诊分诊上下文完整性评测' },
  { id: 'aiops', create: '新建处理额度', createTitle: '新建处理额度', editLabel: '编辑', deleteLabel: '删除', dataText: '就诊摘要医助单次处理上限' },
  { id: 'ai-assistant', create: '新建医助任务', createTitle: '新建医助任务', dataText: '小南医助团队' },
  { id: 'ai-center', dataText: 'AI 中心' },
];

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const failures = [];
const observations = [];
let routeId = 'bootstrap';
page.on('pageerror', (error) => failures.push({ route: routeId, issue: 'PAGE_ERROR', detail: error.message }));
page.on('response', (response) => {
  if (response.url().includes('/api/v1/') && response.status() >= 400) {
    failures.push({ route: routeId, issue: 'API_FAILURE', detail: `${response.status()} ${response.url()}` });
  }
});

try {
  if (!loginPassword) throw new Error('HAOEMR_DEV_LOGIN_PASSWORD is required for browser verification');
  await page.goto(`${baseUrl}#/login`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.getByLabel('用户名').fill('linwei');
  await page.getByLabel('密码', { exact: true }).fill(loginPassword);
  await page.getByRole('button', { name: '登录系统' }).click();
  await page.waitForFunction(() => document.documentElement.dataset.routeId !== 'login-context', { timeout: 20_000 });
  for (const route of routes) {
    routeId = route.id;
    await page.goto(`${baseUrl}#${route.id}`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, route.id, { timeout: 15_000 });
    await page.waitForTimeout(1_200);
    const body = await page.locator('body').innerText();
    if (!body.includes(route.dataText)) failures.push({ route: route.id, issue: 'SIMULATION_DATA_MISSING', detail: route.dataText });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    if (overflow) failures.push({ route: route.id, issue: 'HORIZONTAL_OVERFLOW' });

    let createDialog = false;
    let editDialog = false;
    let deleteDialog = false;
    if (route.create) {
      const createButton = page.getByRole('button', { name: route.create, exact: true }).first();
      await createButton.waitFor({ state: 'visible', timeout: 10_000 });
      await createButton.click();
      const dialog = page.getByRole('dialog').last();
      await dialog.waitFor({ state: 'visible', timeout: 5_000 });
      createDialog = (await dialog.innerText()).includes(route.createTitle);
      if (!createDialog) failures.push({ route: route.id, issue: 'CREATE_DIALOG_TITLE_MISSING', detail: route.createTitle });
      await dialog.getByRole('button', { name: '关闭弹窗' }).click();
      await dialog.waitFor({ state: 'hidden', timeout: 5_000 });
    }

    if (route.editLabel) {
      const editButton = page.getByRole('button', { name: route.editLabel, exact: true }).first();
      await editButton.waitFor({ state: 'visible', timeout: 10_000 });
      await editButton.click();
      const dialog = page.getByRole('dialog').last();
      await dialog.waitFor({ state: 'visible', timeout: 5_000 });
      editDialog = (await dialog.innerText()).includes('编辑');
      if (!editDialog) failures.push({ route: route.id, issue: 'EDIT_DIALOG_TITLE_MISSING' });
      await dialog.getByRole('button', { name: '关闭弹窗' }).click();
      await dialog.waitFor({ state: 'hidden', timeout: 5_000 });
    }

    if (route.deleteLabel) {
      const deleteButton = page.getByRole('button', { name: route.deleteLabel, exact: true }).first();
      await deleteButton.waitFor({ state: 'visible', timeout: 10_000 });
      await deleteButton.click();
      const dialog = page.getByRole('dialog').last();
      await dialog.waitFor({ state: 'visible', timeout: 5_000 });
      const text = await dialog.innerText();
      deleteDialog = text.includes('历史') && (text.includes('停用') || text.includes('归档'));
      if (!deleteDialog) failures.push({ route: route.id, issue: 'DELETE_CONFIRM_IMPACT_MISSING' });
      await dialog.getByRole('button', { name: '取消' }).click();
      await dialog.waitFor({ state: 'hidden', timeout: 5_000 });
    }
    observations.push({ route: route.id, create_dialog: createDialog, edit_dialog: editDialog, delete_dialog: deleteDialog, data_text: route.dataText, overflow });
  }
  routeId = 'final';
  await page.goto(`${baseUrl}#models`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1_000);
  await page.screenshot({ path: resolve(outputDir, 'models-page.png'), fullPage: true });
} finally {
  await browser.close();
}

const result = { routes: routes.length, failures, observations };
await writeFile(resolve(outputDir, 'result.json'), `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify(result));
if (failures.length) process.exitCode = 1;
