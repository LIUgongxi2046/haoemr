import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectDir = resolve(scriptDir, '..');
const apiIndex = JSON.parse(await readFile(resolve(projectDir, 'contracts/generated/api-index.json'), 'utf8'));
const openapi = JSON.parse(await readFile(resolve(projectDir, 'contracts/openapi.json'), 'utf8'));
const staticFailures = [];
const liveFailures = [];
const keys = new Set();
const ids = new Set();

for (const operation of apiIndex.operations) {
  const key = `${operation.method} ${operation.path}`;
  if (keys.has(key)) staticFailures.push({ operation: operation.operation_id, issue: 'DUPLICATE_METHOD_PATH', key });
  if (ids.has(operation.operation_id)) staticFailures.push({ operation: operation.operation_id, issue: 'DUPLICATE_OPERATION_ID' });
  keys.add(key); ids.add(operation.operation_id);
  for (const field of ['caller', 'auth', 'input_schema', 'output_schema', 'idempotency', 'timeout_ms', 'rate_limit', 'version', 'status']) {
    if (operation[field] === undefined || operation[field] === null || operation[field] === '') staticFailures.push({ operation: operation.operation_id, issue: 'MISSING_GOVERNANCE_FIELD', field });
  }
  const source = openapi.paths?.[operation.path]?.[operation.method.toLowerCase()];
  if (!source) staticFailures.push({ operation: operation.operation_id, issue: 'OPENAPI_OPERATION_MISSING', key });
  else if (source.operationId !== operation.operation_id) staticFailures.push({ operation: operation.operation_id, issue: 'OPERATION_ID_DRIFT', actual: source.operationId });
}

const openapiOperations = Object.entries(openapi.paths ?? {}).flatMap(([path, methods]) =>
  Object.entries(methods).filter(([method]) => ['get', 'post', 'put', 'patch', 'delete'].includes(method)).map(([method, operation]) => ({ path, method: method.toUpperCase(), operation_id: operation.operationId })),
);
if (openapiOperations.length !== apiIndex.operations.length) staticFailures.push({ issue: 'OPERATION_COUNT_DRIFT', openapi: openapiOperations.length, api_index: apiIndex.operations.length });

const liveBase = process.env.HAOEMR_API_BASE_URL?.replace(/\/$/, '');
const securityMode = process.env.HAOEMR_API_SECURITY_MODE || 'prod';
const live = [];
const fillPath = (path) => path.replaceAll(/\{[^}]+\}/g, '00000000-0000-7000-8000-000000000001');
if (liveBase) {
  for (const operation of apiIndex.operations) {
    const url = `${liveBase}${fillPath(operation.path)}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch(url, {
        method: operation.method,
        headers: { accept: operation.operation_id.toLowerCase().includes('stream') ? 'text/event-stream' : 'application/json', 'content-type': 'application/json' },
        body: ['POST', 'PUT', 'PATCH'].includes(operation.method) ? '{}' : undefined,
        redirect: 'manual',
        signal: controller.signal,
      });
      const allowed = operation.operation_id === 'getSystemReadiness'
        ? [200, 503]
        : securityMode === 'dev-synthetic' ? [400, 401, 403] : [401, 403];
      live.push({ operation_id: operation.operation_id, status: response.status, pass: allowed.includes(response.status) });
      if (!allowed.includes(response.status)) liveFailures.push({ operation: operation.operation_id, issue: 'UNAUTHORIZED_FAIL_CLOSED_EXPECTATION', expected: allowed, actual: response.status, url });
    } catch (error) {
      liveFailures.push({ operation: operation.operation_id, issue: 'LIVE_REQUEST_FAILED', message: error instanceof Error ? error.message : String(error), url });
    } finally {
      clearTimeout(timer);
    }
  }
}

const result = {
  run_at: new Date().toISOString(),
  contract_operations: apiIndex.operations.length,
  openapi_operations: openapiOperations.length,
  security_mode: securityMode,
  static_verified: apiIndex.operations.length - new Set(staticFailures.filter((item) => item.operation).map((item) => item.operation)).size,
  live_executed: live.length,
  live_passed: live.filter((item) => item.pass).length,
  static_failures: staticFailures,
  live_failures: liveFailures,
  live,
};
const outputDir = resolve(projectDir, 'artifacts/test-runs');
await mkdir(outputDir, { recursive: true });
await writeFile(resolve(outputDir, 'api-surface-audit.json'), `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ contract_operations: result.contract_operations, static_verified: result.static_verified, live_executed: result.live_executed, live_passed: result.live_passed, failures: staticFailures.length + liveFailures.length }));
if (staticFailures.length || liveFailures.length) process.exitCode = 1;
