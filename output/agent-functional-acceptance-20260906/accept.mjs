import {readFile,writeFile,appendFile} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';
const base='http://127.0.0.1:8081/api/v1',scope={organization_id:'018f0000-0000-7000-8000-00000000aa02',facility_id:'018f0000-0000-7000-8000-00000000aa03'};
const deployment=JSON.parse(await readFile(new URL('deployment.json',import.meta.url),'utf8'));
let token;
async function api(path,body,lease,patient,method){const r=await fetch(base+path,{method:method ?? (body===undefined?'GET':'POST'),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(lease?{'X-Context-Lease-Id':lease.lease_id,'X-Authorization-Watermark':lease.authorization_watermark,'X-Organization-Context':scope.organization_id,'X-Facility-Context':scope.facility_id,...(patient?{'X-Patient-Context':patient.patient_id,'X-Encounter-Context':patient.encounter_id}:{})}:{}),'Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const b=await r.json().catch(()=>null);if(!r.ok)throw Error(path+' HTTP '+r.status+' '+JSON.stringify(b));return b;}
token=(await api('/session/login',{username:'linwei',password:'HaoEMR-dev!'})).bearer_token;
const catalogLease=await api('/context-leases',{...scope,patient_id:null,encounter_id:null,purpose_code:'MEDICAL_AGENT_CATALOG'});
const catalog=await api('/medical-agents/catalog',undefined,catalogLease);
await writeFile(new URL('catalog.json',import.meta.url),JSON.stringify(catalog,null,2));
console.log('catalog',catalog.length,'main agents');
const results=[];
const previous=new Map();
if(process.env.FAILED_ONLY){for(const line of (await readFile(new URL('runs.jsonl',import.meta.url),'utf8')).trim().split('\n')){const row=JSON.parse(line);previous.set(row.agent_code,row);}}

for(const family of catalog){if(process.env.AGENT_FAMILY&&family.main_agent.agent_code!==process.env.AGENT_FAMILY)continue;for(const child of family.child_agents){if(process.env.FAILED_ONLY&&previous.get(child.agent_code)?.run?.state==='WAITING_FOR_REVIEW')continue;if(process.env.AGENT_FILTER&&!child.agent_code.includes(process.env.AGENT_FILTER))continue;
 const code=child.agent_code;
 let caseId = /PRE_VISIT|OUTPATIENT|OPD/.test(code) ? 'syn50-opd-001' : 'syn50-ip-036';
 if(family.main_agent.agent_code==='GLYCEMIC_MANAGEMENT')caseId=code==='HYPOGLYCEMIA_GUARD'?'syn50-er-033':'syn50-opd-001';
 if(code==='ANTICOAGULATION_ADVISOR')caseId='syn50-opd-018';
 if(code==='CHEST_PAIN_TRIAGE')caseId='syn50-er-021';
 if(family.main_agent.agent_code==='ICU_RISK_ASSESSMENT'||family.main_agent.agent_code==='INFECTION_SURVEILLANCE')caseId='syn50-er-023';
 if(/SURGERY|OPERATIVE|ANESTHESIA/.test(code))caseId='syn50-ip-042';
 function id(resource){const b=createHash('md5').update(`openemr2026:synthetic-50:${caseId}:${resource}`).digest();b[6]=(b[6]&15)|48;b[8]=(b[8]&63)|128;const h=b.toString('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;}
 const patient={patient_id:id('patient'),encounter_id:id('encounter')};
 try{const lease=await api('/context-leases',{...scope,...patient,purpose_code:'MEDICAL_AGENT_COLLABORATION'});
 let run=await api('/medical-agents/runs',{...scope,...patient,context_lease_id:lease.lease_id,main_agent_code:family.main_agent.agent_code,stage_code:child.stage_code,target_type:'ENCOUNTER',target_id:patient.encounter_id,objective:`${child.display_name}：根据当前合成患者的真实记录完成本岗位工作，给出可供人工核对和承接的结果，标明来源及缺失。`,authorization_level:'STANDARD',context_scopes:['RECORDS','ORDERS','RESULTS','TASKS','CONFIGURATION'],model_deployment_id:deployment.model_deployment_id,source_route:null},lease,patient);
 const started=Date.now();while(['QUEUED','RUNNING'].includes(run.state)&&Date.now()-started<120000){await new Promise(r=>setTimeout(r,1000));run=await api(`/medical-agents/runs/${run.run_id}`,undefined,lease,patient);}
 let handoff=null;
 if(process.env.VERIFY_HANDOFF && run.state==='WAITING_FOR_REVIEW'){
  try{
   const reminder=await api('/clinical-reminders',{...scope,...patient,reminder_type:'OTHER',severity:'INFO',message:`人工验收待办：${child.display_name}。请核对医助来源并按业务流程处理。`,source_medical_agent_run_id:run.run_id},lease,patient);
   const conversion=await api('/clinical-reminder-conversions',{...scope,...patient,reminder_id:reminder.reminder_id,converted_at:new Date().toISOString()},lease,patient);
   handoff={reminder_id:reminder.reminder_id,task_id:conversion.clinical_task_id};
   if(family.main_agent.agent_code==='DOCUMENT_DRAFTER'){
    const draft=Object.assign({},...run.child_runs.map(c=>c.contribution.draft_sections??{}));
    if(!Object.keys(draft).length)throw Error('Missing editable draft sections');
    const docs=await api(`/encounters/${patient.encounter_id}/documents`,undefined,lease,patient);
    const doc=docs.find(d=>d.status==='DRAFT' && run.child_runs.some(c=>c.source_references.some(ref=>ref.source_id===d.document_version_id)));
    if(!doc)throw Error('No matching editable document version');
    const saved=await api(`/documents/${doc.document_id}/draft`,{...scope,...patient,sections:{...doc.sections,...draft},expected_row_version:doc.row_version,source_medical_agent_run_id:run.run_id},lease,patient,'PUT');
    handoff.document_id=saved.document_id;handoff.document_version_id=saved.document_version_id;
    try{await api(`/documents/${doc.document_id}/draft`,{...scope,...patient,sections:saved.sections,expected_row_version:saved.row_version,source_medical_agent_run_id:run.run_id},lease,patient,'PUT');throw Error('STALE_SOURCE_ACCEPTED');}
    catch(error){if(!error.message.includes('MEDICAL_AGENT_DRAFT_SOURCE_STALE'))throw error;handoff.stale_source_rejected=true;}
   }
  }catch(error){handoff={...handoff,error:error.message};}
 }
 const entry={agent_code:child.agent_code,run,handoff};results.push(entry);await appendFile(new URL('runs.jsonl',import.meta.url),JSON.stringify(entry)+'\n');console.log(JSON.stringify({agent:child.agent_code,state:run.state,error:run.failure_code,handoff,children:run.child_runs.map(c=>({code:c.child_agent_code,state:c.state,error:c.failure_code,summary:c.contribution?.summary?.slice(0,100)}))}));
 }catch(error){const entry={agent_code:child.agent_code,error:error.message};results.push(entry);console.log(JSON.stringify(entry));await appendFile(new URL('runs.jsonl',import.meta.url),JSON.stringify(entry)+'\n');}
 if(process.env.FIRST_ONLY)break;
}if(process.env.FIRST_ONLY)break;}
await writeFile(new URL('last-results.json',import.meta.url),JSON.stringify(results,null,2));
