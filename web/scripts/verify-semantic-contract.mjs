import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const routes = JSON.parse(await readFile(resolve(projectDir, 'contracts/generated/route-contract.generated.json'), 'utf8')).routes;
const semantics = JSON.parse(await readFile(resolve(projectDir, 'docs/process/testing/route-semantic-contract.json'), 'utf8'));
const failures = [];
if (semantics.route_count !== routes.length || semantics.routes.length !== routes.length) failures.push('SEMANTIC_ROUTE_COUNT');
const sourceIds = new Set(routes.map((route) => route.route_id));
const semanticIds = new Set(semantics.routes.map((route) => route.route_id));
for (const id of sourceIds) if (!semanticIds.has(id)) failures.push(`MISSING_ROUTE:${id}`);
for (const route of semantics.routes) {
  if (!sourceIds.has(route.route_id)) failures.push(`UNKNOWN_ROUTE:${route.route_id}`);
  for (const field of ['title','primary_domain','data_source']) if (!route[field]) failures.push(`EMPTY_${field}:${route.route_id}`);
  for (const field of ['key_regions','primary_actions','required_states','browser_assertions']) if (!Array.isArray(route[field]) || !route[field].length) failures.push(`EMPTY_${field}:${route.route_id}`);
  for (const state of ['loading','empty','error','permission','success']) if (!route.required_states.includes(state)) failures.push(`MISSING_STATE:${route.route_id}:${state}`);
}
for (const id of ['outpatient','agent-compose','agent-context','agent-evals','data-center','research','admin-auth','model-routing','pathology-workbench']) {
  const route = semantics.routes.find((item) => item.route_id === id);
  if (!route || route.critical_text.length < 3) failures.push(`HIGH_RISK_ASSERTIONS_MISSING:${id}`);
}
if (failures.length) { console.error(JSON.stringify({ status: 'FAIL', failures }, null, 2)); process.exit(1); }
console.log(JSON.stringify({ status: 'PASS', routes: `${semantics.routes.length}/${routes.length}`, high_risk: semantics.routes.filter((route) => route.critical_text.length).length }));
