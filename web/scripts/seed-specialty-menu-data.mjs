import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const baseUrl = (process.env.HAOEMR_BROWSER_BASE_URL || 'http://127.0.0.1:4177').replace(/\/$/, '');
const allRoutes = [
  'obgyn-record', 'reproductive-record', 'pediatrics-record', 'neonatal-record', 'mental-record',
  'ophthalmology-record', 'ent-record', 'dental-record', 'dermatology-record', 'tcm-record',
  'obgyn-treatment', 'obgyn-evidence', 'obgyn-followup', 'obgyn-qc', 'obgyn-care',
  'reproductive-treatment', 'reproductive-followup', 'reproductive-qc', 'reproductive-care', 'reproductive-evidence',
  'pediatrics-care', 'pediatrics-followup', 'pediatrics-qc', 'pediatrics-evidence', 'pediatrics-treatment',
  'neonatal-care', 'neonatal-evidence', 'neonatal-qc', 'neonatal-followup', 'neonatal-treatment',
  'mental-care', 'mental-followup', 'mental-qc', 'mental-evidence', 'mental-treatment',
  'ophthalmology-treatment', 'ophthalmology-followup', 'ophthalmology-qc', 'ophthalmology-care', 'ophthalmology-evidence',
  'ent-care', 'ent-qc', 'ent-followup', 'ent-evidence', 'ent-treatment',
  'dental-treatment', 'dental-qc', 'dental-followup', 'dental-care', 'dental-evidence',
  'dermatology-treatment', 'dermatology-followup', 'dermatology-qc', 'dermatology-care', 'dermatology-evidence',
  'tcm-treatment', 'tcm-evidence', 'tcm-qc', 'tcm-followup', 'tcm-care',
];
const requestedRoutes = new Set((process.env.HAOEMR_SPECIALTY_ROUTES || '').split(',').filter(Boolean));
const routes = requestedRoutes.size ? allRoutes.filter((route) => requestedRoutes.has(route)) : allRoutes;

const clinicalText = {
  assessment: '合成复杂病例评估：存在多系统合并风险，生命体征目前稳定，需持续复核检查结果与治疗反应。',
  intervention: '已完成身份核验、分级评估、用药与过敏史复核，执行专科处置并安排复诊、质控和异常结果追踪。',
  note: '合成验收数据：记录关键病情、风险因素、已执行措施、疗效观察和下一步闭环计划。',
};
const syntheticReferences = {
  cycleId: '',
  patientId: '018f0000-0000-7000-8000-000000000001',
  relatedPatientId: '018f0000-0000-7000-8000-000000000006',
  userId: '018f0000-0000-7000-8000-00000000aa06',
  documentId: '018f0000-0000-7000-8000-000000001001',
};

function textFor(label, index) {
  const value = label.toLowerCase();
  if (value.includes('周期id')) return syntheticReferences.cycleId || syntheticReferences.documentId;
  if (value.includes('母亲') && value.includes('id')) return syntheticReferences.relatedPatientId;
  if (/提供者id|核对者id|见证者id/.test(value)) return syntheticReferences.userId;
  if (/复核记录id|记录id/.test(value)) return syntheticReferences.documentId;
  if (value.includes('牙位') || value.includes('tooth')) return '36';
  if (value.includes('腕带') || value.includes('barcode')) return `WB-SYN-${20260825 + index}`;
  if (value.includes('材料批次')) return 'DENT-RESIN-2026-08-A17';
  if (value.includes('处方') || value.includes('方剂')) return index ? '黄芪15g、白术10g、防风6g，水煎服' : '桂枝9g、白芍9g、生姜6g、大枣4枚，水煎服';
  if (value.includes('诊断') || value.includes('结论')) return '高风险专科病例，主要诊断明确，合并症与鉴别诊断已记录';
  if (value.includes('评估') || value.includes('风险') || value.includes('症状')) return clinicalText.assessment;
  if (value.includes('干预') || value.includes('治疗') || value.includes('计划') || value.includes('措施')) return clinicalText.intervention;
  if (value.includes('编号') || value.includes('代码') || value.includes('批号')) return `SYN-${String(index + 1).padStart(3, '0')}`;
  if (value.includes('姓名')) return index ? '周婉宁' : '张慧敏';
  return clinicalText.note;
}

function numberFor(label, minimum, maximum) {
  if (/孕周|胎龄/.test(label)) return 38;
  if (/apgar 1/.test(label.toLowerCase())) return 8;
  if (/apgar 5/.test(label.toLowerCase())) return 9;
  if (/出生体重/.test(label)) return 3200;
  if (/体重/.test(label)) return 24.6;
  if (/身高/.test(label)) return 128;
  if (/头围/.test(label)) return 52;
  if (/bsa/.test(label.toLowerCase())) return 18;
  if (/pasi/.test(label.toLowerCase())) return 12;
  if (/失血量/.test(label)) return 450;
  if (/产程/.test(label)) return 360;
  if (/胎心/.test(label)) return 148;
  if (/收缩压/.test(label)) return 168;
  if (/舒张压/.test(label)) return 108;
  if (/眼压/.test(label)) return 18;
  if (/活产|枚数|序号|孕次|产次|月龄/.test(label)) return 1;
  const min = Number(minimum);
  const max = Number(maximum);
  return Number.isFinite(min) && Number.isFinite(max) ? (min + max) / 2 : Number.isFinite(min) ? Math.max(min, 1) : 1;
}

function localDateTimeFor(label) {
  const offset = /预产期/.test(label) ? 30 * 86_400_000 : /移植时间/.test(label) ? 86_400_000 : -60 * 60_000;
  return new Date(Date.now() + offset).toISOString().slice(0, 16);
}

function dateFor(label) {
  if (/预约|预产/.test(label)) return new Date(Date.now() + 7 * 86_400_000).toISOString().slice(0, 10);
  if (/结局/.test(label)) return new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
  return new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
}

async function hydrateArtCycleReference(page) {
  if (syntheticReferences.cycleId) return;
  syntheticReferences.cycleId = await page.evaluate(async () => {
    const api = await import('/src/api/specialty.ts');
    const lease = await api.issueSpecialtyPatientLease('ART_CYCLE_RECORD');
    const records = await api.listArtCycleRecords(lease);
    return records[0]?.art_cycle_record_id ?? '';
  }).catch(() => '');
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const results = [];

try {
  for (const [routeIndex, route] of routes.entries()) {
    if (routeIndex === 0) await page.goto(`${baseUrl}/#/${route}`, { waitUntil: 'domcontentloaded', timeout: 25_000 });
    else await page.evaluate((nextRoute) => { window.location.hash = `#/${nextRoute}`; }, route);
    await page.waitForFunction((id) => document.documentElement.dataset.routeId === id, route, { timeout: 8_000 }).catch(() => {});
    await page.locator('main form:has(button), main .state-page.error').first().waitFor({ state: 'visible', timeout: 20_000 }).catch(() => {});
    if (route === 'reproductive-record' || route === 'reproductive-treatment' || route === 'reproductive-followup') {
      await hydrateArtCycleReference(page);
    }
    const before = await page.locator('main tbody tr').count();
    if (before > 0) {
      results.push({ route, status: 'EXISTING', rows: before });
      console.log(`[${routeIndex + 1}/${routes.length}] ${route}: EXISTING (${before})`);
      continue;
    }
    const form = page.locator('main form:has(button)').first();
    if (!await form.count() || !await form.isVisible()) {
      const body = await page.locator('main').innerText().catch(() => '');
      results.push({ route, status: 'NO_VISIBLE_CREATE_FORM', rows: before, detail: body.replace(/\s+/g, ' ').slice(0, 360) });
      console.log(`[${routeIndex + 1}/${routes.length}] ${route}: NO_VISIBLE_CREATE_FORM`);
      continue;
    }
    const controls = form.locator('input:not([type="hidden"]), textarea, select');
    for (let controlIndex = 0; controlIndex < await controls.count(); controlIndex += 1) {
      const control = controls.nth(controlIndex);
      if (!await control.isVisible() || await control.isDisabled()) continue;
      const meta = await control.evaluate((element) => {
        const label = element.closest('label')?.textContent?.replace(/\s+/g, ' ').trim() ?? element.getAttribute('aria-label') ?? '';
        return { tag: element.tagName, type: element.getAttribute('type') ?? '', label, value: element.value, min: element.getAttribute('min'), max: element.getAttribute('max'), required: element.required, placeholder: element.getAttribute('placeholder') ?? '' };
      });
      if (meta.tag === 'SELECT') {
        if (/蛋白尿/.test(meta.label)) {
          await control.selectOption('POSITIVE');
          continue;
        }
        if (!meta.value) {
          const options = await control.locator('option:not([disabled])').evaluateAll((items) => items.map((item) => item.value).filter(Boolean));
          if (options[0]) await control.selectOption(options[0]);
        }
        continue;
      }
      if (meta.type === 'checkbox') {
        if (/风险|异常|高危|复核|到访/.test(meta.label)) await control.check();
        continue;
      }
      if (meta.type === 'number') {
        const number = numberFor(meta.label, meta.min, meta.max);
        await control.fill(String(Number.isInteger(number) ? number : number.toFixed(1)));
        continue;
      }
      if (meta.value) continue;
      if (!meta.required && /可选/.test(`${meta.label} ${meta.placeholder}`) && !/材料批次/.test(meta.label)) continue;
      if (meta.type === 'datetime-local') await control.fill(localDateTimeFor(meta.label));
      else if (meta.type === 'date') await control.fill(dateFor(meta.label));
      else if (meta.type === 'time') await control.fill('09:30');
      else await control.fill(textFor(meta.label, controlIndex));
    }
    const submit = form.locator('button:not([type="button"])').last();
    if (await submit.isDisabled()) {
      results.push({ route, status: 'FORM_NOT_READY', rows: before });
      console.log(`[${routeIndex + 1}/${routes.length}] ${route}: FORM_NOT_READY`);
      continue;
    }
    const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
      && response.url().includes('/api/v1/'), { timeout: 20_000 }).catch(() => null);
    await submit.click();
    const response = await responsePromise;
    if (route === 'reproductive-record' && response?.ok()) {
      const payload = await response.json().catch(() => null);
      syntheticReferences.cycleId = payload?.art_cycle_record_id ?? syntheticReferences.cycleId;
    }
    await page.waitForTimeout(800);
    const after = await page.locator('main tbody tr').count();
    const notice = await page.locator('main [role="status"]').allTextContents();
    const status = after > before ? 'CREATED' : 'SUBMITTED_NO_ROW';
    const responseDetail = response ? await response.text().catch(() => '') : '';
    results.push({ route, status, rows: after, httpStatus: response?.status() ?? null, request: response?.request().postData()?.slice(0, 800), response: responseDetail.slice(0, 800), notice: notice.join(' ').slice(0, 240) });
    console.log(`[${routeIndex + 1}/${routes.length}] ${route}: ${status} (${after}) ${notice.join(' ').replace(/\s+/g, ' ').slice(0, 180)}`);
  }
} finally {
  await browser.close();
}

await mkdir(resolve(projectDir, 'output/playwright'), { recursive: true });
const outputPath = resolve(projectDir, 'output/playwright/specialty-menu-data-seed.json');
await writeFile(outputPath, `${JSON.stringify({ run_at: new Date().toISOString(), routes: routes.length, results }, null, 2)}\n`);
const failed = results.filter((item) => !['EXISTING', 'CREATED'].includes(item.status));
console.log(JSON.stringify({ routes: routes.length, existing: results.filter((item) => item.status === 'EXISTING').length, created: results.filter((item) => item.status === 'CREATED').length, needs_attention: failed.length, artifact: outputPath }));
if (failed.length) process.exitCode = 1;
