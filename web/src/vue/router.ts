import { createMemoryHistory, createRouter, createWebHashHistory, type RouterHistory, type RouteRecordRaw } from 'vue-router';

import { nativeVueRouteIds, routeRegistry, specialtyGuardRouteIds } from './route-registry';
import { executionPatientFlowRouteIds } from './execution-patient-flow';
import { clearInpatientSyntheticActor } from '../clinical-api';
import { authSession } from '../auth-session';

const plannedComponent = () => import('./views/PlannedRoutePage.vue');
const specialtyGuardComponent = () => import('./views/SpecialtySupportGuardPage.vue');
const executionPatientFlowComponent = () => import('./views/ExecutionPatientRoutePage.vue');
const nativeComponents: Record<string, () => Promise<unknown>> = {
  clinical: () => import('./views/ClinicalPortalPage.vue'),
  admin: () => import('./views/AdminWorkspacePage.vue'),
  'admission-bed': () => import('./views/AdmissionBedPage.vue'),
  outpatient: () => import('./views/OutpatientWorkspacePage.vue'),
  'opd-record': () => import('./views/OutpatientRecordPage.vue'),
  record: () => import('./views/RecordCenterPage.vue'),
  'record-qc': () => import('./views/RecordGovernancePage.vue'),
  'record-sign': () => import('./views/RecordGovernancePage.vue'),
  'record-sources': () => import('./views/RecordSourcesPage.vue'),
  inpatient: () => import('./views/InpatientWorkspacePage.vue'),
  'inpatient-overview': () => import('./views/InpatientJourneyPage.vue'),
  'inpatient-course': () => import('./views/InpatientJourneyPage.vue'),
  'inpatient-doc-editor': () => import('./views/InpatientDocumentEditorPage.vue'),
  'inpatient-doc-qc': () => import('./views/InpatientDocumentEditorPage.vue'),
  'inpatient-doc-versions': () => import('./views/InpatientJourneyPage.vue'),
  'inpatient-discharge': () => import('./views/InpatientJourneyPage.vue'),
  'record-versions': () => import('./views/RecordVersionsPage.vue'),
  'record-diff': () => import('./views/RecordDiffPage.vue'),
  'record-editor': () => import('./views/RecordEditorPage.vue'),
  'lis-report': () => import('./views/LisReportPage.vue'),
  'pacs-viewer': () => import('./views/PacsViewerPage.vue'),
  'archive-assets': () => import('./views/ArchiveAssetsPage.vue'),
  'archive-borrow': () => import('./views/ArchiveBorrowPage.vue'),
  'archive-catalog': () => import('./views/ArchiveCatalogPage.vue'),
  'archive-integrity': () => import('./views/ArchiveIntegrityPage.vue'),
  'asset-detail': () => import('./views/AssetDetailPage.vue'),
  'opd-orders': () => import('./views/OrdersWorkspacePage.vue'),
  'ip-orders': () => import('./views/OrdersWorkspacePage.vue'),
  'opd-diagnosis': () => import('./views/DiagnosisWorkspacePage.vue'),
  'clinical-tasks': () => import('./views/ClinicalTasksPage.vue'),
  'opd-results': () => import('./views/ResultsWorkspacePage.vue'),
  'ip-results': () => import('./views/ResultsWorkspacePage.vue'),
  'ip-consult': () => import('./views/InpatientConsultationPage.vue'),
  'ip-pathway': () => import('./views/InpatientPathwayPage.vue'),
  'admin-org': () => import('./views/OrganizationAdministrationPage.vue'),
  'admin-users': () => import('./views/WorkforceAdministrationPage.vue'),
  'admin-permissions': () => import('./views/AuthorizationAdministrationPage.vue'),
  'admin-templates': () => import('./views/DocumentTemplateAdministrationPage.vue'),
  'admin-dictionaries': () => import('./views/DictionaryAdministrationPage.vue'),
  'admin-audit': () => import('./views/AdminAuditPage.vue'),
  'capability-pack': () => import('./views/CapabilityPackPage.vue'),
  integration: () => import('./views/IntegrationPage.vue'),
  'migration': () => import('./views/MigrationPage.vue'),
  models: () => import('./views/ModelDeploymentPage.vue'),
  'agent-catalog': () => import('./views/AgentCatalogPage.vue'),
  'skill-catalog': () => import('./views/SkillCatalogPage.vue'),
  'tool-catalog': () => import('./views/ToolCatalogPage.vue'),
  'model-evaluation': () => import('./views/ModelEvaluationPage.vue'),
  aiops: () => import('./views/AiOpsPage.vue'),
  'data-quality': () => import('./views/DataQualityPage.vue'),
  'cohort-builder': () => import('./views/CohortBuilderPage.vue'),
  'research-dataset': () => import('./views/ResearchDatasetPage.vue'),
  opensource: () => import('./views/OpenSourcePage.vue'),
  'emergency-access': () => import('./views/EmergencyAccessPage.vue'),
  'patient-registry': () => import('./views/PatientRegistryPage.vue'),
  'patient-merge': () => import('./views/PatientMergePage.vue'),
  'patient-timeline': () => import('./views/PatientTimelinePage.vue'),
  'appointment-registration': () => import('./views/AppointmentRegistrationPage.vue'),
  emergency: () => import('./views/EmergencyWorkspacePage.vue'),
  'er-triage': () => import('./views/EmergencyTriagePage.vue'),
  'er-observation': () => import('./views/EmergencyObservationPage.vue'),
  'er-record': () => import('./views/EmergencyRecordPage.vue'),
  'er-nursing': () => import('./views/EmergencyNursingPage.vue'),
  'er-handoff': () => import('./views/EmergencyHandoffPage.vue'),
  'er-patient-overview': () => import('./views/EmergencyJourneyDepthPage.vue'),
  'er-clinical-timeline': () => import('./views/EmergencyJourneyDepthPage.vue'),
  'er-safety-gates': () => import('./views/EmergencyJourneyDepthPage.vue'),
  'er-transfer-readiness': () => import('./views/EmergencyJourneyDepthPage.vue'),
  'er-evidence-ledger': () => import('./views/EmergencyJourneyDepthPage.vue'),
  'opd-consult': () => import('./views/OutpatientConsultPage.vue'),
  billing: () => import('./views/BillingPage.vue'),
  'outpatient-pharmacy': () => import('./views/OutpatientPharmacyPage.vue'),
  'inpatient-pharmacy': () => import('./views/InpatientPharmacyPage.vue'),
  'lab-workbench': () => import('./views/LabWorkbenchPage.vue'),
  'imaging-workbench': () => import('./views/ImagingWorkbenchPage.vue'),
  transfusion: () => import('./views/TransfusionPage.vue'),
  'surgery-schedule': () => import('./views/SurgerySchedulePage.vue'),
  'care-operations': () => import('./views/CareOperationsPage.vue'),
  ward: () => import('./views/WardPage.vue'),
  'infection-events': () => import('./views/InfectionEventsOverviewPage.vue'),
  'quality-rating': () => import('./views/QualityRatingOverviewPage.vue'),
  credentials: () => import('./views/CredentialsOverviewPage.vue'),
  'specialty-center': () => import('./views/SpecialtyCenterPage.vue'),
  'obgyn-record': () => import('./views/ObstetricRecordPage.vue'),
  'reproductive-record': () => import('./views/ReproductiveRecordPage.vue'),
  'pediatrics-record': () => import('./views/PediatricRecordPage.vue'),
  'neonatal-record': () => import('./views/NeonatalRecordPage.vue'),
  'mental-record': () => import('./views/MentalHealthRecordPage.vue'),
  'ophthalmology-record': () => import('./views/OphthalmologyRecordPage.vue'),
  'ent-record': () => import('./views/EntRecordPage.vue'),
  'dental-record': () => import('./views/DentalRecordPage.vue'),
  'dermatology-record': () => import('./views/DermatologyRecordPage.vue'),
  'tcm-record': () => import('./views/TcmRecordPage.vue'),
  'obgyn-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'reproductive-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'pediatrics-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'neonatal-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'mental-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'ophthalmology-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'ent-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'dental-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'dermatology-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'tcm-workbench': () => import('./views/SpecialtyWorkbenchPage.vue'),
  'obgyn-treatment': () => import('./views/ObstetricDeliveryPage.vue'),
  'obgyn-evidence': () => import('./views/ObstetricAntenatalExamPage.vue'),
  'obgyn-followup': () => import('./views/ObstetricPostpartumFollowupPage.vue'),
  'obgyn-qc': () => import('./views/ObstetricQcReviewPage.vue'),
  'reproductive-treatment': () => import('./views/ReproductiveTransferPage.vue'),
  'reproductive-followup': () => import('./views/ReproductiveOutcomePage.vue'),
  'pediatrics-care': () => import('./views/PediatricGrowthPage.vue'),
  'pediatrics-followup': () => import('./views/PediatricFollowupPage.vue'),
  'neonatal-care': () => import('./views/NeonatalWristbandPage.vue'),
  'neonatal-evidence': () => import('./views/NeonatalScreeningPage.vue'),
  'mental-care': () => import('./views/MentalCrisisHandoverPage.vue'),
  'mental-followup': () => import('./views/MentalCrisisFollowupPage.vue'),
  'ophthalmology-treatment': () => import('./views/OphthalmologyPreopPage.vue'),
  'ophthalmology-followup': () => import('./views/OphthalmologyPostopPage.vue'),
  'ent-care': () => import('./views/EntAirwayHandoverPage.vue'),
  'dental-treatment': () => import('./views/DentalTreatmentPage.vue'),
  'dermatology-treatment': () => import('./views/DermatologyScreeningPage.vue'),
  'dermatology-followup': () => import('./views/DermatologyFollowupPage.vue'),
  'tcm-treatment': () => import('./views/TcmHerbalPage.vue'),
  'tcm-evidence': () => import('./views/TcmFourExamPage.vue'),
  'tcm-qc': () => import('./views/TcmQcPage.vue'),
  'reproductive-qc': () => import('./views/ReproductiveQcReviewPage.vue'),
  'pediatrics-qc': () => import('./views/PediatricQcReviewPage.vue'),
  'neonatal-qc': () => import('./views/NeonatalQcReviewPage.vue'),
  'mental-qc': () => import('./views/MentalHealthQcReviewPage.vue'),
  'ophthalmology-qc': () => import('./views/OphthalmologyQcReviewPage.vue'),
  'ent-qc': () => import('./views/EntQcReviewPage.vue'),
  'dental-qc': () => import('./views/DentalQcReviewPage.vue'),
  'dermatology-qc': () => import('./views/DermatologyQcReviewPage.vue'),
  'neonatal-followup': () => import('./views/NeonatalFollowupPage.vue'),
  'ent-followup': () => import('./views/EntFollowupPage.vue'),
  'dental-followup': () => import('./views/DentalFollowupPage.vue'),
  'tcm-followup': () => import('./views/TcmFollowupPage.vue'),
  'obgyn-care': () => import('./views/ObstetricCareNotePage.vue'),
  'reproductive-care': () => import('./views/ReproductiveCareNotePage.vue'),
  'ophthalmology-care': () => import('./views/OphthalmologyCareNotePage.vue'),
  'dental-care': () => import('./views/DentalCareNotePage.vue'),
  'dermatology-care': () => import('./views/DermatologyCareNotePage.vue'),
  'tcm-care': () => import('./views/TcmCareNotePage.vue'),
  'reproductive-evidence': () => import('./views/ReproductiveEvidencePage.vue'),
  'pediatrics-evidence': () => import('./views/PediatricEvidencePage.vue'),
  'mental-evidence': () => import('./views/MentalHealthEvidencePage.vue'),
  'ophthalmology-evidence': () => import('./views/OphthalmologyEvidencePage.vue'),
  'ent-evidence': () => import('./views/EntEvidencePage.vue'),
  'dental-evidence': () => import('./views/DentalEvidencePage.vue'),
  'dermatology-evidence': () => import('./views/DermatologyEvidencePage.vue'),
  'pediatrics-treatment': () => import('./views/PediatricTreatmentPage.vue'),
  'neonatal-treatment': () => import('./views/NeonatalTreatmentPage.vue'),
  'mental-treatment': () => import('./views/MentalHealthTreatmentPage.vue'),
  'ent-treatment': () => import('./views/EntTreatmentPage.vue'),
  'ai-reminder-detail': () => import('./views/AiReminderDetailPage.vue'),
  'ai-assistant': () => import('./views/AiAssistantPage.vue'),
  'integration-mapping': () => import('./views/IntegrationMappingPage.vue'),
  'specialty-coverage': () => import('./views/SpecialtyCoveragePage.vue'),
  'admin-roles': () => import('./views/AdminRolesPage.vue'),
  'admin-auth': () => import('./views/AdminAuthPage.vue'),
  'admin-jobs': () => import('./views/AdminJobsPage.vue'),
  'admin-master-data': () => import('./views/AdminMasterDataPage.vue'),
  'admin-parameters': () => import('./views/AdminParametersPage.vue'),
  // Historical “agent” URL now shows the maintained medical-agent evidence ledger.
  // Keep the route stable, but do not maintain a second, legacy AIRunSnapshot table.
  'agent': () => import('./views/AiCapturePage.vue'),
  'agent-compose': () => import('./views/AgentComposePage.vue'),
  'agent-context': () => import('./views/AgentContextPage.vue'),
  'agent-evals': () => import('./views/AgentEvalsPage.vue'),
  'ai-capture': () => import('./views/AiCapturePage.vue'),
  'anesthesia-workbench': () => import('./views/AnesthesiaWorkbenchPage.vue'),
  'archive-preservation': () => import('./views/ArchivePreservationPage.vue'),
  'archive-scan': () => import('./views/ArchiveScanPage.vue'),
  'backup': () => import('./views/BackupPage.vue'),
  'config-release': () => import('./views/ConfigReleasePage.vue'),
  'config-upgrade': () => import('./views/ConfigUpgradePage.vue'),
  'data-center': () => import('./views/DataCenterPage.vue'),
  'knowledge-center': () => import('./views/KnowledgeCenterPage.vue'),
  'pathway-graph': () => import('./views/PathwayGraphPage.vue'),
  'pathway-review': () => import('./views/PathwayReviewPage.vue'),
  'pathway-versions': () => import('./views/PathwayVersionsPage.vue'),
  'department-qc': () => import('./views/DepartmentQcPage.vue'),
  'device-monitoring': () => import('./views/DeviceMonitoringPage.vue'),
  'devices': () => import('./views/DevicesPage.vue'),
  'form-designer': () => import('./views/FormDesignerPage.vue'),
  'install': () => import('./views/InstallPage.vue'),
  'integration-connectors': () => import('./views/IntegrationConnectorsPage.vue'),
  'integration-messages': () => import('./views/IntegrationMessagesPage.vue'),
  'login-context': () => import('./views/LoginContextPage.vue'),
  'model-connection': () => import('./views/ModelConnectionPage.vue'),
  'model-routing': () => import('./views/ModelRoutingPage.vue'),
  'opd-followup': () => import('./views/OpdFollowupPage.vue'),
  'operations': () => import('./views/OperationsPage.vue'),
  'pathology-workbench': () => import('./views/PathologyWorkbenchPage.vue'),
  'quality-center': () => import('./views/QualityCenterPage.vue'),
  'release-gates': () => import('./views/ReleaseGatesPage.vue'),
  'research': () => import('./views/ResearchPage.vue'),
  'research-stats': () => import('./views/ResearchStatsPage.vue'),
  'rule-center': () => import('./views/RuleCenterPage.vue'),
  'scope-designer': () => import('./views/ScopeDesignerPage.vue'),
  'therapy-workbench': () => import('./views/TherapyWorkbenchPage.vue'),
  'unified-home': () => import('./views/UnifiedHomePage.vue'),
  'workflow': () => import('./views/WorkflowPage.vue'),
};

function vuePath(routeId: string): string {
  if (routeId === 'record-diff') return '/record-diff/:documentId?/:fromVersionId?/:toVersionId?';
  if (routeId === 'login-context') return '/login';
  return `/${routeId}`;
}

export function buildContractRoutes(): RouteRecordRaw[] {
  return routeRegistry.map((definition) => ({
    path: vuePath(definition.route_id),
    name: definition.route_id,
    ...(definition.route_id === 'clinical-tasks'
      ? { alias: ['/tasks'] }
      : definition.route_id === 'login-context' ? { alias: ['/login-context'] } : {}),
    component: (executionPatientFlowRouteIds.has(definition.route_id) ? executionPatientFlowComponent : nativeComponents[definition.route_id])
      ?? (specialtyGuardRouteIds.has(definition.route_id) ? specialtyGuardComponent : undefined)
      ?? plannedComponent,
    meta: {
      contractId: definition.route_id,
      primaryDomain: definition.route_id === 'login-context' ? 'SYSTEM' : definition.primary_domain,
      guards: definition.route_id === 'login-context' ? [] : definition.guards,
      layout: definition.route_id === 'login-context' ? 'SYSTEM_AUTH' : 'APPLICATION',
      publicRoute: definition.route_id === 'login-context',
      implementation: nativeVueRouteIds.has(definition.route_id)
        ? 'VUE_NATIVE'
        : (specialtyGuardRouteIds.has(definition.route_id)
          ? 'SUPPORT_GUARD'
          : 'NOT_AVAILABLE'),
    },
  }));
}

function defaultHistory(): RouterHistory {
  if (typeof window === 'undefined') return createMemoryHistory();
  // 兼容早期高保真入口的 `#ai-center` 链接，统一转换为 Vue Router 的 `#/ai-center`。
  if (window.location.hash.length > 1 && !window.location.hash.startsWith('#/')) {
    const legacyRoute = window.location.hash.slice(1).replace(/^\/+/, '');
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}#/${legacyRoute}`);
  }
  return createWebHashHistory();
}

const qualityDepthRoutes: RouteRecordRaw[] = [
  { path: '/quality-center/initiatives/:parentId', moduleId: 'quality-center', contractId: 'quality-center' },
  { path: '/department-qc/cases/:parentId', moduleId: 'department-qc', contractId: 'department-qc' },
  { path: '/quality-rating/assessments/:parentId', moduleId: 'quality-rating', contractId: 'quality-rating' },
  { path: '/infection-events/clues/:parentId', moduleId: 'infection-events', contractId: 'infection-events' },
  { path: '/credentials/grants/:parentId', moduleId: 'credentials', contractId: 'credentials' },
  { path: '/archive-assets/:parentId', moduleId: 'archive-assets', contractId: 'archive-assets' },
].flatMap(({ path, moduleId, contractId }) => [
  { suffix: 'actions', level: 5 }, { suffix: 'evidence', level: 6 }, { suffix: 'reviews', level: 7 },
].map(({ suffix, level }): RouteRecordRaw => ({
  path: `${path}/${suffix}`,
  name: `${moduleId}-depth-${level}`,
  component: () => import('./views/QualityGovernanceDepthPage.vue'),
  props: (route) => ({ moduleId, parentId: String(route.params.parentId), level }),
  meta: { contractId, primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE', 'SCOPE'], implementation: 'VUE_NATIVE' },
})));

const emergencyDepthRoutes: RouteRecordRaw[] = [
  { path: '/er-patient-overview', name: 'er-patient-overview' },
  { path: '/er-clinical-timeline', name: 'er-clinical-timeline' },
  { path: '/er-safety-gates', name: 'er-safety-gates' },
  { path: '/er-transfer-readiness', name: 'er-transfer-readiness' },
  { path: '/er-evidence-ledger', name: 'er-evidence-ledger' },
].map((definition): RouteRecordRaw => ({
  path: definition.path,
  name: definition.name,
  component: () => import('./views/EmergencyJourneyDepthPage.vue'),
  meta: {
    contractId: String(definition.name),
    primaryDomain: 'CLINICAL',
    guards: ['SESSION', 'ROLE', 'PATIENT_CONTEXT'],
    implementation: 'VUE_NATIVE',
  },
}));

const recordDepthRoutes: RouteRecordRaw[] = [
  { path: '/record/documents/:documentId', name: 'record-document-depth-3', level: 3 },
  { path: '/record/documents/:documentId/versions/:versionId', name: 'record-version-depth-4', level: 4 },
  { path: '/record/documents/:documentId/versions/:versionId/signatures/:signatureId', name: 'record-signature-depth-5', level: 5 },
  { path: '/record/documents/:documentId/versions/:versionId/signatures/:signatureId/sources/:sourceId', name: 'record-source-depth-6', level: 6 },
  { path: '/record/documents/:documentId/versions/:versionId/signatures/:signatureId/sources/:sourceId/audit/:auditEventId', name: 'record-audit-depth-7', level: 7 },
].map((definition): RouteRecordRaw => ({
  path: definition.path,
  name: definition.name,
  component: () => import('./views/RecordEvidenceDepthPage.vue'),
  props: { level: definition.level },
  meta: {
    contractId: 'record-versions', primaryDomain: 'RECORD',
    guards: ['SESSION', 'ROLE', 'PATIENT_CONTEXT'], implementation: 'VUE_NATIVE',
  },
}));

export function createHaoEmrRouter(history: RouterHistory = defaultHistory()) {
  return createRouter({
    history,
    routes: [
      { path: '/', redirect: '/clinical' },
      ...buildContractRoutes(),
      ...emergencyDepthRoutes,
      ...recordDepthRoutes,
      {
        path: '/integration-messages/:messageId', name: 'integration-message-detail', component: () => import('./views/IntegrationMessageDetailPage.vue'),
        meta: { contractId: 'integration-messages', primaryDomain: 'DATA', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/device-monitoring/:deviceCode', name: 'device-detail', component: () => import('./views/DeviceDetailPage.vue'),
        meta: { contractId: 'device-monitoring', primaryDomain: 'DATA', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/research/:projectId', name: 'research-project-detail', component: () => import('./views/ResearchProjectDetailPage.vue'),
        meta: { contractId: 'research', primaryDomain: 'DATA', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/archive-assets/:assetId', name: 'archive-asset-detail', component: () => import('./views/AssetDetailPage.vue'),
        meta: { contractId: 'archive-assets', primaryDomain: 'ARCHIVE', guards: ['SESSION', 'ROLE', 'PATIENT_CONTEXT'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/quality-center/initiatives', name: 'quality-initiatives', component: () => import('./views/QualityOperationsRoutePage.vue'), props: { moduleId: 'quality-center' },
        meta: { contractId: 'quality-center', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/quality-center/initiatives/:itemId', name: 'quality-initiative-detail', component: () => import('./views/QualityOperationsRoutePage.vue'),
        props: (route) => ({ moduleId: 'quality-center', itemId: route.params.itemId }),
        meta: { contractId: 'quality-center', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/department-qc/cases', name: 'department-qc-cases', component: () => import('./views/QualityOperationsRoutePage.vue'), props: { moduleId: 'department-qc' },
        meta: { contractId: 'department-qc', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/department-qc/cases/:itemId', name: 'department-qc-case-detail', component: () => import('./views/QualityOperationsRoutePage.vue'),
        props: (route) => ({ moduleId: 'department-qc', itemId: route.params.itemId }),
        meta: { contractId: 'department-qc', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/quality-rating/assessments', name: 'quality-rating-assessments', component: () => import('./views/QualityRatingPage.vue'),
        meta: { contractId: 'quality-rating', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/quality-rating/assessments/:assessmentId', name: 'quality-rating-assessment-detail', component: () => import('./views/QualityRatingPage.vue'), props: true,
        meta: { contractId: 'quality-rating', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/infection-events/clues', name: 'infection-event-clues', component: () => import('./views/InfectionEventsPage.vue'),
        meta: { contractId: 'infection-events', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/infection-events/clues/:eventId', name: 'infection-event-clue-detail', component: () => import('./views/InfectionEventsPage.vue'), props: true,
        meta: { contractId: 'infection-events', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/credentials/grants', name: 'credential-grants', component: () => import('./views/CredentialsPage.vue'),
        meta: { contractId: 'credentials', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/credentials/grants/:credentialId', name: 'credential-grant-detail', component: () => import('./views/CredentialsPage.vue'), props: true,
        meta: { contractId: 'credentials', primaryDomain: 'QUALITY', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      ...qualityDepthRoutes,
      {
        path: '/mock-interfaces',
        name: 'mock-interfaces',
        component: () => import('./views/MockInterfacePage.vue'),
        meta: { contractId: 'mock-interfaces', primaryDomain: 'ADMIN', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/mock-interfaces/:workbenchId/profiles/:profileKey',
        name: 'mock-interface-profile',
        component: () => import('./views/SimulationHierarchyPage.vue'),
        meta: { contractId: 'mock-interface-workbench', primaryDomain: 'ADMIN', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/mock-interfaces/:workbenchId/profiles/:profileKey/scenarios/:scenario',
        name: 'mock-interface-scenario',
        component: () => import('./views/SimulationHierarchyPage.vue'),
        meta: { contractId: 'mock-interface-workbench', primaryDomain: 'ADMIN', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/mock-interfaces/:workbenchId/profiles/:profileKey/scenarios/:scenario/runs',
        name: 'mock-interface-runs',
        component: () => import('./views/SimulationHierarchyPage.vue'),
        meta: { contractId: 'mock-interface-workbench', primaryDomain: 'ADMIN', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/mock-interfaces/:workbenchId/profiles/:profileKey/scenarios/:scenario/runs/:runId',
        name: 'mock-interface-run-detail',
        component: () => import('./views/SimulationHierarchyPage.vue'),
        meta: { contractId: 'mock-interface-workbench', primaryDomain: 'ADMIN', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/mock-interfaces/:workbenchId/profiles/:profileKey/scenarios/:scenario/runs/:runId/evidence',
        name: 'mock-interface-run-evidence',
        component: () => import('./views/SimulationHierarchyPage.vue'),
        meta: { contractId: 'mock-interface-workbench', primaryDomain: 'ADMIN', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/mock-interfaces/:workbenchId',
        name: 'mock-interface-workbench',
        component: () => import('./views/MockInterfaceWorkbenchPage.vue'),
        beforeEnter: (to) => {
          // Preserve the historical mock-menu URLs, but send retired AI simulations
          // to their maintained real pages instead of creating a parallel workbench.
          const liveRoute = ({
            'ai-capture': '/ai-capture',
            'model-connection': '/model-connection',
            'model-routing': '/model-routing',
          } as Record<string, string>)[String(to.params.workbenchId ?? '')];
          return liveRoute || true;
        },
        meta: { contractId: 'mock-interface-workbench', primaryDomain: 'ADMIN', guards: ['SESSION', 'ROLE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/admin/users/:personId/:section?/:roleAssignmentId?/:auditSection?/:eventId?',
        name: 'administration-workforce-deep-detail',
        component: () => import('./views/AdministrationWorkforceDetailPage.vue'),
        props: true,
        meta: { contractId: 'admin-users', primaryDomain: 'ADMIN', guards: ['SESSION', 'ROLE', 'SCOPE'], implementation: 'VUE_NATIVE' },
      },
      {
        path: '/business-config/:module/:configId?/:section?/:executionId?/:evidenceId?/:auditId?',
        name: 'business-configuration-deep-detail',
        component: () => import('./views/BusinessConfigurationDetailPage.vue'),
        meta: { contractId: 'workflow', primaryDomain: 'CONFIG', guards: ['SESSION', 'ROLE', 'SCOPE', 'SEPARATION_OF_DUTIES'], implementation: 'VUE_NATIVE' },
      },
      { path: '/:pathMatch(.*)*', name: 'safe-not-found', component: () => import('./views/SafeNotFoundPage.vue') },
    ],
    scrollBehavior: () => ({ top: 0 }),
  });
}

export const router = createHaoEmrRouter();

router.beforeEach((to) => {
  if (to.meta.publicRoute) {
    if (authSession.token) return { path: '/clinical' };
    return true;
  }
  if (!authSession.token) {
    return { name: 'login-context', query: { redirect: to.fullPath } };
  }
  return true;
});

router.afterEach((to) => {
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.routeId = String(to.meta.contractId ?? to.name ?? 'unknown');
  }
  if (!to.path.startsWith('/inpatient') && !to.path.startsWith('/ip-')) clearInpatientSyntheticActor();
});

declare module 'vue-router' {
  interface RouteMeta {
    contractId?: string;
    primaryDomain?: string;
    guards?: readonly string[];
    implementation?: 'VUE_NATIVE' | 'SUPPORT_GUARD' | 'NOT_AVAILABLE';
    layout?: 'SYSTEM_AUTH' | 'APPLICATION';
    publicRoute?: boolean;
  }
}
