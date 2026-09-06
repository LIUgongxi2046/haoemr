import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const projectDir = resolve(import.meta.dirname, '..');
const dataset = JSON.parse(await readFile(
  resolve(projectDir, 'evals/datasets/medical-agent-composition-v1.json'),
  'utf8',
));
const migration = await readFile(
  resolve(projectDir, 'src/main/resources/db/migration/V169__medical_agent_harness.sql'),
  'utf8',
);
const service = await readFile(
  resolve(projectDir, 'src/main/java/org/haoemr/agent/MedicalAgentHarnessService.java'),
  'utf8',
);

const failures = [];
const mainCodes = new Set(dataset.families?.map((family) => family.main_agent_code));
const children = new Map();
for (const family of dataset.families ?? []) {
  for (const [childCode, stageCode] of family.children ?? []) {
    if (children.has(childCode)) failures.push(`duplicate child agent ${childCode}`);
    children.set(childCode, { parent: family.main_agent_code, stage: stageCode });
  }
}

if (dataset.synthetic !== true) failures.push('composition dataset must be explicitly synthetic');
if (dataset.max_composition_depth !== 1) failures.push('composition depth must be exactly one');
if (mainCodes.size !== 5) failures.push(`expected 5 main agents, found ${mainCodes.size}`);
if (children.size !== 33) failures.push(`expected 33 child agents, found ${children.size}`);
if ((dataset.safety_cases ?? []).length < 3) failures.push('missing medical-agent safety cases');

for (const mainCode of mainCodes) {
  if (!migration.includes(`('${mainCode}','1.0.0'`)) failures.push(`migration misses main agent ${mainCode}`);
  if (!migration.includes(`('${mainCode}_DEFAULT','1.0.0','${mainCode}',1,'ACTIVE')`)) {
    failures.push(`migration misses depth-one composition for ${mainCode}`);
  }
}
for (const [childCode, expected] of children) {
  const releasePattern = new RegExp(
    `\\('${childCode}','1\\.0\\.0','[^']+','CHILD','${expected.parent}','${expected.stage}'`,
  );
  if (!releasePattern.test(migration)) {
    failures.push(`migration mismatches ${childCode} -> ${expected.parent}/${expected.stage}`);
  }
}

for (const required of [
  "output.put(\"candidate_only\", true)",
  "CONTEXT_NOT_PERMITTED",
  "AGENT_STAGE_UNSUPPORTED",
  "authorization_watermark",
  "source_references",
  "MEDICAL_AGENT_RUN_READY",
]) {
  if (!service.includes(required)) failures.push(`runtime safety invariant missing: ${required}`);
}
for (const forbidden of dataset.forbidden_effects ?? []) {
  if (service.includes(`'${forbidden}'`) || service.includes(`\"${forbidden}\"`)) {
    failures.push(`medical harness contains forbidden direct effect ${forbidden}`);
  }
}

if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(JSON.stringify({
  gate: 'PASS',
  dataset_version: dataset.dataset_version,
  main_agents: mainCodes.size,
  child_agents: children.size,
  max_depth: dataset.max_composition_depth,
  safety_cases: dataset.safety_cases.length,
  candidate_only: true,
}));
