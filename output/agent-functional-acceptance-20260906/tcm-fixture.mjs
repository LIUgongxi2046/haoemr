import {readFile,writeFile,appendFile} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';
const base='http://127.0.0.1:8081/api/v1',scope={organization_id:'018f0000-0000-7000-8000-00000000aa02',facility_id:'018f0000-0000-7000-8000-00000000aa03'};
const deployment=JSON.parse(await readFile(new URL('deployment.json',import.meta.url),'utf8'));
let token;
async function api(path,body,lease,patient,method){const r=await fetch(base+path,{method:method ?? (body===undefined?'GET':'POST'),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(lease?{'X-Context-Lease-Id':lease.lease_id,'X-Authorization-Watermark':lease.authorization_watermark,'X-Organization-Context':scope.organization_id,'X-Facility-Context':scope.facility_id,...(patient?{'X-Patient-Context':patient.patient_id,'X-Encounter-Context':patient.encounter_id}:{})}:{}),'Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const b=await r.json().catch(()=>null);if(!r.ok)throw Error(path+' HTTP '+r.status+' '+JSON.stringify(b));return b;}
token=(await api('/session/login',{username:'linwei',password:'HaoEMR-dev!'})).bearer_token;
const run=JSON.parse((await readFile(new URL('runs.jsonl',import.meta.url),'utf8')).trim().split('\n').filter(l=>JSON.parse(l).agent_code==='SYNDROME_DIFFERENTIATION').at(-1)).run;
const patient={patient_id:run.patient_id,encounter_id:run.encounter_id};
const lease=await api('/context-leases',{...scope,...patient,purpose_code:'TCM_RECORD'});
const common={...scope,...patient};
const record=await api('/tcm-records',{...common,syndrome_pattern:'气虚证（纯合成验收记录）',treatment_principle:'仅用于软件验证，核对既有四诊证据及处方信息',formula_name:'合成验收方A，不属于临床可用方剂',contains_toxic_herb:false},lease,patient);
const exam=await api('/tcm-four-examinations',{...common,inspection:'纯合成：面色淡，舌淡、苔薄白。',auscultation:'纯合成：语声低，无异常气味。',inquiry:'纯合成：乏力，食欲减少；无发热。',palpation:'纯合成：脉细弱；未记录其他查体信息。',examined_at:new Date().toISOString()},lease,patient);
const prescription=await api('/tcm-herbal-prescriptions',{...common,formula_name:'合成验收方A，不属于临床可用方剂',herbs:'合成药材A一份、合成药材B一份。处方标签：仅测试读取及复述，不可供真实患者使用；煎服要求缺失，应联系开方人员补充。',contains_toxic_herb:false,prescribed_at:new Date().toISOString()},lease,patient);
await writeFile(new URL('tcm-fixture.json',import.meta.url),JSON.stringify({record,exam,prescription},null,2));console.log('Synthetic TCM records created through domain APIs.');
