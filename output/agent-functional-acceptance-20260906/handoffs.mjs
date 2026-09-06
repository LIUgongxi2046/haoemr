import {readFile,writeFile,appendFile} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';
const base='http://127.0.0.1:8081/api/v1',scope={organization_id:'018f0000-0000-7000-8000-00000000aa02',facility_id:'018f0000-0000-7000-8000-00000000aa03'};
const deployment=JSON.parse(await readFile(new URL('deployment.json',import.meta.url),'utf8'));
let token;
async function api(path,body,lease,patient,method){const r=await fetch(base+path,{method:method ?? (body===undefined?'GET':'POST'),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(lease?{'X-Context-Lease-Id':lease.lease_id,'X-Authorization-Watermark':lease.authorization_watermark,'X-Organization-Context':scope.organization_id,'X-Facility-Context':scope.facility_id,...(patient?{'X-Patient-Context':patient.patient_id,'X-Encounter-Context':patient.encounter_id}:{})}:{}),'Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const b=await r.json().catch(()=>null);if(!r.ok)throw Error(path+' HTTP '+r.status+' '+JSON.stringify(b));return b;}
token=(await api('/session/login',{username:'linwei',password:'HaoEMR-dev!'})).bearer_token;
const rows=(await readFile(new URL('runs.jsonl',import.meta.url),'utf8')).trim().split('\n').map(JSON.parse);
const latest=new Map(rows.filter(r=>r.run?.state==='WAITING_FOR_REVIEW').map(r=>[r.agent_code,r]));
const results=[];
for(const [code,row] of latest){
 try{
  const patient={patient_id:row.run.patient_id,encounter_id:row.run.encounter_id};
  const lease=await api('/context-leases',{...scope,...patient,purpose_code:'REMINDER_DETAIL'});
  const reminder=await api('/clinical-reminders',{...scope,...patient,reminder_type:'OTHER',severity:'INFO',message:`${code} 完整功能验收：`+'请人工核对医助提供的证据，确认待办并记录处理结果。'.repeat(15),source_medical_agent_run_id:row.run.run_id},lease,patient);
  const conversion=await api('/clinical-reminder-conversions',{...scope,...patient,reminder_id:reminder.reminder_id,converted_at:new Date().toISOString()},lease,patient);
  let detail=await api(`/clinical-tasks/${conversion.clinical_task_id}`,undefined,lease,patient);
  await api(`/clinical-tasks/${conversion.clinical_task_id}/claims`,{...scope,...patient,expected_row_version:detail.task.row_version},lease,patient);
  await api(`/clinical-reminders/${reminder.reminder_id}/acknowledgements`,{...scope,...patient,expected_row_version:reminder.row_version},lease,patient);
  detail=await api(`/clinical-tasks/${conversion.clinical_task_id}`,undefined,lease,patient);
  const passed=detail.task.state==='COMPLETED'&&detail.events.some(e=>e.event_type==='SOURCE_COMPLETED');
  results.push({agent_code:code,run_id:row.run.run_id,reminder_id:reminder.reminder_id,task_id:conversion.clinical_task_id,state:detail.task.state,pass:passed});
 }catch(error){results.push({agent_code:code,pass:false,error:error.message});}
 if(results.length%10===0)console.log(`Completed ${results.length}/58 candidate workflow checks.`);
}
await writeFile(new URL('all-agent-handoffs.json',import.meta.url),JSON.stringify(results,null,2));console.log(JSON.stringify({total:results.length,passed:results.filter(r=>r.pass).length,failures:results.filter(r=>!r.pass)}));
