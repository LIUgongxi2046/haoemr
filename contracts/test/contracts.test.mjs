import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const openApiUrl = new URL('../openapi.json', import.meta.url);
const javaUrl = new URL('../../build/generated/contracts/java/org/haoemr/contracts/ContextLeaseWire.java', import.meta.url);
const tsUrl = new URL('../../web/src/generated/contracts.ts', import.meta.url);
const routeTsUrl = new URL('../../web/src/generated/route-contract.ts', import.meta.url);
const generatedUrl = (name) => new URL(`../generated/${name}`, import.meta.url);

test('given the clinical wire contract, every object is closed and uses snake_case fields', async () => {
  const document = JSON.parse(await readFile(openApiUrl, 'utf8'));
  const schemas = Object.values(document.components.schemas);

  assert.ok(schemas.length >= 10);
  for (const schema of schemas.filter((item) => item.type === 'object')) {
    assert.equal(schema.additionalProperties, false);
    for (const property of Object.keys(schema.properties ?? {})) {
      assert.match(property, /^[a-z][a-z0-9_]*$/);
    }
  }
});

test('given one OpenAPI source, generation emits Java and TypeScript contracts', async () => {
  const [javaSource, tsSource, routeTsSource] = await Promise.all([
    readFile(javaUrl, 'utf8'),
    readFile(tsUrl, 'utf8'),
    readFile(routeTsUrl, 'utf8'),
  ]);

  assert.match(javaSource, /record ContextLeaseWire/);
  assert.match(javaSource, /roleAssignmentIds/);
  assert.match(tsSource, /contextLeaseWireSchema/);
  assert.match(tsSource, /decodeContextLease/);
  assert.match(tsSource, /RUN_STATE_CHANGED/);
  assert.match(routeTsSource, /generatedRouteContract/);
});

test('metric snapshots accept the nullable unit and period returned by computed metrics', async () => {
  const [document, tsSource] = await Promise.all([
    readFile(openApiUrl, 'utf8').then(JSON.parse),
    readFile(tsUrl, 'utf8'),
  ]);
  for (const schemaName of ['MetricSnapshot', 'MetricSnapshotRecordRequest']) {
    const schema = document.components.schemas[schemaName];
    assert.deepEqual(schema.properties.unit.type, ['string', 'null']);
    assert.deepEqual(schema.properties.period.type, ['string', 'null']);
  }
  assert.match(tsSource, /metricSnapshotWireSchema[\s\S]*?"unit": z\.string\(\)\.nullable\(\)\.optional\(\)/);
  assert.match(tsSource, /metricSnapshotWireSchema[\s\S]*?"period": z\.string\(\)\.nullable\(\)\.optional\(\)/);
});

test('generated governance artifacts are complete, unique, and mutually referential', async () => {
  const [fieldDictionary, apiIndex, errorCatalog, eventIndex, registry, routeContract] = await Promise.all([
    generatedUrl('physical-field-dictionary.json'),
    generatedUrl('api-index.json'),
    generatedUrl('error-catalog.json'),
    generatedUrl('event-index.json'),
    generatedUrl('agent-tool-registry.json'),
    generatedUrl('route-contract.generated.json'),
  ].map(async (url) => JSON.parse(await readFile(url, 'utf8'))));

  assert.ok(fieldDictionary.field_count >= 500);
  assert.equal(fieldDictionary.field_count, fieldDictionary.fields.length);
  assert.equal(new Set(fieldDictionary.fields.map((field) => `${field.entity}.${field.field}`)).size, fieldDictionary.field_count);
  for (const field of fieldDictionary.fields) {
    for (const property of ['entity', 'field', 'physical_type', 'classification', 'source', 'lineage', 'validation', 'retention']) {
      assert.ok(field[property], `physical field ${field.entity}.${field.field} is missing ${property}`);
    }
  }

  assert.ok(apiIndex.operation_count >= 60);
  assert.equal(apiIndex.operation_count, apiIndex.operations.length);
  assert.equal(new Set(apiIndex.operations.map((operation) => operation.operation_id)).size, apiIndex.operation_count);
  for (const operation of apiIndex.operations) {
    for (const property of ['operation_id', 'caller', 'method', 'path', 'auth', 'input_schema', 'output_schema', 'idempotency', 'timeout_ms', 'rate_limit', 'version', 'status']) {
      assert.notEqual(operation[property], null, `operation ${operation.operation_id} is missing ${property}`);
      assert.notEqual(operation[property], '', `operation ${operation.operation_id} is missing ${property}`);
    }
    assert.ok(operation.error_refs.length > 0);
  }
  assert.equal(apiIndex.operations.filter((operation) => operation.status === 'EXPLICIT_S005').length, 10);

  const errorCodes = new Set(errorCatalog.errors.map((error) => error.code));
  assert.equal(errorCodes.size, errorCatalog.errors.length);
  for (const error of errorCatalog.errors) {
    assert.ok(Number.isInteger(error.http_status));
    assert.equal(typeof error.retryable, 'boolean');
    assert.ok(error.user_message && error.log_level && error.recovery);
  }
  for (const operation of apiIndex.operations) {
    for (const errorCode of operation.error_refs) assert.ok(errorCodes.has(errorCode), `${operation.operation_id} references unknown ${errorCode}`);
  }

  assert.ok(eventIndex.events.length >= 2);
  assert.equal(new Set(eventIndex.events.map((event) => event.event_id)).size, eventIndex.events.length);
  const toolIds = new Set(registry.tools.map((tool) => tool.tool_id));
  assert.ok(registry.agents.length >= 4 && registry.tools.length >= 5);
  for (const agent of registry.agents) {
    for (const toolId of agent.allowed_tools) assert.ok(toolIds.has(toolId), `${agent.agent_id} references unknown ${toolId}`);
  }
  for (const tool of registry.tools) {
    for (const errorCode of tool.error_codes) assert.ok(errorCodes.has(errorCode), `${tool.tool_id} references unknown ${errorCode}`);
  }

  assert.equal(routeContract.route_count, 196);
  assert.equal(routeContract.routes.length, 196);
  assert.equal(new Set(routeContract.routes.map((route) => route.route_id)).size, 196);
  assert.equal(new Set(routeContract.routes.flatMap((route) => route.fr_refs)).size, 136);
  for (const route of routeContract.routes) {
    assert.ok(route.title && route.primary_domain && route.roles.length && route.requirement_refs.length && route.states.length && route.guards.length);
    assert.ok(['CLINICAL', 'RECORD', 'QUALITY', 'COLLABORATION', 'DATA', 'AI', 'CONFIG', 'ADMIN'].includes(route.primary_domain));
    if (route.source_status === 'VERIFIED') {
      assert.match(route.artifact_path, /^docs\/design\/ui-delivery\/screens\/.+\.png$/);
    }
  }
});
