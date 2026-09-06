import { reactive } from 'vue';
import { notifyClinicalWrite } from './clinical-data-events';
import {
  aiProposalWireSchema,
  aiRunSnapshotWireSchema,
  archiveCaseWireSchema,
  archiveExportPackageWireSchema,
  archiveReadinessWireSchema,
  contextLeaseWireSchema,
  documentDiffWireSchema,
  documentCreateRequestWireSchema,
  documentVoidRequestWireSchema,
  documentEvidenceLifecycleRequestWireSchema,
  documentSourceReferenceCorrectionRequestWireSchema,
  documentCorrectionWireSchema,
  documentCorrectionPropagationWireSchema,
  documentGovernanceSnapshotWireSchema,
  documentVersionWireSchema,
  inpatientOverviewWireSchema,
  inpatientBedBoardItemWireSchema,
  inpatientDocumentRuleWireSchema,
  inpatientDocumentTaskWireSchema,
  inpatientClinicalEventWireSchema,
  inpatientConsultationWireSchema,
  inpatientPathwayInstanceWireSchema,
  inpatientPathwayWorkspaceWireSchema,
  clinicalOrderWireSchema,
  clinicalTaskWireSchema,
  clinicalTaskCollaboratorWireSchema,
  clinicalTaskDetailWireSchema,
  clinicalTaskTeamQueueWireSchema,
  clinicalTaskNotificationWireSchema,
  medicationSafetyEvaluationWireSchema,
  diagnosisTerminologyEntryWireSchema,
  clinicalDiagnosisWireSchema,
  clinicalResultWireSchema,
  criticalValueWireSchema,
  orderExecutionTaskWireSchema,
  inpatientWorklistItemWireSchema,
  qualityFindingWireSchema,
  signatureEvidenceWireSchema,
  signatureRevocationEvidenceWireSchema,
  departmentSupportAssessmentWireSchema,
  departmentSupportAssessmentPutRequestWireSchema,
  organizationUnitWireSchema,
  workforceIdentityWireSchema,
  authorizationPolicyWireSchema,
  authorizationDecisionWireSchema,
  emergencyAccessGrantWireSchema,
  patientMatchCandidateWireSchema,
  patientDemographicVersionWireSchema,
  patientMergeCaseWireSchema,
  patientTimelineWireSchema,
  documentTemplateWireSchema,
  documentAttachmentWireSchema,
  documentSourceReferenceWireSchema,
  documentSourceBundleWireSchema,
  patientSummaryWireSchema,
  encounterStateTransitionRequestWireSchema,
  encounterWireSchema,
  type AIProposalWire,
  type AIRunSnapshotWire,
  type ArchiveCaseWire,
  type ArchiveExportPackageWire,
  type ArchiveReadinessWire,
  type ContextLeaseWire,
  type DocumentDiffWire,
  type DocumentCorrectionWire,
  type DocumentCorrectionPropagationWire,
  type DocumentGovernanceSnapshotWire,
  type DocumentVersionWire,
  type InpatientOverviewWire,
  type InpatientBedBoardItemWire,
  type InpatientDocumentTaskWire,
  type InpatientDocumentRuleWire,
  type InpatientClinicalEventWire,
  type InpatientConsultationWire,
  type InpatientPathwayInstanceWire,
  type InpatientPathwayWorkspaceWire,
  type ClinicalOrderWire,
  type ClinicalTaskWire,
  type ClinicalTaskCollaboratorWire,
  type ClinicalTaskDetailWire,
  type ClinicalTaskTeamQueueWire,
  type ClinicalTaskNotificationWire,
  type MedicationSafetyEvaluationWire,
  type ClinicalDiagnosisWire,
  type ClinicalResultWire,
  type CriticalValueWire,
  type OrderExecutionTaskWire,
  type InpatientWorklistItemWire,
  type QualityFindingWire,
  type SignatureEvidenceWire,
  type SignatureRevocationEvidenceWire,
  type DepartmentSupportAssessmentWire,
  type DepartmentSupportAssessmentPutRequestWire,
  type OrganizationUnitWire,
  type OrganizationUnitCreateRequestWire,
  type WorkforceIdentityWire,
  type WorkforceOnboardingRequestWire,
  type AuthorizationPolicyWire,
  type AuthorizationPolicyCreateRequestWire,
  type AuthorizationDecisionWire,
  type AuthorizationSimulationRequestWire,
  type EmergencyAccessGrantWire,
  type EmergencyAccessRequestWire,
  type PatientMatchCandidateWire,
  type PatientMatchCandidateCreateRequestWire,
  type PatientDemographicCorrectionRequestWire,
  type PatientDemographicVersionWire,
  type PatientMergeCaseCreateRequestWire,
  type PatientMergeCaseWire,
  type PatientTimelineWire,
  type DocumentTemplateCreateRequestWire,
  type DocumentTemplateVersionCreateRequestWire,
  type DocumentTemplateWire,
  type DocumentAttachmentWire,
  type DocumentSourceReferenceWire,
  type DocumentSourceBundleWire,
  type PatientSummaryWire,
  type EncounterWire,
} from './generated/contracts';
import { authSession } from './auth-session';
import { canUseInpatientRoleSimulation } from './development-acceptance';
import {
  persistActiveOutpatientContext,
  restoreActiveOutpatientContext,
} from './active-outpatient-context';

// 仅 dev-synthetic 合成环境的兜底身份与上下文；生产构建必须由 OIDC 会话注入，不依赖这些默认值。
const syntheticDefaults = {
  tenantId: '018f0000-0000-7000-8000-00000000aa01',
  organizationId: '018f0000-0000-7000-8000-00000000aa02',
  facilityId: '018f0000-0000-7000-8000-00000000aa03',
  userId: '018f0000-0000-7000-8000-00000000aa04',
  roleId: '018f0000-0000-7000-8000-00000000aa05',
  adminRoleId: '018f0000-0000-7000-8000-00000000aa09',
  patientId: '018f0000-0000-7000-8000-000000000001',
  encounterId: '018f0000-0000-7000-8000-000000000101',
  emergencyPatientId: '018f0000-0000-7000-8000-000000000003',
  emergencyEncounterId: '018f0000-0000-7000-8000-000000000103',
  documentId: '018f0000-0000-7000-8000-000000001001',
  inpatientPatientId: '018f0000-0000-7000-8000-000000000002',
  inpatientEncounterId: '018f0000-0000-7000-8000-000000000102',
  inpatientAdmissionId: '018f0000-0000-7000-8000-00000000bb03',
  inpatientWardId: '018f0000-0000-7000-8000-00000000bb01',
  collaboratorUserId: '018f0000-0000-7000-8000-00000000aa06',
  departmentId: '018f0000-0000-7000-8000-00000000aa08',
};

const emergencyContextStorageKey = 'haoemr.emergency-context.v1';

function storedEmergencyContext(): { patientId: string; encounterId: string } | null {
  if (typeof sessionStorage === 'undefined') return null;
  try {
    const parsed = JSON.parse(sessionStorage.getItem(emergencyContextStorageKey) ?? 'null') as unknown;
    if (!parsed || typeof parsed !== 'object') return null;
    const value = parsed as Record<string, unknown>;
    return typeof value.patientId === 'string' && typeof value.encounterId === 'string'
      ? { patientId: value.patientId, encounterId: value.encounterId }
      : null;
  } catch {
    return null;
  }
}

const selectedEmergencyContext = storedEmergencyContext();

const developmentDefaults = import.meta.env.DEV ? syntheticDefaults : {
  tenantId: '', organizationId: '', facilityId: '', userId: '', roleId: '', adminRoleId: '',
  patientId: '', encounterId: '', emergencyPatientId: '', emergencyEncounterId: '', documentId: '', inpatientPatientId: '',
  inpatientEncounterId: '', inpatientAdmissionId: '', inpatientWardId: '',
  collaboratorUserId: '', departmentId: '',
};

const restoredOutpatientContext = restoreActiveOutpatientContext(
  authSession.user?.user_id ?? developmentDefaults.userId,
);

export const clinicalContext = reactive({
  tenantId: import.meta.env.VITE_TENANT_ID || developmentDefaults.tenantId,
  organizationId: import.meta.env.VITE_ORGANIZATION_ID || developmentDefaults.organizationId,
  facilityId: import.meta.env.VITE_FACILITY_ID || developmentDefaults.facilityId,
  userId: import.meta.env.VITE_USER_ID || developmentDefaults.userId,
  roleId: import.meta.env.VITE_ROLE_ASSIGNMENT_ID || developmentDefaults.roleId,
  adminRoleId: import.meta.env.VITE_ADMIN_ROLE_ASSIGNMENT_ID || developmentDefaults.adminRoleId,
  patientId: restoredOutpatientContext?.patientId || import.meta.env.VITE_PATIENT_ID || developmentDefaults.patientId,
  encounterId: restoredOutpatientContext?.encounterId || import.meta.env.VITE_ENCOUNTER_ID || developmentDefaults.encounterId,
  patientDisplayName: restoredOutpatientContext?.patientDisplayName || '',
  emergencyPatientId: selectedEmergencyContext?.patientId || import.meta.env.VITE_EMERGENCY_PATIENT_ID || developmentDefaults.emergencyPatientId,
  emergencyEncounterId: selectedEmergencyContext?.encounterId || import.meta.env.VITE_EMERGENCY_ENCOUNTER_ID || developmentDefaults.emergencyEncounterId,
  documentId: restoredOutpatientContext?.documentId || import.meta.env.VITE_DOCUMENT_ID || developmentDefaults.documentId,
  inpatientPatientId: import.meta.env.VITE_INPATIENT_PATIENT_ID || developmentDefaults.inpatientPatientId,
  inpatientEncounterId: import.meta.env.VITE_INPATIENT_ENCOUNTER_ID || developmentDefaults.inpatientEncounterId,
  inpatientAdmissionId: import.meta.env.VITE_INPATIENT_ADMISSION_ID || developmentDefaults.inpatientAdmissionId,
  inpatientWardId: import.meta.env.VITE_INPATIENT_WARD_ID || developmentDefaults.inpatientWardId,
  collaboratorUserId: import.meta.env.VITE_COLLABORATOR_USER_ID || developmentDefaults.collaboratorUserId,
  departmentId: import.meta.env.VITE_DEPARTMENT_ID || developmentDefaults.departmentId,
});

export function setEmergencyClinicalContext(patientId: string, encounterId: string): void {
  if (!patientId || !encounterId) {
    throw new ClinicalApiError('EMERGENCY_CONTEXT_INVALID', '急诊患者与就诊上下文必须同时存在', 400);
  }
  clinicalContext.emergencyPatientId = patientId;
  clinicalContext.emergencyEncounterId = encounterId;
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.setItem(emergencyContextStorageKey, JSON.stringify({ patientId, encounterId }));
  }
}

if (authSession.user) {
  clinicalContext.tenantId = authSession.user.tenant_id;
  clinicalContext.organizationId = authSession.user.organization_id;
  clinicalContext.facilityId = authSession.user.facility_id;
  clinicalContext.userId = authSession.user.user_id;
  clinicalContext.roleId = authSession.user.role_assignment_ids[0] ?? clinicalContext.roleId;
}

export function selectOutpatientContext(input: {
  patientId: string;
  encounterId: string;
  patientDisplayName?: string;
  documentId?: string | null;
}): void {
  const changedPatient = clinicalContext.patientId !== input.patientId || clinicalContext.encounterId !== input.encounterId;
  clinicalContext.patientId = input.patientId;
  clinicalContext.encounterId = input.encounterId;
  clinicalContext.patientDisplayName = input.patientDisplayName?.trim() || clinicalContext.patientDisplayName;
  if (changedPatient || input.documentId !== undefined) clinicalContext.documentId = input.documentId ?? '';
  persistActiveOutpatientContext({
    ownerUserId: authSession.user?.user_id ?? clinicalContext.userId,
    patientId: clinicalContext.patientId,
    encounterId: clinicalContext.encounterId,
    patientDisplayName: clinicalContext.patientDisplayName,
    documentId: clinicalContext.documentId || null,
    selectedAt: new Date().toISOString(),
  });
}

export function selectOutpatientDocument(documentId: string): void {
  selectOutpatientContext({
    patientId: clinicalContext.patientId,
    encounterId: clinicalContext.encounterId,
    patientDisplayName: clinicalContext.patientDisplayName,
    documentId,
  });
}

function configuredBearer() {
  return authSession.token || import.meta.env.VITE_DEV_OIDC_TOKEN || '';
}

export type InpatientSyntheticActorKey = 'AUTHOR' | 'ATTENDING' | 'CHIEF' | 'MEDICAL_RECORDS';
export interface InpatientSyntheticActor {
  key: InpatientSyntheticActorKey;
  displayName: string;
  roleLabel: string;
  userId: string;
  roleId: string;
}

const developmentInpatientActors: Record<InpatientSyntheticActorKey, InpatientSyntheticActor> | null = import.meta.env.DEV
  ? {
      AUTHOR: { key: 'AUTHOR', displayName: '合成住院医师', roleLabel: '作者', userId: syntheticDefaults.userId, roleId: syntheticDefaults.roleId },
      ATTENDING: { key: 'ATTENDING', displayName: '合成主治医师', roleLabel: '主治医师', userId: '018f0000-0000-7000-8000-00000000aa10', roleId: '018f0000-0000-7000-8000-00000000aa11' },
      CHIEF: { key: 'CHIEF', displayName: '合成科主任', roleLabel: '科主任', userId: '018f0000-0000-7000-8000-00000000aa12', roleId: '018f0000-0000-7000-8000-00000000aa13' },
      MEDICAL_RECORDS: { key: 'MEDICAL_RECORDS', displayName: '合成病案人员', roleLabel: '病案人员', userId: '018f0000-0000-7000-8000-00000000aa14', roleId: '018f0000-0000-7000-8000-00000000aa15' },
    }
  : null;

export const inpatientSyntheticActors: InpatientSyntheticActor[] = developmentInpatientActors
  ? Object.values(developmentInpatientActors) : [];
let activeInpatientSyntheticActor: InpatientSyntheticActor | null = null;

export function canUseInpatientSyntheticActors(): boolean {
  return canUseInpatientRoleSimulation() && inpatientSyntheticActors.length > 0;
}

export function setInpatientSyntheticActor(key: InpatientSyntheticActorKey): InpatientSyntheticActor {
  if (!canUseInpatientSyntheticActors() || !developmentInpatientActors) {
    throw new ClinicalApiError('SYNTHETIC_IDENTITY_FORBIDDEN', '开发角色模拟仅限开发验收配置中的系统管理员使用', 403);
  }
  activeInpatientSyntheticActor = developmentInpatientActors[key];
  return activeInpatientSyntheticActor;
}

export function getInpatientSyntheticActor(): InpatientSyntheticActor | null {
  return canUseInpatientSyntheticActors() ? activeInpatientSyntheticActor : null;
}

export function clearInpatientSyntheticActor(): void {
  activeInpatientSyntheticActor = null;
}

export class ClinicalApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly recoveryToken?: string,
  ) {
    super(message);
  }
}

export function clinicalServiceError(payload: unknown, status: number) {
  const value = payload && typeof payload === 'object' ? payload as {
    error?: { code?: string; message?: string; recovery?: { token?: string } };
    code?: string; message?: string; detail?: string;
  } : {};
  return new ClinicalApiError(
    value.error?.code || value.code || `HTTP_${status}`,
    value.error?.message || value.message || value.detail || '临床服务请求失败',
    status,
    value.error?.recovery?.token,
  );
}

function effectiveIdentityHeaders() {
  const actor = getInpatientSyntheticActor();
  const roleIds = authSession.user?.role_assignment_ids.join(',');
  return {
    Authorization: configuredBearer() ? `Bearer ${configuredBearer()}` : '',
    'X-HaoEMR-Tenant-Id': authSession.user?.tenant_id ?? clinicalContext.tenantId,
    'X-HaoEMR-User-Id': actor?.userId ?? authSession.user?.user_id ?? clinicalContext.userId,
    'X-HaoEMR-Role-Assignment-Ids': actor?.roleId ?? roleIds ?? clinicalContext.roleId,
  };
}

export async function request(path: string, init: RequestInit = {}) {
  const actor = getInpatientSyntheticActor();
  if (!clinicalContext.tenantId || !clinicalContext.organizationId || !clinicalContext.facilityId
      || !(actor?.userId ?? clinicalContext.userId) || !(actor?.roleId ?? clinicalContext.roleId) || !configuredBearer()) {
    throw new ClinicalApiError(
      'CLINICAL_IDENTITY_NOT_CONFIGURED',
      '生产构建必须由 OIDC 会话注入身份与临床上下文',
      401,
    );
  }
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { ...effectiveIdentityHeaders(), ...init.headers },
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) as unknown : null;
  if (!response.ok) {
    throw clinicalServiceError(payload, response.status);
  }
  notifyClinicalWrite(path, init.method);
  return payload;
}

export type ClinicalBinaryResponse = {
  blob: Blob;
  filename: string;
  mediaType: string;
  contentHash: string | null;
};

/**
 * Download authenticated clinical evidence without attempting JSON decoding.
 * The same patient/role/lease headers and fail-closed error envelope used by
 * request() are retained so downloads cannot bypass the clinical context gate.
 */
export async function requestBinary(path: string, headers: Record<string, string> = {}): Promise<ClinicalBinaryResponse> {
  const actor = getInpatientSyntheticActor();
  if (!clinicalContext.tenantId || !clinicalContext.organizationId || !clinicalContext.facilityId
      || !(actor?.userId ?? clinicalContext.userId) || !(actor?.roleId ?? clinicalContext.roleId) || !configuredBearer()) {
    throw new ClinicalApiError('CLINICAL_IDENTITY_NOT_CONFIGURED', '生产构建必须由 OIDC 会话注入身份与临床上下文', 401);
  }
  const response = await fetch(`/api/v1${path}`, { headers: { ...effectiveIdentityHeaders(), ...headers } });
  if (!response.ok) {
    const text = await response.text();
    let code = `HTTP_${response.status}`;
    let message = '临床服务请求失败';
    try {
      const payload = JSON.parse(text) as { error?: { code?: string; message?: string } };
      code = payload.error?.code || code;
      message = payload.error?.message || message;
    } catch { /* non-JSON error body */ }
    throw new ClinicalApiError(code, message, response.status);
  }
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const utf8Name = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const quotedName = disposition.match(/filename="([^"]+)"/i)?.[1];
  const filename = utf8Name ? decodeURIComponent(utf8Name) : quotedName ?? 'download.bin';
  return {
    blob: await response.blob(),
    filename,
    mediaType: response.headers.get('Content-Type') ?? 'application/octet-stream',
    contentHash: response.headers.get('X-Content-SHA256'),
  };
}

export async function streamText(path: string, headers: Record<string, string> = {}) {
  const actor = activeInpatientSyntheticActor;
  if (!clinicalContext.tenantId || !clinicalContext.organizationId || !clinicalContext.facilityId
      || !(actor?.userId ?? clinicalContext.userId) || !(actor?.roleId ?? clinicalContext.roleId) || !configuredBearer()) {
    throw new ClinicalApiError(
      'CLINICAL_IDENTITY_NOT_CONFIGURED',
      '生产构建必须由 OIDC 会话注入身份与临床上下文',
      401,
    );
  }
  const response = await fetch(`/api/v1${path}`, {
    headers: { ...effectiveIdentityHeaders(), ...headers },
  });
  const text = await response.text();
  if (!response.ok) {
    let code = `HTTP_${response.status}`;
    let message = '临床服务请求失败';
    try {
      const payload = JSON.parse(text) as { error?: { code?: string; message?: string } };
      code = payload.error?.code || code;
      message = payload.error?.message || message;
    } catch { /* 非 JSON 错误体 */ }
    throw new ClinicalApiError(code, message, response.status);
  }
  return text;
}

export async function adminRequest(path: string, init: RequestInit = {}) {
  if (!clinicalContext.tenantId || !clinicalContext.userId || !clinicalContext.adminRoleId || !configuredBearer()) {
    throw new ClinicalApiError(
      'ADMIN_IDENTITY_NOT_CONFIGURED',
      '生产构建必须由 OIDC 会话注入管理身份',
      401,
    );
  }
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers: {
      ...effectiveIdentityHeaders(),
      'X-HaoEMR-User-Id': clinicalContext.userId,
      'X-HaoEMR-Role-Assignment-Ids': clinicalContext.adminRoleId,
      ...init.headers,
    },
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) as unknown : null;
  if (!response.ok) {
    const envelope = payload as { error?: { code?: string; message?: string; recovery?: { token?: string } } };
    throw new ClinicalApiError(
      envelope.error?.code || `HTTP_${response.status}`,
      envelope.error?.message || '管理服务请求失败',
      response.status,
      envelope.error?.recovery?.token,
    );
  }
  notifyClinicalWrite(path, init.method);
  return payload;
}

export async function issueDocumentLease(): Promise<ContextLeaseWire> {
  return contextLeaseWireSchema.parse(await request('/context-leases', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      purpose_code: 'DOCUMENT_DRAFT',
    }),
  }));
}

export async function issueInpatientLease(): Promise<ContextLeaseWire> {
  return issueContextLease(
    clinicalContext.inpatientPatientId,
    clinicalContext.inpatientEncounterId,
    'INPATIENT_WORKFLOW',
  );
}

export async function issueWardLease(): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, 'INPATIENT_WORKLIST');
}

export async function issuePatientSearchLease(): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, 'PATIENT_SEARCH');
}

export async function issuePatientTimelineLease(patientId: string): Promise<ContextLeaseWire> {
  return issueContextLease(patientId, null, 'PATIENT_TIMELINE');
}

export interface PatientTimelineFilters {
  from?: string;
  to?: string;
  types?: string[];
  statuses?: string[];
  cursor?: string;
  limit?: number;
}

export async function loadPatientTimeline(
  lease: ContextLeaseWire,
  patientId: string,
  filters: PatientTimelineFilters = {},
): Promise<PatientTimelineWire> {
  const query = new URLSearchParams();
  if (filters.from) query.set('from', filters.from);
  if (filters.to) query.set('to', filters.to);
  if (filters.types?.length) query.set('types', filters.types.join(','));
  if (filters.statuses?.length) query.set('statuses', filters.statuses.join(','));
  if (filters.cursor) query.set('cursor', filters.cursor);
  query.set('limit', String(filters.limit ?? 50));
  return patientTimelineWireSchema.parse(await request(
    `/patients/${patientId}/timeline?${query.toString()}`,
    { headers: patientTimelineHeaders(lease, patientId) },
  ));
}

export async function issueOrderLease(mode: 'outpatient' | 'inpatient'): Promise<ContextLeaseWire> {
  const context = orderContext(mode);
  return issueContextLease(context.patientId, context.encounterId, 'ORDER_WORKFLOW');
}

export type ClinicalTaskMode = 'outpatient' | 'emergency' | 'inpatient';

export async function issueClinicalTaskLease(mode: ClinicalTaskMode): Promise<ContextLeaseWire> {
  const context = orderContext(mode);
  return issueContextLease(context.patientId, context.encounterId, 'CLINICAL_TASK_WORKFLOW');
}

export async function issueDiagnosisLease(): Promise<ContextLeaseWire> {
  return issueContextLease(clinicalContext.patientId, clinicalContext.encounterId, 'DIAGNOSIS_WORKFLOW');
}

export async function issueResultLease(mode: 'outpatient' | 'inpatient' = 'outpatient'): Promise<ContextLeaseWire> {
  const context = orderContext(mode);
  return issueContextLease(context.patientId, context.encounterId, 'RESULT_WORKFLOW');
}

export async function issueArchiveLease(): Promise<ContextLeaseWire> {
  return issueContextLease(clinicalContext.patientId, clinicalContext.encounterId, 'ARCHIVE_WORKFLOW');
}

export async function issueSpecialtySupportLease(): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, 'SPECIALTY_SUPPORT_GOVERNANCE');
}

export async function loadSpecialtySupportAssessments(lease: ContextLeaseWire): Promise<DepartmentSupportAssessmentWire[]> {
  const payload = await request(`/specialty-support/${clinicalContext.facilityId}`, {
    headers: wardHeaders(lease),
  });
  return departmentSupportAssessmentWireSchema.array().parse(payload);
}

export async function updateSpecialtySupportAssessment(
  lease: ContextLeaseWire,
  assessment: DepartmentSupportAssessmentWire,
  input: Omit<DepartmentSupportAssessmentPutRequestWire, 'organization_id' | 'expected_row_version'>,
): Promise<DepartmentSupportAssessmentWire> {
  const body = departmentSupportAssessmentPutRequestWireSchema.parse({
    ...input,
    organization_id: clinicalContext.organizationId,
    expected_row_version: assessment.row_version,
  });
  return departmentSupportAssessmentWireSchema.parse(await request(
    `/specialty-support/${clinicalContext.facilityId}/${assessment.department_id}/${encodeURIComponent(assessment.clinical_scope_code)}`,
    {
      method: 'PUT',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(body),
    },
  ));
}

export async function createSpecialtySupportAssessment(
  lease: ContextLeaseWire,
  departmentId: string,
  clinicalScopeCode: string,
  input: Omit<DepartmentSupportAssessmentPutRequestWire, 'organization_id' | 'expected_row_version'>,
): Promise<DepartmentSupportAssessmentWire> {
  const body = departmentSupportAssessmentPutRequestWireSchema.parse({
    ...input,
    organization_id: clinicalContext.organizationId,
    expected_row_version: 0,
  });
  return departmentSupportAssessmentWireSchema.parse(await request(
    `/specialty-support/${clinicalContext.facilityId}/${departmentId}/${encodeURIComponent(clinicalScopeCode)}`,
    {
      method: 'PUT',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(body),
    },
  ));
}

export async function deleteSpecialtySupportAssessment(
  lease: ContextLeaseWire,
  assessment: DepartmentSupportAssessmentWire,
): Promise<void> {
  await request(
    `/specialty-support/${clinicalContext.facilityId}/${assessment.department_id}/${encodeURIComponent(assessment.clinical_scope_code)}?expected_row_version=${assessment.row_version}`,
    {
      method: 'DELETE',
      headers: { ...wardHeaders(lease), 'Idempotency-Key': crypto.randomUUID() },
    },
  );
}

export async function loadArchiveReadiness(lease: ContextLeaseWire): Promise<ArchiveReadinessWire> {
  return archiveReadinessWireSchema.parse(await request(
    `/archive/readiness?encounter_id=${clinicalContext.encounterId}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function createArchiveCase(lease: ContextLeaseWire): Promise<ArchiveCaseWire> {
  return archiveCaseWireSchema.parse(await request('/archive/cases', {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
    }),
  }));
}

export async function transitionArchiveCase(
  lease: ContextLeaseWire,
  archive: ArchiveCaseWire,
  action: 'seals' | 'unseals',
  reason: string,
): Promise<ArchiveCaseWire> {
  return archiveCaseWireSchema.parse(await request(`/archive/cases/${archive.archive_case_id}/${action}`, {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      expected_row_version: archive.row_version,
      reason,
    }),
  }));
}

export async function createArchiveExport(
  lease: ContextLeaseWire,
  archive: ArchiveCaseWire,
  purpose: string,
): Promise<ArchiveExportPackageWire> {
  return archiveExportPackageWireSchema.parse(await request(
    `/archive/cases/${archive.archive_case_id}/export-packages`, {
      method: 'POST',
      headers: {
        ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.patientId,
        encounter_id: clinicalContext.encounterId,
        purpose,
        output_format: 'JSON',
      }),
    },
  ));
}

export async function issueContextLease(
  patientId: string | null,
  encounterId: string | null,
  purposeCode: string,
): Promise<ContextLeaseWire> {
  return contextLeaseWireSchema.parse(await request('/context-leases', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: patientId,
      encounter_id: encounterId,
      purpose_code: purposeCode,
    }),
  }));
}

export function scopedHeaders(lease: ContextLeaseWire) {
  return {
    'X-Context-Lease-Id': lease.lease_id,
    'X-Authorization-Watermark': lease.authorization_watermark,
    'X-Organization-Context': clinicalContext.organizationId,
    'X-Facility-Context': clinicalContext.facilityId,
    'X-Patient-Context': clinicalContext.patientId,
    'X-Encounter-Context': clinicalContext.encounterId,
  };
}

export function patientTimelineHeaders(lease: ContextLeaseWire, patientId: string) {
  return {
    'X-Context-Lease-Id': lease.lease_id,
    'X-Authorization-Watermark': lease.authorization_watermark,
    'X-Organization-Context': clinicalContext.organizationId,
    'X-Facility-Context': clinicalContext.facilityId,
    'X-Patient-Context': patientId,
  };
}

export function inpatientHeaders(lease: ContextLeaseWire) {
  return {
    'X-Context-Lease-Id': lease.lease_id,
    'X-Authorization-Watermark': lease.authorization_watermark,
    'X-Organization-Context': clinicalContext.organizationId,
    'X-Facility-Context': clinicalContext.facilityId,
    'X-Patient-Context': clinicalContext.inpatientPatientId,
    'X-Encounter-Context': clinicalContext.inpatientEncounterId,
  };
}

export function wardHeaders(lease: ContextLeaseWire) {
  return {
    'X-Context-Lease-Id': lease.lease_id,
    'X-Authorization-Watermark': lease.authorization_watermark,
    'X-Organization-Context': clinicalContext.organizationId,
    'X-Facility-Context': clinicalContext.facilityId,
  };
}

export function explicitContextHeaders(
  lease: ContextLeaseWire,
  patientId: string | null,
  encounterId: string | null,
) {
  return {
    'X-Context-Lease-Id': lease.lease_id,
    'X-Authorization-Watermark': lease.authorization_watermark,
    'X-Organization-Context': clinicalContext.organizationId,
    'X-Facility-Context': clinicalContext.facilityId,
    ...(patientId ? { 'X-Patient-Context': patientId } : {}),
    ...(encounterId ? { 'X-Encounter-Context': encounterId } : {}),
  };
}

function orderContext(mode: ClinicalTaskMode) {
  if (mode === 'inpatient') {
    return { patientId: clinicalContext.inpatientPatientId, encounterId: clinicalContext.inpatientEncounterId };
  }
  if (mode === 'emergency') {
    return { patientId: clinicalContext.emergencyPatientId, encounterId: clinicalContext.emergencyEncounterId };
  }
  return { patientId: clinicalContext.patientId, encounterId: clinicalContext.encounterId };
}

export function orderHeaders(lease: ContextLeaseWire, mode: ClinicalTaskMode) {
  const context = orderContext(mode);
  return {
    'X-Context-Lease-Id': lease.lease_id,
    'X-Authorization-Watermark': lease.authorization_watermark,
    'X-Organization-Context': clinicalContext.organizationId,
    'X-Facility-Context': clinicalContext.facilityId,
    'X-Patient-Context': context.patientId,
    'X-Encounter-Context': context.encounterId,
  };
}

export async function listClinicalOrders(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
): Promise<ClinicalOrderWire[]> {
  const context = orderContext(mode);
  // 未选择患者/就诊时不发请求：否则后端对空 encounter_id 返回 400。
  if (!context.encounterId || !context.patientId) return [];
  return clinicalOrderWireSchema.array().parse(await request(
    `/orders?encounter_id=${context.encounterId}`,
    { headers: orderHeaders(lease, mode) },
  ));
}

export async function listClinicalTasks(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
  includeExpired = false,
): Promise<ClinicalTaskWire[]> {
  const context = orderContext(mode);
  if (!context.encounterId || !context.patientId) return [];
  const payload = await request(
    `/clinical-tasks?encounter_id=${context.encounterId}&include_expired=${includeExpired}`,
    { headers: orderHeaders(lease, mode) },
  );
  return clinicalTaskWireSchema.array().parse(payload);
}

export async function commandClinicalTask(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
  task: ClinicalTaskWire,
  action: 'views' | 'claims',
): Promise<ClinicalTaskWire> {
  const context = orderContext(mode);
  return clinicalTaskWireSchema.parse(await request(`/clinical-tasks/${task.task_id}/${action}`, {
    method: 'POST',
    headers: {
      ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      expected_row_version: task.row_version,
    }),
  }));
}

export async function collaborateClinicalTask(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
  task: ClinicalTaskWire,
  action: 'delegations' | 'transfers' | 'escalations',
  targetUserId: string,
  reason: string,
  validUntil?: string | null,
): Promise<ClinicalTaskWire> {
  const context = orderContext(mode);
  return clinicalTaskWireSchema.parse(await request(`/clinical-tasks/${task.task_id}/${action}`, {
    method: 'POST',
    headers: {
      ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      expected_row_version: task.row_version,
      target_user_id: targetUserId,
      reason,
      valid_until: action === 'delegations' ? validUntil : null,
    }),
  }));
}

export async function listEligibleClinicalTaskCollaborators(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
): Promise<ClinicalTaskCollaboratorWire[]> {
  return clinicalTaskCollaboratorWireSchema.array().parse(await request('/clinical-tasks/collaborators', {
    headers: orderHeaders(lease, mode),
  }));
}

export async function getClinicalTaskDetail(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
  taskId: string,
): Promise<ClinicalTaskDetailWire> {
  return clinicalTaskDetailWireSchema.parse(await request(`/clinical-tasks/${taskId}`, {
    headers: orderHeaders(lease, mode),
  }));
}

export async function listClinicalTaskTeamQueue(
  lease: ContextLeaseWire,
): Promise<ClinicalTaskTeamQueueWire[]> {
  const payload = await request(
    `/clinical-task-team-queues?department_id=${clinicalContext.departmentId}`,
    { headers: wardHeaders(lease) },
  );
  return clinicalTaskTeamQueueWireSchema.array().parse(filterOperationalTeamQueuePayload(payload));
}

export function filterOperationalTeamQueuePayload(payload: unknown): unknown {
  if (!Array.isArray(payload)) return payload;
  return payload.filter((item) => {
    if (!item || typeof item !== 'object') return true;
    return (item as { queue_status?: unknown }).queue_status !== 'WITHDRAWN';
  });
}

export async function enqueueClinicalTask(
  lease: ContextLeaseWire,
  taskId: string,
): Promise<ClinicalTaskTeamQueueWire> {
  return clinicalTaskTeamQueueWireSchema.parse(await request('/clinical-task-team-queues', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      department_id: clinicalContext.departmentId,
      clinical_task_id: taskId,
      enqueued_at: new Date().toISOString(),
    }),
  }));
}

export async function transitionClinicalTaskTeamQueue(
  lease: ContextLeaseWire,
  queue: ClinicalTaskTeamQueueWire,
  action: 'claims' | 'completions' | 'withdrawals',
): Promise<ClinicalTaskTeamQueueWire> {
  return clinicalTaskTeamQueueWireSchema.parse(await request(
    `/clinical-task-team-queues/${queue.queue_id}/${action}`,
    {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        expected_row_version: queue.row_version,
      }),
    },
  ));
}

export async function listClinicalTaskNotifications(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
  taskId: string,
): Promise<ClinicalTaskNotificationWire[]> {
  return clinicalTaskNotificationWireSchema.array().parse(await request(
    `/clinical-task-notifications?task_id=${taskId}`,
    { headers: orderHeaders(lease, mode) },
  ));
}

export async function createClinicalTaskNotification(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
  input: {
    taskId: string;
    recipientUserId: string;
    kind: ClinicalTaskNotificationWire['kind'];
    channel: ClinicalTaskNotificationWire['channel'];
    scheduledAt?: string | null;
  },
): Promise<ClinicalTaskNotificationWire> {
  const context = orderContext(mode);
  return clinicalTaskNotificationWireSchema.parse(await request('/clinical-task-notifications', {
    method: 'POST',
    headers: { ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      task_id: input.taskId,
      recipient_user_id: input.recipientUserId,
      kind: input.kind,
      channel: input.channel,
      scheduled_at: input.scheduledAt || null,
    }),
  }));
}

export async function transitionClinicalTaskNotification(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
  notification: ClinicalTaskNotificationWire,
  action: 'deliveries' | 'failures',
  error?: string,
): Promise<ClinicalTaskNotificationWire> {
  const context = orderContext(mode);
  return clinicalTaskNotificationWireSchema.parse(await request(
    `/clinical-task-notifications/${notification.notification_id}/${action}`,
    {
      method: 'POST',
      headers: { ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: context.patientId,
        encounter_id: context.encounterId,
        expected_row_version: notification.row_version,
        ...(action === 'failures' ? { error: error || '消息通道返回可重试失败' } : {}),
      }),
    },
  ));
}

export async function recoverClinicalTaskNotifications(
  lease: ContextLeaseWire,
  mode: ClinicalTaskMode,
  taskId: string,
): Promise<number> {
  const context = orderContext(mode);
  const payload = await request('/clinical-task-notifications/recoveries', {
    method: 'POST',
    headers: { ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      task_id: taskId,
    }),
  }) as { recovered_count: number };
  return payload.recovered_count;
}

export async function createClinicalOrder(
  lease: ContextLeaseWire,
  mode: 'outpatient' | 'inpatient',
  input: {
    orderScope: 'LONG_TERM' | 'TEMPORARY';
    clinicalIndication: string;
    itemType: 'MEDICATION' | 'LAB' | 'IMAGING' | 'TREATMENT' | 'NURSING' | 'DIET' | 'OTHER';
    catalogCode: string;
    displayName: string;
    requestedQuantity: number;
    quantityUnit: string;
    doseValue?: number;
    doseUnit?: string;
    routeCode?: string;
    frequencyCode?: string;
    instructions: string;
  },
): Promise<ClinicalOrderWire> {
  const context = orderContext(mode);
  return clinicalOrderWireSchema.parse(await request('/orders', {
    method: 'POST',
    headers: {
      ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      order_scope: input.orderScope,
      clinical_indication: input.clinicalIndication,
      items: [{
        item_type: input.itemType,
        catalog_code: input.catalogCode,
        display_name: input.displayName,
        requested_quantity: input.requestedQuantity,
        quantity_unit: input.quantityUnit,
        dose_value: input.doseValue ?? null,
        dose_unit: input.doseUnit || null,
        route_code: input.routeCode || null,
        frequency_code: input.frequencyCode || null,
        instructions: input.instructions || null,
      }],
    }),
  }));
}

export async function updateClinicalOrder(
  lease: ContextLeaseWire,
  mode: 'outpatient' | 'inpatient',
  order: ClinicalOrderWire,
  input: {
    orderScope: 'LONG_TERM' | 'TEMPORARY'; clinicalIndication: string;
    itemType: 'MEDICATION' | 'LAB' | 'IMAGING' | 'TREATMENT' | 'NURSING' | 'DIET' | 'OTHER';
    catalogCode: string; displayName: string; requestedQuantity: number; quantityUnit: string;
    doseValue?: number; doseUnit?: string; routeCode?: string; frequencyCode?: string; instructions: string;
  },
): Promise<ClinicalOrderWire> {
  const context = orderContext(mode);
  return clinicalOrderWireSchema.parse(await request(`/orders/${order.order_id}`, {
    method: 'PATCH',
    headers: {
      ...orderHeaders(lease, mode), 'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${order.row_version}"`,
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      order_scope: input.orderScope,
      clinical_indication: input.clinicalIndication,
      items: [{
        item_type: input.itemType, catalog_code: input.catalogCode, display_name: input.displayName,
        requested_quantity: input.requestedQuantity, quantity_unit: input.quantityUnit,
        dose_value: input.doseValue ?? null, dose_unit: input.doseUnit || null,
        route_code: input.routeCode || null, frequency_code: input.frequencyCode || null,
        instructions: input.instructions || null,
      }],
    }),
  }));
}

export async function checkClinicalOrderSafety(
  lease: ContextLeaseWire,
  mode: 'outpatient' | 'inpatient',
  order: ClinicalOrderWire,
): Promise<MedicationSafetyEvaluationWire> {
  const context = orderContext(mode);
  return medicationSafetyEvaluationWireSchema.parse(await request(`/orders/${order.order_id}/safety-check`, {
    method: 'POST',
    headers: {
      ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      expected_row_version: order.row_version,
      rule_watermark: 'RULESET-MEDICATION-6',
    }),
  }));
}

export async function signClinicalOrder(
  lease: ContextLeaseWire,
  mode: 'outpatient' | 'inpatient',
  order: ClinicalOrderWire,
): Promise<ClinicalOrderWire> {
  const context = orderContext(mode);
  return clinicalOrderWireSchema.parse(await request(`/orders/${order.order_id}/sign`, {
    method: 'POST',
    headers: {
      ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      expected_row_version: order.row_version,
      rule_watermark: 'RULESET-MEDICATION-6',
    }),
  }));
}

export async function controlClinicalOrder(
  lease: ContextLeaseWire,
  mode: 'outpatient' | 'inpatient',
  order: ClinicalOrderWire,
  action: 'stop' | 'cancel',
  reason: string,
): Promise<ClinicalOrderWire> {
  const context = orderContext(mode);
  return clinicalOrderWireSchema.parse(await request(`/orders/${order.order_id}/${action}`, {
    method: 'POST',
    headers: {
      ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      expected_row_version: order.row_version,
      reason,
    }),
  }));
}

export async function recordOrderExecution(
  lease: ContextLeaseWire,
  mode: 'outpatient' | 'inpatient',
  task: OrderExecutionTaskWire,
  eventType: 'PARTIAL' | 'COMPLETED',
  performedQuantity: number,
): Promise<OrderExecutionTaskWire> {
  const context = orderContext(mode);
  return orderExecutionTaskWireSchema.parse(await request(`/executions/${task.execution_task_id}/events`, {
    method: 'POST',
    headers: {
      ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: context.patientId,
      encounter_id: context.encounterId,
      event_type: eventType,
      expected_task_row_version: task.row_version,
      performed_quantity: performedQuantity,
      quantity_unit: task.quantity_unit,
      note: eventType === 'PARTIAL' ? '工作台记录部分执行' : '工作台确认执行完成',
    }),
  }));
}

export async function listClinicalDiagnoses(lease: ContextLeaseWire): Promise<ClinicalDiagnosisWire[]> {
  if (!clinicalContext.encounterId || !clinicalContext.patientId) return [];
  return clinicalDiagnosisWireSchema.array().parse(await request(
    `/diagnoses?encounter_id=${clinicalContext.encounterId}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function searchDiagnosisTerminology(lease: ContextLeaseWire, query = '') {
  const parameters = new URLSearchParams({ query: query.trim(), limit: '100' });
  return diagnosisTerminologyEntryWireSchema.array().parse(await request(
    `/diagnosis-terminology?${parameters}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function createClinicalDiagnosis(
  lease: ContextLeaseWire,
  input: {
    terminologyRelease: string; code: string; diagnosisText: string;
    diagnosisRole: 'PRIMARY' | 'SECONDARY' | 'DIFFERENTIAL';
    certainty: 'PROVISIONAL' | 'CONFIRMED'; evidenceSummary: string; planSummary: string;
  },
): Promise<ClinicalDiagnosisWire> {
  return clinicalDiagnosisWireSchema.parse(await request('/diagnoses', {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId, encounter_id: clinicalContext.encounterId,
      terminology_system: 'ICD-10-CN', terminology_release: input.terminologyRelease, code: input.code,
      diagnosis_text: input.diagnosisText, diagnosis_role: input.diagnosisRole, certainty: input.certainty,
      effective_at: new Date().toISOString(), evidence_summary: input.evidenceSummary || null,
      plan_summary: input.planSummary || null,
    }),
  }));
}

function diagnosisCommandBody(lease: ContextLeaseWire, expectedRowVersion: number) {
  void lease;
  return {
    organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
    patient_id: clinicalContext.patientId, encounter_id: clinicalContext.encounterId,
    expected_row_version: expectedRowVersion,
  };
}

export async function confirmClinicalDiagnosis(
  lease: ContextLeaseWire, diagnosis: ClinicalDiagnosisWire,
): Promise<ClinicalDiagnosisWire> {
  return clinicalDiagnosisWireSchema.parse(await request(`/diagnoses/${diagnosis.diagnosis_id}/confirm`, {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(diagnosisCommandBody(lease, diagnosis.row_version)),
  }));
}

export async function correctClinicalDiagnosis(
  lease: ContextLeaseWire,
  diagnosis: ClinicalDiagnosisWire,
  input: {
    terminologyRelease: string; code: string; diagnosisText: string;
    diagnosisRole: 'PRIMARY' | 'SECONDARY' | 'DIFFERENTIAL'; certainty: 'PROVISIONAL' | 'CONFIRMED';
    evidenceSummary: string; planSummary: string; correctionReason: string;
  },
): Promise<ClinicalDiagnosisWire> {
  return clinicalDiagnosisWireSchema.parse(await request(`/diagnoses/${diagnosis.diagnosis_id}/correct`, {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      ...diagnosisCommandBody(lease, diagnosis.row_version), terminology_system: 'ICD-10-CN',
      terminology_release: input.terminologyRelease, code: input.code, diagnosis_text: input.diagnosisText,
      diagnosis_role: input.diagnosisRole, certainty: input.certainty, effective_at: new Date().toISOString(),
      evidence_summary: input.evidenceSummary || null, plan_summary: input.planSummary || null,
      correction_reason: input.correctionReason,
    }),
  }));
}

export async function stopClinicalDiagnosis(
  lease: ContextLeaseWire, diagnosis: ClinicalDiagnosisWire, reason: string,
): Promise<ClinicalDiagnosisWire> {
  return clinicalDiagnosisWireSchema.parse(await request(`/diagnoses/${diagnosis.diagnosis_id}/stop`, {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ ...diagnosisCommandBody(lease, diagnosis.row_version), reason }),
  }));
}

export async function listClinicalResults(
  lease: ContextLeaseWire,
  mode: 'outpatient' | 'inpatient' = 'outpatient',
): Promise<ClinicalResultWire[]> {
  const context = orderContext(mode);
  // 未选择患者/就诊时不发请求：否则后端对空 encounter_id 返回 400。
  if (!context.encounterId || !context.patientId) return [];
  return clinicalResultWireSchema.array().parse(await request(
    `/results?encounter_id=${context.encounterId}`,
    { headers: orderHeaders(lease, mode) },
  ));
}

export type ResultObservationInput = {
  itemCode: string; itemName: string; valueType: 'NUMERIC' | 'TEXT';
  numericValue?: number; textValue?: string; unit?: string;
  referenceLow?: number; referenceHigh?: number;
  abnormalFlag: 'NORMAL' | 'HIGH' | 'LOW' | 'CRITICAL_HIGH' | 'CRITICAL_LOW';
};

function resultObservationPayload(input: ResultObservationInput) {
  return {
    item_code: input.itemCode, item_name: input.itemName, value_type: input.valueType,
    numeric_value: input.numericValue ?? null, text_value: input.textValue || null,
    unit: input.unit || null, reference_low: input.referenceLow ?? null,
    reference_high: input.referenceHigh ?? null, abnormal_flag: input.abnormalFlag,
  };
}

export async function createClinicalResult(
  lease: ContextLeaseWire,
  executionTaskId: string,
  reportType: 'LAB' | 'IMAGING',
  conclusion: string,
  observation: ResultObservationInput,
  mode: 'outpatient' | 'inpatient' = 'outpatient',
): Promise<ClinicalResultWire> {
  const context = orderContext(mode);
  return clinicalResultWireSchema.parse(await request('/results', {
    method: 'POST',
    headers: { ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      patient_id: context.patientId, encounter_id: context.encounterId,
      execution_task_id: executionTaskId, source_system: 'HAOEMR-MANUAL',
      source_report_key: crypto.randomUUID(), report_type: reportType, conclusion,
      reported_at: new Date().toISOString(), observations: [resultObservationPayload(observation)],
    }),
  }));
}

export async function correctClinicalResult(
  lease: ContextLeaseWire,
  result: ClinicalResultWire,
  correctionReason: string,
  conclusion: string,
  observation: ResultObservationInput,
  mode: 'outpatient' | 'inpatient' = 'outpatient',
): Promise<ClinicalResultWire> {
  const context = orderContext(mode);
  return clinicalResultWireSchema.parse(await request(`/results/${result.result_id}/corrections`, {
    method: 'POST',
    headers: { ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      patient_id: context.patientId, encounter_id: context.encounterId,
      expected_row_version: result.row_version, correction_reason: correctionReason, conclusion,
      reported_at: new Date().toISOString(), observations: [resultObservationPayload(observation)],
    }),
  }));
}

export async function acknowledgeCriticalValue(
  lease: ContextLeaseWire, critical: CriticalValueWire,
  mode: 'outpatient' | 'inpatient' = 'outpatient',
): Promise<CriticalValueWire> {
  const context = orderContext(mode);
  return criticalValueWireSchema.parse(await request(`/critical-values/${critical.critical_value_id}/acknowledge`, {
    method: 'POST',
    headers: { ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      patient_id: context.patientId, encounter_id: context.encounterId,
      expected_row_version: critical.row_version, notification_method: 'WORKSTATION_READ_BACK',
      recipient_confirmed: true,
    }),
  }));
}

export async function disposeCriticalValue(
  lease: ContextLeaseWire,
  critical: CriticalValueWire,
  input: { assessment: string; actionTaken: string; outcome: string; retestRequired: boolean },
  mode: 'outpatient' | 'inpatient' = 'outpatient',
): Promise<CriticalValueWire> {
  const context = orderContext(mode);
  return criticalValueWireSchema.parse(await request(`/critical-values/${critical.critical_value_id}/dispositions`, {
    method: 'POST',
    headers: { ...orderHeaders(lease, mode), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      patient_id: context.patientId, encounter_id: context.encounterId,
      expected_row_version: critical.row_version, assessment: input.assessment,
      action_taken: input.actionTaken, outcome: input.outcome, retest_required: input.retestRequired,
    }),
  }));
}

export async function loadInpatientOverview(lease: ContextLeaseWire): Promise<InpatientOverviewWire> {
  return inpatientOverviewWireSchema.parse(await request(
    `/inpatient/admissions/${clinicalContext.inpatientAdmissionId}/overview`,
    { headers: inpatientHeaders(lease) },
  ));
}

export function selectInpatientContext(
  source: Pick<InpatientWorklistItemWire, 'admission_id' | 'encounter_id' | 'patient_id'>,
): void {
  clinicalContext.inpatientAdmissionId = source.admission_id;
  clinicalContext.inpatientEncounterId = source.encounter_id;
  clinicalContext.inpatientPatientId = source.patient_id;
}

export function selectAdmittedInpatientContext(overview: InpatientOverviewWire): void {
  selectInpatientContext({
    admission_id: overview.admission.admission_id,
    encounter_id: overview.admission.encounter_id,
    patient_id: overview.admission.patient_id,
  });
}

export async function loadInpatientConsultations(
  lease: ContextLeaseWire,
): Promise<InpatientConsultationWire[]> {
  const payload = await request(
    `/inpatient/admissions/${clinicalContext.inpatientAdmissionId}/consultations`,
    { headers: inpatientHeaders(lease) },
  );
  return inpatientConsultationWireSchema.array().parse(payload);
}

export async function createInpatientConsultation(
  lease: ContextLeaseWire,
  input: {
    requestedDepartment: string;
    urgency: 'ROUTINE' | 'URGENT' | 'EMERGENCY';
    reason: string;
    clinicalQuestion: string;
    dueAt: string;
  },
): Promise<InpatientConsultationWire> {
  return inpatientConsultationWireSchema.parse(await request(
    `/inpatient/admissions/${clinicalContext.inpatientAdmissionId}/consultations`,
    {
      method: 'POST',
      headers: { ...inpatientHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.inpatientPatientId,
        encounter_id: clinicalContext.inpatientEncounterId,
        requested_department: input.requestedDepartment,
        urgency: input.urgency,
        reason: input.reason,
        clinical_question: input.clinicalQuestion,
        due_at: input.dueAt,
      }),
    },
  ));
}

function consultationActionBody(consultation: InpatientConsultationWire) {
  return {
    organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId,
    patient_id: clinicalContext.inpatientPatientId,
    encounter_id: clinicalContext.inpatientEncounterId,
    expected_row_version: consultation.row_version,
  };
}

async function consultationCommand(
  lease: ContextLeaseWire,
  consultation: InpatientConsultationWire,
  action: 'accept' | 'reject' | 'opinions' | 'complete',
  extra: Record<string, unknown> = {},
): Promise<InpatientConsultationWire> {
  return inpatientConsultationWireSchema.parse(await request(
    `/inpatient/consultations/${consultation.consultation_id}/${action}`,
    {
      method: 'POST',
      headers: { ...inpatientHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...consultationActionBody(consultation), ...extra }),
    },
  ));
}

export function acceptInpatientConsultation(
  lease: ContextLeaseWire,
  consultation: InpatientConsultationWire,
) {
  return consultationCommand(lease, consultation, 'accept');
}

export function rejectInpatientConsultation(
  lease: ContextLeaseWire,
  consultation: InpatientConsultationWire,
  reason: string,
) {
  return consultationCommand(lease, consultation, 'reject', { reason });
}

export function signInpatientConsultationOpinion(
  lease: ContextLeaseWire,
  consultation: InpatientConsultationWire,
  opinion: string,
  recommendation: string,
) {
  return consultationCommand(lease, consultation, 'opinions', { opinion, recommendation });
}

export function completeInpatientConsultation(
  lease: ContextLeaseWire,
  consultation: InpatientConsultationWire,
) {
  return consultationCommand(lease, consultation, 'complete');
}

export async function loadInpatientPathwayWorkspace(
  lease: ContextLeaseWire,
): Promise<InpatientPathwayWorkspaceWire> {
  return inpatientPathwayWorkspaceWireSchema.parse(await request(
    `/inpatient/admissions/${clinicalContext.inpatientAdmissionId}/pathway-workspace`,
    { headers: inpatientHeaders(lease) },
  ));
}

export async function enrollInpatientPathway(
  lease: ContextLeaseWire,
  pathwayVersionId: string,
  admissionBasis: string,
): Promise<InpatientPathwayInstanceWire> {
  return inpatientPathwayInstanceWireSchema.parse(await request(
    `/inpatient/admissions/${clinicalContext.inpatientAdmissionId}/pathways`,
    {
      method: 'POST',
      headers: { ...inpatientHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.inpatientPatientId, encounter_id: clinicalContext.inpatientEncounterId,
        pathway_version_id: pathwayVersionId, admission_basis: admissionBasis,
      }),
    },
  ));
}

function pathwayActionBody(instance: InpatientPathwayInstanceWire) {
  return {
    organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
    patient_id: clinicalContext.inpatientPatientId, encounter_id: clinicalContext.inpatientEncounterId,
    expected_row_version: instance.row_version,
  };
}

async function pathwayCommand(
  lease: ContextLeaseWire,
  instance: InpatientPathwayInstanceWire,
  action: 'refresh' | 'advance' | 'complete',
): Promise<InpatientPathwayInstanceWire> {
  return inpatientPathwayInstanceWireSchema.parse(await request(
    `/inpatient/pathways/${instance.pathway_instance_id}/${action}`,
    {
      method: 'POST',
      headers: { ...inpatientHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(pathwayActionBody(instance)),
    },
  ));
}

export function refreshInpatientPathway(lease: ContextLeaseWire, instance: InpatientPathwayInstanceWire) {
  return pathwayCommand(lease, instance, 'refresh');
}

export function advanceInpatientPathway(lease: ContextLeaseWire, instance: InpatientPathwayInstanceWire) {
  return pathwayCommand(lease, instance, 'advance');
}

export function completeInpatientPathway(lease: ContextLeaseWire, instance: InpatientPathwayInstanceWire) {
  return pathwayCommand(lease, instance, 'complete');
}

export async function requestInpatientPathwayVariance(
  lease: ContextLeaseWire,
  instance: InpatientPathwayInstanceWire,
  input: {
    varianceType: 'CONTRAINDICATION' | 'RESOURCE_UNAVAILABLE' | 'PATIENT_REFUSAL' | 'DIAGNOSIS_CHANGED' | 'TASK_FAILED' | 'OTHER';
    reason: string;
    disposition: 'CONTINUE' | 'WAIVE_TASK' | 'EXIT_PATHWAY';
    affectedTaskId?: string | null;
  },
): Promise<InpatientPathwayInstanceWire> {
  return inpatientPathwayInstanceWireSchema.parse(await request(
    `/inpatient/pathways/${instance.pathway_instance_id}/variances`,
    {
      method: 'POST',
      headers: { ...inpatientHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        ...pathwayActionBody(instance), variance_type: input.varianceType, reason: input.reason,
        disposition: input.disposition, affected_task_id: input.affectedTaskId || null,
      }),
    },
  ));
}

export async function reviewInpatientPathwayVariance(
  lease: ContextLeaseWire,
  instance: InpatientPathwayInstanceWire,
  varianceId: string,
  decision: 'APPROVE' | 'REJECT',
  reviewNote: string,
): Promise<InpatientPathwayInstanceWire> {
  return inpatientPathwayInstanceWireSchema.parse(await request(
    `/inpatient/pathways/${instance.pathway_instance_id}/variances/${varianceId}/review`,
    {
      method: 'POST',
      headers: { ...inpatientHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        ...pathwayActionBody(instance), decision, review_note: reviewNote,
      }),
    },
  ));
}

export async function loadInpatientWorklist(lease: ContextLeaseWire): Promise<InpatientWorklistItemWire[]> {
  const payload = await request(`/inpatient/worklist?ward_id=${clinicalContext.inpatientWardId}`, {
    headers: wardHeaders(lease),
  });
  return inpatientWorklistItemWireSchema.array().parse(payload);
}

export async function loadInpatientBedBoard(lease: ContextLeaseWire): Promise<InpatientBedBoardItemWire[]> {
  const payload = await request(`/inpatient/bed-board?ward_id=${clinicalContext.inpatientWardId}`, {
    headers: wardHeaders(lease),
  });
  return inpatientBedBoardItemWireSchema.array().parse(payload);
}

export async function searchPatientsForAdmission(
  lease: ContextLeaseWire,
  query: string,
): Promise<PatientSummaryWire[]> {
  const payload = await request('/patients/search', {
    method: 'POST',
    headers: { ...explicitContextHeaders(lease, null, null), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      purpose_code: 'PATIENT_SEARCH',
      query: query.trim(),
      limit: 20,
    }),
  });
  return patientSummaryWireSchema.array().parse(payload);
}

export async function listPatientEncounters(
  lease: ContextLeaseWire,
  patientId: string,
): Promise<EncounterWire[]> {
  const payload = await request(`/patients/${encodeURIComponent(patientId)}/encounters`, {
    headers: explicitContextHeaders(lease, patientId, null),
  });
  return encounterWireSchema.array().parse(payload);
}

export async function createInpatientEncounterForAdmission(patientId: string): Promise<EncounterWire> {
  const lease = await issueContextLease(patientId, null, 'INPATIENT_ADMISSION');
  return encounterWireSchema.parse(await request('/encounters', {
    method: 'POST',
    headers: {
      ...explicitContextHeaders(lease, patientId, null),
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: patientId,
      encounter_type: 'INPATIENT',
      started_at: new Date().toISOString(),
      source_system: 'HAOEMR_ADMISSION_DESK',
      source_key: `ADMISSION-DESK:${crypto.randomUUID()}`,
    }),
  }));
}

export async function admitInpatientFromBedBoard(
  patientId: string,
  encounterId: string,
  input: {
    bedId: string; wardId: string; departmentId: string; admittedAt: string;
    admissionSource: 'OUTPATIENT' | 'EMERGENCY' | 'TRANSFER' | 'OTHER';
    admissionType: 'ELECTIVE' | 'URGENT' | 'EMERGENCY';
    conditionLevel: 'GENERAL' | 'SERIOUS' | 'CRITICAL';
    diagnosisCode: string; diagnosisText: string; paymentMethodCode: string;
    verificationMethod: 'RESIDENT_ID' | 'MEDICAL_CARD' | 'OTHER';
    contactName: string; contactRelationship: string; contactPhone: string;
    certificateNo: string; transferFrom: string; remarks: string;
  },
): Promise<InpatientOverviewWire> {
  const lease = await issueContextLease(patientId, encounterId, 'INPATIENT_ADMISSION');
  const overview = inpatientOverviewWireSchema.parse(await request('/inpatient/admissions', {
    method: 'POST',
    headers: {
      ...explicitContextHeaders(lease, patientId, encounterId),
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: patientId,
      encounter_id: encounterId,
      ward_id: input.wardId,
      bed_id: input.bedId,
      attending_user_id: getInpatientSyntheticActor()?.userId ?? clinicalContext.userId,
      admitted_at: input.admittedAt,
      department_id: input.departmentId,
      admission_source: input.admissionSource,
      admission_type: input.admissionType,
      condition_level: input.conditionLevel,
      admitting_diagnosis_code: input.diagnosisCode || null,
      admitting_diagnosis_text: input.diagnosisText,
      payment_method_code: input.paymentMethodCode,
      identity_verification_method: input.verificationMethod,
      contact_name: input.contactName,
      contact_relationship: input.contactRelationship,
      contact_phone: input.contactPhone,
      admission_certificate_no: input.certificateNo || null,
      transfer_from: input.transferFrom || null,
      remarks: input.remarks || null,
    }),
  }));
  selectAdmittedInpatientContext(overview);
  return overview;
}

export async function loadInpatientDocumentRules(lease: ContextLeaseWire): Promise<InpatientDocumentRuleWire[]> {
  const payload = await request('/inpatient/document-rules', { headers: wardHeaders(lease) });
  return inpatientDocumentRuleWireSchema.array().parse(payload);
}

export async function createInpatientDocumentTask(
  lease: ContextLeaseWire,
  overview: InpatientOverviewWire,
  ruleCode: string,
  eventOccurredAt: string,
  occurrenceKey: string,
  sourceEventId?: string,
): Promise<InpatientDocumentTaskWire> {
  return inpatientDocumentTaskWireSchema.parse(await request(
    `/inpatient/admissions/${overview.admission.admission_id}/document-tasks`,
    {
      method: 'POST',
      headers: {
        ...inpatientHeaders(lease),
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: overview.admission.patient_id,
        encounter_id: overview.admission.encounter_id,
        rule_code: ruleCode,
        event_occurred_at: eventOccurredAt,
        occurrence_key: occurrenceKey,
        source_event_id: sourceEventId || null,
      }),
    },
  ));
}

export async function createInpatientClinicalEvent(
  lease: ContextLeaseWire,
  overview: InpatientOverviewWire,
  eventType: 'CONSULTATION_REQUESTED' | 'PREOPERATIVE_DECISION' | 'OPERATION_COMPLETED'
    | 'RESCUE_COMPLETED' | 'TRANSFUSION_COMPLETED' | 'CRITICAL_ILLNESS_DECLARED' | 'DEATH_CONFIRMED',
  summary: string,
): Promise<InpatientClinicalEventWire> {
  const occurredAt = new Date().toISOString();
  return inpatientClinicalEventWireSchema.parse(await request(
    `/inpatient/admissions/${overview.admission.admission_id}/clinical-events`,
    {
      method: 'POST',
      headers: {
        ...inpatientHeaders(lease),
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: overview.admission.patient_id,
        encounter_id: overview.admission.encounter_id,
        event_type: eventType,
        occurred_at: occurredAt,
        summary,
        source_system: 'HAOEMR-WEB',
        source_event_key: `${eventType}:${occurredAt}:${crypto.randomUUID()}`,
      }),
    },
  ));
}

export async function startInpatientDocumentTask(
  lease: ContextLeaseWire,
  task: InpatientDocumentTaskWire,
  rule?: InpatientDocumentRuleWire,
): Promise<DocumentVersionWire> {
  const sectionCodes = rule?.template_sections.length
    ? rule.template_sections
    : ['chief_complaint', 'present_illness', 'assessment', 'treatment_plan'];
  return documentVersionWireSchema.parse(await request(
    `/inpatient/document-tasks/${task.task_id}/documents`,
    {
      method: 'POST',
      headers: {
        ...inpatientHeaders(lease),
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.inpatientPatientId,
        encounter_id: clinicalContext.inpatientEncounterId,
        admission_id: clinicalContext.inpatientAdmissionId,
        expected_task_row_version: task.row_version,
        sections: Object.fromEntries(sectionCodes.map((sectionCode) => [sectionCode, ''])),
      }),
    },
  ));
}

export async function loadInpatientDocument(
  lease: ContextLeaseWire,
  documentId: string,
): Promise<DocumentVersionWire> {
  return documentVersionWireSchema.parse(await request(`/documents/${documentId}`, {
    headers: inpatientHeaders(lease),
  }));
}

export async function loadInpatientDocumentVersions(
  lease: ContextLeaseWire,
  documentId: string,
): Promise<DocumentVersionWire[]> {
  const payload = await request(`/documents/${documentId}/versions`, {
    headers: inpatientHeaders(lease),
  });
  return documentVersionWireSchema.array().parse(payload);
}

export async function saveInpatientDocumentDraft(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  sections: Record<string, unknown>,
): Promise<DocumentVersionWire> {
  return documentVersionWireSchema.parse(await request(`/documents/${document.document_id}/draft`, {
    method: 'PUT',
    headers: {
      ...inpatientHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.inpatientPatientId, encounter_id: clinicalContext.inpatientEncounterId,
      expected_row_version: document.row_version, sections,
    }),
  }));
}

export async function runInpatientDocumentQuality(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  triggerStage?: import('./generated/contracts').DocumentQualityCheckRequestWire['trigger_stage'],
): Promise<QualityFindingWire[]> {
  return qualityFindingWireSchema.array().parse(await request(`/documents/${document.document_id}/quality-checks`, {
    method: 'POST',
    headers: { ...inpatientHeaders(lease), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.inpatientPatientId, encounter_id: clinicalContext.inpatientEncounterId,
      document_version_id: document.document_version_id,
      trigger_stage: triggerStage,
    }),
  }));
}

export async function loadInpatientDocumentGovernance(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
): Promise<DocumentGovernanceSnapshotWire> {
  const query = new URLSearchParams({ document_version_id: document.document_version_id });
  return documentGovernanceSnapshotWireSchema.parse(await request(
    `/documents/${document.document_id}/governance?${query}`,
    { headers: inpatientHeaders(lease) },
  ));
}

export async function signInpatientDocument(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  signatureRole: InpatientSyntheticActorKey,
  warningDisposition?: string,
): Promise<SignatureEvidenceWire> {
  return signatureEvidenceWireSchema.parse(await request(`/documents/${document.document_id}/signatures`, {
    method: 'POST',
    headers: {
      ...inpatientHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.inpatientPatientId, encounter_id: clinicalContext.inpatientEncounterId,
      document_version_id: document.document_version_id, expected_row_version: document.row_version,
      signature_role: signatureRole, warning_disposition: warningDisposition?.trim() || null,
    }),
  }));
}

export async function rejectInpatientDocumentReview(
  lease: ContextLeaseWire,
  task: InpatientDocumentTaskWire,
  rejectionLevel: 'ATTENDING' | 'CHIEF' | 'MEDICAL_RECORDS',
  reason: string,
): Promise<void> {
  if (!task.working_document_id) {
    throw new ClinicalApiError('REVIEW_NOT_ACTIVE', '该任务尚未建立住院病历', 409);
  }
  const document = await loadInpatientDocument(lease, task.working_document_id);
  await request(`/documents/${document.document_id}/review-rejections`, {
    method: 'POST',
    headers: {
      ...inpatientHeaders(lease),
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.inpatientPatientId,
      encounter_id: clinicalContext.inpatientEncounterId,
      document_version_id: document.document_version_id,
      expected_row_version: document.row_version,
      rejection_level: rejectionLevel,
      reason,
    }),
  });
}

export async function dischargeInpatient(
  lease: ContextLeaseWire,
  overview: InpatientOverviewWire,
  dischargeDiagnosis: string,
  dispositionCode: 'HOME' | 'TRANSFER_TO_FACILITY' | 'DEATH' | 'OTHER',
): Promise<InpatientOverviewWire> {
  return inpatientOverviewWireSchema.parse(await request(
    `/inpatient/admissions/${overview.admission.admission_id}/discharges`,
    {
      method: 'POST',
      headers: {
        ...inpatientHeaders(lease),
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.inpatientPatientId,
        encounter_id: clinicalContext.inpatientEncounterId,
        expected_admission_row_version: overview.admission.row_version,
        discharge_diagnosis: dischargeDiagnosis,
        disposition_code: dispositionCode,
      }),
    },
  ));
}

export async function loadCurrentDocument(lease: ContextLeaseWire): Promise<DocumentVersionWire> {
  if (clinicalContext.documentId) {
    try {
      return documentVersionWireSchema.parse(await request(`/documents/${clinicalContext.documentId}`, {
        headers: scopedHeaders(lease),
      }));
    } catch (error) {
      if (!(error instanceof ClinicalApiError) || ![403, 404].includes(error.status)) throw error;
      // A document remembered by the browser may have been voided, reassigned, or
      // selected under an older encounter. Never carry that identifier into the
      // active encounter; rediscover the document through the scoped list API.
      selectOutpatientContext({
        patientId: clinicalContext.patientId,
        encounterId: clinicalContext.encounterId,
        patientDisplayName: clinicalContext.patientDisplayName,
        documentId: null,
      });
    }
  }
  const documents = await loadEncounterDocuments(lease);
  const current = documents.find((item) => item.status !== 'VOID') ?? documents[0];
  if (!current) {
    throw new ClinicalApiError('OUTPATIENT_DOCUMENT_NOT_FOUND', '当前就诊尚未建立门诊病历', 404);
  }
  selectOutpatientDocument(current.document_id);
  return current;
}

export async function loadCurrentOutpatientEncounter(lease: ContextLeaseWire): Promise<EncounterWire> {
  return encounterWireSchema.parse(await request(`/encounters/${clinicalContext.encounterId}`, {
    headers: scopedHeaders(lease),
  }));
}

export async function finishCurrentOutpatientEncounter(
  lease: ContextLeaseWire,
  encounter: EncounterWire,
  reason: string,
): Promise<EncounterWire> {
  return encounterWireSchema.parse(await request(
    `/encounters/${clinicalContext.encounterId}/state-transitions`,
    {
      method: 'POST',
      headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(encounterStateTransitionRequestWireSchema.parse({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.patientId,
        expected_row_version: encounter.row_version,
        target_status: 'FINISHED',
        occurred_at: new Date().toISOString(),
        reason: reason.trim(),
      })),
    },
  ));
}

export async function loadDocument(
  lease: ContextLeaseWire,
  documentId: string,
): Promise<DocumentVersionWire> {
  return documentVersionWireSchema.parse(await request(`/documents/${documentId}`, {
    headers: scopedHeaders(lease),
  }));
}

export async function createClinicalDocument(
  lease: ContextLeaseWire,
  documentTypeCode: string,
  sections: Record<string, unknown>,
): Promise<DocumentVersionWire> {
  return documentVersionWireSchema.parse(await request('/documents', {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify(documentCreateRequestWireSchema.parse({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      document_type_code: documentTypeCode.trim(),
      sections,
    })),
  }));
}

export async function voidClinicalDocument(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  reason: string,
): Promise<DocumentVersionWire> {
  return documentVersionWireSchema.parse(await request(`/documents/${document.document_id}/voids`, {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify(documentVoidRequestWireSchema.parse({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      expected_row_version: document.row_version,
      reason: reason.trim(),
    })),
  }));
}

export async function loadDocumentSources(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
): Promise<DocumentSourceBundleWire> {
  return documentSourceBundleWireSchema.parse(await request(
    `/documents/${document.document_id}/sources?document_version_id=${document.document_version_id}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function uploadDocumentAttachment(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  file: File,
  targetFieldPath: string,
  replacement?: { attachmentId: string; reason: string },
): Promise<DocumentAttachmentWire> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  const expectedSha256 = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
  }
  return documentAttachmentWireSchema.parse(await request(`/documents/${document.document_id}/attachments`, {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      document_version_id: document.document_version_id,
      original_filename: file.name,
      media_type: attachmentMediaType(file),
      content_base64: btoa(binary),
      expected_sha256: expectedSha256,
      target_field_path: targetFieldPath,
      replaces_attachment_id: replacement?.attachmentId ?? null,
      replacement_reason: replacement?.reason.trim() || null,
    }),
  }));
}

function attachmentMediaType(file: File): string {
  if (file.type) return file.type;
  const extension = file.name.split('.').pop()?.toLowerCase();
  return ({
    pdf: 'application/pdf', dcm: 'application/dicom', jpg: 'image/jpeg', jpeg: 'image/jpeg',
    png: 'image/png', txt: 'text/plain',
  } as Record<string, string>)[extension ?? ''] ?? 'application/octet-stream';
}

export async function addDocumentSourceReference(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  sourceType: 'DIAGNOSIS' | 'ORDER' | 'RESULT',
  sourceResourceId: string,
  targetFieldPath: string,
  excerpt?: string,
): Promise<DocumentSourceReferenceWire> {
  return documentSourceReferenceWireSchema.parse(await request(`/documents/${document.document_id}/source-references`, {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      document_version_id: document.document_version_id,
      source_type: sourceType,
      source_resource_id: sourceResourceId,
      target_field_path: targetFieldPath,
      excerpt: excerpt?.trim() || null,
    }),
  }));
}

export async function correctDocumentSourceReference(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  sourceReferenceId: string,
  targetFieldPath: string,
  excerpt: string,
  reason: string,
): Promise<DocumentSourceReferenceWire> {
  return documentSourceReferenceWireSchema.parse(await request(
    `/documents/${document.document_id}/source-references/${sourceReferenceId}/corrections`, {
      method: 'POST',
      headers: {
        ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify(documentSourceReferenceCorrectionRequestWireSchema.parse({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.patientId,
        encounter_id: clinicalContext.encounterId,
        document_version_id: document.document_version_id,
        target_field_path: targetFieldPath,
        excerpt: excerpt.trim() || null,
        reason: reason.trim(),
      })),
    },
  ));
}

export async function revokeDocumentSourceReference(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  sourceReferenceId: string,
  reason: string,
): Promise<DocumentSourceReferenceWire> {
  return documentSourceReferenceWireSchema.parse(await request(
    `/documents/${document.document_id}/source-references/${sourceReferenceId}/revocations`, {
      method: 'POST',
      headers: {
        ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify(documentEvidenceLifecycleRequestWireSchema.parse({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.patientId,
        encounter_id: clinicalContext.encounterId,
        document_version_id: document.document_version_id,
        reason: reason.trim(),
      })),
    },
  ));
}

export async function voidDocumentAttachment(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  attachmentId: string,
  reason: string,
): Promise<DocumentAttachmentWire> {
  return documentAttachmentWireSchema.parse(await request(
    `/documents/${document.document_id}/attachments/${attachmentId}/voids`, {
      method: 'POST',
      headers: {
        ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify(documentEvidenceLifecycleRequestWireSchema.parse({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.patientId,
        encounter_id: clinicalContext.encounterId,
        document_version_id: document.document_version_id,
        reason: reason.trim(),
      })),
    },
  ));
}

export async function loadEncounterDocuments(
  lease: ContextLeaseWire,
  encounterId = clinicalContext.encounterId,
  patientId = clinicalContext.patientId,
): Promise<DocumentVersionWire[]> {
  const payload = await request(`/encounters/${encounterId}/documents`, {
    headers: explicitContextHeaders(lease, patientId, encounterId),
  });
  return documentVersionWireSchema.array().parse(payload);
}

export async function loadDocumentVersions(
  lease: ContextLeaseWire,
  documentId: string,
): Promise<DocumentVersionWire[]> {
  const payload = await request(`/documents/${documentId}/versions`, {
    headers: scopedHeaders(lease),
  });
  return documentVersionWireSchema.array().parse(payload);
}

export async function loadDocumentDiff(
  lease: ContextLeaseWire,
  documentId: string,
  fromVersionId: string,
  toVersionId: string,
): Promise<DocumentDiffWire> {
  const query = new URLSearchParams({
    from_version_id: fromVersionId,
    to_version_id: toVersionId,
  });
  return documentDiffWireSchema.parse(await request(`/documents/${documentId}/diff?${query}`, {
    headers: scopedHeaders(lease),
  }));
}

export async function loadDocumentGovernance(
  lease: ContextLeaseWire,
  documentId: string,
  documentVersionId: string,
): Promise<DocumentGovernanceSnapshotWire> {
  const query = new URLSearchParams({ document_version_id: documentVersionId });
  return documentGovernanceSnapshotWireSchema.parse(await request(
    `/documents/${documentId}/governance?${query}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function loadDocumentCorrections(
  lease: ContextLeaseWire,
  documentId: string,
): Promise<DocumentCorrectionWire[]> {
  return documentCorrectionWireSchema.array().parse(await request(`/documents/${documentId}/corrections`, {
    headers: scopedHeaders(lease),
  }));
}

export async function createDocumentCorrection(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  correctionType: 'CORRECTION' | 'ADDENDUM',
  reason: string,
  sections: Record<string, unknown>,
): Promise<DocumentCorrectionWire> {
  return documentCorrectionWireSchema.parse(await request(`/documents/${document.document_id}/corrections`, {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease),
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      source_document_version_id: document.document_version_id,
      expected_row_version: document.row_version,
      correction_type: correctionType,
      reason: reason.trim(),
      sections,
    }),
  }));
}

export async function revokeDocumentSignature(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  signatureId: string,
  reason: string,
): Promise<SignatureRevocationEvidenceWire> {
  return signatureRevocationEvidenceWireSchema.parse(await request(`/documents/${document.document_id}/signature-revocations`, {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease),
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      signature_id: signatureId,
      expected_document_row_version: document.row_version,
      reason: reason.trim(),
    }),
  }));
}

export async function retryDocumentCorrectionPropagation(
  lease: ContextLeaseWire,
  documentId: string,
  propagation: DocumentCorrectionPropagationWire,
): Promise<DocumentCorrectionPropagationWire> {
  return documentCorrectionPropagationWireSchema.parse(await request(
    `/documents/${documentId}/correction-propagations/${propagation.propagation_id}/retry`,
    {
      method: 'POST',
      headers: {
        ...scopedHeaders(lease),
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        patient_id: clinicalContext.patientId,
        encounter_id: clinicalContext.encounterId,
        expected_row_version: propagation.row_version,
      }),
    },
  ));
}

export async function saveDocumentDraft(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  sections: Record<string, unknown>,
  sourceMedicalAgentRunId?: string | null,
): Promise<DocumentVersionWire> {
  return documentVersionWireSchema.parse(await request(`/documents/${document.document_id}/draft`, {
    method: 'PUT',
    headers: {
      ...scopedHeaders(lease),
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      expected_row_version: document.row_version,
      sections,
      source_medical_agent_run_id: sourceMedicalAgentRunId ?? null,
    }),
  }));
}

export async function runQualityChecks(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  triggerStage?: import('./generated/contracts').DocumentQualityCheckRequestWire['trigger_stage'],
): Promise<QualityFindingWire[]> {
  const payload = await request(`/documents/${document.document_id}/quality-checks`, {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      document_version_id: document.document_version_id,
      trigger_stage: triggerStage,
    }),
  });
  return qualityFindingWireSchema.array().parse(payload);
}

export async function startAiDraft(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
): Promise<AIRunSnapshotWire> {
  return aiRunSnapshotWireSchema.parse(await request('/ai/runs', {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease),
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      context_lease_id: lease.lease_id,
      use_case_code: 'DOCUMENT_DRAFT_ASSIST',
      document_id: document.document_id,
      document_version_id: document.document_version_id,
    }),
  }));
}

export async function decideProposal(
  lease: ContextLeaseWire,
  proposal: AIProposalWire,
  decision: 'ACCEPTED' | 'MODIFIED' | 'REJECTED',
): Promise<AIProposalWire> {
  return aiProposalWireSchema.parse(await request(`/ai/proposals/${proposal.proposal_id}/decisions`, {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      expected_row_version: proposal.row_version,
      decision,
      reason: '医生在病历编辑器中人工审阅',
    }),
  }));
}

export async function signDocument(
  lease: ContextLeaseWire,
  document: DocumentVersionWire,
  warningDisposition?: string,
): Promise<SignatureEvidenceWire> {
  return signatureEvidenceWireSchema.parse(await request(`/documents/${document.document_id}/signatures`, {
    method: 'POST',
    headers: {
      ...scopedHeaders(lease),
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      patient_id: clinicalContext.patientId,
      encounter_id: clinicalContext.encounterId,
      document_version_id: document.document_version_id,
      expected_row_version: document.row_version,
      signature_role: 'ATTENDING',
      warning_disposition: warningDisposition || null,
    }),
  }));
}

export async function loadOrganizationUnits(): Promise<OrganizationUnitWire[]> {
  return organizationUnitWireSchema.array().parse(await adminRequest('/admin/organization-units'));
}

export async function createOrganizationUnit(
  input: OrganizationUnitCreateRequestWire,
): Promise<OrganizationUnitWire> {
  return organizationUnitWireSchema.parse(await adminRequest('/admin/organization-units', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(input),
  }));
}

export async function deactivateOrganizationUnit(unit: OrganizationUnitWire, reason: string): Promise<OrganizationUnitWire> {
  return organizationUnitWireSchema.parse(await adminRequest(
    `/admin/organization-units/${unit.unit_type}/${unit.unit_id}/deactivate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        expected_row_version: unit.row_version,
        effective_until: new Date().toISOString(),
        reason,
      }),
    },
  ));
}

export async function loadWorkforceIdentities(): Promise<WorkforceIdentityWire[]> {
  return workforceIdentityWireSchema.array().parse(await adminRequest('/admin/workforce'));
}

export async function onboardWorkforceIdentity(input: WorkforceOnboardingRequestWire): Promise<WorkforceIdentityWire> {
  return workforceIdentityWireSchema.parse(await adminRequest('/admin/workforce', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(input),
  }));
}

export async function deactivateWorkforceAccount(identity: WorkforceIdentityWire, reason: string): Promise<WorkforceIdentityWire> {
  if (!identity.user_id) throw new ClinicalApiError('ACCOUNT_REQUIRED', '当前人员没有可停用的账号', 409);
  return workforceIdentityWireSchema.parse(await adminRequest(`/admin/workforce/accounts/${identity.user_id}/deactivate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ expected_row_version: identity.account_row_version, reason }),
  }));
}

export async function endWorkforceRole(identity: WorkforceIdentityWire, reason: string): Promise<WorkforceIdentityWire> {
  if (!identity.role_assignment_id) throw new ClinicalApiError('ROLE_ASSIGNMENT_REQUIRED', '当前人员没有可结束的角色', 409);
  return workforceIdentityWireSchema.parse(await adminRequest(
    `/admin/workforce/role-assignments/${identity.role_assignment_id}/end`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        expected_row_version: identity.role_row_version,
        effective_until: new Date().toISOString(),
        reason,
      }),
    },
  ));
}

export async function assignWorkforceRole(input: {
  role_assignment_id: string;
  user_id: string;
  person_id: string;
  role_code: string;
  position_code: string;
  organization_id: string;
  facility_id: string;
  department_id?: string;
  ward_id?: string;
  valid_from: string;
  valid_until?: string;
}): Promise<WorkforceIdentityWire> {
  return workforceIdentityWireSchema.parse(await adminRequest('/admin/workforce/role-assignments', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(input),
  }));
}

export async function loadAuthorizationPolicies(): Promise<AuthorizationPolicyWire[]> {
  return authorizationPolicyWireSchema.array().parse(await adminRequest('/admin/access-policies'));
}

export async function createAuthorizationPolicy(input: AuthorizationPolicyCreateRequestWire): Promise<AuthorizationPolicyWire> {
  return authorizationPolicyWireSchema.parse(await adminRequest('/admin/access-policies', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(input),
  }));
}

export async function publishAuthorizationPolicy(policy: AuthorizationPolicyWire): Promise<AuthorizationPolicyWire> {
  return authorizationPolicyWireSchema.parse(await adminRequest(`/admin/access-policies/${policy.policy_id}/publish`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ expected_row_version: policy.row_version }),
  }));
}

export async function simulateAuthorization(input: AuthorizationSimulationRequestWire): Promise<AuthorizationDecisionWire> {
  return authorizationDecisionWireSchema.parse(await adminRequest('/admin/access-simulations', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input),
  }));
}

export async function loadOwnEmergencyAccess(): Promise<EmergencyAccessGrantWire[]> {
  return emergencyAccessGrantWireSchema.array().parse(await request('/emergency-access-grants'));
}

export async function requestEmergencyAccess(input: EmergencyAccessRequestWire): Promise<EmergencyAccessGrantWire> {
  return emergencyAccessGrantWireSchema.parse(await request('/emergency-access-grants', {
    method: 'POST', headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      ...(import.meta.env.DEV ? { 'X-HaoEMR-Synthetic-Reauthenticated-At': new Date().toISOString() } : {}),
    },
    body: JSON.stringify(input),
  }));
}

export async function loadEmergencyAccessForReview(): Promise<EmergencyAccessGrantWire[]> {
  return emergencyAccessGrantWireSchema.array().parse(await adminRequest('/admin/emergency-access-grants'));
}

export async function reviewEmergencyAccess(
  grant: EmergencyAccessGrantWire,
  outcome: 'APPROPRIATE' | 'INAPPROPRIATE' | 'ESCALATED',
  note: string,
): Promise<EmergencyAccessGrantWire> {
  return emergencyAccessGrantWireSchema.parse(await adminRequest(
    `/admin/emergency-access-grants/${grant.emergency_access_grant_id}/reviews`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ expected_row_version: grant.row_version, outcome, note }),
    },
  ));
}

export async function loadPatientMatchCandidates(
  status: 'OPEN' | 'DISMISSED' | 'MERGE_REQUESTED' | 'MERGED' = 'OPEN',
): Promise<PatientMatchCandidateWire[]> {
  return patientMatchCandidateWireSchema.array().parse(
    await adminRequest(`/patient-match-candidates?status=${encodeURIComponent(status)}`),
  );
}

export async function detectPatientMatchCandidate(
  input: PatientMatchCandidateCreateRequestWire,
): Promise<PatientMatchCandidateWire> {
  return patientMatchCandidateWireSchema.parse(await adminRequest('/patient-match-candidates', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(input),
  }));
}

export async function loadPatientDemographicVersions(patientId: string): Promise<PatientDemographicVersionWire[]> {
  return patientDemographicVersionWireSchema.array().parse(
    await adminRequest(`/patients/${patientId}/demographic-versions`),
  );
}

export async function correctPatientDemographics(
  patientId: string,
  input: PatientDemographicCorrectionRequestWire,
): Promise<PatientDemographicVersionWire> {
  return patientDemographicVersionWireSchema.parse(await adminRequest(
    `/patients/${patientId}/identity-corrections`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(input),
    },
  ));
}

export async function loadPatientMergeCases(status?: PatientMergeCaseWire['status']): Promise<PatientMergeCaseWire[]> {
  return patientMergeCaseWireSchema.array().parse(await adminRequest(
    `/patient-merge-cases${status ? `?status=${encodeURIComponent(status)}` : ''}`,
  ));
}

export async function requestPatientMerge(input: PatientMergeCaseCreateRequestWire): Promise<PatientMergeCaseWire> {
  return patientMergeCaseWireSchema.parse(await adminRequest('/patient-merge-cases', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(input),
  }));
}

export async function approvePatientMerge(mergeCase: PatientMergeCaseWire): Promise<PatientMergeCaseWire> {
  return patientMergeCaseWireSchema.parse(await adminRequest(
    `/patient-merge-cases/${mergeCase.merge_case_id}/approve`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        expected_row_version: mergeCase.row_version,
        confirm_no_clinical_data_loss: true,
      }),
    },
  ));
}

export async function requestPatientMergeReversal(
  mergeCase: PatientMergeCaseWire,
  reason: string,
): Promise<PatientMergeCaseWire> {
  return patientMergeCaseWireSchema.parse(await adminRequest(
    `/patient-merge-cases/${mergeCase.merge_case_id}/reversal-requests`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ expected_row_version: mergeCase.row_version, reason }),
    },
  ));
}

export async function approvePatientMergeReversal(mergeCase: PatientMergeCaseWire): Promise<PatientMergeCaseWire> {
  return patientMergeCaseWireSchema.parse(await adminRequest(
    `/patient-merge-cases/${mergeCase.merge_case_id}/reversal-approve`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        expected_row_version: mergeCase.row_version,
        confirm_links_remain_traceable: true,
      }),
    },
  ));
}

export async function loadDocumentTemplates(): Promise<DocumentTemplateWire[]> {
  return documentTemplateWireSchema.array().parse(await adminRequest('/admin/document-templates'));
}

export async function createDocumentTemplate(
  input: DocumentTemplateCreateRequestWire,
): Promise<DocumentTemplateWire> {
  return documentTemplateWireSchema.parse(await adminRequest('/admin/document-templates', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(input),
  }));
}

export async function createDocumentTemplateVersion(
  template: DocumentTemplateWire,
  input: Omit<DocumentTemplateVersionCreateRequestWire, 'expected_template_row_version'>,
): Promise<DocumentTemplateWire> {
  return documentTemplateWireSchema.parse(await adminRequest(
    `/admin/document-templates/${template.template_id}/versions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...input, expected_template_row_version: template.template_row_version }),
    },
  ));
}

export async function publishDocumentTemplateVersion(template: DocumentTemplateWire): Promise<DocumentTemplateWire> {
  return documentTemplateWireSchema.parse(await adminRequest(
    `/admin/document-templates/${template.template_id}/versions/${template.template_version_id}/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        expected_version_row_version: template.version_row_version,
        effective_from: new Date().toISOString(),
      }),
    },
  ));
}

export async function deactivateDocumentTemplate(
  template: DocumentTemplateWire,
  reason: string,
): Promise<DocumentTemplateWire> {
  return documentTemplateWireSchema.parse(await adminRequest(
    `/admin/document-templates/${template.template_id}/deactivate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ expected_template_row_version: template.template_row_version, reason }),
    },
  ));
}
