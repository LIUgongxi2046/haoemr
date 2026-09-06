import { describe, expect, it } from 'vitest';

const views = import.meta.glob('./views/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const components = import.meta.glob('./components/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

describe('数据中心六个二级页面契约', () => {
  it('数据总览保留五个下游入口并从各域真实台账聚合，不硬编码指标', () => {
    const source = views['./views/DataCenterPage.vue'];
    for (const route of ['/integration', '/migration', '/data-quality', '/devices', '/research']) {
      expect(source).toContain(route);
    }
    // 集成/设备/科研项目与差异工单来自配置目录真实记录
    expect(source).toContain("listConfigurations(configLeaseQuery.data.value!, 'INTEGRATION_CONNECTOR')");
    expect(source).toContain("'INTEGRATION_INCIDENT'");
    expect(source).toContain("'DEVICE_CATALOG'");
    expect(source).toContain("'RESEARCH_PROJECT'");
    // 历史迁移来自治理台账
    expect(source).toContain("issueGovernanceLease('MIGRATION_ADMIN')");
    expect(source).toContain('listSourceSystems');
    expect(source).toContain('listHistoricalMigrationBatches');
    // 质量工单与科研队列/数据申请来自数据中心真实持久化记录
    expect(source).toContain("issueDataLease('DATA_QUALITY_ADMIN')");
    expect(source).toContain('listDataQualityFindings');
    expect(source).toContain("issueDataLease('COHORT_BUILDER_ADMIN')");
    expect(source).toContain('listResearchCohorts');
    expect(source).toContain("issueDataLease('RESEARCH_DATASET_ADMIN')");
    expect(source).toContain('listResearchDatasetRequests');
    // 核心事实规模只读 DATA_CENTER 指标快照，页面不出现伪造的静态指标
    expect(source).toContain("listMetricSnapshots(metricLeaseQuery.data.value!, 'DATA_CENTER')");
    expect(source).toContain('configLeaseQuery.refetch()');
    expect(source).toContain('metricLeaseQuery.refetch()');
    expect(source).toContain('if (error == null) return null;');
    expect(source.match(/queryKey: computed\(\(\) => \[[^\n]+lease_id\]\)/g)?.length).toBe(11);
    expect(source.match(/retry: \(\) => \{ void refreshAll\(\); \}/g)?.length).toBe(5);
    expect(source).not.toContain('99.93');
  });

  it('集成交换保留真实台账并将仿真测试引导至模拟工具', () => {
    const source = views['./views/IntegrationPage.vue'];
    expect(source).not.toContain('invokeMockInterface');
    expect(source).toContain('/mock-interfaces/integration-connectors');
    expect(source).toContain("listConfigurations(configLeaseQuery.data.value!, 'INTEGRATION_CONNECTOR')");
    expect(source).toContain("listConfigurations(configLeaseQuery.data.value!, 'INTEGRATION_INCIDENT')");
    expect(source).toContain('前往模拟接口测试');
    expect(source).toContain('/integration-connectors');
    expect(source).toContain('/integration-messages');
    expect(source).not.toContain("const systemMeta");
    expect(source).not.toContain('82,830');
  });

  it('历史迁移覆盖八阶段、完整流程动作和弹窗交互', () => {
    const source = views['./views/MigrationPage.vue'];
    for (const action of [
      'registerSourceSystem', 'registerSourceFieldMapping', 'recordSourcePatientMatchCandidate',
      'importSourcePatientMatchCandidatesCsv', 'listSourcePatientMatchCandidateImports',
      'startHistoricalMigrationBatch', 'reconcileHistoricalMigrationBatch',
      'switchHistoricalMigrationBatch', 'rollbackHistoricalMigrationBatch',
      'recordHistoricalMigrationCheckpoint',
    ]) expect(source).toContain(action);
    expect(source).toContain("['源盘点', '字段映射', '患者匹配', '试迁', '对账', '切换', '观察', '归档']");
    expect(source).toContain('exportReconciliationReport');
    expect(source).toContain('固定导入记录');
    expect(source).toContain('source_import_id: sourceImport.source_import_id');
    expect(source).not.toContain('batchForm.recordCount');
    expect(source.match(/<AdminActionDialog/g)?.length).toBeGreaterThanOrEqual(4);
    expect(source).toContain('<AdminConfirmDialog');
  });

  it('数据质量覆盖事实扫描、问题工单、人工复核和 Agent 候选建议', () => {
    const source = views['./views/DataQualityPage.vue'];
    expect(source).toContain('质量问题工作队列');
    expect(source).toContain('发现</li><li>分派</li><li>整改</li><li>复核</li><li>关闭');
    expect(source).toContain('registerDataQualityRule');
    expect(source).toContain('startDataQualityScan');
    expect(source).toContain('listDataQualityFindings');
    expect(source).toContain('transitionDataQualityFinding');
    expect(source).toContain('createDataQualityTriageAdvice');
    expect(source).toContain('仅建议，无自动写入权');
    expect(source).not.toContain('recordDataQualityEvaluation');
    expect(source).toContain('deactivateDataQualityRule');
    expect(source).toContain('<AdminActionDialog');
    expect(source).toContain('<AdminConfirmDialog');
  });

  it('设备接入和科研统计使用真实版本化 CRUD 目录', () => {
    const catalog = components['./components/DataCenterConfigurationCatalog.vue'];
    expect(catalog).toContain('defineConfiguration');
    expect(catalog).toContain('updateConfiguration');
    expect(catalog).toContain('transitionConfiguration');
    expect(catalog).toContain('...(selected.value?.payload ?? {})');
    expect(catalog).toContain('<AdminActionDialog');
    expect(catalog).toContain('<AdminConfirmDialog');
    expect(views['./views/DevicesPage.vue']).toContain('listDevices');
    expect(views['./views/DevicesPage.vue']).toContain('createDevice');
    expect(views['./views/DeviceMonitoringPage.vue']).toContain('collectDeviceTelemetry');
    expect(views['./views/DeviceMonitoringPage.vue']).toContain('listDeviceStatuses');
    expect(components['./components/ProductionExecutionWorkbenchPage.vue']).toContain('人工确认门禁');
    expect(views['./views/ResearchPage.vue']).toContain('listResearchProjects');
    expect(views['./views/ResearchPage.vue']).toContain('createResearchProject');
    const cohort = views['./views/CohortBuilderPage.vue'];
    expect(cohort).toContain('age_gte=18;diagnosis_code=E11.9');
    expect(cohort).toContain('member_count: members.value.length');
    expect(cohort).not.toContain('snapshotForm.memberCount');
    const stats = views['./views/ResearchStatsPage.vue'];
    expect(stats).toContain("item.dimension?.group === 'AGE_DISTRIBUTION'");
    expect(stats).toContain("item.dimension?.group === 'TREND'");
    expect(stats).toContain('computeMetricSnapshots');
    expect(stats).toContain("'队列快照', '纳入成员', '平均队列规模', '已输出研究集'");
    expect(stats).not.toContain('const ages = [');
    expect(stats).not.toContain('STAT-20260813-09');
  });

  it('科研项目提供四级详情页并读取真实项目台账', () => {
    const detail = views['./views/ResearchProjectDetailPage.vue'];
    expect(detail).toContain('listResearchProjects');
    expect(detail).toContain("route.params.projectId");
    expect(detail).toContain('approved_purpose');
  });

  it('目录卡片限制宽度并保持紧凑操作间距', () => {
    const catalog = components['./components/DataCenterConfigurationCatalog.vue'];
    expect(catalog).toContain('minmax(288px, 350px)');
    expect(catalog).toContain('gap: 14px');
    expect(catalog).toContain('.catalog-actions { display: flex; flex-wrap: wrap; gap: 10px;');
  });
});
