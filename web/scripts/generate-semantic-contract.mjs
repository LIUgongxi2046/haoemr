import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const projectDir = resolve(webDir, '..');
const source = JSON.parse(await readFile(resolve(projectDir, 'contracts/generated/route-contract.generated.json'), 'utf8'));

const configurationRoutes = new Set(['workflow','form-designer','rule-center','scope-designer','agent-compose','agent-context','agent-evals','config-release','config-upgrade','admin-master-data','admin-parameters','admin-jobs','backup','install','operations','release-gates']);
const metricRoutes = new Set(['data-center','research','research-stats','department-qc','quality-center']);
const simulationRoutes = new Set(['admin-auth','ai-capture','model-connection','model-routing','devices','device-monitoring','integration-connectors','integration-messages','archive-scan','archive-preservation','pathology-workbench','anesthesia-workbench','therapy-workbench']);

function dataSource(route) {
  if (configurationRoutes.has(route.route_id)) return 'CONFIGURATION_LIFECYCLE_API';
  if (metricRoutes.has(route.route_id)) return 'METRIC_SNAPSHOT_API_WITH_LINEAGE';
  if (simulationRoutes.has(route.route_id)) return 'DETERMINISTIC_SCENARIO_API';
  if (route.primary_domain === 'AI') return 'AI_GOVERNANCE_API';
  if (route.primary_domain === 'ADMIN' || route.primary_domain === 'CONFIG') return 'ADMIN_OR_CONFIGURATION_API';
  return 'CLINICAL_FACT_API';
}

function primaryActions(route) {
  if (route.route_id === 'outpatient') return ['选择候诊患者', '刷新诊疗快照', '进入病历', '质控与签署'];
  if (configurationRoutes.has(route.route_id)) return ['创建草稿', '校验', '提交审批', '独立批准', '发布', '回滚'];
  if (metricRoutes.has(route.route_id)) return ['按登记口径计算', '查看事实血缘', '返回可整改事实'];
  if (simulationRoutes.has(route.route_id)) return ['选择场景', '运行成功场景', '运行降级场景', '验证不可用人工路径'];
  if (route.primary_domain === 'RECORD') return ['打开记录', '查看版本或证据'];
  if (route.primary_domain === 'ADMIN' || route.primary_domain === 'CONFIG') return ['刷新', '查看或维护治理对象'];
  return ['刷新业务事实', '进入主要业务动作'];
}

function criticalText(route) {
  const highRiskText = {
    outpatient: ['门诊医生工作台', '候诊队列', '下一位患者'],
    'agent-compose': ['医助团队与能力工具编排', '版本台账', '新建草稿'],
    'agent-context': ['医助诊疗范围策略', '版本台账', '新建草稿'],
    'agent-evals': ['医助评测与发布审核', '版本台账', '新建草稿'],
    'data-center': ['数据总览', '跨域需关注事项', '核心临床数据规模指标快照'],
    research: ['科研项目与统计中心', '科研项目台账', '新建项目'],
    'admin-auth': ['认证与账户安全策略', '安全基线差异', '创建策略草稿'],
    'model-routing': ['医助模型候选与数据边界', '运行时模型候选集', '医疗数据使用要求'],
    'pathology-workbench': ['病理标本到诊断签署', '新建病理病例', '专业执行深层功能'],
  };
  return highRiskText[route.route_id] ?? [];
}

const routes = source.routes.map((route) => ({
  route_id: route.route_id,
  title: route.title,
  primary_domain: route.primary_domain,
  key_regions: ['页面标题与业务范围', '主要业务工作区', '状态与结果反馈', '跨域导航或业务入口'],
  primary_actions: primaryActions(route),
  required_states: [...new Set(['loading', 'empty', 'error', 'permission', 'success', ...(route.states ?? [])])],
  data_source: dataSource(route),
  critical_text: criticalText(route),
  browser_assertions: ['main [data-page-root].vue-native-page 可见', 'H1 非空', '一级导航唯一激活', '无横向溢出', 'API 请求可收敛'],
  requirement_refs: route.requirement_refs,
  artifact_path: route.artifact_path,
}));

const contract = {
  schema_version: 1,
  generated_at: '2026-08-24',
  source: 'contracts/generated/route-contract.generated.json + explicit high-risk page semantics',
  route_count: routes.length,
  routes,
};
await writeFile(resolve(projectDir, 'docs/process/testing/route-semantic-contract.json'), `${JSON.stringify(contract, null, 2)}\n`);
console.log(JSON.stringify({ routes: routes.length, high_risk_overrides: routes.filter((route) => route.critical_text.length).length }));
