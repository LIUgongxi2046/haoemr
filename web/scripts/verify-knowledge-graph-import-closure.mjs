import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/knowledge-graph-import-closure');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const username = process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei';
const password = process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!';
const system = `E2E_GRAPH_${Date.now()}`;
const result = { run_at: new Date().toISOString(), system, checks: [], findings: [] };

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.HAOEMR_BROWSER_EXECUTABLE_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => result.findings.push({ name: 'pageerror', detail: error.message, url: page.url() }));

async function check(name, action) {
  try { result.checks.push({ name, status: 'PASS', detail: await action() }); }
  catch (error) { result.findings.push({ name, detail: error instanceof Error ? error.message : String(error), url: page.url() }); }
}

async function openImport() {
  await page.getByRole('button', { name: /受控导入/ }).click();
  const panel = page.locator('[aria-label="受控图谱导入"]');
  await panel.waitFor({ state: 'visible', timeout: 30_000 });
  await panel.getByLabel('图谱导入来源', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
  return panel;
}

try {
  await check('registered-source-imports-reviewed-dictionary-concepts-and-relation', async () => {
    await page.goto(`${baseUrl}/#/login`, { waitUntil: 'domcontentloaded' });
    const loginButton = page.getByRole('button', { name: '登录系统', exact: true });
    await loginButton.waitFor({ state: 'visible', timeout: 30_000 });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.locator('#system-login-password').fill(password);
    await loginButton.click();
    await page.waitForFunction(() => Boolean(sessionStorage.getItem('haoemr.clinical-session')), undefined, { timeout: 30_000 });
    await page.goto(`${baseUrl}/#/pathway-graph`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '知识图谱', exact: true }).waitFor({ timeout: 30_000 });
    const panel = await openImport();
    await panel.getByLabel('词典概念 JSON', { exact: true }).fill(JSON.stringify([
      { system, code: 'A', display: '图谱闭环概念 A' },
      { system, code: 'B', display: '图谱闭环概念 B' },
    ]));
    await panel.getByLabel('关系 JSON', { exact: true }).fill(JSON.stringify([
      { from_system: system, from_code: 'A', to_system: system, to_code: 'B', relation_type: 'SUPPORTED_BY', version: 'E2E' },
    ]));
    await panel.getByRole('button', { name: '导入审核词典概念', exact: true }).click();
    await page.getByText('图谱导入已完成：新增 2 个词典概念、1 条关系', { exact: false }).waitFor({ timeout: 30_000 });
    return '已从已登记来源导入 2 个 DICTIONARY 概念与 1 条关系，并回显服务端批次结果';
  });

  await check('same-semantic-import-is-idempotent-and-extracted-content-is-refused-client-side', async () => {
    const panel = await openImport();
    await panel.getByLabel('词典概念 JSON', { exact: true }).fill(JSON.stringify([
      { system, code: 'A', display: '图谱闭环概念 A' },
      { system, code: 'B', display: '图谱闭环概念 B' },
    ]));
    await panel.getByLabel('关系 JSON', { exact: true }).fill(JSON.stringify([
      { from_system: system, from_code: 'A', to_system: system, to_code: 'B', relation_type: 'SUPPORTED_BY', version: 'E2E' },
    ]));
    await panel.getByRole('button', { name: '导入审核词典概念', exact: true }).click();
    await page.getByText('图谱导入已完成：新增 0 个词典概念、0 条关系', { exact: false }).waitFor({ timeout: 30_000 });
    const blocked = await openImport();
    await blocked.getByLabel('词典概念 JSON', { exact: true }).fill(JSON.stringify([
      { system, code: 'C', display: '未经复核抽取内容', source_type: 'EXTRACTED' },
    ]));
    await blocked.getByRole('button', { name: '导入审核词典概念', exact: true }).click();
    await page.getByText('仅允许已审核的 DICTIONARY 词典概念导入', { exact: false }).waitFor({ timeout: 30_000 });
    return '相同语义重复导入新增数为 0；EXTRACTED 内容被前端阻断，未作为图谱事实写入';
  });
} finally {
  await page.screenshot({ path: resolve(outputDir, 'final-state.png'), fullPage: true }).catch(() => {});
  await context.close();
  await browser.close();
}

const artifact = resolve(outputDir, 'knowledge-graph-import-closure.json');
await writeFile(artifact, `${JSON.stringify({ ...result, passed: result.checks.length, failed: result.findings.length }, null, 2)}\n`);
console.log(JSON.stringify({ ...result, artifact }, null, 2));
if (result.findings.length) process.exitCode = 1;
