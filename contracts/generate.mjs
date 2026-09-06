import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const contractsDir = dirname(fileURLToPath(import.meta.url));
const projectDir = resolve(contractsDir, '..');
const openApi = JSON.parse(await readFile(resolve(contractsDir, 'openapi.json'), 'utf8'));
const governance = JSON.parse(await readFile(resolve(contractsDir, 'governance.source.json'), 'utf8'));
const schemas = openApi.components.schemas;
const checkOnly = process.argv.includes('--check');

const stableJson = (value) => `${JSON.stringify(value, null, 2)}\n`;

function parseCsv(source) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index];
    if (quoted) {
      if (character === '"' && source[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (character === '"') {
        quoted = false;
      } else {
        field += character;
      }
    } else if (character === '"') {
      quoted = true;
    } else if (character === ',') {
      row.push(field);
      field = '';
    } else if (character === '\n') {
      row.push(field.replace(/\r$/, ''));
      if (row.some((value) => value !== '')) rows.push(row);
      row = [];
      field = '';
    } else {
      field += character;
    }
  }
  if (field || row.length) {
    row.push(field);
    rows.push(row);
  }
  const [headers, ...records] = rows;
  return records.map((record) => Object.fromEntries(headers.map((header, index) => [header, record[index] ?? ''])));
}

function schemaRef(schema) {
  if (!schema) return null;
  if (schema.$ref) return refName(schema.$ref);
  if (schema.items?.$ref) return `${refName(schema.items.$ref)}[]`;
  return schema.type ?? 'inline';
}

function splitSqlColumns(body) {
  const parts = [];
  let current = '';
  let depth = 0;
  let quoted = false;
  for (const character of body) {
    if (character === "'") quoted = !quoted;
    if (!quoted && character === '(') depth += 1;
    if (!quoted && character === ')') depth -= 1;
    if (!quoted && depth === 0 && character === ',') {
      parts.push(current.trim());
      current = '';
    } else {
      current += character;
    }
  }
  if (current.trim()) parts.push(current.trim());
  return parts;
}

function classifyColumn(name) {
  if (/(ciphertext|password|secret|token|content_text|narrative|sections|payload)/.test(name)) return 'RESTRICTED';
  if (/(patient|encounter|document|observation|diagnosis|order|signature|archive)/.test(name)) return 'SENSITIVE';
  return 'INTERNAL';
}

async function physicalFieldDictionary() {
  const migrationDir = resolve(projectDir, 'src/main/resources/db/migration');
  const files = (await readdir(migrationDir)).filter((name) => /^V\d+__.*\.sql$/.test(name)).sort((a, b) => {
    const version = (name) => Number(name.match(/^V(\d+)/)[1]);
    return version(a) - version(b) || a.localeCompare(b);
  });
  const fields = [];
  for (const file of files) {
    const source = await readFile(resolve(migrationDir, file), 'utf8');
    for (const match of source.matchAll(/create\s+table\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(([\s\S]*?)\n\);/gi)) {
      const [, table, body] = match;
      for (const definition of splitSqlColumns(body)) {
        if (/^(primary|unique|foreign|check|constraint|exclude)\b/i.test(definition)) continue;
        const column = definition.match(/^([a-zA-Z_][a-zA-Z0-9_]*)\s+(.+)$/s);
        if (!column) continue;
        const [, name, rest] = column;
        const type = rest.split(/\s+(?=not\s+null|default|primary\s+key|unique|check\s*\(|references\s+)/i)[0].trim();
        fields.push({
          entity: table,
          field: name,
          physical_type: type,
          nullable: !/\bnot\s+null\b|\bprimary\s+key\b/i.test(rest),
          identifier: /(^|_)id$/.test(name) || /\bprimary\s+key\b/i.test(rest),
          uniqueness: /\bprimary\s+key\b/i.test(rest) ? 'PRIMARY_KEY' : (/\bunique\b/i.test(rest) ? 'UNIQUE' : 'NOT_DECLARED_INLINE'),
          classification: classifyColumn(name),
          source: file,
          lineage: `${file}:${table}.${name}`,
          validation: rest.replace(type, '').trim() || 'TYPE_ONLY',
          retention: classifyColumn(name) === 'RESTRICTED' ? 'DOMAIN_OR_LEGAL_POLICY' : 'ENTITY_LIFECYCLE',
        });
      }
    }
  }
  return {
    schema_version: 1,
    generated_from: files.map((file) => `src/main/resources/db/migration/${file}`),
    status: 'OBSERVED_FROM_MIGRATIONS',
    field_count: fields.length,
    fields,
  };
}

function operationIndex() {
  const operations = [];
  const methods = ['get', 'post', 'put', 'patch', 'delete'];
  for (const [path, pathItem] of Object.entries(openApi.paths)) {
    for (const method of methods) {
      const operation = pathItem[method];
      if (!operation) continue;
      const success = Object.entries(operation.responses ?? {}).find(([status]) => /^2/.test(status));
      const requestSchema = operation.requestBody?.content?.['application/json']?.schema;
      const responseSchema = success?.[1]?.content?.['application/json']?.schema;
      const safeRead = method === 'get';
      const systemEndpoint = path === '/system/readiness';
      const override = governance.operation_overrides?.[operation.operationId] ?? {};
      operations.push({
        operation_id: operation.operationId,
        caller: override.caller ?? (systemEndpoint ? 'ORCHESTRATOR_OR_OPERATOR' : (path.startsWith('/ai/') ? 'WEB_BFF_OR_AI_RUNTIME' : 'AUTHORIZED_WEB_OR_INTEGRATION_CLIENT')),
        method: method.toUpperCase(),
        path,
        auth: override.auth ?? (systemEndpoint ? 'SERVICE_OR_OPERATOR_POLICY' : governance.defaults.auth),
        input_schema: schemaRef(requestSchema) ?? (pathItem.parameters?.length ? 'PATH_PARAMETERS' : 'NONE'),
        output_schema: schemaRef(responseSchema) ?? `HTTP_${success?.[0] ?? 'UNSPECIFIED'}`,
        error_refs: override.error_refs ?? operation['x-error-codes'] ?? ['API_ERROR'],
        idempotency: override.idempotency ?? (safeRead ? governance.defaults.read_idempotency : governance.defaults.write_idempotency),
        timeout_ms: override.timeout_ms ?? (safeRead ? governance.defaults.read_timeout_ms : governance.defaults.write_timeout_ms),
        rate_limit: governance.defaults.rate_limit.replace('<operation_id>', operation.operationId),
        version: openApi.info.version,
        status: override.status ?? operation['x-contract-status'] ?? 'DEFAULTED_POLICY_V1',
      });
    }
  }
  operations.sort((a, b) => a.operation_id.localeCompare(b.operation_id));
  return { schema_version: 1, generated_from: 'contracts/openapi.json + contracts/governance.source.json', operation_count: operations.length, operations };
}

function routePolicy(routeId, notes) {
  if (/^(admin|configuration|workflow|form|rule|permission|role|user|dictionary|master|parameter|notification|job|audit)/.test(routeId)) {
    return { roles: ['ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE'], guards: ['SESSION', 'ROLE', 'SCOPE', 'SEPARATION_OF_DUTIES'], api_refs: ['Admin/Config API'] };
  }
  if (/^(ai-|model-|agent-|skill-|tool-)/.test(routeId)) {
    return { roles: ['AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE'], guards: ['SESSION', 'PURPOSE', 'MODEL_POLICY', 'AI_USE_CASE'], api_refs: ['ContextLease', 'AIRun', 'AIProposal'] };
  }
  if (/^(obgyn|reproductive|pediatrics|neonatal|mental|ophthalmology|ent|dental|dermatology|tcm)-/.test(routeId)) {
    return { roles: ['AUTHORIZED_SPECIALTY_CLINICAL_ROLE'], guards: ['SESSION', 'PATIENT_CONTEXT', 'ENCOUNTER_CONTEXT', 'DEPARTMENT_SUPPORT'], api_refs: ['SpecialtySupport', 'Clinical Core API'] };
  }
  if (/^(integration|lis|pacs|ris|device|message)/.test(routeId)) {
    return { roles: ['AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE'], guards: ['SESSION', 'ROLE', 'SOURCE_STATUS'], api_refs: ['Integration/Result/DICOM API'] };
  }
  const patientRequired = /(record|outpatient|opd|inpatient|ip-|emergency|order|result|diagnosis|nursing|archive|clinical)/.test(routeId);
  return {
    roles: ['AUTHORIZED_ROLE'],
    guards: patientRequired ? ['SESSION', 'PATIENT_CONTEXT', 'ENCOUNTER_OR_PURPOSE'] : ['SESSION', 'ROLE'],
    api_refs: patientRequired ? ['Clinical Core API'] : ['Platform API'],
    policy_evidence: notes,
  };
}

function primaryDomain(routeId, notes) {
  const sourceDomain = notes.split('；')[0];
  if (sourceDomain === '病历与病案') return 'RECORD';
  if (sourceDomain === '诊疗执行') return 'COLLABORATION';
  if (sourceDomain === '核心专科' || sourceDomain === '临床工作域') return 'CLINICAL';
  if (/^(quality|department-qc|infection|credentials)/.test(routeId)) return 'QUALITY';
  if (/^(data|research|cohort|opensource)/.test(routeId)) return 'DATA';
  if (/^(ai|agent|skill|tool|model)/.test(routeId)) return 'AI';
  if (/^(admin)/.test(routeId)) return 'ADMIN';
  return 'CONFIG';
}

async function routeContract() {
  const routeRows = parseCsv(await readFile(resolve(projectDir, 'docs/design/ui-delivery/route-design-map.csv'), 'utf8'));
  const traceRows = parseCsv(await readFile(resolve(projectDir, 'prototype/traceability.csv'), 'utf8'));
  const traceByRoute = new Map();
  for (const row of traceRows) {
    const route = row.route.replace(/^#\/?/, '');
    const current = traceByRoute.get(route) ?? { fr_refs: new Set(), ac_refs: new Set(), scr_refs: new Set() };
    if (row.fr) current.fr_refs.add(row.fr);
    if (row.ac) current.ac_refs.add(row.ac);
    if (row.scr) current.scr_refs.add(row.scr);
    traceByRoute.set(route, current);
  }
  const routes = routeRows.map((row) => {
    const routeId = row.name;
    const trace = traceByRoute.get(routeId) ?? { fr_refs: new Set(), ac_refs: new Set(), scr_refs: new Set() };
    const policy = routePolicy(routeId, row.notes);
    return {
      route_id: routeId,
      path: `#/${routeId}`,
      screen_id: row.source_id,
      title: row.screen_id,
      primary_domain: primaryDomain(routeId, row.notes),
      roles: policy.roles,
      fr_refs: [...trace.fr_refs].sort(),
      ac_refs: [...trace.ac_refs].sort(),
      scr_refs: [...trace.scr_refs].sort(),
      requirement_refs: row.requirement_refs.split(';').filter(Boolean),
      api_refs: policy.api_refs,
      states: row.required_states.split(';').filter(Boolean),
      guards: policy.guards,
      layout: row.notes.split('；')[0] || 'DEFAULT',
      source_status: row.status,
      policy_status: 'INFERRED_BY_ROUTE_POLICY_V1',
      artifact_path: `docs/design/ui-delivery/${row.artifact_path}`,
    };
  });
  routes.sort((a, b) => a.route_id.localeCompare(b.route_id));
  return {
    schema_version: 1,
    generated_from: ['docs/design/ui-delivery/route-design-map.csv', 'prototype/traceability.csv'],
    route_count: routes.length,
    routes,
  };
}

const pascal = (value) => value.split('_').map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join('');
const camel = (value) => {
  const name = pascal(value);
  const acronymNormalized = name.replace(/^[A-Z]+(?=[A-Z][a-z]|$)/, (prefix) => prefix.toLowerCase());
  return acronymNormalized === name
    ? name.charAt(0).toLowerCase() + name.slice(1)
    : acronymNormalized;
};
const refName = (ref) => ref.split('/').at(-1);
const actualType = (schema) => Array.isArray(schema.type) ? schema.type.find((type) => type !== 'null') : schema.type;
const isNullable = (schema) => Array.isArray(schema.type) && schema.type.includes('null');

function javaType(schema, propertyName, nestedEnums) {
  if (schema.$ref) return `${refName(schema.$ref)}Wire`;
  if (schema.enum && schema.enum.every((value) => typeof value === 'string')) {
    const enumName = `${pascal(propertyName)}Value`;
    nestedEnums.set(enumName, schema.enum);
    return enumName;
  }
  const type = actualType(schema);
  if (type === 'string') {
    if (schema.format === 'uuid') return 'UUID';
    if (schema.format === 'date-time') return 'Instant';
    if (schema.format === 'date') return 'LocalDate';
    return 'String';
  }
  if (type === 'integer') return schema.format === 'int64' ? 'Long' : 'Integer';
  if (type === 'number') return 'Double';
  if (type === 'boolean') return 'Boolean';
  if (type === 'array') return `List<${javaType(schema.items, `${propertyName}_item`, nestedEnums)}>`;
  if (type === 'object') return 'Map<String, Object>';
  throw new Error(`Unsupported Java schema for ${propertyName}: ${JSON.stringify(schema)}`);
}

function javaRecord(name, schema) {
  const nestedEnums = new Map();
  const fields = Object.entries(schema.properties ?? {}).map(([wireName, property]) => {
    const type = javaType(property, wireName, nestedEnums);
    return `        @JsonProperty("${wireName}") ${type} ${camel(wireName)}`;
  });
  const enums = [...nestedEnums].map(([enumName, values]) =>
    `    public enum ${enumName} { ${values.join(', ')} }`,
  ).join('\n\n');
  return `// Generated from contracts/openapi.json. Do not edit.\npackage org.haoemr.contracts;\n\nimport com.fasterxml.jackson.annotation.JsonProperty;\nimport java.time.Instant;\nimport java.time.LocalDate;\nimport java.util.List;\nimport java.util.Map;\nimport java.util.UUID;\n\npublic record ${name}Wire(\n${fields.join(',\n')}\n) {\n${enums ? `${enums}\n` : ''}}\n`;
}

function zodExpression(schema) {
  if (schema.$ref) {
    const expression = `${camel(refName(schema.$ref))}WireSchema`;
    return isNullable(schema) ? `${expression}.nullable()` : expression;
  }
  const type = actualType(schema);
  let expression;
  if (schema.enum) {
    const enumValues = schema.enum.filter((value) => value !== null);
    expression = enumValues.length === 1
      ? `z.literal(${JSON.stringify(enumValues[0])})`
      : `z.enum(${JSON.stringify(enumValues)})`;
  } else if (type === 'string') {
    expression = schema.format === 'uuid' ? 'z.string().uuid()' : 'z.string()';
  } else if (type === 'integer') {
    expression = 'z.number().int()';
  } else if (type === 'number') {
    expression = 'z.number()';
  } else if (type === 'boolean') {
    expression = 'z.boolean()';
  } else if (type === 'array') {
    expression = `z.array(${zodExpression(schema.items)})`;
    if (schema.minItems !== undefined) expression += `.min(${schema.minItems})`;
    if (schema.maxItems !== undefined) expression += `.max(${schema.maxItems})`;
  } else if (type === 'object') {
    expression = schema.additionalProperties === true
      ? 'z.record(z.string(), z.unknown())'
      : zodObject(schema);
  } else {
    throw new Error(`Unsupported Zod schema: ${JSON.stringify(schema)}`);
  }
  return isNullable(schema) ? `${expression}.nullable()` : expression;
}

function zodObject(schema) {
  const required = new Set(schema.required ?? []);
  const entries = Object.entries(schema.properties ?? {}).map(([name, property]) => {
    const expression = zodExpression(property);
    return `  ${JSON.stringify(name)}: ${required.has(name) ? expression : `${expression}.optional()`},`;
  });
  return `z.object({\n${entries.join('\n')}\n}).strict()`;
}

const tsSchemas = Object.entries(schemas).map(([name, schema]) => {
  const variable = `${camel(name)}WireSchema`;
  return `export const ${variable} = ${zodObject(schema)};\nexport type ${name}Wire = z.infer<typeof ${variable}>;`;
}).join('\n\n');

const tsSource = `// Generated from contracts/openapi.json. Do not edit.\nimport { z } from 'zod';\n\n${tsSchemas}\n\nexport interface ClinicalContextLease {\n  leaseId: string;\n  tenantId: string;\n  organizationId: string;\n  facilityId: string;\n  userId: string;\n  roleAssignmentIds: string[];\n  patientId: string | null;\n  encounterId: string | null;\n  taskId: string | null;\n  purposeCode: string;\n  allowedSourceTypes: ContextLeaseWire['allowed_source_types'];\n  authorizationWatermark: string;\n  dataClassificationCeiling: ContextLeaseWire['data_classification_ceiling'];\n  modelResidencyPolicy: ContextLeaseWire['model_residency_policy'];\n  expiresAt: string;\n}\n\nexport function decodeContextLease(input: unknown): ClinicalContextLease {\n  const wire = contextLeaseWireSchema.parse(input);\n  return {\n    leaseId: wire.lease_id,\n    tenantId: wire.tenant_id,\n    organizationId: wire.organization_id,\n    facilityId: wire.facility_id,\n    userId: wire.user_id,\n    roleAssignmentIds: wire.role_assignment_ids,\n    patientId: wire.patient_id,\n    encounterId: wire.encounter_id,\n    taskId: wire.task_id,\n    purposeCode: wire.purpose_code,\n    allowedSourceTypes: wire.allowed_source_types,\n    authorizationWatermark: wire.authorization_watermark,\n    dataClassificationCeiling: wire.data_classification_ceiling,\n    modelResidencyPolicy: wire.model_residency_policy,\n    expiresAt: wire.expires_at,\n  };\n}\n`;

const outputs = new Map();
const generatedRouteContract = await routeContract();
for (const [name, schema] of Object.entries(schemas)) {
  outputs.set(
    resolve(projectDir, 'build/generated/contracts/java/org/haoemr/contracts', `${name}Wire.java`),
    javaRecord(name, schema),
  );
}
outputs.set(resolve(projectDir, 'web/src/generated/contracts.ts'), tsSource);
outputs.set(resolve(contractsDir, 'generated/physical-field-dictionary.json'), stableJson(await physicalFieldDictionary()));
outputs.set(resolve(contractsDir, 'generated/api-index.json'), stableJson(operationIndex()));
outputs.set(resolve(contractsDir, 'generated/error-catalog.json'), stableJson({ schema_version: governance.schema_version, generated_from: 'contracts/governance.source.json', errors: governance.errors }));
outputs.set(resolve(contractsDir, 'generated/event-index.json'), stableJson({ schema_version: governance.schema_version, generated_from: 'contracts/governance.source.json', events: governance.events }));
outputs.set(resolve(contractsDir, 'generated/agent-tool-registry.json'), stableJson({ schema_version: governance.schema_version, generated_from: 'contracts/governance.source.json', status: governance.status, agents: governance.agents, tools: governance.tools }));
outputs.set(resolve(contractsDir, 'generated/route-contract.generated.json'), stableJson(generatedRouteContract));
outputs.set(
  resolve(projectDir, 'web/src/generated/route-contract.ts'),
  `// Generated from docs/design/ui-delivery/route-design-map.csv and prototype/traceability.csv. Do not edit.\nexport const generatedRouteContract = ${JSON.stringify(generatedRouteContract, null, 2)} as const;\n`,
);

for (const [path, content] of outputs) {
  if (checkOnly) {
    const current = await readFile(path, 'utf8').catch(() => null);
    if (current !== content) {
      console.error(`Generated contract is stale: ${path}`);
      process.exitCode = 1;
    }
  } else {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, content, 'utf8');
  }
}

if (!process.exitCode) {
  console.log(JSON.stringify({ schemas: Object.keys(schemas).length, outputs: outputs.size, mode: checkOnly ? 'check' : 'write' }));
}
