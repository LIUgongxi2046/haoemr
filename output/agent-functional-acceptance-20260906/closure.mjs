import {readFile,writeFile,appendFile} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';
const base='http://127.0.0.1:8081/api/v1',scope={organization_id:'018f0000-0000-7000-8000-00000000aa02',facility_id:'018f0000-0000-7000-8000-00000000aa03'};
const deployment=JSON.parse(await readFile(new URL('deployment.json',import.meta.url),'utf8'));
let token;
async function api(path,body,lease,patient,method){const r=await fetch(base+path,{method:method ?? (body===undefined?'GET':'POST'),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(lease?{'X-Context-Lease-Id':lease.lease_id,'X-Authorization-Watermark':lease.authorization_watermark,'X-Organization-Context':scope.organization_id,'X-Facility-Context':scope.facility_id,...(patient?{'X-Patient-Context':patient.patient_id,'X-Encounter-Context':patient.encounter_id}:{})}:{}),'Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const b=await r.json().catch(()=>null);if(!r.ok)throw Error(path+' HTTP '+r.status+' '+JSON.stringify(b));return b;}
token=(await api('/session/login',{username:'linwei',password:'HaoEMR-dev!'})).bearer_token;
const records=(await readFile(new URL('runs.jsonl',import.meta.url),'utf8')).trim().split('\n').map(JSON.parse);
const source=records.findLast(r=>r.handoff?.task_id && !r.handoff.error);
const patient={patient_id:source.run.patient_id,encounter_id:source.run.encounter_id};
const lease=await api('/context-leases',{...scope,...patient,purpose_code:'REMINDER_DETAIL'});
const checks=[];
let detail=await api(`/clinical-tasks/${source.handoff.task_id}`,undefined,lease,patient);
const claimed=await api(`/clinical-tasks/${source.handoff.task_id}/claims`,{...scope,...patient,expected_row_version:detail.task.row_version},lease,patient);
checks.push({name:'candidate_task_claim',state:claimed.state,task_id:claimed.task_id});
const reminders=await api(`/clinical-reminders?encounter_id=${patient.encounter_id}`,undefined,lease,patient);
const reminder=reminders.find(r=>r.reminder_id===source.handoff.reminder_id);
await api(`/clinical-reminders/${reminder.reminder_id}/acknowledgements`,{...scope,...patient,expected_row_version:reminder.row_version},lease,patient);
const deadline=Date.now()+30000;
do{detail=await api(`/clinical-tasks/${source.handoff.task_id}`,undefined,lease,patient);if(detail.task.state==='COMPLETED')break;await new Promise(r=>setTimeout(r,1000));}while(Date.now()<deadline);
checks.push({name:'source_acknowledgement_closes_task',state:detail.task.state,pass:detail.task.state==='COMPLETED'});
const other={patient_id:'018f0000-0000-7000-8000-000000000001',encounter_id:'018f0000-0000-7000-8000-000000000101'};
const otherLease=await api('/context-leases',{...scope,...other,purpose_code:'REMINDER_DETAIL'});
try{await api('/clinical-reminders',{...scope,...other,reminder_type:'OTHER',severity:'INFO',message:'跨患者候选来源应拒绝',source_medical_agent_run_id:source.run.run_id},otherLease,other);checks.push({name:'cross_patient_reminder_rejected',pass:false});}
catch(error){checks.push({name:'cross_patient_reminder_rejected',pass:error.message.includes('MEDICAL_AGENT_REMINDER_SOURCE_INVALID'),error:error.message});}
const docs=await api(`/encounters/${other.encounter_id}/documents`,undefined,otherLease,other);
const doc=docs.find(d=>d.status==='DRAFT');
if(doc){try{await api(`/documents/${doc.document_id}/draft`,{...scope,...other,sections:doc.sections,expected_row_version:doc.row_version,source_medical_agent_run_id:source.run.run_id},otherLease,other,'PUT');checks.push({name:'cross_patient_document_rejected',pass:false});}catch(error){checks.push({name:'cross_patient_document_rejected',pass:error.message.includes('MEDICAL_AGENT_DRAFT_SOURCE_STALE'),error:error.message});}}
const generalLease=await api('/context-leases',{...scope,patient_id:null,encounter_id:null,purpose_code:'MEDICAL_AGENT_COLLABORATION'});
let run=await api('/medical-agents/runs',{...scope,patient_id:null,encounter_id:null,context_lease_id:generalLease.lease_id,main_agent_code:'ENCOUNTER_SUMMARIZER',stage_code:'PRE_VISIT',target_type:null,target_id:null,objective:'通用问题：诊前病历资料的核对清单，缺少患者上下文时说明限制。',authorization_level:'READ_ONLY',context_scopes:['CONFIGURATION'],model_deployment_id:deployment.model_deployment_id,source_route:null},generalLease);
const limit=Date.now()+90000;while(['QUEUED','RUNNING'].includes(run.state)&&Date.now()<limit){await new Promise(r=>setTimeout(r,1000));run=await api(`/medical-agents/runs/${run.run_id}`,undefined,generalLease);}
checks.push({name:'no_patient_real_execution',state:run.state,run_id:run.run_id,pass:run.events.some(e=>e.event_type==='ModelGenerationCompleted'&&e.payload.execution_mode==='LIVE_MODEL')});
await writeFile(new URL('closure-checks.json',import.meta.url),JSON.stringify(checks,null,2));console.log(JSON.stringify(checks));
