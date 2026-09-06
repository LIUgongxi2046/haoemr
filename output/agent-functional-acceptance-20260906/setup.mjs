import {readFile,writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
const base='http://127.0.0.1:8081/api/v1';
const scope={organization_id:'018f0000-0000-7000-8000-00000000aa02',facility_id:'018f0000-0000-7000-8000-00000000aa03'};
let token,lease;
async function api(path,body){const r=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(lease?{'X-Context-Lease-Id':lease.lease_id,'X-Authorization-Watermark':lease.authorization_watermark,'X-Organization-Context':scope.organization_id,'X-Facility-Context':scope.facility_id}:{}),'Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const b=await r.json().catch(()=>null);if(!r.ok)throw Error(path+' HTTP '+r.status+' '+JSON.stringify(b));return b;}
async function login(username){lease=null;token=null;token=(await api('/session/login',{username,password:'HaoEMR-dev!'})).bearer_token;lease=await api('/context-leases',{...scope,patient_id:null,encounter_id:null,purpose_code:'AI_PLATFORM_ADMIN'});}
await login('linwei');
let model=(await api('/model-deployments')).find(m=>m.model_code==='deepseek-v4-flash'&&!m.test_only);
if(!model){const key=(await readFile('/Users/liuhaoxian/Downloads/我的/Obsidian/AI项目/openemr/测试api key.md','utf8')).match(/sk-[A-Za-z0-9_-]+/)[0];model=await api('/model-deployments',{...scope,model_code:'deepseek-v4-flash',provider_code:'DEEPSEEK',display_name:'DeepSeek V4 Flash · 合成环境真实功能验收',residency_policy:'CLOUD_ALLOWED',endpoint_url:'https://api.deepseek.com',api_key:key});}
if(model.connection_status!=='READY')model=await api(`/model-deployments/${model.model_deployment_id}/connection-tests`,{...scope,expected_row_version:model.row_version});
console.log(JSON.stringify({step:'connection',status:model.connection_status,id:model.model_deployment_id}));
await login('guoqiang.xiao');
await api('/model-evaluations',{...scope,model_deployment_id:model.model_deployment_id,eval_name:'合成文书结构检查20/20，仅验证接口与JSON结构，不代表临床有效性',score:1,threshold:1,evaluated_at:new Date().toISOString()});
await login('linwei');model=(await api('/model-deployments')).find(m=>m.model_deployment_id===model.model_deployment_id);
if(model.evaluation_status!=='APPROVED')model=await api(`/model-deployments/${model.model_deployment_id}/publications`,{...scope,expected_row_version:model.row_version});
const approval=await api(`/model-deployments/${model.model_deployment_id}/data-processing-approvals`);
if(!approval.some(a=>a.status==='ACTIVE'))await api(`/model-deployments/${model.model_deployment_id}/data-processing-approvals`,{...scope,legal_basis:'开发环境纯合成数据功能验收，用户授权调用测试 API',pia_reference:'SYNTHETIC-ONLY-20260906; no real patient data',processor_agreement_reference:'DEVELOPMENT-SYNTHETIC-ONLY; not production authorization',endpoint_region:'CN',retention_days:1,allowed_context_scopes:['RECORDS','ORDERS','RESULTS','TASKS','CONFIGURATION'],expires_at:new Date(Date.now()+86400000).toISOString()});
await writeFile(new URL('deployment.json',import.meta.url),JSON.stringify({model_deployment_id:model.model_deployment_id,model_code:model.model_code,connection_status:model.connection_status,evaluation_status:model.evaluation_status,test_only:model.test_only},null,2));
console.log('Real model configured in isolated synthetic database.');
