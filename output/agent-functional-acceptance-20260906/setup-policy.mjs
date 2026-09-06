import {readFile,writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
const base='http://127.0.0.1:8081/api/v1';
const scope={organization_id:'018f0000-0000-7000-8000-00000000aa02',facility_id:'018f0000-0000-7000-8000-00000000aa03'};
let token,lease;
async function api(path,body,method){const r=await fetch(base+path,{method:method ?? (body===undefined?'GET':'POST'),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(lease?{'X-Context-Lease-Id':lease.lease_id,'X-Authorization-Watermark':lease.authorization_watermark,'X-Organization-Context':scope.organization_id,'X-Facility-Context':scope.facility_id}:{}),'Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const b=await r.json().catch(()=>null);if(!r.ok)throw Error(path+' HTTP '+r.status+' '+JSON.stringify(b));return b;}
async function login(username){lease=null;token=null;token=(await api('/session/login',{username,password:'HaoEMR-dev!'})).bearer_token;lease=await api('/context-leases',{...scope,patient_id:null,encounter_id:null,purpose_code:'AI_PLATFORM_ADMIN'});}
await login('linwei');
let policy=(await api('/configurations?config_type=AI_ASSISTANT_POLICY')).find(c=>c.status==='ACTIVE');
await writeFile(new URL('original-policy.json',import.meta.url),JSON.stringify(policy,null,2));
const original=policy;
policy=await api('/configurations',{config_type:policy.config_type,config_key:'agent-functional-acceptance-20260906',display_name:'纯合成环境医助功能验收策略',payload:{...policy.payload,rate_limit:200}});
for(const action of ['VALIDATE','SUBMIT'])policy=await api(`/configurations/${policy.config_id}/lifecycle`,{action,expected_version:policy.row_version,reason:'独立合成环境逐个验收58个医助所需调用额度，保持正式权限及审批流程'});
await login('guoqiang.xiao');
for(const action of ['APPROVE'])policy=await api(`/configurations/${policy.config_id}/lifecycle`,{action,expected_version:policy.row_version,reason:'独立合成环境逐个验收58个医助所需调用额度，保持正式权限及审批流程'});
await api(`/configurations/${original.config_id}/lifecycle`,{action:'ARCHIVE',expected_version:original.row_version,reason:'独立合成验收环境切换到完整agent功能验收策略'});
policy=await api(`/configurations/${policy.config_id}/lifecycle`,{action:'PUBLISH',expected_version:policy.row_version,reason:'独立合成环境逐个验收58个医助，发布验收策略'});
console.log(JSON.stringify({status:policy.status,rate_limit:policy.payload.rate_limit}));
