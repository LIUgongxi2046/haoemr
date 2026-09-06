import { access, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const projectDir = resolve(import.meta.dirname, '..');
const requiredFiles = [
  'evals/deepseek/harness.config.json',
  'evals/deepseek/run-harness.mjs',
  'evals/deepseek/provider-contract.json',
];
const failures = [];
for (const file of requiredFiles) {
  try { await access(resolve(projectDir, file)); } catch { failures.push({ issue: 'REQUIRED_HARNESS_FILE_MISSING', file }); }
}

let config;
let contract;
try { config = JSON.parse(await readFile(resolve(projectDir, requiredFiles[0]), 'utf8')); } catch { failures.push({ issue: 'HARNESS_CONFIG_INVALID' }); }
try { contract = JSON.parse(await readFile(resolve(projectDir, requiredFiles[2]), 'utf8')); } catch { failures.push({ issue: 'PROVIDER_CONTRACT_INVALID' }); }

const requiredConfigFields = ['execution_mode', 'provisioned', 'seed', 'repeats', 'max_cases', 'dataset_version', 'threshold_source', 'thresholds'];
for (const field of requiredConfigFields) if (!(field in (config ?? {}))) failures.push({ issue: 'HARNESS_CONFIG_FIELD_MISSING', field });
if (config?.execution_mode === 'MANAGED_REMOTE') {
  for (const field of ['endpoint_env', 'model_env', 'api_key_ref_env']) if (!config[field]) failures.push({ issue: 'MANAGED_REMOTE_FIELD_MISSING', field });
} else if (config?.execution_mode === 'LOCAL_ARTIFACT') {
  for (const field of ['model_artifact', 'artifact_sha256', 'quantization', 'inference_engine', 'engine_version', 'hardware']) if (!config[field]) failures.push({ issue: 'LOCAL_ARTIFACT_FIELD_MISSING', field });
} else failures.push({ issue: 'HARNESS_EXECUTION_MODE_INVALID' });

for (const metric of ['schema_validity', 'citation_accuracy', 'clinical_factuality', 'cross_patient_leakage', 'unauthorized_side_effects', 'p95_latency_ms', 'timeout_recovery', 'cancellation_recovery']) {
  if (config?.thresholds?.[metric] === undefined || config.thresholds[metric] === null) failures.push({ issue: 'HARNESS_THRESHOLD_MISSING', metric });
}
const requiredOutputFields = contract?.response?.content_schema?.required ?? [];
for (const field of ['summary', 'facts', 'gaps', 'warnings']) if (!requiredOutputFields.includes(field)) failures.push({ issue: 'PROVIDER_OUTPUT_CONTRACT_STALE', field });

const providerSource = (await Promise.all([
  'src/main/java/org/haoemr/agent/MedicalAgentModelGateway.java',
  'src/main/java/org/haoemr/agent/DeepSeekHttpChatTransport.java',
].map((file) => readFile(resolve(projectDir, file), 'utf8')))).join('\n');
if (!/DeepSeek/i.test(providerSource)) failures.push({ issue: 'DEEPSEEK_PROVIDER_ADAPTER_MISSING' });

const blockers = [];
if (config?.provisioned !== true && process.env.HAOEMR_EVAL_GOVERNANCE_APPROVED !== 'true') {
  blockers.push({ issue: 'HARNESS_GOVERNANCE_APPROVAL_REQUIRED' });
}
if (config?.execution_mode === 'MANAGED_REMOTE') {
  for (const field of ['endpoint_env', 'model_env', 'api_key_ref_env']) {
    if (!process.env[config[field]]) blockers.push({ issue: 'MANAGED_REMOTE_ENV_NOT_SET', env: config[field] });
  }
}
const gate = failures.length ? 'FAIL' : blockers.length ? 'INTEGRATED_READY_NOT_EXECUTED' : 'READY_TO_EXECUTE';
console.log(JSON.stringify({ gate, execution_mode: config?.execution_mode, failures, blockers }, null, 2));
if (failures.length) process.exitCode = 1;
