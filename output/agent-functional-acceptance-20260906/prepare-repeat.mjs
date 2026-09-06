import {readFile,writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
const base='http://127.0.0.1:8081/api/v1';
const scope={organization_id:'018f0000-0000-7000-8000-00000000aa02',facility_id:'018f0000-0000-7000-8000-00000000aa03'};
let token,lease;
async function api(path,body,method){const r=await fetch(base+path,{method:method ?? (body===undefined?'GET':'POST'),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(lease?{'X-Context-Lease-Id':lease.lease_id,'X-Authorization-Watermark':lease.authorization_watermark,'X-Organization-Context':scope.organization_id,'X-Facility-Context':scope.facility_id}:{}),'Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const b=await r.json().catch(()=>null);if(!r.ok)throw Error(path+' HTTP '+r.status+' '+JSON.stringify(b));return b;}
async function login(username){lease=null;token=null;token=(await api('/session/login',{username,password:'HaoEMR-dev!'})).bearer_token;lease=await api('/context-leases',{...scope,patient_id:null,encounter_id:null,purpose_code:'AI_PLATFORM_ADMIN'});}
await login('linwei');
const policies=await api('/configurations?config_type=AI_ASSISTANT_POLICY');
if(!policies.some(p=>p.config_key==='agent-functional-acceptance-20260906'&&p.status==='ACTIVE'))throw Error('Acceptance policy is not active');
for(const policy of policies.filter(p=>p.status==='ACTIVE'&&p.config_key!=='agent-functional-acceptance-20260906'))await api(`/configurations/${policy.config_id}/lifecycle`,{action:'ARCHIVE',expected_version:policy.row_version,reason:'合成环境使用已发布验收策略；初始化覆盖问题已修复于源码'});
console.log('Acceptance policy ready.');
