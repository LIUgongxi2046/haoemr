import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const outputDir = resolve(projectDir, 'output/playwright/system-administration/prototype');
const baseUrl = (process.env.HAOEMR_PROTOTYPE_BASE_URL || 'http://127.0.0.1:4180').replace(/\/$/, '');
const routes = [
  'admin', 'admin-org', 'admin-users', 'admin-roles', 'admin-permissions', 'admin-auth',
  'admin-dictionaries', 'admin-master-data', 'admin-templates', 'admin-parameters', 'admin-jobs', 'admin-audit',
];

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  for (const route of routes) {
    await page.goto(`${baseUrl}/app/index.html#${route}`, { waitUntil: 'domcontentloaded' });
    await page.locator('h1').first().waitFor({ timeout: 20_000 });
    await page.screenshot({ path: resolve(outputDir, `${route}-1440x1000.png`), fullPage: true });
  }
  console.log(JSON.stringify({ captured: routes.length, output_dir: outputDir }));
} finally {
  await browser.close();
}
