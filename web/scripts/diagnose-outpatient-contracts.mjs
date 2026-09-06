import { chromium } from 'playwright';

const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const orderResponses = [];
page.on('response', async (response) => {
  if (response.url().includes('/api/v1/orders?')) {
    orderResponses.push(await response.json().catch(() => null));
  }
});

try {
  await page.goto(`${baseUrl}/#/outpatient`, { waitUntil: 'domcontentloaded' });
  const login = page.getByRole('button', { name: '登录系统' });
  if (await login.isVisible().catch(() => false)) {
    await page.getByLabel('用户名').fill(process.env.HAOEMR_DEV_LOGIN_USERNAME || 'linwei');
    await page.locator('#system-login-password').fill(process.env.HAOEMR_DEV_LOGIN_PASSWORD || 'HaoEMR-dev!');
    await login.click();
  }
  await page.waitForFunction(() => document.documentElement.dataset.routeId === 'outpatient');
  const diagnostics = await page.evaluate(async () => {
    const api = await import('/src/clinical-api.ts');
    const followup = await import('/src/api/outpatient-followup.ts');
    const workspace = await import('/src/api/outpatient-workspace.ts');
    const serialize = (error) => ({
      name: error?.name,
      message: error?.message,
      issues: error?.issues,
      stack: error?.stack?.split('\n').slice(0, 4),
    });
    const run = async (action) => {
      try { return { ok: true, count: (await action())?.length }; }
      catch (error) { return { ok: false, error: serialize(error) }; }
    };
    return {
      context: { ...api.clinicalContext },
      workspace: await run(() => workspace.loadOutpatientWorkspaceSnapshot(api.clinicalContext.patientId, api.clinicalContext.encounterId)),
      orders: await run(async () => api.listClinicalOrders(await api.issueOrderLease('outpatient'), 'outpatient')),
      results: await run(async () => api.listClinicalResults(await api.issueResultLease('outpatient'), 'outpatient')),
      followups: await run(async () => followup.listOutpatientFollowups(await followup.issueFollowupPatientLease())),
    };
  });
  const invalidUuid = (value) => typeof value === 'string' && !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
  const rawInvalidOrders = orderResponses.flatMap((items) => Array.isArray(items) ? items : [])
    .flatMap((order, index) => [
      ...(invalidUuid(order.order_id) ? [{ index, field: 'order_id', value: order.order_id }] : []),
      ...(order.items || []).flatMap((item, itemIndex) => invalidUuid(item.order_item_id)
        ? [{ index, itemIndex, field: 'order_item_id', value: item.order_item_id, order_id: order.order_id }] : []),
    ]);
  console.log(JSON.stringify({ ...diagnostics, rawInvalidOrders }, null, 2));
} finally {
  await browser.close();
}
