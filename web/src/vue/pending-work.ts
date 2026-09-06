import { issueClinicalTaskLease, listClinicalTasks, issueWardLease, loadInpatientWorklist, issueSpecialtySupportLease, loadSpecialtySupportAssessments, type ClinicalTaskMode } from '../clinical-api';
import { issueRecordCenterLease, listRecordCenterWorklist } from '../api/record-center';
import { issueMedicalRecordAssetLease, listMedicalRecordAssets } from '../api/records';
import { issueConfigurationLease, listConfigurations } from '../api/config';
import { issueInfectionLease, listInfectionMonitoringEvents } from '../api/quality';
import { issueDataLease, listDataQualityFindings, listResearchDatasetRequests } from '../api/data';
import { issueExecutionWorklistLease, listExecutionWorklist, type ExecutionDomain } from '../api/execution-center';
import { issuePathwayKnowledgeLease, listPathwayKnowledge, listPathwayKnowledgeVersions } from '../api/pathway-knowledge';
import { issueAiLease, listAiRuns } from '../api/ai-platform';
import { listAdministrationJobRuns } from '../api/administration-runtime';
import { listPractitionerCredentials } from '../api/credentials';
import { issueGovernanceLease, listHistoricalMigrationBatches, listSourceSystems } from '../api/governance';
import { configurationStudios } from './configuration-studios';

export interface PendingSource {
  id: string; menu: string; label: string; scope: string; to: string;
  load: () => Promise<number>;
}
export interface PendingResult extends Omit<PendingSource, 'load'> {
  count: number | null; state: 'loading' | 'error' | 'ready';
}
export function isOpenClinicalTask(task: { state: string }) {
  return !['COMPLETED', 'WITHDRAWN', 'EXPIRED'].includes(task.state);
}
export function uniqueTaskScopeRows(rows: PendingResult[], scopes: Record<string, string>): PendingResult[] {
  const seen = new Set<string>();
  return rows.filter(row => {
    const scope = scopes[row.id];
    if (!scope || seen.has(scope)) return false;
    seen.add(scope);
    return true;
  });
}
export function pendingTotal(rows: Pick<PendingResult, 'count' | 'state'>[]): number | null {
  return rows.length && rows.every(row => row.state === 'ready' && row.count !== null)
    ? rows.reduce((sum, row) => sum + row.count!, 0) : null;
}
export function pendingDisplay(rows: Pick<PendingResult, 'count' | 'state'>[]): string {
  const count = pendingTotal(rows);
  return count !== null ? String(count) : rows.some(row => row.state === 'error') ? '!' : '…';
}
const facilityScope = '当前岗位授权的机构/院区范围';
export function requireCompleteWorklist<T>(rows: T[], limit: number): T[] {
  if (rows.length >= limit) throw new Error('待办台账达到接口返回上限，无法确认完整总数');
  return rows;
}
const config = (type: string) => issueConfigurationLease().then(lease => listConfigurations(lease, type)).then(rows => requireCompleteWorklist(rows, 500));
const taskSource = (mode: ClinicalTaskMode): PendingSource => ({
  id: `tasks-${mode}`, menu: mode, label: '未完成临床任务',
  scope: `${{ outpatient: '门诊', emergency: '急诊', inpatient: '住院' }[mode]}工作域当前选中的就诊`,
  to: `/clinical-tasks?mode=${mode}`, load: async () => (await listClinicalTasks(await issueClinicalTaskLease(mode), mode)).filter(isOpenClinicalTask).length,
});
const executions: [ExecutionDomain, string, string][] = [
  ['CARE_OPERATIONS', '护理执行', '/care-operations'], ['BILLING', '费用处理', '/billing'],
  ['OUTPATIENT_PHARMACY', '门诊发药', '/outpatient-pharmacy'], ['INPATIENT_PHARMACY', '住院发药', '/inpatient-pharmacy'],
  ['LAB', '检验处理', '/lab-workbench'], ['PATHOLOGY', '病理处理', '/pathology-workbench'],
  ['IMAGING', '影像处理', '/imaging-workbench'], ['THERAPY', '治疗执行', '/therapy-workbench'],
  ['SURGERY', '手术处理', '/surgery-schedule'], ['ANESTHESIA', '麻醉处理', '/anesthesia-workbench'],
  ['TRANSFUSION', '输血处理', '/transfusion'], ['DEVICE_MONITORING', '设备监护处理', '/device-monitoring'],
];
export function pendingSources(): PendingSource[] {
  return [
    ...(['outpatient', 'emergency', 'inpatient'] as const).map(taskSource),
    { id: 'ward-documents', menu: 'inpatient', label: '病区待完成文书', scope: '当前授权病区全部在院患者', to: '/inpatient',
      load: async () => (await loadInpatientWorklist(await issueWardLease())).reduce((sum, row) => sum + row.pending_task_count, 0) },
    { id: 'record-worklist', menu: 'record', label: '待审签或存在质控阻断的病历', scope: facilityScope, to: '/record',
      load: async () => (requireCompleteWorklist(await listRecordCenterWorklist(await issueRecordCenterLease()), 200)).filter(row => row.status !== 'VOID' && (['DRAFT', 'READY_TO_SIGN'].includes(row.status) || row.has_blocking_finding)).length },
    { id: 'quality-cases', menu: 'quality-center', label: '待整改质控事项', scope: facilityScope, to: '/department-qc/cases',
      load: async () => (await config('DEPARTMENT_QC_CASE')).filter(row => row.status !== 'ARCHIVED' && row.payload?.workflow_status !== 'CLOSED').length },
    { id: 'infection-review', menu: 'quality-center', label: '院感待复核', scope: facilityScope, to: '/infection-events/clues',
      load: async () => (await listInfectionMonitoringEvents(await issueInfectionLease('INFECTION_MONITORING'))).filter(row => row.status === 'REPORTED').length },
    { id: 'quality-evidence', menu: 'quality-center', label: '评级证据缺口', scope: facilityScope, to: '/quality-rating/assessments',
      load: async () => (await loadSpecialtySupportAssessments(await issueSpecialtySupportLease())).filter(row => row.missing_safety_gates.length > 0).length },
    { id: 'credential-expiring', menu: 'quality-center', label: '30 日内到期的有效资质', scope: facilityScope, to: '/credentials/grants',
      load: async () => (await listPractitionerCredentials()).filter(row => row.status === 'ACTIVE' && row.valid_until && new Date(row.valid_until).getTime() > Date.now() && new Date(row.valid_until).getTime() <= Date.now() + 30 * 86400_000).length },
    { id: 'archive-integrity', menu: 'archive-assets', label: '待验真或需处理资产', scope: '当前患者的病案资产', to: '/archive-integrity',
      load: async () => (requireCompleteWorklist(await listMedicalRecordAssets(await issueMedicalRecordAssetLease()), 100)).filter(row => row.status !== 'RETIRED' && (row.integrity_status !== 'VERIFIED' || row.storage_status === 'MISSING' || row.cda_status === 'FAILED')).length },
    ...executions.map(([domain, label, to]): PendingSource => ({
      id: `execution-${domain}`, menu: 'care-operations', label, scope: facilityScope, to,
      load: async () => (await listExecutionWorklist(await issueExecutionWorklistLease(domain), domain)).reduce((sum, row) => sum + row.pending_count, 0),
    })),
    { id: 'integration-incidents', menu: 'data-center', label: '集成对账差异', scope: facilityScope, to: '/integration-messages',
      load: async () => (await config('INTEGRATION_INCIDENT')).filter(row => row.status === 'ACTIVE').length },
    { id: 'migration-mismatch', menu: 'data-center', label: '迁移差异批次', scope: facilityScope, to: '/migration',
      load: async () => {
        const lease = await issueGovernanceLease('MIGRATION_ADMIN');
        const [sources, batches] = await Promise.all([listSourceSystems(lease), listHistoricalMigrationBatches(lease)]);
        const codes = new Set(sources.map(row => row.source_code));
        return batches.filter(row => codes.has(row.source_system) && row.mismatch_count > 0).length;
      } },
    { id: 'device-attention', menu: 'data-center', label: '设备校准或时钟异常', scope: facilityScope, to: '/devices',
      load: async () => (await config('DEVICE_CATALOG')).filter(row => {
        if (row.status !== 'ACTIVE') return false;
        const due = row.payload?.calibration_due;
        const today = new Date(); today.setHours(0, 0, 0, 0);
        return (typeof due === 'string' && due.trim() && new Date(due.trim().length === 10 ? `${due.trim()}T00:00:00` : due.trim()).getTime() <= today.getTime() + 30 * 86400_000)
          || Math.abs(Number(row.payload?.clock_offset_seconds ?? 0)) > 30;
      }).length },
    { id: 'data-quality', menu: 'data-center', label: '数据质量待处理', scope: facilityScope, to: '/data-quality',
      load: async () => (requireCompleteWorklist(await listDataQualityFindings(await issueDataLease('DATA_QUALITY_ADMIN')), 1000)).filter(row => row.status !== 'CLOSED').length },
    { id: 'dataset-review', menu: 'data-center', label: '科研申请待审', scope: facilityScope, to: '/research-dataset',
      load: async () => (requireCompleteWorklist(await listResearchDatasetRequests(await issueDataLease('RESEARCH_DATASET_ADMIN')), 200)).filter(row => row.status === 'REQUESTED').length },
    { id: 'ai-review', menu: 'ai-assistant', label: 'AI 运行待复核', scope: facilityScope, to: '/agent',
      load: async () => (requireCompleteWorklist(await listAiRuns(await issueAiLease('AI_RUN_GOVERNANCE')), 500)).filter(row => ['WAITING_APPROVAL', 'READY_FOR_REVIEW'].includes(row.state)).length },
    { id: 'knowledge-review', menu: 'knowledge-center', label: '路径版本待审核/审批', scope: facilityScope, to: '/pathway-review',
      load: async () => {
        const lease = await issuePathwayKnowledgeLease();
        const pathways = requireCompleteWorklist(await listPathwayKnowledge(lease), 500);
        let count = 0;
        for (const pathway of pathways.filter(row => row.status === 'ACTIVE')) {
          const versions = requireCompleteWorklist(await listPathwayKnowledgeVersions(lease, pathway.pathway_knowledge_id), 100);
          count += versions.filter(row => ['IN_REVIEW', 'APPROVED'].includes(row.status)).length;
        }
        return count;
      } },
    ...[
      ...['workflow', 'form-designer', 'rule-center', 'scope-designer', 'config-release', 'config-upgrade']
        .map(id => configurationStudios[id]).filter(Boolean).map(studio => [studio.configType, studio.title, `/${studio.routeId}`]),
      ['CLINICAL_TASK_RULE', '任务规则', '/clinical-tasks?view=rules'], ['CLINICAL_PATHWAY', '临床路径配置', '/clinical-tasks?view=pathway'],
    ].map(([type, label, to]): PendingSource => ({ id: `config-${type}`, menu: 'workflow', label: `${label}待审批/发布`, scope: facilityScope, to,
      load: async () => (await config(type)).filter(row => ['PENDING_APPROVAL', 'APPROVED'].includes(row.status)).length })),
    { id: 'admin-failed-jobs', menu: 'admin', label: '失败或部分失败的管理任务', scope: '当前账户的管理授权范围', to: '/admin-jobs',
      load: async () => (requireCompleteWorklist(await listAdministrationJobRuns(), 500)).filter(row => ['FAILED', 'PARTIAL'].includes(row.status)).length },
  ];
}
