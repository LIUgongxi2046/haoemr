import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createSSRApp, defineComponent, h, unref, type Component, type Ref, type Slots } from 'vue';
import { renderToString } from '@vue/server-renderer';
import ClinicalTasksPage from './views/ClinicalTasksPage.vue';
import ClinicalShell from './components/ClinicalShell.vue';
import QualityGovernanceOverview from './components/QualityGovernanceOverview.vue';
import PatientRegistryPage from './views/PatientRegistryPage.vue';
import DataCenterPage from './views/DataCenterPage.vue';
import PendingWorkPopover from './components/PendingWorkPopover.vue';

const state = vi.hoisted(() => ({
  navState: null as null | 'ready' | 'loading' | 'error',
  navCount: 0,
  dataError: null as Error | null,
  view: 'overview',
  searchText: '',
  patientSearchError: null as Error | null,
  taskPending: false,
  taskError: null as Error | null,
  configError: null as Error | null,
  queries: [] as Array<{ queryKey: unknown[]; enabled?: unknown }>,
}));

vi.mock('vue-router', async (importOriginal) => {
  const original = await importOriginal<typeof import('vue-router')>();
  const { h } = await import('vue');
  return {
    ...original,
    useRoute: () => ({ meta: { contractId: 'clinical-tasks' }, query: { view: state.view, q: state.searchText }, params: {} }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
    RouterLink: { props: ['to'], setup: (props: { to: unknown }, { slots }: { slots: Slots }) => () => h('a', { href: typeof props.to === 'string' ? props.to : '#' }, slots.default?.()) },
  };
});

vi.mock('./composables/use-pending-work', () => ({ usePendingWork: () => ({ forMenu: (id: string) => id === 'clinical-tasks' && state.navState ? [{ id: 'task-count', menu: id, label: '未完成临床任务', scope: '当前住院患者就诊', to: '/clinical-tasks', count: state.navState === 'ready' ? state.navCount : null, state: state.navState }] : [], refresh: vi.fn() }) }));

vi.mock('@tanstack/vue-query', async () => {
  const { ref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown[]; enabled?: unknown }) => {
      state.queries.push(options);
      const key = options.queryKey[1];
      const task = key === 'tasks-center';
      const config = key === 'task-center-config';
      const data = task ? { lease: {}, tasks: [{
        task_id: 'task-one', title: '待处理检验结果', task_type: 'RESULT_REVIEW',
        state: 'PENDING', business_state: '待处理', risk_level: 'HIGH',
        due_at: '2099-01-01T00:00:00Z', data_watermark: 'technical-watermark',
        source_id: 'result-one', source_type: 'RESULT', source_route: '/opd-results',
      }] } : config ? { lease: {}, rules: [], pathways: [] }
        : key === 'patient-search' ? [{ patient_id: 'patient-search-one', display_name: '验收患者', birth_date: '1990-01-01' }]
        : key === 'task-team-queue' ? { lease: {}, items: [] }
          : key === 'task-detail' ? { events: [], delegations: [], rule_snapshot: { rule: 'technical-rule' } }
            : key === 'task-center-pathway' ? { workspace: { instance: null } } : [];
      return {
        data: ref(data), error: ref(task ? state.taskError : config ? state.configError : key === 'patient-search' ? state.patientSearchError : options.queryKey[0] === 'data-center' ? state.dataError : null),
        isPending: ref(task && state.taskPending), isFetching: ref(false), refetch: vi.fn(),
      };
    },
  };
});

async function render(component: Component, props = {}) {
  const app = createSSRApp({ render: () => h(component, props) });
  app.component('RouterLink', defineComponent({ props: ['to'], setup: (p, { slots }) => () => h('a', { href: p.to }, slots.default?.()) }));
  app.component('RouterView', { render: () => null });
  return renderToString(app);
}

beforeEach(() => {
  state.navState = null; state.navCount = 0; state.dataError = null;
  state.view = 'overview'; state.taskPending = false; state.searchText = ''; state.patientSearchError = null;
  state.taskError = null; state.configError = null; state.queries = [];
});

describe('清理后的实际组件渲染', () => {
  it.each(['pathway', 'rules'])('配置视图 %s 不受个人任务加载或失败阻断，也不显示任务模块', async (view) => {
    state.view = view; state.taskPending = true; state.taskError = new Error('TASK_ACCESS_DENIED');
    const html = await render(ClinicalTasksPage);
    expect(html).toContain(view === 'pathway' ? '新建路径配置' : '新建任务规则');
    expect(html).not.toContain('TASK_ACCESS_DENIED');
    expect(html).not.toContain('aria-label="任务与路径指标"');
    expect(html).not.toContain('>我的委托</button>');
    expect(html).not.toContain('创建临床路径版本');
    const enabled = state.queries.filter(q => q.enabled === undefined || (typeof q.enabled === 'function' ? q.enabled() : unref(q.enabled as boolean | Ref<boolean>)));
    expect(enabled.map(q => q.queryKey[1])).toEqual(['task-center-config']);
  });

  it('配置请求失败时展示自身错误，避免误显示空配置列表', async () => {
    state.view = 'rules'; state.configError = new Error('CONFIG_SERVICE_UNAVAILABLE');
    const html = await render(ClinicalTasksPage);
    expect(html).toContain('NETWORK_UNAVAILABLE');
    expect(html).not.toContain('暂无任务规则配置');
  });

  it('任务总览保留处理入口、业务指标与来源，技术信息默认折叠', async () => {
    const html = await render(ClinicalTasksPage);
    expect(html).toContain('aria-label="任务与路径指标"');
    expect(html).toContain('>我的委托</button>');
    expect(html).toContain('href="/opd-results"');
    expect(html).not.toContain('>任务规则</button>');
    expect(html).not.toContain('创建临床路径版本');
    expect(html).not.toContain('L7 ·');
    const details = html.match(/<details\b[^>]*class="[^"]*task-technical-details[\s\S]*?<\/details>/)?.[0];
    expect(details).toBeDefined();
    expect(details).toContain('technical-watermark');
    expect(details).not.toMatch(/^<details[^>]*\bopen(?:\s|=|>)/);
  });

  it.each(['overview', 'pathway', 'rules'])('壳层 %s 保留岗位与患者搜索，移除示例通知和医院切换', async (view) => {
    state.view = view;
    const html = await render(ClinicalShell);
    expect(html).toContain('aria-label="搜索患者"');
    expect(html).toContain('aria-label="选择角色"');
    expect(html).not.toContain('aria-label="选择医院"');
    expect(html).not.toContain('notification-trigger');
    expect(html).not.toContain('心内科会诊剩余 20 分钟');
    expect(html).not.toContain('患者纵深');
    const selectedDomain = view === 'overview' ? '任务中心' : '业务配置';
    expect(html).toMatch(new RegExp('<a[^>]*aria-current="page"[^>]*>[\\s\\S]*?' + selectedDomain));
  });

  it('质量概览保留真实风险提示和详情入口，不渲染固定的审计结论或流程图', async () => {
    const html = await render(QualityGovernanceOverview, {
      title: '评级取证', description: '', metrics: [], rows: [], warning: '2 项待核验',
      safetyText: '请复核当前业务对象', exportLabel: '导出清单', primaryLabel: '新建评级评估', detailLabel: '进入处理详情',
    });
    expect(html).toContain('2 项待核验');
    expect(html).toContain('进入处理详情');
    expect(html).not.toContain('v2026.08');
    expect(html).not.toContain('<dt>审计</dt>');
    expect(html).not.toContain('aria-label="处理流程"');
  });
});


describe('顶栏患者搜索的实际结果页面', () => {
  it('读取地址中的搜索词并展示匹配患者', async () => {
    state.searchText = '  验收患者  ';
    const html = await render(PatientRegistryPage);
    expect(html).toContain('aria-label="患者搜索结果"');
    expect(html).toContain('关键词：验收患者');
    expect(html).toContain('1990-01-01');
    const query = state.queries.find(q => q.queryKey[1] === 'patient-search');
    expect(unref(query?.queryKey[2] as Ref<string>)).toBe('验收患者');
    expect(typeof query?.enabled === 'function' && query.enabled()).toBe(true);
  });

  it('没有搜索词时不发起患者搜索，保留原身份核验页面', async () => {
    const html = await render(PatientRegistryPage);
    expect(html).not.toContain('aria-label="患者搜索结果"');
    expect(html).toContain('重复身份候选队列');
    const query = state.queries.find(q => q.queryKey[1] === 'patient-search');
    expect(typeof query?.enabled === 'function' && query.enabled()).toBe(false);
  });

  it('搜索失败时展示错误，不把失败显示为没有匹配患者', async () => {
    state.searchText = '验收患者'; state.patientSearchError = new Error('SEARCH_FAILURE');
    const html = await render(PatientRegistryPage);
    expect(html).toContain('NETWORK_UNAVAILABLE');
    expect(html).not.toContain('未找到匹配患者');
  });
});


describe('真实菜单待办与错误呈现', () => {
  it.each([['ready', 7, '7'], ['ready', 0, '0'], ['loading', 0, '…'], ['error', 0, '!']] as const)('菜单状态 %s 不混淆真实零和未取到数据', async (status, count, display) => {
    state.navState = status; state.navCount = count;
    const html = await render(ClinicalShell);
    expect(html).toMatch(new RegExp(`class="nav-count[^\"]*"[^>]*>${display === '!' ? '!' : display}</span>`));
    expect(html).toContain('当前住院患者就诊');
    expect(html).toContain('aria-label="统一待办"');
    expect(html.indexOf('aria-label="打开 AI医助 EVA"')).toBeLessThan(html.indexOf('aria-label="统一待办"'));
    expect(html.indexOf('aria-label="统一待办"')).toBeLessThan(html.indexOf('aria-label="打开操作指引"'));
    expect(html).toMatch(/aria-label="统一待办"[^>]*><svg/);
    expect(html).not.toContain('pending-work-details');
    expect(html).not.toContain('查看统计范围与处理入口');
    expect(html).not.toContain('id="topbar-pending-work"');
  });
  it('数据中心接口失败时不宣称没有待办或运行正常', async () => {
    state.dataError = new Error('连接失败');
    const html = await render(DataCenterPage);
    expect(html).toContain('待办统计暂不可用');
    expect(html).not.toContain('各域台账暂无开放阻断与待办，运行正常');
  });
});


describe('顶部统一待办', () => {
  const group = (count: number | null, status: 'ready' | 'error') => ({
    id: 'clinical-tasks', label: '任务中心', rows: [{ id: 'task', menu: 'clinical-tasks', label: '未完成任务', scope: '当前就诊', to: '/clinical-tasks', count, state: status }],
  });
  it('只列出需要处理的业务，提供真实处理入口', async () => {
    const html = await render(PendingWorkPopover, { groups: [group(7, 'ready'), { id: 'empty', label: '不应出现的空队列', rows: [] }] });
    expect(html).toContain('href="/clinical-tasks"');
    expect(html).toContain('未完成任务');
    expect(html).not.toContain('不应出现的空队列');
  });
  it('零待办不展开功能清单', async () => {
    const html = await render(PendingWorkPopover, { groups: [group(0, 'ready')] });
    expect(html).toContain('当前统计范围暂无待办');
    expect(html).not.toContain('未完成任务');
  });
  it('接口失败不误报没有待办', async () => {
    const html = await render(PendingWorkPopover, { groups: [group(null, 'error')] });
    expect(html).toContain('暂不可用');
    expect(html).not.toContain('当前统计范围暂无待办');
  });
});
