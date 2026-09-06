import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
const base = 'http://127.0.0.1:8080/api/v1';
const scope = { organization_id: '018f0000-0000-7000-8000-00000000aa02', facility_id: '018f0000-0000-7000-8000-00000000aa03' };
let token;
async function api(path, body, headers = {}) {
  const r = await fetch(base + path, {method: body === undefined ? 'GET' : 'POST', headers: { ...(token ? {Authorization:`Bearer ${token}`} : {}), 'Content-Type':'application/json', ...headers }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000)});
  return {status:r.status, body:await r.json().catch(()=>null)};
}
const snapshot = JSON.parse(await readFile(new URL('database-snapshot.json', import.meta.url),'utf8'));
const results = [];
try {
  const session = await api('/session/login', {username:'linwei', password:process.env.HAOEMR_AUDIT_DEV_PASSWORD ?? 'HaoEMR-dev!'});
  if(session.status !== 200) throw new Error(`Login HTTP ${session.status}`);
  token = session.body.bearer_token;
  const context = await api('/context-leases', {...scope, patient_id:null, encounter_id:null, purpose_code:'MEDICAL_AGENT_COLLABORATION'});
  if(context.status !== 201) throw new Error(`Context HTTP ${context.status}: ${JSON.stringify(context.body)}`);
  const lease = context.body;
  for (const main of snapshot.agents.filter(a=>a.level==='MAIN')) {
    const child = snapshot.agents.find(a=>a.parent===main.code);
    const response = await api('/medical-agents/runs', {...scope, patient_id:null, encounter_id:null,context_lease_id:lease.lease_id,main_agent_code:main.code,stage_code:child.stage,target_type:null,target_id:null,objective:'合成测试：仅检查当前模型可用性，不读取患者、不执行临床动作。',authorization_level:'READ_ONLY',context_scopes:['RECORDS'],model_deployment_id:null,source_route:null}, {'X-Context-Lease-Id':lease.lease_id,'X-Authorization-Watermark':lease.authorization_watermark,'X-Organization-Context':scope.organization_id,'X-Facility-Context':scope.facility_id,'Idempotency-Key':randomUUID()});
    const result = {agent:main.code,display_name:main.display_name,stage:child.stage,http_status:response.status,body:response.body};
    results.push(result); console.log(JSON.stringify(result));
    if(response.status===202) break; // Unexpected availability: do not fan out live model runs.
  }
} catch(error) { results.push({error:error.message}); console.log(error.message); }
finally { if(token) await api('/session/logout',{}); }
await writeFile(new URL('application-results.json',import.meta.url),JSON.stringify({run_at:new Date().toISOString(),scope:'Local application API, no patient context; no deployment changes',results},null,2));
