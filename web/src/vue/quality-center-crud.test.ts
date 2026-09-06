import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { qualityOperationDefinitions } from './quality-operations';

const source = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('medical quality center full secondary-menu CRUD', () => {
  it('keeps every prototype secondary menu visible, including department QC', () => {
    const shell = source('./components/ClinicalShell.vue');
    for (const route of ['quality-center', 'department-qc', 'quality-rating', 'infection-events', 'credentials']) {
      expect(shell).toContain(`'${route}'`);
    }
    for (const label of ['质量总览', '院科质控', '评级取证', '院感事件', '临床资质']) {
      expect(shell).toContain(`'${label}'`);
    }
  });

  it('defines one distinct auditable workflow type for every secondary menu', () => {
    const definitions = Object.values(qualityOperationDefinitions);
    expect(definitions).toHaveLength(5);
    expect(new Set(definitions.map((item) => item.configType)).size).toBe(5);
    expect(definitions.every((item) => item.workflow.length === 4)).toBe(true);
    expect(definitions.every((item) => item.statuses.some((status) => status.terminal))).toBe(true);
  });

  it('uses backend CRUD and modal interactions for create, edit and logical delete', () => {
    const panel = source('./components/QualityOperationsPanel.vue');
    for (const api of ['listConfigurations', 'defineConfiguration', 'updateConfiguration', 'transitionConfiguration']) {
      expect(panel).toContain(api);
    }
    expect(panel).toContain('AdminActionDialog');
    expect(panel).toContain('AdminConfirmDialog');
    expect(panel).toContain("action: 'ARCHIVE'");
    expect(panel).toContain('保存并影响流程');
    expect(panel).not.toMatch(/window\.(?:prompt|confirm)\s*\(/);
  });

  it('registers dedicated collection and detail URLs for every quality business object', () => {
    const router = source('./router.ts');
    for (const path of [
      '/quality-center/initiatives', '/quality-center/initiatives/:itemId',
      '/department-qc/cases', '/department-qc/cases/:itemId',
      '/quality-rating/assessments', '/quality-rating/assessments/:assessmentId',
      '/infection-events/clues', '/infection-events/clues/:eventId',
      '/credentials/grants', '/credentials/grants/:credentialId',
    ]) expect(router).toContain(`path: '${path}'`);
  });

  it('moves initiative and department remediation CRUD into third/fourth-level route pages', () => {
    const routePage = source('./views/QualityOperationsRoutePage.vue');
    expect(routePage).toContain('QualityOperationsPanel');
    expect(routePage).toContain("四级详情");
    expect(source('./views/QualityCenterPage.vue')).toContain('/quality-center/initiatives');
    expect(source('./views/DepartmentQcPage.vue')).toContain('/department-qc/cases');
  });

  it('keeps prototype-faithful secondary workbenches backed by live domain queries', () => {
    const department = source('./views/DepartmentQcPage.vue');
    const rating = source('./views/QualityRatingOverviewPage.vue');
    const infection = source('./views/InfectionEventsOverviewPage.vue');
    const credentials = source('./views/CredentialsOverviewPage.vue');
    expect(department).toContain('listConfigurations');
    expect(department).toContain('创建质控抽查');
    expect(rating).toContain('loadSpecialtySupportAssessments');
    expect(rating).toContain('新建评级评估');
    expect(infection).toContain('listInfectionMonitoringEvents');
    expect(infection).toContain('审核高风险线索');
    expect(credentials).toContain('listPractitionerCredentials');
    expect(credentials).toContain('临床授权模拟');
  });

  it('routes secondary create/review actions into modal-backed tertiary workflows', () => {
    expect(source('./components/QualityOperationsPanel.vue')).toContain('route.query.create');
    expect(source('./views/QualityRatingPage.vue')).toContain('route.query.create');
    expect(source('./views/InfectionEventsPage.vue')).toContain('route.query.review');
    expect(source('./views/CredentialsPage.vue')).toContain('route.query.create');
  });

  it('connects rating create, edit and delete buttons to the real support-assessment API', () => {
    const page = source('./views/QualityRatingPage.vue');
    for (const api of ['createSpecialtySupportAssessment', 'updateSpecialtySupportAssessment', 'deleteSpecialtySupportAssessment']) {
      expect(page).toContain(api);
    }
    expect(page).toContain('/quality-rating/assessments/');
    expect(page).toContain('AdminActionDialog');
    expect(page).toContain('AdminConfirmDialog');
  });

  it('connects credential create, edit and revoke buttons to practitioner credential records', () => {
    const page = source('./views/CredentialsPage.vue');
    const api = source('../api/credentials.ts');
    for (const operation of ['createPractitionerCredential', 'updatePractitionerCredential', 'revokePractitionerCredential']) {
      expect(page).toContain(operation);
    }
    expect(api).toContain('/admin/credentials');
    expect(page).toContain('/credentials/grants/');
  });

  it('moves infection report and review actions into dialogs', () => {
    const infection = source('./views/InfectionEventsPage.vue');
    expect(infection).toContain('title="新建院感线索"');
    expect(infection).toContain("'确认院感线索'");
    expect(infection).toContain("'排除院感线索'");
    expect(infection).not.toMatch(/window\.(?:prompt|confirm)\s*\(/);
  });

  it('implements real level-five through level-seven routes with modal CRUD and candidate-only Agent proposals', () => {
    const router = source('./router.ts');
    const page = source('./views/QualityGovernanceDepthPage.vue');
    const api = source('../api/quality-governance.ts');
    for (const suffix of ['actions', 'evidence', 'reviews']) expect(router).toContain(`suffix: '${suffix}'`);
    for (const operation of ['createQualityGovernanceRecord', 'updateQualityGovernanceRecord', 'voidQualityGovernanceRecord', 'createQualityGovernanceAgentProposal']) {
      expect(api).toContain(operation);
      expect(page).toContain(operation);
    }
    expect(page).toContain('AdminActionDialog');
    expect(page).toContain('AdminConfirmDialog');
    expect(page).toContain('候选建议');
  });

  it('uses China reporting fields and backend credential simulation rather than fake formulas or JSON regex authorization', () => {
    const infection = source('./views/InfectionEventsPage.vue');
    const credentials = source('./views/CredentialsOverviewPage.vue');
    const credentialEditor = source('./views/CredentialsPage.vue');
    for (const field of ['eventCategory', 'reportingWindowHours', 'externalReportRequired', 'reportingPolicyCode']) expect(infection).toContain(field);
    expect(credentials).toContain('simulatePractitionerCredentialAuthorization');
    expect(credentials).not.toContain('JSON.stringify(item.practice_scope).toUpperCase()');
    expect(credentialEditor).toContain('schema_version: 2');
    expect(credentialEditor).not.toContain('执业范围 JSON');
    expect(source('./views/QualityCenterPage.vue')).not.toContain('<b>39/39</b>');
    expect(source('./views/DepartmentQcPage.vue')).not.toContain('100 - overdue');
  });
});
